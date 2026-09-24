package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Opt-in real M2 read-only probe, no model and no fault control. Not shipped in the runtime JAR.
 */
public class LiveToolsProbe {
  public static void main(String[] args) throws Exception {
    Instant end = Instant.now();
    Request request =
        new Request("tracepilot-business", "demo", end.minusSeconds(600), end, "只读数据契约检查");
    String token = System.getenv("OBSERVER_TOKEN"),
        root =
            System.getenv()
                .getOrDefault("DIAG_EVIDENCE_ROOT", ".local/m2-acceptance/observability");
    ReadTools tools = new ReadTools("http://127.0.0.1:8082", token, root);
    Map<String, Object> results = new LinkedHashMap<>();
    Result overview = tools.probe(request, new Query("overview", Map.of()));
    check(overview);
    results.put("overview", overview);
    Result metrics = tools.probe(request, new Query("metrics", Map.of("limit", "20")));
    check(metrics);
    results.put("metrics", metrics);
    Result logs = tools.probe(request, new Query("logs", Map.of("limit", "100")));
    check(logs);
    results.put("logs", logs);
    String trace = null, version = null;
    for (var row : logs.data().path("data")) {
      if (row.path("traceId").asText().matches("[0-9a-f]{32}")) {
        trace = row.path("traceId").asText();
        version = row.path("deploymentVersion").asText();
        break;
      }
    }
    if (trace == null) throw new IllegalStateException("No actual trace in source window");
    Result correlated =
        tools.probe(request, new Query("trace", Map.of("traceId", trace, "limit", "20")));
    check(correlated);
    results.put("trace", correlated);
    Result code =
        tools.query(
            request,
            new Query("code", Map.of("version", version, "query", "EventPublisher", "limit", "10")),
            Set.of(version));
    check(code);
    if (code.data().path("hits").isEmpty()) throw new IllegalStateException("No real code match");
    results.put("code", code);
    ReadTools offline = new ReadTools("http://127.0.0.1:1", token, root);
    Result archived = offline.probe(request, new Query("logs", Map.of("limit", "5")));
    check(archived);
    if (!"offline-observability".equals(archived.locator().get("source")))
      throw new IllegalStateException("Not an offline result");
    results.put("offline", archived);
    Result denied = tools.query(request, new Query("drills", Map.of()), Set.of());
    if (!denied.status().equals("FORBIDDEN")) throw new IllegalStateException("Policy failed");
    results.put("denied", denied);
    Files.writeString(
        Path.of(".local/m3/live-tools.json"),
        encode(Map.of("result", "PASS", "kind", "REAL_M2_READ_ONLY_NO_MODEL", "queries", results)));
    System.out.println(
        "Real M2 overview, metrics, logs, trace, verified code, offline fallback and tool denial"
            + " PASS");
  }

  private static void check(Result result) {
    if (!Set.of("AVAILABLE", "PARTIAL").contains(result.status()))
      throw new IllegalStateException("Actual source check failed: " + result.status());
  }
}
