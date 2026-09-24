package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import com.zaxxer.hikari.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate JVMs against the real test schema. This is NOT a real diagnosis or model result. */
public class WorkerProcessProbe {
  public static void main(String[] args) throws Exception {
    var config = new HikariConfig();
    config.setJdbcUrl(
        System.getenv()
            .getOrDefault(
                "DIAG_TEST_DB_URL",
                "jdbc:mysql://127.0.0.1:3307/tracepilot_diagnosis_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
    config.setUsername("tracepilot_diag");
    config.setPassword(System.getenv("DIAG_DB_PASSWORD"));
    config.setMaximumPoolSize(2);
    config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    try (var ds = new HikariDataSource(config)) {
      var db = new JdbcTemplate(ds);
      if (!db.queryForObject("SELECT DATABASE()", String.class).endsWith("_test"))
        throw new IllegalStateException("TEST_SCHEMA_REQUIRED");
      var store = new TaskStore(db, new TransactionTemplate(new DataSourceTransactionManager(ds)));
      long end = System.nanoTime() + 15_000_000_000L;
      while (System.nanoTime() < end) {
        Claim c = store.claim();
        if (c == null) {
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM diagnosis_task WHERE status IN ('QUEUED','RUNNING')",
                  Integer.class)
              == 0) return;
          Thread.sleep(50);
          continue;
        }
        store.step(
            c,
            "TEST_PROCESS",
            "EXECUTING",
            Map.of("pid", ProcessHandle.current().pid(), "fixture", "NO_MODEL"));
        Thread.sleep(1800);
        store.finish(c, "PARTIAL", Reports.partial("TEST_DOUBLE_ONLY", List.of()));
      }
      throw new IllegalStateException("PROBE_TIMEOUT");
    }
  }
}
