package com.taskflow.scheduler.dispatch;

import java.time.*;
import java.util.UUID;
import com.taskflow.scheduler.dispatch.config.DispatchProperties;
import com.taskflow.scheduler.dispatch.model.OutboxEvent;
import com.taskflow.scheduler.dispatch.service.impl.RabbitDispatchPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RabbitDispatchPublisherTests {
    @Test void waitsForCorrelatedConfirmAndPublishesPersistentVersionedMessage() {
        var rabbit=mock(RabbitTemplate.class); var event=event();
        doAnswer(call -> {
            Message message=call.getArgument(2); CorrelationData data=call.getArgument(3);
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
            assertThat(message.getMessageProperties().getType()).isEqualTo("taskflow.dispatch.v1");
            data.getFuture().complete(new CorrelationData.Confirm(true,null)); return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        new RabbitDispatchPublisher(rabbit,properties()).publish(event);
    }
    @Test void brokerNackAndAckWithMandatoryReturnAreBothFailures() {
        for (boolean returned : new boolean[]{false,true}) {
            var rabbit=mock(RabbitTemplate.class);
            doAnswer(call -> {
                CorrelationData data=call.getArgument(3);
                if (returned) data.setReturned(new ReturnedMessage(call.getArgument(2),312,"NO_ROUTE","dispatch","worker"));
                data.getFuture().complete(new CorrelationData.Confirm(returned,"injected")); return null;
            }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
            assertThatThrownBy(() -> new RabbitDispatchPublisher(rabbit,properties()).publish(event())).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void missingConfirmationIsFailureInsteadOfAssumingDelivery() {
        assertThatThrownBy(() -> new RabbitDispatchPublisher(mock(RabbitTemplate.class),properties()).publish(event()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unconfirmed");
    }
    private DispatchProperties properties() {
        return new DispatchProperties("rabbitmq","dispatch","queue","worker","dead","dead-queue","dead-worker",
                Duration.ofMillis(20),Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(10),3,false,Duration.ofSeconds(2));
    }
    private OutboxEvent event() {
        var id=UUID.randomUUID();
        return new OutboxEvent(id,id,"SENDING","{}",Instant.now().plusSeconds(60),1,Instant.now(),1,Instant.now().plusSeconds(30),null);
    }
}
