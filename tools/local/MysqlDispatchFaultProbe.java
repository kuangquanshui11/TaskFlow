import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** External acceptance fixture, loaded only by PropertiesLauncher; never packaged in a service. */
public final class MysqlDispatchFaultProbe implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final JsonMapper JSON = new JsonMapper();

    private static Path root() {
        var path = Path.of(System.getProperty("taskflow.fault.root")).toAbsolutePath().normalize();
        if (!path.toString().replace('\\', '/').contains("/.tools/dispatch-mysql/"))
            throw new IllegalArgumentException("Isolated dispatch-mysql evidence directory required");
        return path;
    }

    @Override public void initialize(ConfigurableApplicationContext context) {
        root();
        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource source) {
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                            (proxy, method, args) -> {
                                var result = invoke(source, method, args);
                                return method.getName().equals("getConnection") ? connection((Connection)result) : result;
                            });
                }
                if (name.equals("rabbitDispatchPublisher")) {
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), bean.getClass().getInterfaces(),
                            (proxy, method, args) -> {
                                if (method.getName().equals("publish")) {
                                    if (TransactionSynchronizationManager.isActualTransactionActive())
                                        throw new IllegalStateException("Publish barrier must be outside SQL transaction");
                                    gate("before-publish", Map.of("eventId", args[0].getClass().getMethod("eventId").invoke(args[0]).toString()));
                                }
                                return invoke(bean, method, args);
                            });
                }
                return bean;
            }
        });
    }

    private static Connection connection(Connection target) {
        boolean[] insertsOutbox = {false};
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.toLowerCase(Locale.ROOT).matches("(?s).*insert\\s+into\\s+dispatch_outbox\\b.*"))
                        insertsOutbox[0] = true;
                    if (method.getName().equals("commit") && insertsOutbox[0]) gate("before-commit", Map.of());
                    var result = invoke(target, method, args);
                    if (Set.of("commit", "rollback").contains(method.getName())) insertsOutbox[0] = false;
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static void gate(String name, Map<String, Object> details) throws Exception {
        var directory = root();
        var arm = directory.resolve(name + ".arm");
        try { Files.move(arm, directory.resolve(name + ".hit")); }
        catch (NoSuchFileException raced) { return; }
        var marker = new LinkedHashMap<>(details);
        marker.put("time", Instant.now().toString());
        marker.put("pid", ProcessHandle.current().pid());
        marker.put("transactionActive", TransactionSynchronizationManager.isActualTransactionActive());
        var temporary = directory.resolve(name + ".tmp");
        Files.writeString(temporary, JSON.writeValueAsString(marker));
        Files.move(temporary, directory.resolve(name + ".json"), StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!Files.exists(directory.resolve(name + ".release"))) {
            if (System.nanoTime() > deadline) throw new SQLException("Fault barrier timed out: " + name);
            Thread.sleep(25);
        }
    }

    /** Read-only external evidence query, using the same test YAML datasource settings via environment. */
    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean("taskflow.fault.boot")) {
            var application = new SpringApplication(Class.forName("com.taskflow.scheduler.TaskFlowSchedulerApplication"));
            application.addInitializers(new MysqlDispatchFaultProbe());
            application.run(args);
            return;
        }
        String schema = args[0];
        if (!schema.matches("taskflow_it_[a-f0-9]{32}_(scheduler|worker|order)"))
            throw new IllegalArgumentException("Isolated schema required");
        if (!args[1].stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ") || args[1].contains(";"))
            throw new IllegalArgumentException("One SELECT required");
        if (!schema.equals(System.getenv("TASKFLOW_FAULT_DB_SCHEMA"))) throw new IllegalArgumentException("Schema environment mismatch");
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(System.getenv("TASKFLOW_FAULT_YAML")));
        var settings = Objects.requireNonNull(yaml.getObject());
        var url = new StandardEnvironment().resolveRequiredPlaceholders(settings.getProperty("spring.datasource.url"));
        try (var db = DriverManager.getConnection(url, settings.getProperty("spring.datasource.username"),
                settings.getProperty("spring.datasource.password")); var statement = db.createStatement()) {
            db.setReadOnly(true);
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery(args[1])) {
                var result = new ArrayList<Object>();
                var metadata = rows.getMetaData();
                while (rows.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        Object value = rows.getObject(i);
                        if (value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor)
                            value = value.toString();
                        row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
                    }
                    result.add(row);
                }
                System.out.println(JSON.writeValueAsString(result));
            }
        }
    }
}
