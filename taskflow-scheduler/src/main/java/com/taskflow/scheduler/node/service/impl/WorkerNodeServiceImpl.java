package com.taskflow.scheduler.node.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.taskflow.scheduler.dispatch.config.DispatchProperties;
import com.taskflow.scheduler.node.config.WorkerNodeProperties;
import com.taskflow.scheduler.node.dto.WorkerNodeProtocol.*;
import com.taskflow.scheduler.node.exception.WorkerNodeException;
import com.taskflow.scheduler.node.mapper.WorkerNodeMapper;
import com.taskflow.scheduler.node.model.WorkerNode;
import com.taskflow.scheduler.node.service.IWorkerNodeService;
import org.springframework.beans.factory.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkerNodeServiceImpl implements IWorkerNodeService {
    private final WorkerNodeMapper mapper;
    private final WorkerNodeProperties properties;
    private final DispatchProperties dispatch;
    private final String vhost;
    private final Clock clock;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    public WorkerNodeServiceImpl(WorkerNodeMapper mapper,WorkerNodeProperties properties,DispatchProperties dispatch,
            @Value("${spring.rabbitmq.virtual-host}") String vhost,@Qualifier("nodeClock") Clock clock,JsonMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper;this.properties=properties;this.dispatch=dispatch;this.vhost=vhost;this.clock=clock;this.json=json;
        transactions=new TransactionTemplate(manager);
    }
    @Override
    public Acknowledgement register(Registration request) {
        if (request.maxPending()<request.parallelism() || new HashSet<>(request.handlerKeys()).size()!=request.handlerKeys().size()) throw invalid();
        var route=List.of(request.dispatchVhost()==null?"":request.dispatchVhost(),request.dispatchExchange()==null?"":request.dispatchExchange(),
                request.dispatchQueue()==null?"":request.dispatchQueue(),request.dispatchRoutingKey()==null?"":request.dispatchRoutingKey());
        if (request.dispatchTransport().equals("rabbitmq") ? route.stream().anyMatch(String::isBlank) : route.stream().anyMatch(s->!s.isEmpty())) throw invalid();
        String fingerprint=hash(json.writeValueAsString(request));
        try {
            return transactions.execute(status -> {
                var old=mapper.find(request.workerId(),true); var now=now();
                if (old.isPresent() && old.get().sessionId().equals(request.sessionId())) {
                    if (!old.get().registrationFingerprint().equals(fingerprint)) throw conflict("REGISTRATION_CONFLICT","同一会话不能修改注册能力");
                    return acknowledgement(old.get());
                }
                // 历史 session 永久保留，旧进程不能通过重发注册请求取代新进程。
                if (mapper.sessionExists(request.sessionId())) throw conflict("STALE_NODE_SESSION","节点会话已被替代");
                if (old.isPresent() && old.get().online(now)) throw conflict("NODE_ID_IN_USE","相同节点标识的进程仍在线");
                var node=new WorkerNode(request.workerId(),request.displayName(),request.sessionId(),old.map(n->n.generation()+1).orElse(1L),fingerprint,
                        json.writeValueAsString(request.handlerKeys()),request.parallelism(),request.maxPending(),0,0,request.dispatchTransport(),
                        blankToNull(request.dispatchVhost()),blankToNull(request.dispatchExchange()),blankToNull(request.dispatchQueue()),blankToNull(request.dispatchRoutingKey()),
                        0,now,now,now.plus(properties.offlineTimeout()));
                mapper.save(node,old.isEmpty()); return acknowledgement(node);
            });
        } catch (DuplicateKeyException concurrent) {
            throw conflict("NODE_REGISTRATION_CONFLICT","节点标识、会话或队列已被其他注册占用，请重试并核对配置");
        }
    }
    @Override
    @Transactional
    public Acknowledgement heartbeat(Heartbeat request) {
        var node=mapper.find(request.workerId(),true).orElseThrow(()->new WorkerNodeException(404,"NODE_NOT_REGISTERED","节点尚未注册"));
        if (!node.sessionId().equals(request.sessionId()) || node.generation()!=request.generation()) throw conflict("STALE_NODE_SESSION","节点会话已被替代");
        if (request.runningCount()>node.parallelism() || (long)request.runningCount()+request.pendingCount()>node.maxPending()) throw invalid();
        if (request.sequence()<=node.lastSequence()) {
            if (request.sequence()==node.lastSequence() && (request.runningCount()!=node.runningCount() || request.pendingCount()!=node.pendingCount()))
                throw conflict("HEARTBEAT_CONFLICT","同一心跳序号对应不同负载");
            return acknowledgement(node);
        }
        var now=now(); var expires=now.plus(properties.offlineTimeout());
        mapper.heartbeat(node.workerId(),request.sequence(),request.runningCount(),request.pendingCount(),now,expires);
        return new Acknowledgement(node.workerId(),node.sessionId(),node.generation(),request.sequence(),expires);
    }
    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page select(String keyword,String state,int page,int size) {
        if (keyword==null || keyword.length()>100 || !Set.of("ALL","ONLINE","OFFLINE").contains(state) || page<1 || page>1000000 || size<1 || size>100) throw invalid();
        var now=now(); var search=keyword.trim();
        return new Page(mapper.select(search,state,now,page,size).stream().map(n->view(n,now)).toList(),mapper.count(search,state,now),page,size,mapper.summary(now),now);
    }
    @Override
    @Transactional(readOnly=true)
    public View selectById(String id) {
        if (id==null || !id.matches("[a-z0-9][a-z0-9_.-]{0,99}")) throw invalid();
        return view(mapper.find(id,false).orElseThrow(()->new WorkerNodeException(404,"NODE_NOT_FOUND","节点不存在")),now());
    }
    @Override
    public boolean canDispatch(String handlerKey) {
        if (!properties.dispatchGatingEnabled()) return true;
        return mapper.find(properties.targetWorkerId(),false).filter(n->eligible(n,now())).filter(n->handlers(n).contains(handlerKey)).isPresent();
    }
    private boolean eligible(WorkerNode node,Instant now) {
        return node.workerId().equals(properties.targetWorkerId()) && node.online(now) && node.dispatchTransport().equals(dispatch.transport())
                && (!dispatch.rabbitmq() || Objects.equals(node.dispatchVhost(),vhost) && Objects.equals(node.dispatchExchange(),dispatch.exchange())
                && Objects.equals(node.dispatchQueue(),dispatch.queue()) && Objects.equals(node.dispatchRoutingKey(),dispatch.routingKey()));
    }
    private View view(WorkerNode n,Instant now) {
        return new View(n.workerId(),n.displayName(),n.generation(),handlers(n),n.parallelism(),n.maxPending(),n.runningCount(),n.pendingCount(),
                n.dispatchTransport(),n.dispatchVhost(),n.dispatchExchange(),n.dispatchQueue(),n.dispatchRoutingKey(),n.online(now)?"ONLINE":"OFFLINE",
                n.workerId().equals(properties.targetWorkerId()),eligible(n,now),n.registeredAt(),n.lastHeartbeatAt(),n.expiresAt());
    }
    private List<String> handlers(WorkerNode node) { return Arrays.asList(json.readValue(node.handlerKeysJson(),String[].class)); }
    private Acknowledgement acknowledgement(WorkerNode n) { return new Acknowledgement(n.workerId(),n.sessionId(),n.generation(),n.lastSequence(),n.expiresAt()); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private String blankToNull(String s) { return s==null || s.isEmpty()?null:s; }
    private WorkerNodeException invalid() { return new WorkerNodeException(400,"INVALID_ARGUMENT","节点参数或负载不合法"); }
    private WorkerNodeException conflict(String code,String message) { return new WorkerNodeException(409,code,message); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
