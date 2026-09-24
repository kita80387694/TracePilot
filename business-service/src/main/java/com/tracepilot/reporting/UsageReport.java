package com.tracepilot.reporting;

import com.tracepilot.observability.Evidence;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Demo report workload, with real bounded database execution on the business connection pool. */
@RestController
@Profile("demo")
public class UsageReport {
  private final JdbcTemplate db;
  private final ReportQueryPolicy policy;
  private final MeterRegistry meters;

  public UsageReport(JdbcTemplate db, ReportQueryPolicy policy, MeterRegistry meters) {
    this.db = db;
    this.policy = policy;
    this.meters = meters;
  }

  public static String sql(boolean alternate) {
    return "SELECT /*+ MAX_EXECUTION_TIME(3000) */ COUNT(*)"
        + " matches,COALESCE(SUM(OCTET_LENGTH(payload)),0) bytes FROM report_sample WHERE "
        + (alternate ? "lookup_scan" : "lookup_indexed")
        + "=?";
  }

  @GetMapping("/api/reports/usage")
  public Object report() {
    long started = System.nanoTime();
    long[] connectionAcquired = {-1L};
    // Query shape contains no parameter value or drill/control state.
    String statementSql = sql(policy.alternate());
    String statementFingerprint;
    try {
      statementFingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(statementSql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    String outcome = "SUCCESS";
    try {
      return Map.of(
          "data",
          db.execute(
              (ConnectionCallback<Map<String, Object>>)
                  c -> {
                    connectionAcquired[0] = System.nanoTime();
                    try (var statement = c.prepareStatement(statementSql)) {
                      statement.setInt(1, 4242);
                      statement.setQueryTimeout(4);
                      try (var rs = statement.executeQuery()) {
                        rs.next();
                        return Map.of("matches", rs.getLong(1), "bytes", rs.getLong(2));
                      }
                    }
                  }));
    } catch (Exception e) {
      outcome = "ERROR";
      Evidence.error("report_query_failed", e);
      throw e;
    } finally {
      long finished = System.nanoTime();
      long elapsed = finished - started;
      meters
          .timer("tracepilot.sql", "query", "usage-lookup-v1", "outcome", outcome)
          .record(elapsed, TimeUnit.NANOSECONDS);
      Evidence.emit(
          "sql_completed",
          "queryId",
          "usage-lookup-v1",
          "durationMs",
          elapsed / 1_000_000.0,
          "connectionAcquired", connectionAcquired[0] >= 0,
          "connectionAcquireMs", connectionAcquired[0] >= 0 ? (connectionAcquired[0] - started) / 1_000_000.0 : null,
          "jdbcOperationMs", connectionAcquired[0] >= 0 ? (finished - connectionAcquired[0]) / 1_000_000.0 : null,
          "statementFingerprint", statementFingerprint,
          "outcome",
          outcome);
    }
  }
}

