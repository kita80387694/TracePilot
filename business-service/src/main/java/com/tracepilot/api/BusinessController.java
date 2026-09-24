package com.tracepilot.api;

import static com.tracepilot.api.ApiSupport.*;

import com.tracepilot.booking.BookingService;
import com.tracepilot.catalog.CatalogService;
import com.tracepilot.identity.SecurityConfig.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BusinessController {
  private final JdbcTemplate db;
  private final Idempotency idem;
  private final BookingService booking;
  private final CatalogService catalog;

  public BusinessController(
      JdbcTemplate db, Idempotency idem, BookingService booking, CatalogService catalog) {
    this.db = db;
    this.idem = idem;
    this.booking = booking;
    this.catalog = catalog;
  }

  public record Join(@Positive long slotId) {}

  public record ResourceInput(
      @NotBlank @Size(max = 120) String name, @NotNull @Size(max = 1000) String description) {}

  public record SlotInput(
      @Positive long resourceId,
      @NotNull Instant startAt,
      @NotNull Instant endAt,
      @Min(1) @Max(100000) int capacity) {}

  public record SlotUpdate(@Min(1) @Max(100000) int capacity, @NotNull Boolean open) {}

  public record Enable(@NotNull Boolean enabled) {}

  private ResponseEntity<?> write(
      Actor actor, String key, Object body, HttpServletRequest req, Supplier<Result> action) {
    String op = req.getMethod() + " " + req.getRequestURI();
    Result result =
        idem.execute(
            actor.id(),
            op,
            key,
            body,
            () -> {
              Result r = action.get();
              if (op.contains("/admin/"))
                db.update("INSERT INTO admin_audit(user_id,operation) VALUES(?,?)", actor.id(), op);
              return r;
            });
    var response = new LinkedHashMap<>(result.body());
    com.tracepilot.observability.Evidence.emit(
        "business_operation_completed",
        "operation",
        req.getMethod()
            + " "
            + com.tracepilot.observability.RequestTelemetry.route(req.getRequestURI()),
        "status",
        result.status());
    if (result.status() >= 400) response.put("requestId", req.getAttribute("requestId"));
    return ResponseEntity.status(result.status()).body(response);
  }

  @PostMapping("/reservations")
  Object reserve(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Join body,
      HttpServletRequest req) {
    return write(a, key, body, req, () -> booking.join(a.id(), body.slotId(), false));
  }

  @PostMapping("/waitlists")
  Object waitlist(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Join body,
      HttpServletRequest req) {
    return write(a, key, body, req, () -> booking.join(a.id(), body.slotId(), true));
  }

  @PostMapping("/reservations/{id}/cancel")
  Object cancel(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body,
      HttpServletRequest req) {
    return write(
        a,
        key,
        body == null ? Map.of() : body,
        req,
        () -> {
          require(body == null || body.isEmpty(), 400, "UNEXPECTED_BODY");
          return booking.cancel(a.id(), id, false);
        });
  }

  @PostMapping("/waitlists/{id}/leave")
  Object leave(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body,
      HttpServletRequest req) {
    return write(
        a,
        key,
        body == null ? Map.of() : body,
        req,
        () -> {
          require(body == null || body.isEmpty(), 400, "UNEXPECTED_BODY");
          return booking.cancel(a.id(), id, true);
        });
  }

  @GetMapping({"/reservations/{id}", "/waitlists/{id}"})
  Object detail(@AuthenticationPrincipal Actor a, @PathVariable long id) {
    return Map.of("data", booking.owned(a.id(), id));
  }

  private int offset(int page, int size) {
    require(page >= 0 && page <= 100000 && size >= 1 && size <= 100, 400, "INVALID_PAGE");
    return page * size;
  }

  @GetMapping("/resources")
  Object resources(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return Map.of(
        "data",
        db.queryForList(
            "SELECT * FROM resource ORDER BY id LIMIT ? OFFSET ?", size, offset(page, size)),
        "page",
        page,
        "size",
        size);
  }

  @GetMapping("/slots")
  Object slots(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return Map.of(
        "data",
        db.queryForList(
            "SELECT s.*,r.enabled,(s.capacity-s.reserved_count) remaining FROM slot s JOIN resource"
                + " r ON r.id=s.resource_id ORDER BY s.id LIMIT ? OFFSET ?",
            size,
            offset(page, size)),
        "page",
        page,
        "size",
        size,
        "waitlistRule",
        "FIFO direct promotion; joining authorizes automatic reservation without a second"
            + " confirmation");
  }

  @GetMapping("/me/participations")
  Object mine(
      @AuthenticationPrincipal Actor a,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Map.of(
        "data",
        db.queryForList(
            "SELECT id,slot_id,status,created_at,updated_at FROM participation WHERE user_id=?"
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
            a.id(),
            size,
            offset(page, size)),
        "page",
        page,
        "size",
        size);
  }

  @GetMapping("/me/notifications")
  Object notifications(
      @AuthenticationPrincipal Actor a,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) Long eventId) {
    require(eventId==null||eventId>0,400,"INVALID_EVENT_ID");
    var rows=eventId==null?db.queryForList("SELECT * FROM notification WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",a.id(),size,offset(page,size))
      :db.queryForList("SELECT * FROM notification WHERE user_id=? AND event_id=? LIMIT ? OFFSET ?",a.id(),eventId,size,offset(page,size));
    if(eventId!=null)com.tracepilot.observability.Evidence.emit("notification_query_result","queryEventId",eventId,"eventId",eventId,"actorId",a.id(),"notificationCount",rows.size(),"notificationId",rows.isEmpty()?"NONE":rows.getFirst().get("id"),"outcome","CALLER_SCOPED_FILTERED_RESULT");
    return Map.of(
        "data",
        rows,
        "page",
        page,
        "size",
        size);
  }

  @PostMapping("/admin/resources")
  Object create(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ResourceInput body,
      HttpServletRequest req) {
    return write(a, key, body, req, () -> catalog.create(a.id(), body.name(), body.description()));
  }

  @PatchMapping("/admin/resources/{id}")
  Object enable(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @PathVariable long id,
      @Valid @RequestBody Enable body,
      HttpServletRequest req) {
    return write(a, key, body, req, () -> catalog.enable(a.id(), id, body.enabled()));
  }

  @PostMapping("/admin/slots")
  Object createSlot(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody SlotInput body,
      HttpServletRequest req) {
    return write(
        a,
        key,
        body,
        req,
        () ->
            catalog.createSlot(
                a.id(), body.resourceId(), body.startAt(), body.endAt(), body.capacity()));
  }

  @PatchMapping("/admin/slots/{id}")
  Object updateSlot(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @PathVariable long id,
      @Valid @RequestBody SlotUpdate body,
      HttpServletRequest req) {
    return write(
        a, key, body, req, () -> catalog.updateSlot(a.id(), id, body.capacity(), body.open()));
  }

  @GetMapping("/admin/stats")
  Object stats(@AuthenticationPrincipal Actor a) {
    return Map.of(
        "data",
        db.queryForList(
            "SELECT p.status,COUNT(*) count FROM participation p JOIN slot s ON s.id=p.slot_id JOIN"
                + " resource r ON r.id=s.resource_id WHERE r.owner_id=? GROUP BY p.status",
            a.id()),
        "capacityRejections",
        db.queryForObject(
            "SELECT COUNT(*) FROM idempotency WHERE"
                + " JSON_UNQUOTE(JSON_EXTRACT(response_json,'$.code'))='CAPACITY_FULL'",
            Long.class));
  }

  @GetMapping("/admin/events/failed")
  Object failed(
      @AuthenticationPrincipal Actor a,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Map.of(
        "data",
        db.queryForList(
            "SELECT e.* FROM outbox e JOIN participation p ON p.id=e.participation_id JOIN slot s"
                + " ON s.id=p.slot_id JOIN resource r ON r.id=s.resource_id WHERE e.status='FAILED'"
                + " AND r.owner_id=? ORDER BY e.id LIMIT ? OFFSET ?",
            a.id(),
            size,
            offset(page, size)),
        "page",
        page,
        "size",
        size);
  }

  @PostMapping("/admin/events/{id}/retry")
  Object retry(
      @AuthenticationPrincipal Actor a,
      @RequestHeader("Idempotency-Key") String key,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body,
      HttpServletRequest req) {
    return write(
        a,
        key,
        body == null ? Map.of() : body,
        req,
        () -> {
          require(body == null || body.isEmpty(), 400, "UNEXPECTED_BODY");
          var rows =
              db.queryForList(
                  "SELECT r.owner_id FROM outbox e JOIN participation p ON p.id=e.participation_id"
                      + " JOIN slot s ON s.id=p.slot_id JOIN resource r ON r.id=s.resource_id WHERE"
                      + " e.id=?",
                  id);
          require(!rows.isEmpty(), 404, "EVENT_NOT_FOUND");
          require(
              ((Number) rows.getFirst().get("owner_id")).longValue() == a.id(), 403, "NOT_OWNER");
          var row = db.queryForMap("SELECT status FROM outbox WHERE id=? FOR UPDATE", id);
          require("FAILED".equals(row.get("status")), 409, "EVENT_NOT_FAILED");
          db.update(
              "UPDATE outbox SET"
                  + " status='PENDING',attempts=0,last_error=NULL,next_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL"
                  + " WHERE id=?",
              id);
          return Result.ok(Map.of("id", id, "status", "PENDING"));
        });
  }
}
