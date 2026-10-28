package com.taskflow.scheduler.node;

import java.time.*;
import java.util.*;
import com.taskflow.scheduler.node.dto.WorkerNodeProtocol.*;
import com.taskflow.scheduler.node.service.IWorkerNodeService;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.service.IRunService;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.dispatch.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"taskflow.nodes.dispatch-gating-enabled=true","taskflow.dispatch.transport=rabbitmq"})
@ActiveProfiles("test")
@ContextConfiguration(initializers=com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class WorkerNodeMqGatingTests {
    @Autowired IWorkerNodeService nodes;
    @Autowired IRunService runs;
    @Autowired IDispatchRelayService relay;
    @Autowired DispatchOutboxMapper outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoBean IDispatchPublisher publisher;
    @MockitoBean(name="nodeClock") Clock clock;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM worker_node_session");jdbc.update("DELETE FROM worker_node");
        jdbc.update("DELETE FROM dispatch_outbox");jdbc.update("DELETE FROM run_attempt");jdbc.update("DELETE FROM run_execution");
        when(clock.instant()).thenReturn(WorkerNodeTests.BASE);
    }
    @Test void wrongRouteBlocksPreparationAndExpiredNodeBlocksPublishingWithoutSpendingBudget() {
        var run=runs.insert(new Create(UUID.randomUUID(),new Snapshot(7,1,"mq-node-gate","demoSuccess",json.readTree("{}"),60,1,5,"MANUAL",null,"UTC","SKIP",false,null)));
        var id=run.attempts().getFirst().id();
        nodes.register(registration("wrong.queue",UUID.randomUUID()));runs.dispatchPending();
        assertThat(outbox.find(id,false).orElseThrow().state()).isEqualTo("WAITING");
        assertThat(nodes.selectById("worker-default").dispatchEligible()).isFalse();
        when(clock.instant()).thenReturn(WorkerNodeTests.BASE.plusSeconds(30));
        var correct=registration("taskflow.worker.default.v1",UUID.randomUUID());nodes.register(correct);
        jdbc.update("UPDATE run_attempt SET next_dispatch_at=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)));runs.dispatchPending();
        assertThat(outbox.find(id,false).orElseThrow().state()).isEqualTo("READY");
        when(clock.instant()).thenReturn(WorkerNodeTests.BASE.plusSeconds(60));relay.relay();
        assertThat(outbox.find(id,false).orElseThrow().publishAttempts()).isZero();verifyNoInteractions(publisher);
        nodes.heartbeat(new Heartbeat("worker-default",correct.sessionId(),2,1,0,0));
        jdbc.update("UPDATE dispatch_outbox SET next_publish_at=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)));relay.relay();
        assertThat(outbox.find(id,false).orElseThrow().state()).isEqualTo("PUBLISHED");verify(publisher,times(1)).publish(any());
    }
    private Registration registration(String queue,UUID session) {
        return new Registration("worker-default","mq node",session,List.of("demoSuccess"),2,4,"rabbitmq","taskflow","taskflow.dispatch.v1",queue,"worker.default");
    }
}
