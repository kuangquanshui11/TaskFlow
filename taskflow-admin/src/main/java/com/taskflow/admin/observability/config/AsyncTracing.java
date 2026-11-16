package com.taskflow.admin.observability.config;

import java.util.HashMap;
import java.util.Map;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class AsyncTracing {
    private final Tracer tracer;
    private final Propagator propagator;

    public AsyncTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public record Context(String traceparent, String tracestate) {
        public static final Context EMPTY = new Context(null, null);

        private String header(String name) {
            return switch (name) {
                case "traceparent" -> traceparent;
                case "tracestate" -> tracestate;
                default -> null;
            };
        }

        public String traceId() {
            return traceparent == null || traceparent.length() < 55 ? null : traceparent.substring(3, 35);
        }
    }

    public Context capture() {
        var current = tracer.currentSpan();
        if (current == null) return Context.EMPTY;
        Map<String, String> headers = new HashMap<>();
        propagator.inject(current.context(), headers, Map::put);
        return new Context(headers.get("traceparent"), headers.get("tracestate"));
    }

    public Trace start(String name, Context parent, Map<String, ?> fields) {
        var builder = parent.traceparent() == null ? tracer.spanBuilder().setNoParent()
                : propagator.extract(parent, Context::header);
        var span = builder.name(name).start();
        var scope = tracer.withSpan(span);
        Map<String, String> previous = new HashMap<>();
        fields.forEach((key, value) -> {
            previous.put(key, MDC.get(key));
            MDC.put(key, value.toString());
            span.tag(key, value.toString());
        });
        previous.put("event", MDC.get("event"));
        MDC.put("event", name);
        return new Trace(span, scope, previous);
    }

    public void tag(String name, Object value) {
        var span = tracer.currentSpan();
        if (span != null) span.tag(name, value.toString());
    }

    public record Trace(Span span, Tracer.SpanInScope scope, Map<String, String> previous) implements AutoCloseable {
        @Override
        public void close() {
            previous.forEach((name, value) -> {
                if (value == null) MDC.remove(name);
                else MDC.put(name, value);
            });
            scope.close();
            span.end();
        }
    }
}
