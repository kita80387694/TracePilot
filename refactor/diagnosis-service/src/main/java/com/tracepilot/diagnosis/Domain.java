package com.tracepilot.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

public final class Domain {
  public static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  public static final String PROMPT_VERSION = "tools-report-v9.5-bounded-completion-offline";

  public record Request(
      String service,
      String environment,
      Instant start,
      Instant end,
      String symptom,
      String traceId,
      String interfacePath) {
    public Request(String service, String environment, Instant start, Instant end, String symptom) {
      this(service, environment, start, end, symptom, null, null);
    }

    public Request {
      if (!"tracepilot-business".equals(service) || !"demo".equals(environment))
        throw new IllegalArgumentException("SERVICE_DENIED");
      if (start == null
          || end == null
          || !start.isBefore(end)
          || Duration.between(start, end).compareTo(Duration.ofHours(1)) > 0
          || end.isAfter(Instant.now().plusSeconds(5)))
        throw new IllegalArgumentException("INVALID_WINDOW");
      if (symptom == null) symptom = "";
      if (symptom.length() > 2000) throw new IllegalArgumentException("INVALID_SYMPTOM");
      if (traceId != null && !traceId.isBlank() && !traceId.matches("[0-9a-f]{32}"))
        throw new IllegalArgumentException("INVALID_TRACE");
      if (interfacePath != null
          && !interfacePath.isBlank()
          && !interfacePath.matches("/api/[A-Za-z0-9_/{}/-]{0,180}"))
        throw new IllegalArgumentException("INVALID_INTERFACE");
    }
  }

  public record Query(String tool, Map<String, String> args) {
    public Query {
      if (args == null) args = Map.of();
    }
  }

  public record Result(String status, JsonNode data, Map<String, Object> locator) {}

  public record Evidence(
      String id,
      String source,
      String status,
      String start,
      String end,
      Map<String, Object> locator,
      JsonNode data) {}

  public record Claim(
      String id, String lease, Request request, Map<String, Object> state, Instant deadline) {}

  public record ModelReply(
      JsonNode action, String model, Integer inputTokens, Integer outputTokens,
      Map<String,Object> protocol) {
    public ModelReply(JsonNode action, String model, Integer inputTokens, Integer outputTokens) {
      this(action, model, inputTokens, outputTokens, Map.of());
    }
  }

  public static String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON_ENCODING");
    }
  }

  public static JsonNode tree(Object value) {
    return JSON.valueToTree(value);
  }

  public static JsonNode parse(String value) {
    try {
      return JSON.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("INVALID_JSON");
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(String value) {
    return JSON.convertValue(parse(value), Map.class);
  }

  public static Result result(String status, Object data, Map<String, Object> locator) {
    return new Result(status, tree(data), locator);
  }

  private Domain() {}
}
