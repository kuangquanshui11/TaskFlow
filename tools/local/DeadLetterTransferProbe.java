import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.rabbitmq.client.*;

/** 在专用测试 vhost 验证 quorum 目标拒收、源保留和恢复转移。 */
public final class DeadLetterTransferProbe {
    private static final String SOURCE = "taskflow.worker.default.v1";
    private static final String TARGET = "taskflow.worker.default.dead.v1";

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !args[1].matches("taskflow_it_[0-9a-f]{32}"))
            throw new IllegalArgumentException("A disposable test vhost is required");
        var factory = new ConnectionFactory();
        factory.setUsername(System.getenv("TASKFLOW_P02_PROBE_USER"));
        factory.setPassword(System.getenv("TASKFLOW_P02_PROBE_PASSWORD"));
        factory.setVirtualHost(args[1]);
        factory.setConnectionTimeout(3000);
        factory.setAutomaticRecoveryEnabled(false);
        try (var connection = factory.newConnection(Address.parseAddresses(args[0]))) {
            switch (args[2]) {
                case "prepare-reliable", "prepare-legacy" -> prepare(connection, args[2].equals("prepare-reliable"));
                case "recover" -> recover(connection, UUID.fromString(args[3]).toString(), Integer.parseInt(args[4]));
                case "duplicate" -> duplicate(connection, UUID.fromString(args[3]).toString());
                default -> throw new IllegalArgumentException("Unknown probe phase");
            }
        }
    }

    private static void prepare(Connection connection, boolean reliable) throws Exception {
        try (var channel = connection.createChannel()) {
            channel.confirmSelect();
            int fillers = 0;
            if (reliable) {
                while (fillers < 32 && publish(channel, TARGET, "filler-" + fillers)) fillers++;
                check(fillers > 0 && fillers < 32, "Full target queue refused a publication with negative confirm");
            }
            String marker = UUID.randomUUID().toString();
            check(publish(channel, SOURCE, marker), "Source quorum confirmed original publication");
            rejectOriginal(channel, marker);
            check(channel.basicGet(SOURCE, false) == null, "Rejected message is unavailable to source consumers");
            System.out.println("MARKER " + marker);
            System.out.println("FILLERS " + fillers);
        }
    }

    private static void recover(Connection connection, String marker, int fillers) throws Exception {
        if (fillers < 0 || fillers >= 32) throw new IllegalArgumentException("Invalid fixture count");
        try (var channel = connection.createChannel()) {
            for (int i = 0; i < fillers; i++) {
                var filler = await(channel, TARGET, Duration.ofSeconds(45));
                checkBody(filler, "filler-" + i);
                channel.basicAck(filler.getEnvelope().getDeliveryTag(), false);
            }
            // Broker 的内部转移重试可跨越数分钟。
            var dead = await(channel, TARGET, fillers == 0 ? Duration.ofSeconds(45) : Duration.ofSeconds(270));
            verifyMarker(dead, marker);
            Object header = dead.getProps().getHeaders().get("x-death");
            if (!(header instanceof java.util.List<?> entries) || entries.isEmpty()
                    || !(entries.getFirst() instanceof java.util.Map<?, ?> death)
                    || !"rejected".equals(String.valueOf(death.get("reason")))
                    || !SOURCE.equals(String.valueOf(death.get("queue"))))
                throw new IllegalStateException("Missing source queue or broker rejection reason");
            System.out.println(fillers > 0 ? "PASS Original body and ID transferred after target recovered, without republishing the original"
                    : "PASS Legacy broker transferred the rejected delivery while the target was available");
        }
        try (var channel = connection.createChannel()) {
            var replay = await(channel, TARGET, Duration.ofSeconds(45));
            verifyMarker(replay, marker);
            check(replay.getEnvelope().isRedeliver(), "Closing the channel before ACK redelivered the same dead letter");
        }
    }

    private static void duplicate(Connection connection, String marker) throws Exception {
        try (var channel = connection.createChannel()) {
            channel.confirmSelect();
            check(publish(channel, SOURCE, marker), "Duplicate fixture published after the transfer was verified");
            rejectOriginal(channel, marker);
            var deadline = Instant.now().plusSeconds(45);
            while (channel.messageCount(TARGET) < 2 && Instant.now().isBefore(deadline)) Thread.sleep(250);
            check(channel.messageCount(TARGET) >= 2, "Two copies are waiting in the target queue");
        }
    }

    private static void rejectOriginal(Channel channel, String marker) throws Exception {
        var delivery = await(channel, SOURCE, Duration.ofSeconds(45));
        verifyMarker(delivery, marker);
        channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
    }

    private static boolean publish(Channel channel, String queue, String body) throws Exception {
        channel.basicPublish("", queue, true, new AMQP.BasicProperties.Builder().deliveryMode(2)
                .messageId(body).build(), body.getBytes(StandardCharsets.UTF_8));
        return channel.waitForConfirms(5000);
    }

    private static GetResponse await(Channel channel, String queue, Duration timeout) throws Exception {
        var deadline = Instant.now().plus(timeout);
        do {
            var delivery = channel.basicGet(queue, false);
            if (delivery != null) return delivery;
            Thread.sleep(250);
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("No delivery on " + queue);
    }

    private static void verifyMarker(GetResponse delivery, String marker) {
        checkBody(delivery, marker);
        if (!marker.equals(delivery.getProps().getMessageId())) throw new IllegalStateException("Unexpected message ID");
    }

    private static void checkBody(GetResponse delivery, String expected) {
        if (!expected.equals(new String(delivery.getBody(), StandardCharsets.UTF_8)))
            throw new IllegalStateException("Unexpected body while verifying isolated queues");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException(name);
        System.out.println("PASS " + name);
    }
}
