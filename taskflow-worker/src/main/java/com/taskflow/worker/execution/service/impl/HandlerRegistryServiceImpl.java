package com.taskflow.worker.execution.service.impl;

import java.util.*;
import com.taskflow.worker.execution.dto.ExecutionProtocol.Snapshot;
import com.taskflow.worker.execution.model.ExecutionContext;
import com.taskflow.worker.execution.service.*;
import org.springframework.stereotype.Service;

@Service
public class HandlerRegistryServiceImpl implements IHandlerRegistryService {
    private final Map<String,ITaskHandler> handlers;
    public HandlerRegistryServiceImpl(List<ITaskHandler> providers) {
        var registry=new HashMap<String,ITaskHandler>();
        for(var provider:providers) for(var key:provider.keys()) {
            if(key==null || key.isBlank() || registry.putIfAbsent(key,provider)!=null)
                throw new IllegalStateException("Duplicate or empty Handler key: "+key);
        }
        handlers=Map.copyOf(registry);
    }
    @Override public Set<String> keys() { return handlers.keySet(); }
    @Override public String execute(Snapshot snapshot,ExecutionContext context) throws InterruptedException {
        var handler=handlers.get(snapshot.handlerKey());
        if(handler==null) throw new IllegalArgumentException("Handler not registered");
        return handler.execute(snapshot,context);
    }
}
