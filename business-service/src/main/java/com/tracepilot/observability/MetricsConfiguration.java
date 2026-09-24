package com.tracepilot.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.*;

@Configuration
public class MetricsConfiguration {
  @Bean
  MeterFilter hideControlRequestMetrics() {
    // Use our business-only timers; default servlet timers would reveal control route labels.
    return MeterFilter.deny(id -> id.getName().startsWith("http.server.requests"));
  }
}
