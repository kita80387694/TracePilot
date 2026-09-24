package com.tracepilot.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationConsumer {
  private final JdbcTemplate db;

  public NotificationConsumer(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public long deliver(long eventId) {
    db.update(
        "INSERT INTO notification(event_id,user_id,message) SELECT id,user_id,payload FROM outbox"
            + " WHERE id=? ON DUPLICATE KEY UPDATE event_id=event_id",
        eventId);
    return db.queryForObject("SELECT id FROM notification WHERE event_id=?",Long.class,eventId);
  }
}
