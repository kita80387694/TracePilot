package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@RestController
@RestControllerAdvice
public class Api {
  @Value("${diag.worker-enabled:true}")
  private boolean workerEnabled = true;

  private final TaskStore store;
  private final ModelGateway model;
  private final ReadTools tools;

  public Api(TaskStore store, ModelGateway model, ReadTools tools) {
    this.store = store;
    this.model = model;
    this.tools = tools;
  }

  @GetMapping("/health")
  public Object health() {
    return Map.of(
        "status",
        "UP",
        "modelConfigured",
        model.configured(),
        "promptVersion",
        model instanceof ActionModelGateway gateway?gateway.actionPromptVersion():PROMPT_VERSION,
        "workerEnabled",
        workerEnabled);
  }

  @PostMapping("/api/diagnoses")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public Object create(HttpServletRequest http, @RequestBody Request request) {
    Result preflight = tools.probe(request, new Query("overview", Map.of()));
    if (Set.of("FORBIDDEN", "UNAVAILABLE", "TIMEOUT").contains(preflight.status())) {
      Result logs = tools.probe(request, new Query("logs", Map.of("limit", "1")));
      if (Set.of("FORBIDDEN", "UNAVAILABLE", "TIMEOUT").contains(logs.status()))
        throw new IllegalStateException("ALL_SOURCES_UNAVAILABLE");
    }
    String id = store.create(owner(http), request);
    return Map.of("id", id, "status", "QUEUED");
  }

  @GetMapping("/api/diagnoses/{id}")
  public Object get(HttpServletRequest http, @PathVariable String id) {
    return store.get(id, owner(http));
  }

  @PostMapping("/api/diagnoses/{id}/cancel")
  public Object cancel(HttpServletRequest http, @PathVariable String id) {
    store.cancel(id, owner(http));
    return store.get(id, owner(http));
  }

  private String owner(HttpServletRequest request) {
    return (String) request.getAttribute("diagnosticOwner");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> bad(IllegalArgumentException e) {
    if ("QUEUE_FULL".equals(e.getMessage()))
      return ResponseEntity.status(429)
          .header("Retry-After", "5")
          .body(
              Map.of(
                  "code",
                  "QUEUE_FULL",
                  "message",
                  "任务队列已满，请稍后重试",
                  "requestId",
                  UUID.randomUUID().toString()));
    return failure(400, "INVALID_REQUEST", "参数不合法，请检查时间范围及输入格式");
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<?> malformed() {
    return failure(400, "INVALID_REQUEST", "请求格式不合法");
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<?> missing() {
    return failure(404, "NOT_FOUND", "资源不存在或无权访问");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unavailable() {
    return failure(503, "UNAVAILABLE", "诊断数据源或服务暂不可用");
  }

  private ResponseEntity<?> failure(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "error",
                code,
                "code",
                code,
                "message",
                message,
                "requestId",
                UUID.randomUUID().toString()));
  }

  @Component
  static class Auth extends OncePerRequestFilter {
    @Value("${diag.web-auth-secret:}")
    private String webSecret = "";

    private final Map<String, byte[]> keys = new LinkedHashMap<>();

    Auth(@Value("${diag.api-keys}") String configured) {
      for (String entry : configured.split(",")) {
        if (entry.isBlank()) continue;
        String[] pair = entry.split(":", 2);
        if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]{1,80}") || pair[1].length() < 32)
          throw new IllegalArgumentException("INVALID_API_KEY_CONFIGURATION");
        keys.put(pair[0], hash(pair[1]));
      }
    }

    protected void doFilterInternal(
        HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws java.io.IOException, ServletException {
      if (req.getRequestURI().equals("/health")) {
        chain.doFilter(req, res);
        return;
      }
      String header = req.getHeader("Authorization");
      String key = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
      String owner = null;
      String webOwner = req.getHeader("X-TracePilot-Owner");
      if (webSecret.length() >= 32
          && webOwner != null
          && webOwner.matches("user-[1-9][0-9]{0,18}")
          && MessageDigest.isEqual(hash(key), hash(sign(webSecret, webOwner)))) owner = webOwner;
      for (var entry : keys.entrySet())
        if (MessageDigest.isEqual(entry.getValue(), hash(key))) owner = entry.getKey();
      if (owner == null) {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
        return;
      }
      req.setAttribute("diagnosticOwner", owner);
      chain.doFilter(req, res);
    }

    static byte[] hash(String key) {
      try {
        return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) {
        throw new IllegalStateException();
      }
    }

    static String sign(String secret, String owner) {
      try {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
            new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(owner.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception e) {
        throw new IllegalStateException();
      }
    }
  }
}
