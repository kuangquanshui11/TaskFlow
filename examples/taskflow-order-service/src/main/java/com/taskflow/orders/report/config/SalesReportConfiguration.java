package com.taskflow.orders.report.config;
import java.time.Clock;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class SalesReportConfiguration {
    @Bean public Clock salesClock() { return Clock.systemUTC(); }
}
