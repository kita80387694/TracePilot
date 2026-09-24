package com.tracepilot.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** One JSON object per line. Never serialize Throwable messages, headers, or arbitrary MDC. */
public class SafeJsonEncoder extends EncoderBase<ILoggingEvent> {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final String INSTANCE = UUID.randomUUID().toString();
  private static final Set<String> FIELDS =
      Set.of(
          "eventId",
          "attemptId","notificationId","stateAt","nextAt","attempts","notificationCount","queryEventId","sqlErrorCode","actorId",
          "operation",
          "operationPhase", "notificationCommitObservation",
          "connectionAcquired", "connectionAcquireMs", "jdbcOperationMs", "statementFingerprint",
          "status",
          "durationMs",
          "errorType",
          "sqlState",
          "queryId",
          "rows",
          "outcome",
          "sourceRequestId",
          "sourceTraceId",
          "sourceVersion",
          "method",
          "route",
          "replayed",
          "frames");

  @Override
  public byte[] headerBytes() {
    return null;
  }

  @Override
  public byte[] footerBytes() {
    return null;
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    try {
      var row = new LinkedHashMap<String, Object>();
      row.put("time", Instant.ofEpochMilli(event.getTimeStamp()).toString());
      row.put("collectedAt", Instant.now().toString());
      row.put("service", getContext().getProperty("service"));
      row.put("environment", getContext().getProperty("environment"));
      row.put("deploymentVersion", getContext().getProperty("deploymentVersion"));
      row.put("instanceId", INSTANCE);
      row.put("evidenceId", UUID.randomUUID().toString());
      row.put("level", event.getLevel().toString());
      row.put("requestId", event.getMDCPropertyMap().getOrDefault("requestId", ""));
      row.put("traceId", event.getMDCPropertyMap().getOrDefault("traceId", ""));
      for(String key:List.of("eventId","attemptId"))if(event.getMDCPropertyMap().containsKey(key))row.put(key,event.getMDCPropertyMap().get(key));
      row.put("logger", event.getLoggerName());
      row.put("message", redact(event.getFormattedMessage()));
      if (event.getKeyValuePairs() != null)
        for (var kv : event.getKeyValuePairs())
          if (FIELDS.contains(kv.key))
            row.put(
                kv.key,
                kv.value == null || kv.value instanceof Number || kv.value instanceof Boolean
                    ? kv.value
                    : redact(String.valueOf(kv.value)));
      if (event.getThrowableProxy() != null)
        row.put("errorType", event.getThrowableProxy().getClassName());
      return (JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "{\"level\":\"ERROR\",\"message\":\"LOG_ENCODING_FAILED\"}\n"
          .getBytes(StandardCharsets.UTF_8);
    }
  }

  public static String redact(String value) {
    if (value == null) return "";
    return value
        .replaceAll("(?i)Bearer\\s+[^\\s\"',}]+", "Bearer [REDACTED]")
        .replaceAll(
            "(?i)(password|token|secret|authorization)([\\s\"']*[:=][\\s\"']*)[^\\s,;}\"']+",
            "$1$2[REDACTED]")
        .replaceAll("jdbc:[^\\s\"']+", "[JDBC_REDACTED]")
        .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL_REDACTED]");
  }
}
