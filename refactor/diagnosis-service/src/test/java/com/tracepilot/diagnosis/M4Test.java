package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Product API and additional lease tests. Real MySQL; model/source fixtures are explicitly doubles.
 */
class M4Test {
  @Test void correctionDraftExpiresWithoutRemovingTaskAndIsRemovedOnTerminalState(){
    var correction=Map.of("details",List.of("TEST_ERROR"),"rejectedAction",Map.of("included",true,"expiresAtEpochMilli",Instant.now().plus(Duration.ofDays(7)).toEpochMilli(),"action",Map.of("mechanism","public draft")));
    Claim cancelled=fixture.claim();store.checkpoint(cancelled,Map.of("correction",correction));
    store.cancel(cancelled.id(),"alice");
    assertThat(tree(store.get(cancelled.id(),"alice").get("state_json")).at("/correction/rejectedAction").isMissingNode()).isTrue();
    store.finish(cancelled,"PARTIAL",Map.of());
    Claim finished=fixture.claim();store.checkpoint(finished,Map.of("correction",correction));store.finish(finished,"PARTIAL",Map.of());
    assertThat(tree(store.get(finished.id(),"alice").get("state_json")).at("/correction/rejectedAction").isMissingNode()).isTrue();
    Claim expired=fixture.claim();store.checkpoint(expired,Map.of("phase","REVIEW","correction",Map.of("details",List.of("TEST_ERROR"),"rejectedAction",Map.of("included",true,"expiresAtEpochMilli",1,"action",Map.of("mechanism","old draft")))));
    var before=M3Test.db.queryForObject("SELECT updated_at FROM diagnosis_task WHERE id=?",java.sql.Timestamp.class,expired.id());
    store.expireActionDiagnostics();
    var raw=M3Test.db.queryForObject("SELECT state_json FROM diagnosis_task WHERE id=?",String.class,expired.id());
    assertThat(raw).doesNotContain("old draft","rejectedAction").contains("TEST_ERROR","REVIEW");
    assertThat(M3Test.db.queryForObject("SELECT updated_at FROM diagnosis_task WHERE id=?",java.sql.Timestamp.class,expired.id())).isEqualTo(before);
    assertThat(store.active(expired)).isTrue();
  }
  @Test
  void retentionAtomicallyRemovesExpiredReportsAndChildrenButKeepsActiveTasks() {
    Claim c = fixture.claim();
    store.evidence(c, "logs", result("NO_DATA", Map.of(), Map.of()));
    store.finish(c, "PARTIAL", Reports.partial("TEST_DOUBLE_ONLY", List.of()));
    String active = store.create("alice", fixture.request);
    M3Test.db.update(
        "UPDATE diagnosis_task SET"
            + " created_at=TIMESTAMPADD(DAY,-31,NOW(6)),updated_at=TIMESTAMPADD(DAY,-31,NOW(6))");
    var retention =
        new Retention(
            M3Test.db,
            new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(M3Test.ds)));
    assertThat(retention.purge()).isEqualTo(1);
    assertThat(store.owned(active, "alice")).isEqualTo("QUEUED");
    for (String table : List.of("diagnosis_step", "diagnosis_evidence", "diagnosis_event"))
      assertThat(
              M3Test.db.queryForObject(
                  "SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, c.id()))
          .isZero();
    assertThatThrownBy(() -> store.get(c.id(), "alice")).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void cancelledInflightWorkerKeepsItsGlobalSlotUntilExit() {
    Claim first = fixture.claim();
    store.create("alice", fixture.request);
    Claim second = store.claim();
    store.cancel(first.id(), "alice");
    store.create("alice", fixture.request);
    assertThat(store.claim()).isNull();
    store.finish(first, "PARTIAL", Map.of("late", "must not replace report"));
    assertThat(store.claim()).isNotNull();
    assertThat(store.owned(first.id(), "alice")).isEqualTo("CANCELLED");
    assertThat(tree(store.get(first.id(), "alice").get("report_json")).path("gaps").toString())
        .contains("CANCELLED_BY_USER");
    assertThat(store.active(second)).isTrue();
  }

  @Test
  void modelFailureEndsPartiallyWithEvidenceAndUsage() {
    Claim c = fixture.claim();
    ModelGateway failing =
        new ModelGateway() {
          public boolean configured() {
            return true;
          }

          public ModelReply call(String p, String context) {
            throw new IllegalStateException("EXPLICIT_MODEL_FAILURE_FIXTURE");
          }
        };
    var workflow = new Workflow(store, fixture.tools(), failing);
    try {
      workflow.run(c);
    } finally {
      workflow.close();
    }
    var task = store.get(c.id(), "alice");
    assertThat(task.get("status")).isEqualTo("PARTIAL");
    assertThat(task.get("model_calls")).isEqualTo(1);
    assertThat(store.evidence(c.id())).hasSize(1);
    assertThat(tree(task.get("report_json")).path("gaps").toString()).contains("MODEL_CALL_FAILED");
    assertThat(store.modelUsage(c.id()).toString()).contains("UNAVAILABLE", "UNKNOWN");
  }

  @Test
  void realHttpToolTimeoutRetainsEvidenceAndEndsWithoutModel() throws Exception {
    fixture.server.removeContext("/ops/snapshot");
    fixture.server.createContext(
        "/ops/snapshot",
        exchange -> {
          try {
            Thread.sleep(11000);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    Claim c = fixture.claim();
    ModelGateway absent =
        new ModelGateway() {
          public boolean configured() {
            return false;
          }

          public ModelReply call(String p, String context) {
            throw new AssertionError();
          }
        };
    var workflow = new Workflow(store, fixture.tools(), absent);
    try {
      workflow.run(c);
    } finally {
      workflow.close();
    }
    assertThat(store.owned(c.id(), "alice")).isEqualTo("PARTIAL");
    assertThat(store.evidence(c.id())).hasSize(2).allMatch(e -> e.status().equals("TIMEOUT"));
    assertThat(store.usage(c.id()).get("model_calls")).isEqualTo(0);
  }

  M3Test fixture;
  TaskStore store;
  MockMvc mvc;
  @TempDir Path temp;
  static final String ALICE = "a".repeat(40), BOB = "b".repeat(40), WEB = "w".repeat(40);

  @BeforeAll
  static void database() {
    M3Test.database();
  }

  @AfterAll
  static void close() {
    M3Test.close();
  }

  @BeforeEach
  void setup() throws Exception {
    fixture = new M3Test();
    fixture.temp = temp;
    fixture.setup();
    store = fixture.store;
    fixture.response("/ops/snapshot", 200, "{}");
    ModelGateway absent =
        new ModelGateway() {
          public boolean configured() {
            return false;
          }

          public ModelReply call(String p, String c) {
            throw new AssertionError("No real model in fixture test");
          }
        };
    var api = new Api(store, absent, fixture.tools());
    var auth = new Api.Auth("alice:" + ALICE + ",bob:" + BOB);
    ReflectionTestUtils.setField(auth, "webSecret", WEB);
    mvc =
        MockMvcBuilders.standaloneSetup(api, new ProductApi(store, fixture.tools()))
            .setControllerAdvice(api)
            .addFilters(auth)
            .build();
  }

  @AfterEach
  void stop() {
    fixture.stop();
  }

  @Test
  void expiredLeaseCannotWriteEvenBeforeTakeover() {
    Claim old = fixture.claim();
    store.checkpoint(old, Map.of("phase", "INVESTIGATE"));
    M3Test.db.update(
        "UPDATE diagnosis_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?",
        old.id());
    int steps = M3Test.db.queryForObject("SELECT COUNT(*) FROM diagnosis_step", Integer.class);
    store.checkpoint(old, Map.of("phase", "REPORT"));
    store.finish(old, "COMPLETED", Map.of("bad", "stale"));
    store.step(old, "MODEL", "SUCCEEDED", Map.of("late", true));
    assertThat(store.get(old.id(), "alice").get("status")).isEqualTo("RUNNING");
    assertThat(tree(store.get(old.id(), "alice").get("state_json")).path("phase").asText())
        .isEqualTo("INVESTIGATE");
    assertThat(M3Test.db.queryForObject("SELECT COUNT(*) FROM diagnosis_step", Integer.class))
        .isEqualTo(steps);
    assertThatThrownBy(() -> store.evidence(old, "logs", result("NO_DATA", Map.of(), Map.of())))
        .isInstanceOf(IllegalStateException.class);
    Claim next = store.claim();
    assertThat(next.lease()).isNotEqualTo(old.lease());
    store.finish(next, "PARTIAL", Reports.partial("TEST_DOUBLE_ONLY", List.of()));
    store.finish(old, "COMPLETED", Map.of());
    assertThat(store.owned(old.id(), "alice")).isEqualTo("PARTIAL");
  }

  @Test
  void ownerCheckedForEveryProductReadAndMutation() throws Exception {
    Claim c = fixture.claim();
    var e = store.evidence(c, "logs", result("NO_DATA", Map.of(), Map.of()));
    for (String path :
        List.of(
            "/api/diagnoses/" + c.id(),
            "/api/diagnoses/" + c.id() + "/events",
            "/api/diagnoses/" + c.id() + "/export",
            "/api/evidence/" + e.id()))
      mvc.perform(get(path).header("Authorization", "Bearer " + BOB))
          .andExpect(status().isNotFound());
    for (String suffix : List.of("cancel", "retry", "feedback"))
      mvc.perform(
              post("/api/diagnoses/" + c.id() + "/" + suffix)
                  .header("Authorization", "Bearer " + BOB)
                  .contentType("application/json")
                  .content("{\"rating\":\"HELPFUL\",\"note\":\"test\"}"))
          .andExpect(status().isNotFound());
    mvc.perform(get("/api/diagnoses").header("Authorization", "Bearer " + BOB))
        .andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/api/evidence/" + e.id())).andExpect(status().isUnauthorized());
  }

  @Test
  void signedWebOwnerCannotBeForgedOrChanged() throws Exception {
    String signature = Api.Auth.sign(WEB, "user-1");
    String id = store.create("user-1", fixture.request);
    mvc.perform(
            get("/api/diagnoses/" + id)
                .header("X-TracePilot-Owner", "user-1")
                .header("Authorization", "Bearer " + signature))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/diagnoses/" + id)
                .header("X-TracePilot-Owner", "user-2")
                .header("Authorization", "Bearer " + signature))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/diagnoses/" + id)
                .header("X-TracePilot-Owner", "user-1")
                .header("Authorization", "Bearer forged"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void retryHistoryFeedbackAndExportPreserveOriginal() throws Exception {
    Claim c = fixture.claim();
    var ev =
        store.evidence(
            c,
            "logs",
            result(
                "AVAILABLE",
                Map.of("message", "Bearer " + ALICE + " <script>alert(1)</script>"),
                Map.of("version", "fixed-version")));
    store.finish(c, "PARTIAL", Reports.partial("TEST_DOUBLE_ONLY", List.of(ev)));
    String next = store.retry(c.id(), "alice");
    assertThat(store.get(next, "alice").get("parent_id")).isEqualTo(c.id());
    assertThat(store.owned(c.id(), "alice")).isEqualTo("PARTIAL");
    store.feedback(c.id(), "alice", "INSUFFICIENT", "test feedback");
    mvc.perform(
            get("/api/diagnoses?status=PARTIAL&size=1").header("Authorization", "Bearer " + ALICE))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.data[0].id").value(c.id()));
    new ReportExport("alice:" + ALICE, BOB, "", WEB, "password-test");
    String markdown =
        mvc.perform(
                get("/api/diagnoses/" + c.id() + "/export")
                    .header("Authorization", "Bearer " + ALICE))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(markdown)
        .contains("[REDACTED]", ev.id(), "fixed-version")
        .doesNotContain(ALICE, "<script>", next);
    assertThat(tree(store.get(c.id(), "alice").get("feedback_json")).path("kind").asText())
        .isEqualTo("USER_FEEDBACK_NOT_GROUND_TRUTH");
  }

  @Test
  void persistedSseReplaysAfterCursorAndIncludesTerminalSnapshot() throws Exception {
    Claim c = fixture.claim();
    var first = store.events(c.id(), "alice", 0);
    long cursor = ((Number) first.getLast().get("id")).longValue();
    store.checkpoint(c, Map.of("phase", "REVIEW"));
    store.cancel(c.id(), "alice");
    var pending =
        mvc.perform(
                get("/api/diagnoses/" + c.id() + "/events")
                    .header("Authorization", "Bearer " + ALICE)
                    .header("Last-Event-ID", cursor))
            .andExpect(request().asyncStarted())
            .andReturn();
    pending.getAsyncResult(5000);
    String stream =
        mvc.perform(asyncDispatch(pending))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(stream)
        .contains("event:stage", "event:snapshot", "CANCELLED")
        .doesNotContain("id:" + cursor + "\n");
    assertThat(store.events(c.id(), "alice", cursor)).hasSize(2);
    store.finish(c, "COMPLETED", Map.of("late", true));
    assertThat(store.owned(c.id(), "alice")).isEqualTo("CANCELLED");
  }

  @Test
  void queueFullHasActionableHttpResponse() throws Exception {
    for (int i = 0; i < 20; i++) store.create("alice", fixture.request);
    mvc.perform(
            post("/api/diagnoses")
                .header("Authorization", "Bearer " + ALICE)
                .contentType("application/json")
                .content(encode(fixture.request)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "5"))
        .andExpect(jsonPath("$.code").value("QUEUE_FULL"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
  }

  @Test
  void optionalInputsAndBundledRunbookStayBounded() {
    new Request(
        "tracepilot-business",
        "demo",
        Instant.now().minusSeconds(60),
        Instant.now(),
        null,
        "a".repeat(32),
        "/api/reservations");
    var result = fixture.tools().query(fixture.request, new Query("runbook", Map.of()), Set.of());
    assertThat(result.status()).isEqualTo("AVAILABLE");
    assertThat(result.locator()).containsKeys("version", "document");
    assertThat(
            fixture
                .tools()
                .query(
                    fixture.request,
                    new Query("runbook", Map.of("path", "../../private")),
                    Set.of())
                .status())
        .isEqualTo("FORBIDDEN");
    assertThat(result.data().toString()).doesNotContain("F01", "F02", "F03", "/api/drills");
  }

  @Test
  void threeRealJvmWorkersNeverExecuteSameTaskOrExceedGlobalLimit() throws Exception {
    for (int i = 0; i < 4; i++) store.create("alice", fixture.request);
    String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    String cp =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var children = new ArrayList<Process>();
    int max = 0;
    try {
      for (int i = 0; i < 3; i++)
        children.add(
            new ProcessBuilder(java, "-cp", cp, "com.tracepilot.diagnosis.WorkerProcessProbe")
                .redirectErrorStream(true)
                .redirectOutput(temp.resolve("worker-" + i + ".log").toFile())
                .start());
      long end = System.nanoTime() + 25_000_000_000L;
      while (children.stream().anyMatch(Process::isAlive) && System.nanoTime() < end) {
        int active =
            M3Test.db.queryForObject(
                "SELECT COUNT(*) FROM diagnosis_task WHERE status='RUNNING' AND lease_until>NOW(6)",
                Integer.class);
        max = Math.max(max, active);
        assertThat(active).isLessThanOrEqualTo(2);
        Thread.sleep(40);
      }
      for (var p : children) {
        assertThat(p.waitFor(2, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
      }
      var claims =
          M3Test.db.queryForList(
              "SELECT task_id,COUNT(*) n FROM diagnosis_step WHERE kind='TEST_PROCESS' GROUP BY"
                  + " task_id");
      assertThat(claims).hasSize(4);
      for (var row : claims) assertThat(((Number) row.get("n")).intValue()).isEqualTo(1);
      assertThat(
              M3Test.db.queryForObject(
                  "SELECT COUNT(*) FROM diagnosis_task WHERE status='PARTIAL'", Integer.class))
          .isEqualTo(4);
      assertThat(max).isEqualTo(2);
      Files.writeString(
          Path.of("target/m4-process-evidence.json"),
          encode(
              Map.of(
                  "fixture",
                  "TEST_DOUBLE_ONLY_NO_MODEL",
                  "workerPids",
                  children.stream().map(Process::pid).toList(),
                  "maxObservedActive",
                  max,
                  "executionClaims",
                  claims,
                  "steps",
                  M3Test.db.queryForList(
                      "SELECT task_id,kind,status,detail_json,created_at FROM diagnosis_step ORDER"
                          + " BY id"))));
    } finally {
      for (var p : children) if (p.isAlive()) p.destroyForcibly();
    }
  }
}
