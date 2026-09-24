package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class TaskStore {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final int maxTools, maxModels, timeoutSeconds;

  public TaskStore(JdbcTemplate db, TransactionTemplate tx) {
    this(db, tx, 12, 8, 180);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public TaskStore(
      JdbcTemplate db,
      TransactionTemplate tx,
      @org.springframework.beans.factory.annotation.Value("${diag.max-tools:12}") int maxTools,
      @org.springframework.beans.factory.annotation.Value("${diag.max-models:8}") int maxModels,
      @org.springframework.beans.factory.annotation.Value("${diag.timeout-seconds:180}")
          int timeoutSeconds) {
    if (maxTools < 1
        || maxTools > 12
        || maxModels < 1
        || maxModels > 8
        || timeoutSeconds < 1
        || timeoutSeconds > 180) throw new IllegalArgumentException("INVALID_BUDGET_CONFIGURATION");
    this.db = db;
    this.tx = tx;
    this.maxTools = maxTools;
    this.maxModels = maxModels;
    this.timeoutSeconds = timeoutSeconds;
  }

  public String create(String owner, Request request) {
    return tx.execute(
        s -> {
          db.queryForObject(
              "SELECT id FROM diagnosis_queue_guard WHERE id=1 FOR UPDATE", Integer.class);
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM diagnosis_task WHERE status='QUEUED'", Integer.class)
              >= 20) throw new IllegalArgumentException("QUEUE_FULL");
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM diagnosis_task WHERE status IN ('QUEUED','RUNNING')",
                  Integer.class)
              >= 22) throw new IllegalArgumentException("QUEUE_FULL");
          String id = UUID.randomUUID().toString();
          db.update(
              "INSERT INTO"
                  + " diagnosis_task(id,owner,status,request_json,state_json,max_tools,max_models,timeout_seconds)"
                  + " VALUES (?,?,'QUEUED',?,?,?,?,?)",
              id,
              owner,
              encode(request),
              encode(Map.of("phase", "OVERVIEW", "repairs", 0)),
              maxTools,
              maxModels,
              timeoutSeconds);
          event(id, "STATE", Map.of("status", "QUEUED", "phase", "OVERVIEW"));
          return id;
        });
  }

  public Claim claim() {
    return tx.execute(
        s -> {
          db.queryForObject(
              "SELECT id FROM diagnosis_queue_guard WHERE id=1 FOR UPDATE", Integer.class);
          var candidates =
              db.queryForList(
                  "SELECT * FROM diagnosis_task WHERE (status='QUEUED' OR (status='RUNNING' AND"
                      + " lease_until<NOW(6))) AND cancel_requested=FALSE ORDER BY created_at LIMIT"
                      + " 1 FOR UPDATE SKIP LOCKED");
          if (candidates.isEmpty()) return null;
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM diagnosis_task WHERE status IN ('RUNNING','CANCELLED') AND"
                      + " lease_until>=NOW(6)",
                  Integer.class)
              >= 2) return null;
          var row = candidates.getFirst();
          String id = (String) row.get("id"), lease = UUID.randomUUID().toString();
          db.update(
              "UPDATE diagnosis_task SET"
                  + " status='RUNNING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,65,NOW(6)),started_at=COALESCE(started_at,NOW(6)),deadline=COALESCE(deadline,TIMESTAMPADD(SECOND,timeout_seconds,NOW(6)))"
                  + " WHERE id=?",
              lease,
              id);
          try {
            var request = JSON.readValue((String) row.get("request_json"), Request.class);
            Instant deadline =
                db.queryForObject(
                        "SELECT deadline FROM diagnosis_task WHERE id=?", Timestamp.class, id)
                    .toInstant();
            step(id, "LEASE", "CLAIMED", Map.of("resumed", row.get("status").equals("RUNNING")));
            return new Claim(id, lease, request, map((String) row.get("state_json")), deadline);
          } catch (Exception e) {
            throw new IllegalStateException("PERSISTED_STATE_INVALID");
          }
        });
  }

  public boolean active(Claim c) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT COUNT(*)=1 FROM diagnosis_task WHERE id=? AND lease_token=? AND"
                + " status='RUNNING' AND cancel_requested=FALSE AND deadline>NOW(6) AND"
                + " lease_until>NOW(6)",
            Boolean.class,
            c.id(),
            c.lease()));
  }

  public boolean reserve(Claim c, String kind) {
    return tx.execute(
        s -> {
          var rows = db.queryForList("SELECT * FROM diagnosis_task WHERE id=? FOR UPDATE", c.id());
          if (rows.isEmpty() || !active(c)) return false;
          String column = kind.equals("TOOL") ? "tool_calls" : "model_calls";
          int max =
              ((Number) rows.getFirst().get(kind.equals("TOOL") ? "max_tools" : "max_models"))
                  .intValue();
          int n = ((Number) rows.getFirst().get(column)).intValue();
          if (n >= max) return false;
          db.update(
              "UPDATE diagnosis_task SET "
                  + column
                  + "="
                  + column
                  + "+1,lease_until=TIMESTAMPADD(SECOND,65,NOW(6)) WHERE id=?",
              c.id());
          step(c.id(), kind, "STARTED", Map.of("attempt", n + 1));
          return true;
        });
  }

  public void checkpoint(Claim c, Map<String, Object> state) {
    tx.executeWithoutResult(
        s -> {
          int changed =
              db.update(
                  "UPDATE diagnosis_task SET"
                      + " state_json=?,lease_until=TIMESTAMPADD(SECOND,65,NOW(6)) WHERE id=? AND"
                      + " lease_token=? AND lease_until>NOW(6) AND status='RUNNING' AND"
                      + " cancel_requested=FALSE",
                  encode(state),
                  c.id(),
                  c.lease());
          if (changed == 1)
            event(c.id(), "PHASE", Map.of("phase", state.getOrDefault("phase", "UNKNOWN")));
        });
  }

  public Evidence evidence(Claim c, String tool, Result result) {
    String start = c.request().start().toString(), end = c.request().end().toString();
    if (tool.equals("overview") && result.data().has("time")) {
      start = Instant.parse(result.data().path("time").asText()).toString();
      end = start;
    }
    var e =
        new Evidence(
            UUID.randomUUID().toString(),
            tool,
            result.status(),
            start,
            end,
            result.locator(),
            result.data());
    tx.executeWithoutResult(
        s -> {
          // Fence late responses after another worker takes an expired lease.
          var row =
              db.queryForMap(
                  "SELECT lease_token,(lease_until>NOW(6)) valid_lease FROM diagnosis_task WHERE"
                      + " id=? FOR UPDATE",
                  c.id());
          if (!c.lease().equals(row.get("lease_token"))
              || ((Number) row.get("valid_lease")).intValue() != 1)
            throw new IllegalStateException("LEASE_LOST");
          db.update(
              "INSERT INTO"
                  + " diagnosis_evidence(id,task_id,source,status,start_at,end_at,locator_json,payload_json)"
                  + " VALUES (?,?,?,?,?,?,?,?)",
              e.id(),
              c.id(),
              tool,
              e.status(),
              e.start(),
              e.end(),
              encode(e.locator()),
              encode(e.data()));
          step(c.id(), "TOOL", e.status(), Map.of("evidenceId", e.id(), "tool", tool));
        });
    return e;
  }

  public List<Evidence> evidence(String id) {
    return db.query(
        "SELECT * FROM diagnosis_evidence WHERE task_id=? ORDER BY created_at,id",
        (r, n) ->
            new Evidence(
                r.getString("id"),
                r.getString("source"),
                r.getString("status"),
                r.getString("start_at"),
                r.getString("end_at"),
                map(r.getString("locator_json")),
                parse(r.getString("payload_json"))),
        id);
  }

  public void step(String id, String kind, String status, Object detail) {
    tx.executeWithoutResult(
        s -> {
          db.queryForObject(
              "SELECT id FROM diagnosis_task WHERE id=? FOR UPDATE", String.class, id);
          db.update(
              "INSERT INTO diagnosis_step(task_id,kind,status,detail_json) VALUES (?,?,?,?)",
              id,
              kind,
              status,
              encode(detail));
          // SSE carries action summaries, never private draft/report reasoning.
          event(
              id,
              "STEP",
              Map.of(
                  "kind",
                  kind,
                  "status",
                  status,
                  "detail",
                  (kind.equals("REPORT") || kind.equals("ACTION")) ? Map.of("validation", "REJECTED") : detail));
        });
  }

  public void step(Claim claim, String kind, String status, Object detail) {
    tx.executeWithoutResult(
        s -> {
          db.queryForObject(
              "SELECT id FROM diagnosis_task WHERE id=? FOR UPDATE", String.class, claim.id());
          if (leaseValid(claim)) step(claim.id(), kind, status, detail);
        });
  }

  private boolean leaseValid(Claim c) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM diagnosis_task WHERE id=? AND lease_token=? AND"
                + " lease_until>NOW(6)",
            Integer.class,
            c.id(),
            c.lease())
        == 1;
  }

  private void event(String id, String kind, Object payload) {
    db.queryForObject("SELECT id FROM diagnosis_task WHERE id=? FOR UPDATE", String.class, id);
    db.update(
        "INSERT INTO diagnosis_event(task_id,kind,payload_json) VALUES (?,?,?)",
        id,
        kind,
        encode(payload));
  }

  public void finish(Claim c, String status, Object report) {
    tx.executeWithoutResult(
        s -> {
          int changed =
              db.update(
                  "UPDATE diagnosis_task SET"
                      + " status=IF(cancel_requested,'CANCELLED',?),report_json=?,lease_token=NULL,lease_until=NULL,state_json=JSON_REMOVE(state_json,'$.correction.rejectedAction')"
                      + " WHERE id=? AND lease_token=? AND lease_until>NOW(6) AND status='RUNNING'",
                  status,
                  encode(report),
                  c.id(),
                  c.lease());
          if (changed == 1) event(c.id(), "STATE", Map.of("status", status, "phase", "FINISHED"));
          else
            // A cancelled in-flight call still occupies a global slot until its worker exits.
            // Release only this lease, without replacing the cancellation report or state.
            db.update(
                "UPDATE diagnosis_task SET lease_token=NULL,lease_until=NULL WHERE id=? AND"
                    + " lease_token=? AND lease_until>NOW(6) AND status='CANCELLED'",
                c.id(),
                c.lease());
        });
  }

  static com.fasterxml.jackson.databind.JsonNode readStepDetail(String raw,Instant created) {
    var node=parse(raw);
    if(node.isObject() && node.has("actionRecord") && created.isBefore(Instant.now().minus(java.time.Duration.ofDays(7)))) {
      ((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("actionRecord");
      ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("actionRecordExpired",true);
    }
    return node;
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
  public void expireActionDiagnostics() {
    db.update("UPDATE diagnosis_step SET detail_json=JSON_SET(JSON_REMOVE(detail_json,'$.actionRecord'),'$.actionRecordExpired',true) "
        + "WHERE kind='ACTION' AND created_at < TIMESTAMPADD(DAY,-7,NOW(6)) AND JSON_CONTAINS_PATH(detail_json,'one','$.actionRecord')");
    db.update("UPDATE diagnosis_task SET state_json=JSON_REMOVE(state_json,'$.correction.rejectedAction'),updated_at=updated_at "
        + "WHERE JSON_EXTRACT(state_json,'$.correction.rejectedAction.expiresAtEpochMilli') < ?",System.currentTimeMillis());
  }

  static com.fasterxml.jackson.databind.JsonNode hideExpiredDraft(com.fasterxml.jackson.databind.JsonNode state){
    var draft=state.at("/correction/rejectedAction");
    if(draft.path("included").asBoolean()&&draft.path("expiresAtEpochMilli").asLong(0)<System.currentTimeMillis()
        &&state.path("correction") instanceof com.fasterxml.jackson.databind.node.ObjectNode correction){
      correction.remove("rejectedAction");correction.put("rejectedActionExpired",true);
    }
    return state;
  }

  public Map<String, Object> get(String id, String owner) {
    long cursor =
        db.queryForObject(
            "SELECT COALESCE(MAX(id),0) FROM diagnosis_event WHERE task_id=?", Long.class, id);
    var rows =
        db.queryForList(
            "SELECT"
                + " id,status,parent_id,feedback_json,request_json,state_json,report_json,tool_calls,model_calls,max_tools,max_models,timeout_seconds,cancel_requested,created_at,updated_at"
                + " FROM diagnosis_task WHERE id=? AND owner=?",
            id,
            owner);
    if (rows.isEmpty()) throw new NoSuchElementException();
    var row = new LinkedHashMap<>(rows.getFirst());
    for (String key : List.of("request_json", "state_json", "report_json", "feedback_json"))
      if (row.get(key) != null) row.put(key, parse((String) row.get(key)));
    if(row.get("state_json") instanceof com.fasterxml.jackson.databind.JsonNode state)hideExpiredDraft(state);
    row.put("evidence", evidence(id));
    row.put("eventCursor", cursor);
    row.put(
        "steps",
        db.query(
            "SELECT id,kind,status,detail_json,created_at FROM diagnosis_step WHERE task_id=? ORDER"
                + " BY id",
            (r, n) ->
                Map.of(
                    "id",
                    r.getLong(1),
                    "kind",
                    r.getString(2),
                    "status",
                    r.getString(3),
                    "detail",
                    readStepDetail(r.getString(4),r.getTimestamp(5).toInstant()),
                    "time",
                    r.getTimestamp(5).toInstant().toString()),
            id));
    return row;
  }

  public void cancel(String id, String owner) {
    tx.executeWithoutResult(
        s -> {
          get(id, owner);
          int changed =
              db.update(
                  "UPDATE diagnosis_task SET cancel_requested=TRUE,status='CANCELLED',report_json=?,state_json=JSON_REMOVE(state_json,'$.correction.rejectedAction')"
                      + " WHERE id=? AND owner=? AND status IN ('QUEUED','RUNNING')",
                  encode(Reports.partial("CANCELLED_BY_USER", evidence(id))),
                  id,
                  owner);
          if (changed == 1) event(id, "STATE", Map.of("status", "CANCELLED", "phase", "FINISHED"));
        });
  }

  public List<Map<String, Object>> events(String id, String owner, long after) {
    owned(id, owner);
    if (after < 0) throw new IllegalArgumentException("INVALID_CURSOR");
    return db.query(
        "SELECT id,kind,payload_json,created_at FROM diagnosis_event WHERE task_id=? AND id>? ORDER"
            + " BY id LIMIT 100",
        (r, n) ->
            Map.of(
                "id",
                r.getLong(1),
                "kind",
                r.getString(2),
                "payload",
                parse(r.getString(3)),
                "time",
                r.getTimestamp(4).toInstant().toString()),
        id,
        after);
  }

  public String owned(String id, String owner) {
    var rows =
        db.queryForList(
            "SELECT status FROM diagnosis_task WHERE id=? AND owner=?", String.class, id, owner);
    if (rows.isEmpty()) throw new NoSuchElementException();
    return rows.getFirst();
  }

  public Object ownedEvidence(String id, String owner) {
    var rows =
        db.queryForList(
            "SELECT e.task_id FROM diagnosis_evidence e JOIN diagnosis_task t ON t.id=e.task_id"
                + " WHERE e.id=? AND t.owner=?",
            String.class,
            id,
            owner);
    if (rows.isEmpty()) throw new NoSuchElementException();
    return evidence(rows.getFirst()).stream()
        .filter(e -> e.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  public Object history(
      String owner, String status, String service, Instant from, Instant to, int page, int size) {
    if (page < 0
        || page > 100000
        || size < 1
        || size > 100
        || !Set.of("", "QUEUED", "RUNNING", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED")
            .contains(status)
        || !Set.of("", "tracepilot-business").contains(service)
        || from.isAfter(to)) throw new IllegalArgumentException("INVALID_FILTER");
    String where =
        " WHERE owner=? AND (?='' OR status=?) AND (?='' OR"
            + " JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.service'))=?) AND created_at>=? AND"
            + " created_at<=?";
    Object[] values = {
      owner, status, status, service, service, Timestamp.from(from), Timestamp.from(to)
    };
    var params = new ArrayList<Object>(Arrays.asList(values));
    params.add(size);
    params.add(page * size);
    return Map.of(
        "data",
        db.queryForList(
            "SELECT id,status,parent_id,request_json,tool_calls,model_calls,created_at FROM"
                + " diagnosis_task"
                + where
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            params.toArray()),
        "total",
        db.queryForObject("SELECT COUNT(*) FROM diagnosis_task" + where, Long.class, values),
        "page",
        page,
        "size",
        size);
  }

  public String retry(String id, String owner) {
    return tx.execute(
        s -> {
          var original = get(id, owner);
          if (Set.of("QUEUED", "RUNNING").contains(original.get("status")))
            throw new IllegalArgumentException("TASK_STILL_ACTIVE");
          try {
            Request request =
                JSON.treeToValue(
                    (com.fasterxml.jackson.databind.JsonNode) original.get("request_json"),
                    Request.class);
            String next = create(owner, request);
            db.update("UPDATE diagnosis_task SET parent_id=? WHERE id=?", id, next);
            return next;
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException();
          }
        });
  }

  public void feedback(String id, String owner, String rating, String note) {
    if (!Set.of("HELPFUL", "NOT_HELPFUL", "INSUFFICIENT").contains(rating)
        || note == null
        || note.length() > 1000) throw new IllegalArgumentException("INVALID_FEEDBACK");
    tx.executeWithoutResult(
        s -> {
          owned(id, owner);
          db.update(
              "UPDATE diagnosis_task SET feedback_json=? WHERE id=? AND owner=?",
              encode(
                  Map.of(
                      "rating",
                      rating,
                      "note",
                      note,
                      "kind",
                      "USER_FEEDBACK_NOT_GROUND_TRUTH",
                      "time",
                      Instant.now().toString())),
              id,
              owner);
          event(id, "FEEDBACK", Map.of("rating", rating));
        });
  }

  public Map<String, Object> usage(String id) {
    return db.queryForMap(
        "SELECT tool_calls,model_calls,max_tools,max_models,timeout_seconds,started_at,deadline"
            + " FROM diagnosis_task WHERE id=?",
        id);
  }

  public List<Object> modelUsage(String id) {
    return db.query(
        "SELECT status,detail_json FROM diagnosis_step WHERE task_id=? AND kind='MODEL' AND"
            + " status!='STARTED' ORDER BY id",
        (r, n) -> Map.of("status", r.getString(1), "usage", parse(r.getString(2))),
        id);
  }
  public int contractRejections(String id){
    return db.queryForObject("SELECT COUNT(*) FROM diagnosis_step WHERE task_id=? AND kind IN ('ACTION','REPORT') AND status='REJECTED'",Integer.class,id);
  }
  public Map<String,Object> transportAccounting(String id){
    var rows=db.queryForList("SELECT status,COUNT(*) n FROM diagnosis_step WHERE task_id=? AND kind='MODEL_TRANSPORT' GROUP BY status",id);
    var counts=new LinkedHashMap<String,Object>();for(var row:rows)counts.put(row.get("status").toString(),row.get("n"));
    return Map.of("budgetReservations",usage(id).get("model_calls"),"transportEvents",counts,"localFailures",db.queryForObject("SELECT COUNT(*) FROM diagnosis_step WHERE task_id=? AND kind='MODEL' AND status='LOCAL_FAILURE'",Integer.class,id),"meaning","SEND_ATTEMPTED is transport handoff, not proof of upstream receipt. Only returned provider metadata is token usage; missing fields are not zero.");
  }

}
