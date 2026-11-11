import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.api.Trigger;

/** 仅隔离进程验收加载，控制终态 UPDATE 的真实回滚。 */
public final class FailFirstResultSave implements Trigger {
    private final AtomicBoolean failed = new AtomicBoolean();
    private int stateColumn = -1;
    private Path outageMarker;

    @Override
    public void init(Connection connection, String schema, String trigger, String table, boolean before, int type)
            throws SQLException {
        // H2 的触发器连接可能使用 jdbc:default:connection，按真实文件路径检查隔离边界。
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("CALL DATABASE_PATH()")) {
            String path = rows.next() ? rows.getString(1) : null;
            if (path == null || !path.replace('\\', '/').contains("/.tools/execution-e2e/"))
                throw new SQLException("Result save fault injection requires the isolated execution-e2e file database");
            if (Boolean.getBoolean("taskflow.test.result-save.exhaustion")) {
                outageMarker = Path.of(path).resolveSibling("worker-result-save.blocked");
            }
        }
        try (var statement = connection.prepareStatement("SELECT * FROM worker_execution WHERE 1=0")) {
            var metadata = statement.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if (metadata.getColumnName(index).equalsIgnoreCase("state")) stateColumn = index - 1;
            }
        }
        if (stateColumn < 0) throw new SQLException("Worker execution state column is missing");
    }

    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        if ("RUNNING".equals(oldRow[stateColumn])
                && ("SUCCEEDED".equals(newRow[stateColumn]) || "FAILED".equals(newRow[stateColumn]))
                && (outageMarker == null ? failed.compareAndSet(false, true) : Files.exists(outageMarker))) {
            throw new SQLException("Injected Worker terminal result save failure", "08006");
        }
    }
}
