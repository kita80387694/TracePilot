package com.tracepilot.identity;

import static com.tracepilot.api.ApiSupport.require;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;

  public AuthController(JdbcTemplate db, PasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  public record Login(
      @NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 72) String password) {}

  @PostMapping("/api/auth/login")
  public Object login(@Valid @RequestBody Login input) {
    var rows = db.queryForList("SELECT * FROM app_user WHERE username=?", input.username());
    require(
        !rows.isEmpty()
            && encoder.matches(input.password(), (String) rows.getFirst().get("password_hash")),
        401,
        "INVALID_CREDENTIALS");
    var user = rows.getFirst();
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    db.update(
        "INSERT INTO auth_token(token_hash,user_id,expires_at)"
            + " VALUES(?,?,TIMESTAMPADD(HOUR,24,CURRENT_TIMESTAMP(6)))",
        SecurityConfig.hash(token),
        user.get("id"));
    return Map.of(
        "token", token, "expiresIn", 86400, "userId", user.get("id"), "role", user.get("role"));
  }
}
