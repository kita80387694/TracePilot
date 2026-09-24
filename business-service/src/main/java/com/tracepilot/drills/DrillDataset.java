package com.tracepilot.drills;

import com.tracepilot.reporting.UsageReport;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DrillDataset {
  private final JdbcTemplate db;

  public DrillDataset(JdbcTemplate db) {
    this.db = db;
  }

  public synchronized Map<String, Object> prepare() {
    db.execute(
        "CREATE TABLE IF NOT EXISTS report_sample(id BIGINT PRIMARY KEY,lookup_indexed INT NOT"
            + " NULL,lookup_scan INT NOT NULL,payload VARCHAR(128) NOT NULL,INDEX"
            + " idx_lookup(lookup_indexed)) ENGINE=InnoDB");
    long count = db.queryForObject("SELECT COUNT(*) FROM report_sample", Long.class);
    if (count == 0) {
      String digit =
          "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4"
              + " UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION"
              + " ALL SELECT 9)";
      String n = "a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n";
      db.execute(
          "INSERT INTO report_sample SELECT "
              + n
              + "+1,MOD("
              + n
              + ",100000),MOD("
              + n
              + ",100000),REPEAT('x',128) FROM "
              + digit
              + " a CROSS JOIN "
              + digit
              + " b CROSS JOIN "
              + digit
              + " c CROSS JOIN "
              + digit
              + " d CROSS JOIN "
              + digit
              + " e CROSS JOIN "
              + digit
              + " f");
    }
    long actual = db.queryForObject("SELECT COUNT(*) FROM report_sample", Long.class);
    com.tracepilot.api.ApiSupport.require(actual == 1000000, 409, "DATASET_SIZE_MISMATCH");
    db.execute("ANALYZE TABLE report_sample");
    return Map.of(
        "rows",
        actual,
        "recipe",
        "sequential-id-v1",
        "lookupCardinality",
        100000,
        "payloadBytes",
        128,
        "mysqlVersion",
        db.queryForObject("SELECT VERSION()", String.class));
  }

  public List<String> explain(boolean alternate) {
    return db.query(
        "EXPLAIN ANALYZE " + UsageReport.sql(alternate), (rs, i) -> rs.getString(1), 4242);
  }

  public long size() {
    try {
      return db.queryForObject("SELECT COUNT(*) FROM report_sample", Long.class);
    } catch (org.springframework.dao.DataAccessException e) {
      return 0;
    }
  }
}
