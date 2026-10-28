import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.security.SecureRandom;
import java.util.regex.*;
import org.yaml.snakeyaml.Yaml;

/** 本机设施准备工具：读取现有 YAML，用独立 schema/账号隔离服务，不读写业务表。 */
public class ProvisionLocalExecution {
    static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    static final SecureRandom RANDOM = new SecureRandom();
    static final Yaml YAML = new Yaml();
    @SuppressWarnings("unchecked")
    static Map<String,Object> load(Path path) throws Exception {
        return Files.exists(path) ? YAML.load(Files.readString(path, StandardCharsets.UTF_8)) : Map.of();
    }
    static Object value(Map<String,Object> data, String... path) {
        Object node = data;
        for (String key : path) node = node instanceof Map<?,?> map ? map.get(key) : null;
        return node;
    }
    static String resolve(Object value) {
        if (value == null) return "";
        String raw = value.toString();
        var match = Pattern.compile("^\\$\\{([A-Z0-9_]+):(.*)}$", Pattern.DOTALL).matcher(raw);
        if (!match.matches()) return raw;
        return System.getenv().getOrDefault(match.group(1), match.group(2));
    }
    static String random(int size) {
        byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes);
    }
    static void write(Path file, String contents) throws Exception {
        if (!file.normalize().startsWith(ROOT.resolve("config"))) throw new IllegalStateException("配置路径越界");
        Files.writeString(file, contents, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Exception {
        var main = load(ROOT.resolve("taskflow-admin/src/main/resources/application.yml"));
        var local = load(ROOT.resolve("config/taskflow-admin-local.yml"));
        String url = resolve(Optional.ofNullable(value(local, "spring","datasource","url")).orElse(value(main,"spring","datasource","url")));
        String username = resolve(Optional.ofNullable(value(local,"spring","datasource","username")).orElse(value(main,"spring","datasource","username")));
        String password = resolve(Optional.ofNullable(value(local,"spring","datasource","password")).orElse(value(main,"spring","datasource","password")));
        var address = java.net.URI.create(url.substring("jdbc:".length()));
        if (!Set.of("localhost","127.0.0.1").contains(address.getHost()) || !"/taskflow_admin".equals(address.getPath()))
            throw new IllegalStateException("只允许使用已配置的本机 taskflow_admin 管理连接准备隔离服务");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            for (String service : List.of("scheduler","worker")) {
                Path config = ROOT.resolve("config/taskflow-" + service + "-local.yml");
                String schema = "taskflow_" + service;
                if (!Files.exists(config)) {
                    try (var statement = connection.createStatement()) {
                        statement.execute("CREATE DATABASE IF NOT EXISTS " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    }
                    try (var check = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?")) {
                        check.setString(1, schema);
                        try (var rows = check.executeQuery()) {
                            rows.next();
                            if (rows.getInt(1) != 0) throw new IllegalStateException("目标服务 schema 非空，未自动接管既有数据：" + schema);
                        }
                    }
                    String account = ("scheduler".equals(service) ? "tf_sched_" : "tf_work_") + random(4);
                    String secret = random(24);
                    try (var statement = connection.createStatement()) {
                        for (String host : List.of("localhost","127.0.0.1")) {
                            statement.execute("CREATE USER '" + account + "'@'" + host + "' IDENTIFIED BY '" + secret + "'");
                            statement.execute("GRANT ALL PRIVILEGES ON " + schema + ".* TO '" + account + "'@'" + host + "'");
                        }
                    }
                    String ownUrl = url.replaceFirst("/taskflow_admin(?=\\?|$)", "/" + schema);
                    String environment = "TASKFLOW_" + service.toUpperCase(Locale.ROOT) + "_DB_";
                    write(config, "spring:\n  datasource:\n    url: \"" + "$" + "{" + environment + "URL:" + ownUrl + "}\"\n"
                            + "    username: \"" + "$" + "{" + environment + "USERNAME:" + account + "}\"\n"
                            + "    password: \"" + "$" + "{" + environment + "PASSWORD:" + secret + "}\"\n");
                }
                var own = load(config);
                String ownUrl = resolve(value(own,"spring","datasource","url"));
                String ownUser = resolve(value(own,"spring","datasource","username"));
                String ownPassword = resolve(value(own,"spring","datasource","password"));
                if ("root".equalsIgnoreCase(ownUser) || !ownUrl.contains("/" + schema + "?"))
                    throw new IllegalStateException("服务连接未使用独立 schema 和账号");
                try (var serviceConnection = DriverManager.getConnection(ownUrl,ownUser,ownPassword)) {
                    try (var statement = serviceConnection.createStatement(); var rows = statement.executeQuery("SELECT DATABASE()")) {
                        rows.next(); if (!schema.equals(rows.getString(1))) throw new IllegalStateException("实际 schema 不匹配");
                    }
                    try (var statement = serviceConnection.createStatement(); var grants = statement.executeQuery("SHOW GRANTS")) {
                        while (grants.next()) {
                            String grant = grants.getString(1);
                            if (grant.startsWith("GRANT ALL PRIVILEGES ON *.*") || grant.contains(" ON \u0060taskflow_admin\u0060."))
                                throw new IllegalStateException("服务账号权限未隔离");
                        }
                    }
                }
                System.out.println("Prepared and verified isolated schema/account for " + schema);
            }
        }
        Path internal = ROOT.resolve("config/taskflow-internal-local.yml");
        if (!Files.exists(internal)) {
            write(internal, "taskflow:\n  execution:\n"
                    + "    control-token: \"" + "$" + "{TASKFLOW_CONTROL_TOKEN:" + random(32) + "}\"\n"
                    + "    dispatch-token: \"" + "$" + "{TASKFLOW_DISPATCH_TOKEN:" + random(32) + "}\"\n"
                    + "    report-token: \"" + "$" + "{TASKFLOW_REPORT_TOKEN:" + random(32) + "}\"\n");
        }
        System.out.println("Internal service secrets are configured in ignored local YAML.");
    }
}
