package com.tracepilot.identity;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.demo-seed", havingValue = "true")
public class DemoSeed implements CommandLineRunner {
  private final JdbcTemplate db;
  private final PasswordEncoder passwords;

  public DemoSeed(JdbcTemplate db, PasswordEncoder passwords) {
    this.db = db;
    this.passwords = passwords;
  }

  @Override
  @Transactional
  public void run(String... args) {
    String hash = passwords.encode("Demo-pass-123");
    db.update(
        "INSERT INTO app_user(username,password_hash,role) VALUES('admin',?,'ADMIN') ON DUPLICATE"
            + " KEY UPDATE username=username",
        hash);
    for (int i = 1; i <= 200; i++)
      db.update(
          "INSERT INTO app_user(username,password_hash,role) VALUES(?,?,'USER') ON DUPLICATE KEY"
              + " UPDATE username=username",
          "user" + i,
          hash);
  }
}
