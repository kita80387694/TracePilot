package com.tracepilot.drills;

import static com.tracepilot.api.ApiSupport.require;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracepilot.events.DeliveryGate;
import com.tracepilot.observability.AuxiliaryDatabase;
import com.tracepilot.reporting.ReportQueryPolicy;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DrillManager {
  private final HikariDataSource pool;
  private final DeliveryGate gate;
  private final ReportQueryPolicy policy;
  private final AuxiliaryDatabase auxiliary;
  private final ObjectMapper json;
  private final Path dir;
  private final String environment, version;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService watchdog =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "drill-recovery");
            t.setDaemon(true);
            return t;
          });
  private Run active;

  private static final class Run {
    final Map<String, Object> data;
    final CountDownLatch stop = new CountDownLatch(1);
    final Set<Connection> held = ConcurrentHashMap.newKeySet();
    final List<Future<?>> jobs = new ArrayList<>();
    volatile boolean running = true;

    Run(Map<String, Object> data) {
      this.data = data;
    }
  }

  public DrillManager(
      DataSource ds,
      DeliveryGate gate,
      ReportQueryPolicy policy,
      AuxiliaryDatabase auxiliary,
      ObjectMapper json,
      @Value("${app.data-dir}") String dir,
      @Value("${app.environment}") String environment,
      @Value("${app.deployment-version}") String version)
      throws Exception {
    require("demo".equals(environment), 500, "DEMO_ENVIRONMENT_REQUIRED");
    this.pool = (HikariDataSource) ds;
    this.gate = gate;
    this.policy = policy;
    this.auxiliary = auxiliary;
    this.json = json;
    this.dir = Path.of(dir).resolve("private/drill-runs");
    this.environment = environment;
    this.version = version;
    Files.createDirectories(this.dir);
    // Injection is process-local and never automatically resumed after a restart.
    try (var files = Files.list(this.dir)) {
      for (Path p :
          files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).toList()) {
        var row = json.readValue(Files.readString(p), new TypeReference<Map<String, Object>>() {});
        if ("RUNNING".equals(row.get("status"))) {
          row.put("status", "INTERRUPTED");
          row.put("endedAt", Instant.now().toString());
          row.put("recovery", Map.of("reason", "PROCESS_RESTART", "switchesReset", true));
          persist(row);
        }
      }
    }
    watchdog.scheduleWithFixedDelay(
        () -> {
          try {
            expire();
          } catch (Exception ignored) {
            /* Next tick retries recovery; no labels enter business logs. */
          }
        },
        200,
        200,
        TimeUnit.MILLISECONDS);
  }

  public synchronized Map<String, Object> start(
      String scenario, int seconds, long actor, int concurrency, int requests, long datasetRows) {
    require(Set.of("F01", "F02", "F03").contains(scenario), 400, "UNKNOWN_SCENARIO");
    require(seconds >= 1 && seconds <= 300, 400, "DURATION_OUT_OF_RANGE");
    require(active == null, 409, "DRILL_ALREADY_RUNNING");
    require(!scenario.equals("F02") || datasetRows == 1000000, 409, "DATASET_NOT_READY");
    Instant start = Instant.now(), deadline = start.plusSeconds(seconds);
    var data = new LinkedHashMap<String, Object>();
    data.put("id", UUID.randomUUID().toString());
    data.put("scenario", scenario);
    data.put("status", "RUNNING");
    data.put("environment", environment);
    data.put("service", "tracepilot-business");
    data.put("deploymentVersion", version);
    data.put("actorId", actor);
    data.put("startedAt", start.toString());
    data.put("deadline", deadline.toString());
    data.put("durationSeconds", seconds);
    data.put("datasetRows", datasetRows);
    data.put("load", Map.of("concurrency", concurrency, "requestsPerPhase", requests));
    data.put("poolMax", pool.getMaximumPoolSize());
    data.put(
        "businessScale",
        auxiliary.jdbc.queryForMap(
            "SELECT (SELECT COUNT(*) FROM app_user) users,(SELECT COUNT(*) FROM slot) slots,(SELECT"
                + " COUNT(*) FROM participation) participationRows,(SELECT COUNT(*) FROM outbox"
                + " WHERE status<>'DONE') pendingEvents"));
    persist(data);
    Run run = new Run(data);
    active = run;
    try {
      switch (scenario) {
        case "F01" -> {
          for (int i = 0; i < pool.getMaximumPoolSize(); i++)
            run.jobs.add(workers.submit(() -> hold(run, deadline)));
        }
        case "F02" -> policy.alternateUntil(deadline);
        case "F03" -> gate.pauseUntil(deadline);
      }
    } catch (Exception e) {
      stop((String) data.get("id"), "START_FAILED");
      throw e;
    }
    return new LinkedHashMap<>(data);
  }

  private void hold(Run run, Instant deadline) {
    while (run.running && Instant.now().isBefore(deadline)) {
      try (Connection c = pool.getConnection()) {
        if (!run.running) return;
        run.held.add(c);
        try {
          if (run.running)
            run.stop.await(
                Math.max(1, Duration.between(Instant.now(), deadline).toMillis()),
                TimeUnit.MILLISECONDS);
        } finally {
          run.held.remove(c);
        }
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        if (!run.running) return;
      }
    }
  }

  private synchronized void expire() {
    if (active != null
        && Instant.now().isAfter(Instant.parse((String) active.data.get("deadline"))))
      stop((String) active.data.get("id"), "AUTO_DEADLINE");
  }

  public synchronized Map<String, Object> stop(String id, String reason) {
    return stop(id, reason, 0);
  }

  public synchronized Map<String, Object> current() {
    return active == null ? Map.of("status", "IDLE") : get((String) active.data.get("id"));
  }

  public synchronized Map<String, Object> stop(String id, String reason, long stoppedBy) {
    if (active == null || !id.equals(active.data.get("id"))) return get(id);
    Run run = active;
    run.running = false;
    gate.resume();
    policy.restore();
    run.stop.countDown();
    int closeFailures = 0;
    for (Connection c : run.held)
      try {
        c.close();
      } catch (Exception ignored) {
        closeFailures++;
      }
    for (Future<?> job : run.jobs) job.cancel(true);
    var recovery = new LinkedHashMap<String, Object>();
    recovery.put("reason", reason);
    recovery.put("consumerEnabled", gate.open());
    recovery.put("normalQueryPath", !policy.alternate());
    recovery.put("inFlightQueryTimeoutSeconds", 4);
    recovery.put("heldConnectionsClosed", closeFailures == 0);
    recovery.put("closeFailures", closeFailures);
    try {
      recovery.put(
          "businessConstraintViolations",
          auxiliary.jdbc.queryForObject(
              "SELECT COUNT(*) FROM slot s LEFT JOIN (SELECT slot_id,COUNT(*) n FROM participation"
                  + " WHERE status='RESERVED' GROUP BY slot_id) p ON p.slot_id=s.id WHERE"
                  + " s.reserved_count<>COALESCE(p.n,0) OR s.reserved_count>s.capacity OR"
                  + " s.reserved_count<0",
              Long.class));
    } catch (Exception e) {
      recovery.put("constraintCheck", "UNAVAILABLE");
    }
    run.data.put("endedAt", Instant.now().toString());
    run.data.put("stoppedBy", stoppedBy == 0 ? "SYSTEM" : stoppedBy);
    run.data.put("status", closeFailures == 0 ? "STOPPED" : "RECOVERY_FAILED");
    run.data.put("recovery", recovery);
    // Clear the active guard only after a durable terminal record. Switches are already restored.
    persist(run.data);
    active = null;
    return new LinkedHashMap<>(run.data);
  }

  public synchronized Map<String, Object> get(String id) {
    require(id.matches("[0-9a-f-]{36}"), 400, "INVALID_RUN_ID");
    if (active != null && id.equals(active.data.get("id"))) {
      var result = new LinkedHashMap<>(active.data);
      result.put("heldConnections", active.held.size());
      result.put(
          "remainingSeconds",
          Math.max(
              0,
              Duration.between(Instant.now(), Instant.parse((String) active.data.get("deadline")))
                  .toSeconds()));
      return result;
    }
    try {
      Path file = dir.resolve(id + ".json");
      require(Files.exists(file), 404, "DRILL_NOT_FOUND");
      return json.readValue(Files.readString(file), new TypeReference<>() {});
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Control record unavailable", e);
    }
  }

  private void persist(Map<String, Object> row) {
    try {
      Path target = dir.resolve(row.get("id") + ".json"), tmp = dir.resolve(row.get("id") + ".tmp");
      Files.writeString(tmp, json.writeValueAsString(row));
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      throw new IllegalStateException("Control record persistence failed", e);
    }
  }

  @PreDestroy
  public synchronized void close() {
    if (active != null) stop((String) active.data.get("id"), "APPLICATION_SHUTDOWN");
    watchdog.shutdownNow();
    workers.shutdownNow();
  }
}
