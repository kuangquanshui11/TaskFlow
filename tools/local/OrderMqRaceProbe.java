import java.nio.file.*;
import java.time.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Pauses the first real OrderMapper.lock after it obtains the InnoDB row lock. */
public final class OrderMqRaceProbe {
    public static void main(String[] args) throws Exception {
        var root = Path.of(System.getenv("TASKFLOW_ORDER_RACE_ROOT")).toAbsolutePath().normalize();
        var name = root.getFileName().toString();
        if (!name.matches("taskflow_it_[a-f0-9]{32}")
                || !root.toString().replace('\\', '/').contains("/.tools/dispatch-mysql/")
                || !(name + "_order").equals(System.getenv("TASKFLOW_FAULT_DB_SCHEMA"))) {
            throw new IllegalArgumentException("An isolated order acceptance schema is required");
        }
        var application = new SpringApplication(Class.forName("com.taskflow.orders.OrderServiceApplication"));
        application.addInitializers(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!beanName.equals("orderMapper")) return bean;
                var proxy = new ProxyFactory(bean);
                proxy.addAdvice((MethodInterceptor) call -> {
                    var result = call.proceed();
                    if (result != null && call.getMethod().getName().equals("lock")) {
                        pause(root, (String)call.getArguments()[0]);
                    }
                    return result;
                });
                return proxy.getProxy();
            }
        }));
        application.run(args);
    }

    private static void pause(Path root, String orderId) throws Exception {
        var arm = root.resolve("order-row.arm");
        if (!Files.exists(arm) || !Files.readString(arm).equals(orderId)) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Order row must be locked in a real transaction");
        }
        Files.move(arm, root.resolve("order-row.hit"), StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(root.resolve("order-row.json"), "{\"orderId\":\"" + orderId + "\",\"lockedAt\":\"" + Instant.now() + "\"}");
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (!Files.exists(root.resolve("order-row.release"))) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Order race barrier timed out");
            Thread.sleep(25);
        }
    }
}
