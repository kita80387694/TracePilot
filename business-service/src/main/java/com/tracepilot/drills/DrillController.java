package com.tracepilot.drills;

import com.tracepilot.identity.SecurityConfig.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("demo")
@RequestMapping("/api/drills")
public class DrillController {
  private final DrillManager drills;
  private final DrillDataset dataset;

  public DrillController(DrillManager drills, DrillDataset dataset) {
    this.drills = drills;
    this.dataset = dataset;
  }

  public record Start(
      @NotNull String scenario,
      @Min(1) @Max(300) int durationSeconds,
      @Min(1) @Max(100) int concurrency,
      @Min(1) @Max(10000) int requestsPerPhase,
      @AssertTrue boolean acknowledged) {}

  @GetMapping
  public Object scenarios() {
    return Map.of(
        "maxDurationSeconds",
        300,
        "scenarios",
        List.of(
            Map.of(
                "id",
                "F01",
                "impact",
                "Occupies all business DB connections; requests may time out; manual stop uses a"
                    + " separate pool"),
            Map.of(
                "id",
                "F02",
                "impact",
                "Runs a real full scan over a one-million-row report dataset; query timeout is"
                    + " bounded"),
            Map.of(
                "id",
                "F03",
                "impact",
                "Pauses new notification deliveries while reservations continue")));
  }

  @PostMapping
  public Object start(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Start input) {
    return drills.start(
        input.scenario(),
        input.durationSeconds(),
        actor.id(),
        input.concurrency(),
        input.requestsPerPhase(),
        input.scenario().equals("F02") ? dataset.size() : 0);
  }

  @GetMapping("/{id}")
  public Object state(@PathVariable String id) {
    return drills.get(id);
  }

  @GetMapping("/current")
  public Object current() {
    return drills.current();
  }

  @PostMapping("/{id}/stop")
  public Object stop(@PathVariable String id, @AuthenticationPrincipal Actor actor) {
    return drills.stop(id, "MANUAL", actor.id());
  }

  @PostMapping("/dataset")
  public Object prepare() {
    return dataset.prepare();
  }

  @GetMapping("/dataset/plans")
  public Object plans() {
    return Map.of(
        "indexed",
        dataset.explain(false),
        "unindexed",
        dataset.explain(true),
        "rows",
        dataset.size());
  }
}
