package com.tracepilot.catalog;

import static com.tracepilot.api.ApiSupport.*;

import com.tracepilot.booking.BookingService;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
  private final JdbcTemplate db;
  private final BookingService booking;

  public CatalogService(JdbcTemplate db, BookingService booking) {
    this.db = db;
    this.booking = booking;
  }

  public Result create(long user, String name, String description) {
    db.update(
        "INSERT INTO resource(owner_id,name,description) VALUES(?,?,?)", user, name, description);
    return Result.ok(db.queryForMap("SELECT * FROM resource WHERE id=LAST_INSERT_ID()"));
  }

  public void own(long user, long resource) {
    var rows = db.queryForList("SELECT * FROM resource WHERE id=? FOR UPDATE", resource);
    require(!rows.isEmpty(), 404, "RESOURCE_NOT_FOUND");
    require(((Number) rows.getFirst().get("owner_id")).longValue() == user, 403, "NOT_OWNER");
  }

  public Result createSlot(long user, long resource, Instant start, Instant end, int capacity) {
    own(user, resource);
    require(start.isBefore(end), 400, "INVALID_TIME_RANGE");
    require(end.isBefore(Instant.parse("2038-01-19T03:14:07Z")), 400, "TIME_OUT_OF_RANGE");
    require(
        start.isAfter(
            db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant()),
        400,
        "START_MUST_BE_FUTURE");
    db.update(
        "INSERT INTO slot(resource_id,start_at,end_at,capacity) VALUES(?,?,?,?)",
        resource,
        Timestamp.from(start),
        Timestamp.from(end),
        capacity);
    return Result.ok(db.queryForMap("SELECT * FROM slot WHERE id=LAST_INSERT_ID()"));
  }

  public Result updateSlot(long user, long id, int capacity, boolean open) {
    var slot = booking.lockSlot(id,true);
    own(user, ((Number) slot.get("resource_id")).longValue());
    require(
        capacity >= ((Number) slot.get("reserved_count")).intValue(),
        409,
        "CAPACITY_BELOW_RESERVED");
    db.update("UPDATE slot SET capacity=?,open=? WHERE id=?", capacity, open, id);
    booking.promote(id);
    return Result.ok(db.queryForMap("SELECT * FROM slot WHERE id=?", id));
  }

  public Result enable(long user, long resource, boolean enabled) {
    own(user, resource);
    db.update("UPDATE resource SET enabled=? WHERE id=?", enabled, resource);
    if (enabled)
      for (Long id :
          db.queryForList(
              "SELECT id FROM slot WHERE resource_id=? ORDER BY id", Long.class, resource))
        booking.promote(id);
    return Result.ok(db.queryForMap("SELECT * FROM resource WHERE id=?", resource));
  }
}
