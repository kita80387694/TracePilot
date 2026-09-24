package com.tracepilot.events;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventPublisher {
  public record Lease(long id, String token, int attempts) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final NotificationConsumer consumer;
  private final boolean enabled;
  private final java.util.concurrent.ExecutorService workers;
  private final java.util.concurrent.Semaphore workerSlots;
  private DeliveryGate gate = new DeliveryGate();
  private io.micrometer.core.instrument.MeterRegistry meters;

  @org.springframework.beans.factory.annotation.Autowired
  public void telemetry(DeliveryGate gate, io.micrometer.core.instrument.MeterRegistry meters) {
    this.gate = gate;
    this.meters = meters;
    meters.counter("tracepilot.events.success");
    meters.counter("tracepilot.events.failure");
  }

  public EventPublisher(JdbcTemplate db,TransactionTemplate tx,NotificationConsumer consumer,boolean enabled) {
    this(db,tx,consumer,enabled,24);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public EventPublisher(
      JdbcTemplate db,
      TransactionTemplate tx,
      NotificationConsumer consumer,
      @Value("${app.events-enabled}") boolean enabled,
      @Value("${spring.datasource.hikari.maximum-pool-size:24}") int poolSize) {
    this.db = db;
    this.tx = tx;
    this.consumer = consumer;
    this.enabled = enabled;
    // Together with writer admission (half the pool), leave capacity for reads. Each
    // consumer still commits notification before acknowledgement; no transactions merge.
    int concurrency=Math.max(1,poolSize/6);
    this.workers=java.util.concurrent.Executors.newFixedThreadPool(concurrency);
    this.workerSlots=new java.util.concurrent.Semaphore(concurrency);
  }

  public Lease claim() {
    return tx.execute(
        s -> {
          // Separate range scans avoid walking DONE history through the primary key.
          // Expired leases are checked first so a busy producer cannot starve crash recovery.
          var rows = db.queryForList(
              "SELECT id,attempts FROM outbox FORCE INDEX (status_2) WHERE status='PROCESSING'"
                  + " AND lease_until<CURRENT_TIMESTAMP(6) ORDER BY lease_until,id"
                  + " LIMIT 1 FOR UPDATE SKIP LOCKED");
          if (rows.isEmpty()) rows = db.queryForList(
              "SELECT id,attempts FROM outbox FORCE INDEX (status) WHERE status='PENDING'"
                  + " AND next_at<=CURRENT_TIMESTAMP(6) ORDER BY next_at,id"
                  + " LIMIT 1 FOR UPDATE SKIP LOCKED");
          if (rows.isEmpty()) return null;
          var row = rows.getFirst();
          long id = ((Number) row.get("id")).longValue();
          String token = UUID.randomUUID().toString();
          db.update(
              "UPDATE outbox SET"
                  + " status='PROCESSING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6))"
                  + " WHERE id=?",
              token,
              id);
          return new Lease(id, token, ((Number) row.get("attempts")).intValue());
        });
  }

  public int acknowledge(Lease lease) {
    return db.update(
        "UPDATE outbox SET status='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL WHERE"
            + " id=? AND lease_token=? AND status='PROCESSING'",
        lease.id(),
        lease.token());
  }

  public void fail(Lease lease, Exception error) {
    // Store a bounded error class, never credentials or arbitrary downstream response bodies.
    db.update(
        "UPDATE outbox SET"
            + " status=?,attempts=attempts+1,last_error=?,next_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),lease_token=NULL,lease_until=NULL"
            + " WHERE id=? AND lease_token=? AND status='PROCESSING'",
        lease.attempts() + 1 >= 5 ? "FAILED" : "PENDING",
        error.getClass().getSimpleName(),
        Math.min(300, 1L << Math.min(lease.attempts() + 1, 8)),
        lease.id(),
        lease.token());
  }

  public boolean processOne() {
    if (!gate.open()) return false;
    var lease = claim();
    if (lease == null) return false;
    String attemptId=UUID.randomUUID().toString();
    var source =
        db.queryForMap(
            "SELECT source_request_id,source_trace_id,source_version,user_id FROM outbox WHERE id=?",
            lease.id());
    try (var request =
            org.slf4j.MDC.putCloseable(
                "requestId", Objects.toString(source.get("source_request_id"), ""));
        var trace =
            org.slf4j.MDC.putCloseable(
                "traceId", Objects.toString(source.get("source_trace_id"), ""));
        var event=org.slf4j.MDC.putCloseable("eventId",Long.toString(lease.id()));
        var attempt=org.slf4j.MDC.putCloseable("attemptId",attemptId)) {
      com.tracepilot.observability.Evidence.emit("event_claimed","eventId",lease.id(),"attemptId",attemptId,"actorId",source.get("user_id"),"status","PROCESSING","stateAt",java.time.Instant.now(),"attempts",lease.attempts());
      String operationPhase = "NOTIFICATION_TRANSACTION";
      String commitObservation = "NOT_OBSERVED";
      try {
        com.tracepilot.observability.Evidence.emit("notification_write_started","eventId",lease.id(),"attemptId",attemptId);
        long notificationId=consumer.deliver(lease.id());
        commitObservation = "COMMITTED_BY_PROXY_RETURN";
        // The transactional proxy has returned: the notification transaction committed.
        com.tracepilot.observability.Evidence.emit("notification_write_committed","eventId",lease.id(),"attemptId",attemptId,"notificationId",notificationId,"outcome","COMMITTED");
        operationPhase = "OUTBOX_ACKNOWLEDGEMENT";
        int acknowledged=acknowledge(lease);
        com.tracepilot.observability.Evidence.emit(acknowledged==1?"event_acknowledged":"event_ack_not_applied","eventId",lease.id(),"attemptId",attemptId,"notificationId",notificationId,"rows",acknowledged,"stateAt",java.time.Instant.now());
        if (acknowledged == 0) {
          com.tracepilot.observability.Evidence.emit("event_delivery_unconfirmed",
              "eventId", lease.id(), "attemptId", attemptId, "notificationId", notificationId,
              "operationPhase", operationPhase, "notificationCommitObservation", commitObservation,
              "outcome", "ACK_NOT_APPLIED", "rows", 0);
          return true;
        }
        if (meters != null) meters.counter("tracepilot.events.success").increment();
        com.tracepilot.observability.Evidence.emit(
            "event_delivery_completed",
            "eventId",
            lease.id(),
            "attemptId",attemptId,"notificationId",notificationId,"stateAt",java.time.Instant.now(),
            "sourceRequestId",
            Objects.toString(source.get("source_request_id"), ""),
            "sourceTraceId",
            Objects.toString(source.get("source_trace_id"), ""),
            "sourceVersion",
            Objects.toString(source.get("source_version"), "unknown"),
            "outcome",
            "SUCCESS");
      } catch (Exception e) {
        com.tracepilot.observability.Evidence.emit("event_operation_failed",
            "eventId", lease.id(), "attemptId", attemptId, "operationPhase", operationPhase,
            "notificationCommitObservation", commitObservation,
            "errorType", e.getClass().getSimpleName(), "outcome", "FAILED",
            "stateAt", java.time.Instant.now());
        com.tracepilot.observability.Evidence.error("notification_attempt_error",e);
        fail(lease, e);
        var after=db.queryForMap("SELECT status,attempts,next_at FROM outbox WHERE id=?",lease.id());
        if (meters != null) meters.counter("tracepilot.events.failure").increment();
        com.tracepilot.observability.Evidence.emit(
            "event_delivery_failed",
            "eventId",
            lease.id(),
            "attemptId",attemptId,"status",after.get("status"),"attempts",after.get("attempts"),"nextAt",((java.sql.Timestamp)after.get("next_at")).toInstant(),"stateAt",java.time.Instant.now(),
            "operationPhase", operationPhase, "notificationCommitObservation", commitObservation,
            "errorType",
            e.getClass().getSimpleName(),
            "outcome",
            "RETRY_OR_FAILED");
      }
    }
    return true;
  }

  @Scheduled(fixedDelayString = "${app.event-delay-ms}")
  public void tick() {
    if (!enabled || !workerSlots.tryAcquire()) return;
    try { workers.submit(() -> { try { drainSlice(); } finally { workerSlots.release(); } }); }
    catch (RuntimeException rejected) { workerSlots.release(); throw rejected; }
  }

  private void drainSlice() {
    try {
      // Bound both work and scheduler occupancy; no one-second throughput ceiling.
      long until = System.nanoTime() + 250_000_000L;
      for (int i = 0; i < 100 && System.nanoTime() < until && !Thread.currentThread().isInterrupted() && processOne(); i++) {}
    } catch (Exception e) {
      com.tracepilot.observability.Evidence.error("event_poll_failed", e);
    }
  }

  @jakarta.annotation.PreDestroy
  void close() { workers.shutdownNow(); }
}
