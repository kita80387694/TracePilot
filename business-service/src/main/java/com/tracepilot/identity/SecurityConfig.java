package com.tracepilot.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  public record Actor(long id, String role) {}

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      JdbcTemplate db,
      ObjectMapper json,
      com.tracepilot.observability.AuxiliaryDatabase auxiliary,
      @org.springframework.beans.factory.annotation.Value("${app.observer-token}")
          String observerToken)
      throws Exception {
    var filter =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws IOException, ServletException {
            String auth = req.getHeader("Authorization");
            boolean observation =
                req.getRequestURI().startsWith("/ops/")
                    || req.getRequestURI().startsWith("/actuator/");
            if (observation) {
              if (!observerToken.isBlank()
                  && auth != null
                  && MessageDigest.isEqual(
                      hash(auth).getBytes(StandardCharsets.UTF_8),
                      hash("Bearer " + observerToken).getBytes(StandardCharsets.UTF_8)))
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                            new Actor(0, "OBSERVER"),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_OBSERVER"))));
              chain.doFilter(req, res);
              return;
            }
            if (auth != null && auth.startsWith("Bearer ")) {
              try {
                JdbcTemplate authDb =
                    req.getRequestURI().startsWith("/api/drills") ? auxiliary.jdbc : db;
                var actors =
                    authDb.query(
                        "SELECT u.id,u.role FROM auth_token t JOIN app_user u ON u.id=t.user_id"
                            + " WHERE token_hash=? AND expires_at>CURRENT_TIMESTAMP(6)",
                        (rs, n) -> new Actor(rs.getLong(1), rs.getString(2)),
                        hash(auth.substring(7)));
                if (!actors.isEmpty()) {
                  var a = actors.getFirst();
                  SecurityContextHolder.getContext()
                      .setAuthentication(
                          new UsernamePasswordAuthenticationToken(
                              a, null, List.of(new SimpleGrantedAuthority("ROLE_" + a.role()))));
                }
              } catch (org.springframework.dao.DataAccessException e) {
                if (!com.tracepilot.observability.RequestTelemetry.control(req.getRequestURI()))
                  com.tracepilot.observability.Evidence.error(
                      "authentication_store_unavailable", e);
                res.setStatus(503);
                res.setContentType("application/json");
                json.writeValue(
                    res.getOutputStream(),
                    Map.of(
                        "code",
                        "DATABASE_UNAVAILABLE",
                        "message",
                        "Authentication store unavailable",
                        "requestId",
                        String.valueOf(req.getAttribute("requestId"))));
                return;
              }
            }
            chain.doFilter(req, res);
          }
        };
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/auth/login")
                    .permitAll()
                    .requestMatchers("/ops/**", "/actuator/**")
                    .hasRole("OBSERVER")
                    .requestMatchers("/api/admin/**", "/api/drills", "/api/drills/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .hasAnyRole("USER", "ADMIN"))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((r, s, x) -> write(json, r, s, 401))
                    .accessDeniedHandler((r, s, x) -> write(json, r, s, 403)))
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static void write(
      ObjectMapper json, HttpServletRequest r, HttpServletResponse s, int status)
      throws IOException {
    s.setStatus(status);
    s.setContentType("application/json");
    json.writeValue(
        s.getOutputStream(),
        Map.of(
            "code",
            status == 401 ? "UNAUTHENTICATED" : "FORBIDDEN",
            "message",
            status == 401 ? "Login required" : "Permission denied",
            "requestId",
            String.valueOf(r.getAttribute("requestId"))));
  }
}
