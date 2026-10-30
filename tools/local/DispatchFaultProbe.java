import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.h2.api.Trigger;

/** Test-only SQL barriers and read-only evidence; never included in an application JAR. */
public final class DispatchFaultProbe implements Trigger {
    private Path root;
    private String table;
    private final Map<String, Integer> columns = new HashMap<>();

    @Override public void init(Connection connection, String schema, String name, String table,
            boolean before, int type) throws SQLException {
        this.table = table.toLowerCase(Locale.ROOT);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("CALL DATABASE_PATH()")) {
            if (!rows.next()) throw new SQLException("Missing file database");
            root = guarded(rows.getString(1)).getParent();
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            var metadata = rows.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++)
                columns.put(metadata.getColumnName(i).toLowerCase(Locale.ROOT), i - 1);
        }
    }

    @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        String gate = null;
        if (table.equals("dispatch_outbox") && "SENDING".equals(oldRow[columns.get("state")])
                && "PUBLISHED".equals(newRow[columns.get("state")])) gate = "published";
        if (table.equals("worker_execution") && ((Number)newRow[columns.get("report_count")]).intValue()
                > ((Number)oldRow[columns.get("report_count")]).intValue()) gate = "report";
        if (gate == null) return;
        var arm = root.resolve(table + "-" + gate + ".arm");
        try {
            if (!Files.exists(arm)) return;
            String expected = Files.readString(arm).trim();
            String id = String.valueOf(oldRow[columns.get(table.equals("dispatch_outbox") ? "event_id" : "attempt_id")]);
            if (!expected.equals("*") && !expected.equals(id)) return;
            // Atomic rename ensures a single barrier even when multiple SQL threads race.
            var hit = root.resolve(table + "-" + gate + ".hit");
            Files.move(arm, hit);
            var marker = new LinkedHashMap<String, Object>();
            columns.forEach((key, index) -> marker.put(key, oldRow[index]));
            Files.writeString(root.resolve(table + "-" + gate + ".json"), json(marker));
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
            while (!Files.exists(root.resolve(table + "-" + gate + ".release"))) {
                if (System.nanoTime() >= deadline) throw new SQLException("Fault barrier timed out");
                Thread.sleep(25);
            }
        } catch (java.nio.file.NoSuchFileException raced) {
            // Another update consumed the arm file.
        } catch (Exception error) {
            throw new SQLException("Fault barrier failed", error);
        }
    }

    private static Path guarded(String path) throws SQLException {
        var resolved = Path.of(path).toAbsolutePath().normalize();
        if (!resolved.toString().replace('\\', '/').contains("/.tools/dispatch-faults/"))
            throw new SQLException("Probe requires an isolated dispatch-faults database");
        return resolved;
    }

    public static void main(String[] args) throws Exception {
        var database = guarded(args[0]);
        if (!Files.isRegularFile(Path.of(database + ".mv.db"))) throw new IllegalArgumentException("Database must already exist");
        if (!args[1].stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ") || args[1].contains(";"))
            throw new IllegalArgumentException("Only one SELECT is allowed");
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + database.toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE;IFEXISTS=TRUE", "sa", "");
                var statement = connection.createStatement()) {
            connection.setReadOnly(true);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(args[1])) {
                var result = new ArrayList<Object>();
                var metadata = rows.getMetaData();
                while (rows.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        Object value = rows.getObject(i);
                        if (value instanceof Clob clob) value = clob.getSubString(1, Math.toIntExact(clob.length()));
                        row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
                    }
                    result.add(row);
                }
                System.out.println(json(result));
            }
        }
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var fields = new ArrayList<String>();
            map.forEach((key, item) -> fields.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable<?> items) {
            var parts = new ArrayList<String>();
            items.forEach(item -> parts.add(json(item)));
            return "[" + String.join(",", parts) + "]";
        }
        var text = value.toString();
        var escaped = new StringBuilder("\"");
        for (char character : text.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 32) escaped.append(String.format("\\u%04x", (int)character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }
}
