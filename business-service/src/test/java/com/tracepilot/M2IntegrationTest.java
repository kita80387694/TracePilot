package com.tracepilot;

import static org.assertj.core.api.Assertions.*;

import com.tracepilot.drills.DrillManager;
import com.tracepilot.events.DeliveryGate;
import com.tracepilot.identity.SecurityConfig;
import com.tracepilot.observability.SafeJsonEncoder;
import com.tracepilot.reporting.ReportQueryPolicy;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.demo-seed=false",
      "app.environment=demo",
      "app.events-enabled=false",
      "app.observer-token=test-observer-value",
      "app.data-dir=target/m2-test"
    })
@ActiveProfiles("demo")
class M2IntegrationTest {
  @Test void queryPlanIsObserverOnlyAndAcceptsRegisteredFingerprintsOnly(){
    db.execute("CREATE TABLE IF NOT EXISTS report_sample(id BIGINT PRIMARY KEY,lookup_indexed INT NOT NULL,lookup_scan INT NOT NULL,payload VARCHAR(128) NOT NULL,INDEX idx_lookup(lookup_indexed)) ENGINE=InnoDB");
    String fingerprint=com.tracepilot.observability.QueryPlanObservation.fingerprint(com.tracepilot.reporting.UsageReport.sql(false));
    String q="/ops/query-plan?statementFingerprint="+fingerprint;
    assertThat(call(HttpMethod.GET,q,admin,null).getStatusCode().value()).isEqualTo(401);
    var response=call(HttpMethod.GET,q,"test-observer-value",null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().get("status")).isEqualTo("AVAILABLE");
    assertThat(response.getBody().get("statementFingerprint")).isEqualTo(fingerprint);
    assertThat(response.getBody().get("plan").toString()).contains("report_sample","idx_lookup");
    assertThat(response.getBody().get("observationMeaning").toString()).contains("not historical execution");
    assertThat(call(HttpMethod.GET,"/ops/query-plan?statementFingerprint="+"0".repeat(64),"test-observer-value",null).getStatusCode().value()).isEqualTo(400);
    assertThat(call(HttpMethod.GET,"/ops/query-plan?statementFingerprint=SELECT", "test-observer-value",null).getStatusCode().value()).isEqualTo(400);
  }
  @Test void eventChannelIsReadOnlyBoundedAndDoesNotClaimUncollectedHistory() {
    var start=java.time.Instant.now().minusSeconds(300);var end=java.time.Instant.now();
    String q="/ops/logs?start="+start+"&end="+end+"&limit=1&channel=events";
    assertThat(call(HttpMethod.GET,q,user,null).getStatusCode().value()).isEqualTo(401);
    var result=call(HttpMethod.GET,q,"test-observer-value",null);
    assertThat(result.getStatusCode().value()).isEqualTo(200);
    // Retained test archives can predate this process. Query across the actual coverage boundary,
    // rather than assuming the last five minutes precede collection on every test rerun.
    var boundary=java.time.Instant.parse(result.getBody().get("sourceCoverageStart").toString());
    q="/ops/logs?start="+boundary.minusSeconds(60)+"&end="+boundary.plusSeconds(60)+"&limit=1&channel=events";
    result=call(HttpMethod.GET,q,"test-observer-value",null);
    assertThat(result.getBody().get("sourceGap")).isEqualTo("EVENT_CHANNEL_NOT_COLLECTED_BEFORE_SOURCE_START");
    assertThat(result.getBody().get("status")).isEqualTo("PARTIAL");
    assertThat(call(HttpMethod.GET,q.replace("channel=events","channel=../../private"),"test-observer-value",null).getStatusCode().value()).isEqualTo(400);
  }
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    M1IntegrationTest.database(r);
  }

  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @Autowired DrillManager drills;
  @Autowired DeliveryGate gate;
  @Autowired ReportQueryPolicy policy;
  @Autowired org.springframework.boot.autoconfigure.jdbc.DataSourceProperties properties;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  String admin, user;

  @BeforeEach
  void credentials() {
    assertThat(db.queryForObject("SELECT DATABASE()", String.class)).endsWith("_test");
    db.update(
        "INSERT INTO app_user(username,password_hash,role) VALUES('m2admin','unused','ADMIN') ON"
            + " DUPLICATE KEY UPDATE role='ADMIN'");
    db.update(
        "INSERT INTO app_user(username,password_hash,role) VALUES('m2user','unused','USER') ON"
            + " DUPLICATE KEY UPDATE role='USER'");
    admin = token("m2admin");
    user = token("m2user");
  }

  String token(String name) {
    String t = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO auth_token(token_hash,user_id,expires_at) SELECT"
            + " ?,id,TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP()) FROM app_user WHERE username=?",
        SecurityConfig.hash(t),
        name);
    return t;
  }

  ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.setBearerAuth(token);
    return http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
  }

  Map<String, Object> startBody(int seconds) {
    return Map.of(
        "scenario",
        "F03",
        "durationSeconds",
        seconds,
        "concurrency",
        1,
        "requestsPerPhase",
        1,
        "acknowledged",
        true);
  }

  @Test
  void controlsRequireAdministratorAndObserverIsReadOnly() {
    assertThat(call(HttpMethod.GET, "/api/drills", null, null).getStatusCode().value())
        .isEqualTo(401);
    assertThat(call(HttpMethod.GET, "/api/drills", user, null).getStatusCode().value())
        .isEqualTo(403);
    assertThat(call(HttpMethod.GET, "/api/drills", admin, null).getStatusCode().value())
        .isEqualTo(200);
    assertThat(
            call(HttpMethod.GET, "/api/drills", "test-observer-value", null)
                .getStatusCode()
                .value())
        .isEqualTo(401);
    assertThat(call(HttpMethod.GET, "/ops/snapshot", admin, null).getStatusCode().value())
        .isEqualTo(401);
    assertThat(
            call(HttpMethod.GET, "/ops/snapshot", "test-observer-value", null)
                .getStatusCode()
                .value())
        .isEqualTo(200);
  }

  @Test
  void durationBoundAcknowledgementAndOverlappingRunsAreEnforced() {
    assertThat(call(HttpMethod.POST, "/api/drills", admin, startBody(301)).getStatusCode().value())
        .isEqualTo(400);
    var body = new HashMap<>(startBody(30));
    body.put("acknowledged", false);
    assertThat(call(HttpMethod.POST, "/api/drills", admin, body).getStatusCode().value())
        .isEqualTo(400);
    var run = call(HttpMethod.POST, "/api/drills", admin, startBody(30));
    assertThat(run.getStatusCode().value()).isEqualTo(200);
    String id = (String) run.getBody().get("id");
    try {
      assertThat(gate.open()).isFalse();
      assertThat(call(HttpMethod.POST, "/api/drills", admin, startBody(30)).getStatusCode().value())
          .isEqualTo(409);
    } finally {
      call(HttpMethod.POST, "/api/drills/" + id + "/stop", admin, null);
    }
    assertThat(gate.open()).isTrue();
    assertThat(
            call(HttpMethod.POST, "/api/drills/" + id + "/stop", admin, null)
                .getBody()
                .get("status"))
        .isEqualTo("STOPPED");
  }

  @Test
  void automaticDeadlineRestoresConsumerAndPersistsResult() throws Exception {
    var run = call(HttpMethod.POST, "/api/drills", admin, startBody(1));
    String id = (String) run.getBody().get("id");
    long until = System.nanoTime() + 5_000_000_000L;
    while (!"STOPPED".equals(drills.get(id).get("status")) && System.nanoTime() < until)
      Thread.sleep(50);
    assertThat(drills.get(id).get("status")).isEqualTo("STOPPED");
    assertThat(gate.open()).isTrue();
    assertThat(((Map<?, ?>) drills.get(id).get("recovery")).get("reason"))
        .isEqualTo("AUTO_DEADLINE");
  }

  @Test
  void boundedHistoricalQueryDistinguishesNoData() {
    String path = "/ops/samples?start=2001-01-01T00:00:00Z&end=2001-01-01T00:10:00Z";
    var result = call(HttpMethod.GET, path, "test-observer-value", null);
    assertThat(result.getBody().get("status")).isEqualTo("NO_DATA");
    assertThat((List<?>) result.getBody().get("data")).isEmpty();
    assertThat(
            call(
                    HttpMethod.GET,
                    "/ops/logs?start=2001-01-01T00:00:00Z&end=2001-01-02T00:00:00Z",
                    "test-observer-value",
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }

  @Test
  void redactionDropsCredentialAndConnectionValues() {
    String safe =
        SafeJsonEncoder.redact(
            "password=sentinel-pass token=sentinel-token Bearer sentinel-bearer"
                + " jdbc:mysql://private/db?password=hidden a@example.com");
    assertThat(safe)
        .doesNotContain(
            "sentinel-pass", "sentinel-token", "sentinel-bearer", "private/db", "a@example.com");
  }

  @Test
  void requestTraceCorrelationIsPropagated() {
    var headers = new HttpHeaders();
    headers.setBearerAuth(user);
    headers.set("traceparent", "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01");
    var response =
        http.exchange("/api/slots", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(response.getHeaders().getFirst("X-Trace-Id"))
        .isEqualTo("1234567890abcdef1234567890abcdef");
    assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
  }

  @Test
  void unavailableMetricSourceIsNotReportedAsZero() {
    var closed = new com.tracepilot.observability.AuxiliaryDatabase(properties);
    assertThat(closed.jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    closed.close(); // Real datasource failure, not a fixed/mock metric result.
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    try {
      var sampler =
          new com.tracepilot.observability.MetricSampler(
              dataSource, closed, registry, json, "target/m2-source-failure", "demo", "test", true);
      sampler.sample();
      var value = sampler.latest();
      assertThat(value.get("status")).isEqualTo("PARTIAL");
      var events = (Map<?, ?>) value.get("events");
      assertThat(events.get("status")).isEqualTo("UNAVAILABLE");
      assertThat(events.get("pending")).isNull();
      assertThat(events.get("oldestSeconds")).isNull();
      assertThat(((Map<?, ?>) value.get("http")).get("errorRate")).isNull();
    } finally {
      registry.close();
    }
  }
}
