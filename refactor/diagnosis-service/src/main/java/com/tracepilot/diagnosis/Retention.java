package com.tracepilot.diagnosis;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** PRD report retention. Housekeeping of this service's own data, never an Agent tool. */
@Component
public class Retention {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public Retention(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  public void tick() {
    purge();
  }

  public int purge() {
    return tx.execute(
        status -> {
          var ids =
              db.queryForList(
                  "SELECT id FROM diagnosis_task WHERE status IN"
                      + " ('COMPLETED','PARTIAL','FAILED','CANCELLED') AND"
                      + " updated_at<TIMESTAMPADD(DAY,-30,NOW(6)) AND (lease_until IS NULL OR"
                      + " lease_until<NOW(6)) ORDER BY updated_at LIMIT 100 FOR UPDATE SKIP LOCKED",
                  String.class);
          for (String id : ids) {
            db.update("DELETE FROM diagnosis_event WHERE task_id=?", id);
            db.update("DELETE FROM diagnosis_step WHERE task_id=?", id);
            db.update("DELETE FROM diagnosis_evidence WHERE task_id=?", id);
            db.update("DELETE FROM diagnosis_task WHERE id=?", id);
          }
          return ids.size();
        });
  }
}
