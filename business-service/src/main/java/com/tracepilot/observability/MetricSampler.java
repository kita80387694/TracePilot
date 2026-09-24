package com.tracepilot.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MetricSampler {
  private final HikariDataSource pool;
  private final AuxiliaryDatabase auxiliary;
  private final MeterRegistry meters;
  private final ObjectMapper json;
  private final Path directory;
  private final String environment, version;
  private final boolean enabled;
  private volatile Map<String, Object> latest = Map.of("status", "NO_DATA");
  private long previousCount, previousErrors;
  private double previousMs;
  private Instant previousSampleAt;
  private volatile double pendingValue = Double.NaN, oldestValue = Double.NaN;

  public MetricSampler(
      DataSource ds,
      AuxiliaryDatabase auxiliary,
      MeterRegistry meters,
      ObjectMapper json,
      @Value("${app.data-dir}") String dir,
      @Value("${app.environment}") String environment,
      @Value("${app.deployment-version}") String version,
      @Value("${app.sample-enabled}") boolean enabled) {
    this.pool = (HikariDataSource) ds;
    this.auxiliary = auxiliary;
    this.meters = meters;
    this.json = json;
    this.directory = Path.of(dir).resolve("observability");
    this.environment = environment;
    this.version = version;
    this.enabled = enabled;
    io.micrometer.core.instrument.Gauge.builder(
            "tracepilot.events.pending", this, s -> s.pendingValue)
        .register(meters);
    io.micrometer.core.instrument.Gauge.builder(
            "tracepilot.events.oldest.seconds", this, s -> s.oldestValue)
        .register(meters);
  }

  public Map<String, Object> latest() {
    var result = new LinkedHashMap<>(latest);
    if (result.containsKey("collectedAt")) {
      long age =
          Duration.between(Instant.parse((String) result.get("collectedAt")), Instant.now())
              .toMillis();
      result.put("sampleAgeMs", age);
      if (age > 5000) result.put("status", "STALE");
    }
    return result;
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 1000)
  public synchronized void sample() {
    if (!enabled) return;
    Instant started = Instant.now();
    var sample = new LinkedHashMap<String, Object>();
    sample.put("time", started.toString());
    sample.put("service", "tracepilot-business");
    sample.put("environment", environment);
    sample.put("deploymentVersion", version);
    sample.put("instanceId", SafeJsonEncoder.INSTANCE);
    sample.put("evidenceId", UUID.randomUUID().toString());
    long count = 0, errors = 0, rejected = 0;
    double durationMs = 0;
    var routes = new ArrayList<Map<String, Object>>();
    for (var timer : meters.find("tracepilot.http").timers()) {
      var id = timer.getId();
      count += timer.count();
      durationMs += timer.totalTime(TimeUnit.MILLISECONDS);
      int status = Integer.parseInt(id.getTag("status"));
      if (status >= 500) errors += timer.count();
      if (status == 409) rejected += timer.count();
      routes.add(
          Map.of(
              "route",
              id.getTag("route"),
              "method",
              id.getTag("method"),
              "status",
              status,
              "count",
              timer.count(),
              "totalMs",
              timer.totalTime(TimeUnit.MILLISECONDS),
              "maxMs",
              timer.max(TimeUnit.MILLISECONDS)));
    }
    var http = new LinkedHashMap<String, Object>();
    http.put("count", count);
    http.put("errors5xx", errors);
    http.put("businessRejections", rejected);
    http.put("totalMs", durationMs);
    http.put("routes", routes);
    long delta = count - previousCount;
    http.put("intervalCount", delta);
    http.put("intervalStart", previousSampleAt == null ? null : previousSampleAt.toString());
    http.put("intervalEnd", started.toString());
    http.put("intervalCoverage", previousSampleAt == null ? "FIRST_SAMPLE_START_UNKNOWN" : "BETWEEN_SAMPLES");
    http.put("errorRate", delta > 0 ? (double) (errors - previousErrors) / delta : null);
    http.put("averageMs", delta > 0 ? (durationMs - previousMs) / delta : null);
    http.put("status", delta > 0 ? "AVAILABLE" : "NO_REQUESTS");
    previousCount = count;
    previousErrors = errors;
    previousMs = durationMs;
    previousSampleAt = started;
    sample.put("http", http);
    var mx = pool.getHikariPoolMXBean();
    sample.put(
        "pool",
        mx == null
            ? Map.of("status", "UNAVAILABLE")
            : Map.of(
                "status",
                "AVAILABLE",
                "sampledAt", Instant.now().toString(),
                "active",
                mx.getActiveConnections(),
                "idle",
                mx.getIdleConnections(),
                "pending",
                mx.getThreadsAwaitingConnection(),
                "total",
                mx.getTotalConnections(),
                "max",
                pool.getMaximumPoolSize()));
    var backlog = new LinkedHashMap<String, Object>();
    try {
      var row =
          auxiliary.jdbc.queryForMap(
              "SELECT COUNT(*)"
                  + " pending,COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,created_at,UTC_TIMESTAMP(6)))/1000000.0,0)"
                  + " oldestSeconds FROM outbox WHERE status<>'DONE'");
      backlog.put("status", "AVAILABLE");
      backlog.putAll(row);
      pendingValue = ((Number) row.get("pending")).doubleValue();
      oldestValue = ((Number) row.get("oldestSeconds")).doubleValue();
    } catch (Exception e) {
      pendingValue = Double.NaN;
      oldestValue = Double.NaN;
      backlog.put("status", "UNAVAILABLE");
      backlog.put("pending", null);
      backlog.put("oldestSeconds", null);
      Evidence.error("event_metrics_unavailable", e);
    }
    backlog.put("success", counter("tracepilot.events.success"));
    backlog.put("failure", counter("tracepilot.events.failure"));
    sample.put("events", backlog);
    sample.put(
        "status",
        mx != null && backlog.get("status").equals("AVAILABLE") ? "AVAILABLE" : "PARTIAL");
    sample.put("collectedAt", Instant.now().toString());
    sample.put("collectionDurationMs", Duration.between(started, Instant.now()).toMillis());
    latest = Collections.unmodifiableMap(sample);
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve("metrics." + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
      Files.writeString(
          file,
          json.writeValueAsString(sample) + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      try (var files = Files.list(directory)) {
        for (Path p :
            files
                .filter(
                    p ->
                        p.getFileName()
                            .toString()
                            .matches("metrics\\.\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
                .toList())
          if (Files.getLastModifiedTime(p)
              .toInstant()
              .isBefore(Instant.now().minus(Duration.ofDays(7)))) Files.delete(p);
      }
    } catch (Exception e) {
      Evidence.error("metric_archive_unavailable", e);
    }
  }

  private double counter(String name) {
    var c = meters.find(name).counter();
    return c == null ? 0 : c.count();
  }
}
