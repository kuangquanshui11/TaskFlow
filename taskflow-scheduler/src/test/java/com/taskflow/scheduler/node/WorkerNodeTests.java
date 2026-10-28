package com.taskflow.scheduler.node;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import com.taskflow.scheduler.node.dto.WorkerNodeProtocol.*;
import com.taskflow.scheduler.node.exception.WorkerNodeException;
import com.taskflow.scheduler.node.mapper.WorkerNodeMapper;
import com.taskflow.scheduler.node.service.IWorkerNodeService;
import com.taskflow.scheduler.execution.client.WorkerClient;
import com.taskflow.scheduler.execution.dto.ExecutionProtocol.*;
import com.taskflow.scheduler.execution.service.IRunService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="taskflow.nodes.dispatch-gating-enabled=true")
@ActiveProfiles("test")
@ContextConfiguration(initializers=com.taskflow.scheduler.support.IsolatedTestDatabaseInitializer.class)
class WorkerNodeTests {
    static final Instant BASE=Instant.parse("2026-10-27T00:00:00Z");
    @Autowired IWorkerNodeService nodes;
    @Autowired IRunService runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean(name="nodeClock") Clock clock;
    @MockitoBean WorkerClient worker;
    @MockitoSpyBean WorkerNodeMapper mapper;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM worker_node_session");jdbc.update("DELETE FROM worker_node");
        jdbc.update("DELETE FROM dispatch_outbox");jdbc.update("DELETE FROM run_attempt");jdbc.update("DELETE FROM run_execution");
        when(clock.instant()).thenReturn(BASE);
    }
    @Test void dedicatedAuthenticationValidationAndReadOnlyQueryRoles() throws Exception {
        var registration=registration("worker-default",UUID.randomUUID());
        assertThat(call("POST","insert",registration,null).statusCode()).isEqualTo(401);
        assertThat(call("POST","insert",registration,"test-control-token").statusCode()).isEqualTo(401);
        assertThat(call("POST","insert",registration,"test-node-token").statusCode()).isEqualTo(200);
        assertThat(call("GET","select",null,"test-node-token").statusCode()).isEqualTo(401);
        assertThat(call("GET","select",null,"test-control-token").statusCode()).isEqualTo(200);
        assertThat(call("POST","updateHeartbeat",new Heartbeat("worker-default",registration.sessionId(),1,1,-1,0),"test-node-token").statusCode()).isEqualTo(400);
        assertThat(call("GET","select?page=0",null,"test-control-token").statusCode()).isEqualTo(400);
        assertThat(call("GET","selectById?workerId=missing",null,"test-control-token").statusCode()).isEqualTo(404);
    }
    @Test void registrationRetriesAreIdempotentAndDoNotRefreshTheDeadline() {
        var request=registration("worker-default",UUID.randomUUID()); var first=nodes.register(request);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        assertThat(nodes.register(request)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_node_session",Long.class)).isEqualTo(1);
        var changed=new Registration(request.workerId(),"changed",request.sessionId(),request.handlerKeys(),2,4,"http",null,null,null,null);
        assertThatThrownBy(()->nodes.register(changed)).isInstanceOf(WorkerNodeException.class).hasMessageContaining("同一会话");
    }
    @Test void onlineExpiryBoundaryRecoveryAndOutOfOrderHeartbeatsUseSchedulerTime() {
        var registration=registration("worker-default",UUID.randomUUID()); nodes.register(registration);
        when(clock.instant()).thenReturn(BASE.plusSeconds(30));
        assertThat(nodes.selectById("worker-default").status()).isEqualTo("OFFLINE");
        assertThat(nodes.canDispatch("demoSuccess")).isFalse();
        var beat=new Heartbeat("worker-default",registration.sessionId(),1,2,1,2);
        var ack=nodes.heartbeat(beat);
        assertThat(ack.expiresAt()).isEqualTo(BASE.plusSeconds(60));
        when(clock.instant()).thenReturn(BASE.plusSeconds(40));
        assertThat(nodes.heartbeat(beat)).isEqualTo(ack);
        assertThat(nodes.heartbeat(new Heartbeat("worker-default",registration.sessionId(),1,1,0,0))).isEqualTo(ack);
        assertThat(nodes.selectById("worker-default").lastHeartbeatAt()).isEqualTo(BASE.plusSeconds(30));
        assertThat(nodes.selectById("worker-default").status()).isEqualTo("ONLINE");
        assertThatThrownBy(()->nodes.heartbeat(new Heartbeat("worker-default",registration.sessionId(),1,2,0,0))).isInstanceOf(WorkerNodeException.class);
        assertThatThrownBy(()->nodes.heartbeat(new Heartbeat("worker-default",registration.sessionId(),1,3,2,3))).isInstanceOf(WorkerNodeException.class);
    }
    @Test void liveIdentityCannotBeReplacedAndRetiredProcessCannotReviveAfterNewRegistration() {
        var old=registration("worker-default",UUID.randomUUID()); var replacement=registration("worker-default",UUID.randomUUID()); nodes.register(old);
        assertThatThrownBy(()->nodes.register(replacement)).isInstanceOf(WorkerNodeException.class).hasMessageContaining("仍在线");
        when(clock.instant()).thenReturn(BASE.plusSeconds(30));
        assertThat(nodes.register(replacement).generation()).isEqualTo(2);
        assertThatThrownBy(()->nodes.heartbeat(new Heartbeat("worker-default",old.sessionId(),1,100,0,0))).isInstanceOf(WorkerNodeException.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(61));
        assertThatThrownBy(()->nodes.register(old)).isInstanceOf(WorkerNodeException.class).hasMessageContaining("已被替代");
        assertThat(nodes.selectById("worker-default").generation()).isEqualTo(2);
    }
    @Test void sessionLedgerAndNodeWriteRollbackTogether() {
        doAnswer(call->{ call.callRealMethod();throw new IllegalStateException("session transaction failure"); }).when(mapper).save(any(),anyBoolean());
        assertThatThrownBy(()->nodes.register(registration("worker-default",UUID.randomUUID()))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_node",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_node_session",Long.class)).isZero();
    }
    @Test void pagingFilteringSummaryAndLiteralKeywordAreConsistent() {
        nodes.register(registration("worker-default",UUID.randomUUID()));
        when(clock.instant()).thenReturn(BASE.plusSeconds(20));nodes.register(registration("worker-other",UUID.randomUUID()));
        when(clock.instant()).thenReturn(BASE.plusSeconds(30));
        var all=nodes.select("","ALL",1,1);
        assertThat(all.records()).hasSize(1);assertThat(all.total()).isEqualTo(2);
        assertThat(all.summary()).isEqualTo(new Summary(2,1,1));
        assertThat(nodes.select("other","ONLINE",1,10).records().getFirst().workerId()).isEqualTo("worker-other");
        assertThat(nodes.select("","OFFLINE",1,10).records().getFirst().workerId()).isEqualTo("worker-default");
        assertThat(nodes.select("%","ALL",1,10).records()).isEmpty();
        assertThatThrownBy(()->nodes.select("","invalid",1,10)).isInstanceOf(WorkerNodeException.class);
    }
    @Test void onlyOnlineTargetWithMatchingHandlersCanReceiveNewAttempts() {
        var run=runs.insert(new Create(UUID.randomUUID(),new Snapshot(7,1,"node-gate","demoSuccess",json.readTree("{}"),30,1,5,"MANUAL",null,"UTC","SKIP",false,null)));
        runs.dispatchPending();verifyNoInteractions(worker);
        assertThat(runs.selectById(run.id()).attempts().getFirst().expiresAt()).isNull();
        nodes.register(registration("worker-other",UUID.randomUUID()));makeAttemptsDue();runs.dispatchPending();verifyNoInteractions(worker);
        var target=registration("worker-default",UUID.randomUUID());nodes.register(target);
        assertThat(nodes.canDispatch("unknownHandler")).isFalse();
        when(clock.instant()).thenReturn(BASE.plusSeconds(30));makeAttemptsDue();runs.dispatchPending();verifyNoInteractions(worker);
        nodes.heartbeat(new Heartbeat("worker-default",target.sessionId(),1,1,0,0));
        doAnswer(call->{ assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return null; }).when(worker).dispatch(any());
        makeAttemptsDue();runs.dispatchPending();verify(worker,times(1)).dispatch(any());
        assertThat(runs.selectById(run.id()).state()).isEqualTo("RUNNING");
    }
    @Test void onlineNodeWithWrongTransportIsVisibleButIneligibleAndMqQueueOwnershipIsUnique() {
        var first=new Registration("worker-default","mq worker",UUID.randomUUID(),List.of("demoSuccess"),2,4,"rabbitmq","taskflow","taskflow.dispatch.v1","taskflow.worker.default.v1","worker.default");
        nodes.register(first);
        assertThat(nodes.selectById("worker-default").status()).isEqualTo("ONLINE");
        assertThat(nodes.selectById("worker-default").dispatchEligible()).isFalse();
        var conflict=new Registration("worker-other","other",UUID.randomUUID(),first.handlerKeys(),2,4,"rabbitmq",first.dispatchVhost(),first.dispatchExchange(),first.dispatchQueue(),first.dispatchRoutingKey());
        assertThatThrownBy(()->nodes.register(conflict)).isInstanceOf(WorkerNodeException.class);
        assertThat(nodes.select("","ALL",1,10).total()).isEqualTo(1);
    }
    @Test void immediateDispatchUsesDatabaseMillisecondPrecisionInsteadOfRoundingIntoFuture() {
        var mapper=new com.taskflow.scheduler.execution.mapper.RunDataMapper(jdbc);
        var outbox=new com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper(jdbc);
        var id=UUID.randomUUID(); var nanos=BASE.plusNanos(999999);
        mapper.insertAttempt(new com.taskflow.scheduler.execution.model.RunRecords.Attempt(id,UUID.randomUUID(),1,1,"READY",0,nanos,null,null,null,null,null));
        outbox.insert(id,nanos);outbox.prepare(id,"{}",BASE.plusSeconds(30),nanos);
        assertThat(mapper.due(BASE)).contains(id);
        assertThat(outbox.due(BASE)).contains(id);
        assertThat(mapper.attempt(id).orElseThrow().nextDispatchAt()).isEqualTo(BASE);
    }
    private void makeAttemptsDue() { jdbc.update("UPDATE run_attempt SET next_dispatch_at=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1))); }
    Registration registration(String id,UUID session) { return new Registration(id,"测试执行节点",session,List.of("demoSuccess","demoSlow"),2,4,"http",null,null,null,null); }
    HttpResponse<String> call(String method,String path,Object body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/workers/"+path));
        if (token!=null) builder.header("X-Taskflow-Token",token);
        builder.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try(var client=HttpClient.newHttpClient()) { return client.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
