package com.tracepilot.booking;

import static com.tracepilot.api.ApiSupport.*;

import com.tracepilot.api.Idempotency;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BookingService {
  private final JdbcTemplate db;
  private final Idempotency json;

  @org.springframework.beans.factory.annotation.Value("${app.deployment-version}")
  private String deploymentVersion;

  public BookingService(JdbcTemplate db, Idempotency json) {
    this.db = db;
    this.json = json;
  }

  public Map<String, Object> lockSlot(long id) {
    return lockSlot(id,false);
  }

  public Map<String,Object> lockSlot(long id,boolean exclusiveResource) {
    // All callers must share the outer idempotency transaction; autocommit would release the lock.
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      throw new IllegalStateException("Slot mutation requires an active transaction");
    }
    var ids = db.queryForList("SELECT resource_id FROM slot WHERE id=?", Long.class, id);
    require(!ids.isEmpty(), 404, "SLOT_NOT_FOUND");
    // Preserve resource-before-slot ordering. Bookings only read resource state, so shared
    // locking permits different slots while still excluding an administrator's enable change.
    // Admin mutations request X upfront; no shared-to-exclusive lock upgrade is attempted.
    var resource=db.queryForMap("SELECT id,enabled FROM resource WHERE id=? "+(exclusiveResource?"FOR UPDATE":"FOR SHARE"),ids.getFirst());
    var slot=db.queryForMap("SELECT s.*,(s.start_at>CURRENT_TIMESTAMP(6)) AS future FROM slot s WHERE s.id=? FOR UPDATE",id);
    slot.put("enabled",resource.get("enabled"));
    return slot;
  }

  public static boolean flag(Object v) {
    return v instanceof Boolean b ? b : ((Number) v).intValue() != 0;
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private void available(Map<String, Object> slot) {
    require(
        flag(slot.get("enabled")) && flag(slot.get("open")) && flag(slot.get("future")),
        409,
        "SLOT_UNAVAILABLE");
  }

  public Result join(long user, long slotId, boolean wait) {
    var slot = lockSlot(slotId);
    available(slot);
    require(
        db.queryForObject(
                "SELECT COUNT(*) FROM participation WHERE user_id=? AND active_slot=?",
                Integer.class,
                user,
                slotId)
            == 0,
        409,
        "ALREADY_ACTIVE");
    boolean full = number(slot, "reserved_count") >= number(slot, "capacity");
    if (wait) require(full, 409, "CAPACITY_AVAILABLE");
    else {
      require(!full, 409, "CAPACITY_FULL");
      require(
          db.queryForObject(
                  "SELECT COUNT(*) FROM participation WHERE slot_id=? AND status='WAITING'",
                  Integer.class,
                  slotId)
              == 0,
          409,
          "QUEUE_HAS_PRIORITY");
    }
    db.update(
        "INSERT INTO participation(user_id,slot_id,status) VALUES(?,?,?)",
        user,
        slotId,
        wait ? "WAITING" : "RESERVED");
    long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    if (!wait) {
      db.update("UPDATE slot SET reserved_count=reserved_count+1 WHERE id=?", slotId);
      event(id, user, "RESERVED");
    }
    return Result.ok(detail(id));
  }

  public Result cancel(long user, long id, boolean leave) {
    var existing = owned(user, id);
    lockSlot(number(existing, "slot_id"));
    var row = owned(user, id);
    String state = (String) row.get("status");
    if (leave) {
      require(state.equals("WAITING") || state.equals("LEFT"), 409, "NOT_WAITING");
      if (state.equals("WAITING"))
        db.update("UPDATE participation SET status='LEFT' WHERE id=?", id);
    } else {
      require(state.equals("RESERVED") || state.equals("CANCELLED"), 409, "NOT_RESERVED");
      if (state.equals("RESERVED")) {
        long slotId = number(row, "slot_id");
        var slot = lockSlot(slotId);
        require(flag(slot.get("future")), 409, "SLOT_STARTED");
        db.update("UPDATE participation SET status='CANCELLED' WHERE id=?", id);
        db.update("UPDATE slot SET reserved_count=reserved_count-1 WHERE id=?", slotId);
        event(id, user, "CANCELLED");
        promote(slotId);
      }
    }
    return Result.ok(detail(id));
  }

  public void promote(long slotId) {
    var slot = lockSlot(slotId);
    if (!flag(slot.get("future")) || !flag(slot.get("enabled")) || !flag(slot.get("open"))) return;
    long free = number(slot, "capacity") - number(slot, "reserved_count");
    var waiting =
        db.queryForList(
            "SELECT * FROM participation WHERE slot_id=? AND status='WAITING' ORDER BY id LIMIT ?"
                + " FOR UPDATE",
            slotId,
            free);
    for (var w : waiting) {
      long id = number(w, "id");
      db.update("UPDATE participation SET status='RESERVED' WHERE id=?", id);
      db.update("UPDATE slot SET reserved_count=reserved_count+1 WHERE id=?", slotId);
      event(id, number(w, "user_id"), "PROMOTED");
    }
  }

  public Map<String, Object> owned(long user, long id) {
    var row = detail(id);
    require(number(row, "user_id") == user, 403, "NOT_OWNER");
    return row;
  }

  public Map<String, Object> detail(long id) {
    var rows =
        db.queryForList(
            "SELECT id,user_id,slot_id,status,created_at,updated_at FROM participation WHERE id=?",
            id);
    require(!rows.isEmpty(), 404, "PARTICIPATION_NOT_FOUND");
    return rows.getFirst();
  }

  private void event(long id, long user, String type) {
    db.update(
        "INSERT INTO"
            + " outbox(user_id,participation_id,type,payload,source_request_id,source_trace_id,source_version)"
            + " VALUES(?,?,?,?,?,?,?)",
        user,
        id,
        type,
        json.encode(Map.of("participationId", id, "type", type)),
        org.slf4j.MDC.get("requestId"),
        org.slf4j.MDC.get("traceId"),
        deploymentVersion);
    long eventId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    com.tracepilot.observability.Evidence.afterCommit(
        "business_event_committed", "eventId", eventId, "operation", type, "outcome", "COMMITTED");
  }
}
