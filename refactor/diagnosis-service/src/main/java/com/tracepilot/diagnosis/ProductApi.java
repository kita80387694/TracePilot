package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ProductApi {
  private final TaskStore store;
  private final ReadTools tools;

  public ProductApi(TaskStore store, ReadTools tools) {
    this.store = store;
    this.tools = tools;
  }

  private String owner(HttpServletRequest r) {
    return (String) r.getAttribute("diagnosticOwner");
  }

  @GetMapping("/api/overview")
  public Object overview() {
    Instant end = Instant.now();
    return tools.probe(
        new Request("tracepilot-business", "demo", end.minusSeconds(600), end, "服务概览"),
        new Query("overview", Map.of()));
  }

  @GetMapping("/api/diagnoses")
  public Object history(
      HttpServletRequest r,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String service,
      @RequestParam(defaultValue = "2000-01-01T00:00:00Z") Instant from,
      @RequestParam(defaultValue = "2100-01-01T00:00:00Z") Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return store.history(owner(r), status, service, from, to, page, size);
  }

  @GetMapping("/api/evidence/{id}")
  public Object evidence(HttpServletRequest r, @PathVariable String id) {
    return store.ownedEvidence(id, owner(r));
  }

  @PostMapping("/api/diagnoses/{id}/retry")
  public ResponseEntity<?> retry(HttpServletRequest r, @PathVariable String id) {
    String next = store.retry(id, owner(r));
    return ResponseEntity.accepted().body(Map.of("id", next, "parentId", id, "status", "QUEUED"));
  }

  public record Feedback(String rating, String note) {}

  @PostMapping("/api/diagnoses/{id}/feedback")
  public Object feedback(
      HttpServletRequest r, @PathVariable String id, @RequestBody Feedback feedback) {
    store.feedback(id, owner(r), feedback.rating(), feedback.note());
    return Map.of("status", "SAVED", "kind", "USER_FEEDBACK_NOT_GROUND_TRUTH");
  }

  @GetMapping(value = "/api/diagnoses/{id}/export", produces = "text/markdown;charset=UTF-8")
  public ResponseEntity<String> export(HttpServletRequest r, @PathVariable String id) {
    var task = store.get(id, owner(r));
    String text = ReportExport.markdown(task);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=tracepilot-" + UUID.fromString(id) + ".md")
        .body(text);
  }

  @GetMapping(value = "/api/diagnoses/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      HttpServletRequest r,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) String previous) {
    String owner = owner(r);
    store.owned(id, owner);
    long cursor = previous == null ? after : Long.parseLong(previous);
    if (cursor < 0) throw new IllegalArgumentException("INVALID_CURSOR");
    SseEmitter emitter = new SseEmitter(55000L);
    AtomicBoolean closed = new AtomicBoolean();
    emitter.onCompletion(() -> closed.set(true));
    emitter.onTimeout(() -> closed.set(true));
    emitter.onError(e -> closed.set(true));
    Thread.ofVirtual()
        .start(
            () -> {
              long next = cursor, until = System.nanoTime() + 50_000_000_000L, heartbeat = 0;
              try {
                while (!closed.get() && System.nanoTime() < until) {
                  var rows = store.events(id, owner, next);
                  for (var row : rows) {
                    next = ((Number) row.get("id")).longValue();
                    emitter.send(
                        SseEmitter.event().id(Long.toString(next)).name("stage").data(row));
                  }
                  String status = store.owned(id, owner);
                  if (!Set.of("QUEUED", "RUNNING").contains(status)) {
                    emitter.send(SseEmitter.event().name("snapshot").data(store.get(id, owner)));
                    break;
                  }
                  if (System.nanoTime() > heartbeat) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    heartbeat = System.nanoTime() + 10_000_000_000L;
                  }
                  Thread.sleep(250);
                }
                emitter.complete();
              } catch (Exception ended) {
                emitter.completeWithError(ended);
              }
            });
    return emitter;
  }
}
