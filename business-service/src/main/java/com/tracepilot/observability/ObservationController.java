package com.tracepilot.observability;

import static com.tracepilot.api.ApiSupport.require;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Read-only, fixed sources only. Never accepts a filename, SQL, URL, or control label. */
@RestController
@RequestMapping("/ops")
public class ObservationController {
  private final MetricSampler sampler;
  private final Path dir;
  private final ObjectMapper json;

  public ObservationController(
      MetricSampler sampler, ObjectMapper json, @Value("${app.data-dir}") String dir) {
    this.sampler = sampler;
    this.json = json;
    this.dir = Path.of(dir).resolve("observability");
  }

  @jakarta.annotation.PostConstruct
  void eventCoverage() throws Exception {
    Path events=dir.resolve("events");Files.createDirectories(events);
    if(!Files.exists(events.resolve("coverage-start.txt")))Files.writeString(events.resolve("coverage-start.txt"),Instant.now().toString(),StandardOpenOption.CREATE_NEW);
  }

  @GetMapping("/snapshot")
  public Object latest() {
    return sampler.latest();
  }

  @GetMapping({"/logs", "/samples"})
  public Object query(
      jakarta.servlet.http.HttpServletRequest request,
      @RequestParam Instant start,
      @RequestParam Instant end,
      @RequestParam(defaultValue = "0") String cursor,
      @RequestParam(defaultValue = "200") int limit,
      @RequestParam(required = false) String traceId,
      @RequestParam(required = false) String level,
      @RequestParam(defaultValue = "all") String channel)
      throws Exception {
    require(
        !start.isAfter(end)
            && Duration.between(start, end).toSeconds() <= 3600
            && ArchivePage.validCursor(cursor)
            && limit >= 1
            && limit <= 200,
        400,
        "INVALID_WINDOW_OR_PAGE");
    require(traceId == null || traceId.matches("[0-9a-f]{32}"), 400, "INVALID_TRACE_ID");
    require(level == null || Set.of("INFO", "WARN", "ERROR").contains(level), 400, "INVALID_LEVEL");
    boolean logs = request.getRequestURI().endsWith("/logs");
    require(Set.of("all","events").contains(channel)&& (logs||channel.equals("all")),400,"INVALID_CHANNEL");
    try {
      var result=ArchivePage.read(channel.equals("events")?dir.resolve("events"):dir, logs, start, end, cursor, limit, traceId, level, json);
      result.put("channel",channel);
      if(channel.equals("events")){
        Instant coverageStart=Instant.parse(Files.readString(dir.resolve("events/coverage-start.txt")));
        result.put("sourceCoverageStart",coverageStart.toString());
        result.put("sourceScope","Event lifecycle and caller-scoped notification queries only; general HTTP logs require channel=all");
        if(start.isBefore(coverageStart)){result.put("status","PARTIAL");result.put("sourceGap","EVENT_CHANNEL_NOT_COLLECTED_BEFORE_SOURCE_START");}
      }
      return result;
    } catch (IllegalArgumentException invalid) {
      require(false, 400, "STALE_OR_INVALID_CURSOR_RESTART_AT_0");
      return Map.of();
    }
  }
}
