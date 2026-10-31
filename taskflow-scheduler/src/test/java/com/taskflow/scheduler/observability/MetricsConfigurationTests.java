package com.taskflow.scheduler.observability;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import com.taskflow.scheduler.observability.config.*;
import com.taskflow.scheduler.observability.service.IMetricsSampler;
import com.taskflow.scheduler.observability.service.impl.MetricsSamplerImpl;
import com.taskflow.scheduler.dispatch.mapper.DispatchOutboxMapper;
import com.taskflow.scheduler.execution.mapper.RunDataMapper;
import com.taskflow.scheduler.node.mapper.WorkerNodeMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetricsConfigurationTests {
    @Test void allGaugesStartUnknownAndFirstFailureKeepsThemUnknown() {
        var registry = new SimpleMeterRegistry();
        try {
            var mapper = mock(WorkerNodeMapper.class);
            when(mapper.oldestOnlineHeartbeat(any())).thenThrow(new IllegalStateException("injected"));
            var sampler = new MetricsSamplerImpl(mock(DispatchOutboxMapper.class), mock(RunDataMapper.class), mapper, registry, Clock.systemUTC());
            registry.getMeters().stream().filter(m -> m instanceof Gauge).forEach(m -> assertThat(((Gauge)m).value()).isEqualTo(-1));
            sampler.sample();
            registry.getMeters().stream().filter(m -> m instanceof Gauge).forEach(m -> assertThat(((Gauge)m).value()).isEqualTo(-1));
            assertThat(registry.get("taskflow.metrics.sample").tag("outcome","failed").counter().count()).isEqualTo(1);
        } finally { registry.close(); }
    }

    @Test void disabledSamplingCreatesNeitherMetersNorScheduler() {
        var runner = new ApplicationContextRunner().withUserConfiguration(MetricsConfiguration.class, MetricsSamplerImpl.class)
                .withPropertyValues("taskflow.metrics.enabled=false", "taskflow.metrics.sample-interval=15s")
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(IMetricsSampler.class).doesNotHaveBean("metricsSampleScheduler");
            assertThat(context.getBean(SimpleMeterRegistry.class).getMeters()).isEmpty();
        });
    }

    @Test void sampleIntervalHasInclusiveBounds() {
        for (int seconds : new int[]{-1,0,4,301})
            assertThatThrownBy(() -> new MetricsProperties(true, Duration.ofSeconds(seconds))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new MetricsProperties(true, Duration.ofSeconds(5))).doesNotThrowAnyException();
        assertThatCode(() -> new MetricsProperties(true, Duration.ofSeconds(300))).doesNotThrowAnyException();
    }

    @Test void otlpMetricsUrlIsDerivedAndExplicitConfigurationWins() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of("TASKFLOW_OTLP_ENDPOINT", "http://collector:4318/v1/traces")));
        new OtlpMetricsEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.otlp.metrics.export.url")).isEqualTo("http://collector:4318/v1/metrics");
        env.getPropertySources().addFirst(new MapPropertySource("override", Map.of("management.otlp.metrics.export.url", "http://other:4318/v1/metrics")));
        assertThat(env.getProperty("management.otlp.metrics.export.url")).isEqualTo("http://other:4318/v1/metrics");
    }
}
