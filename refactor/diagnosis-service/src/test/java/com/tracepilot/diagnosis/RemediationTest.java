package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RemediationTest {
  @Test void unavailableSourcesCannotBecomeFactsOrDefiniteCauses() {
    var e=new Evidence("e1","logs","UNAVAILABLE","a","b",Map.of(),tree(Map.of("httpStatus",503)));
    assertThat(Reports.factCatalog(e)).isEmpty();
    var report=tree(Map.of("summary","窗口日志不可用，无法确定原因", "facts",List.of(),
        "candidates",List.of(),"conflicts",List.of(),"gaps",List.of("日志HTTP503；恢复日志查询后重试"),"actions",List.of()));
    assertThat(Reports.validate(report,List.of(e))).isEmpty();
    assertThat(Reports.hasWindowData(List.of(e))).isFalse(); // Workflow must publish PARTIAL.
    ((com.fasterxml.jackson.databind.node.ArrayNode)report.path("candidates")).add(tree(Map.of("cause","unsupported",
        "confidence","SUPPORTED","inference",true,"evidenceIds",List.of("e1"))));
    assertThat(Reports.validate(report,List.of(e))).contains("MISSING_WINDOW_EVIDENCE");
  }
  @Test void wrongPhaseAndMalformedJsonHaveSafeSpecificErrors() {
    assertThat(Workflow.actionError("INVESTIGATE",tree(Map.of("type","report","report",Map.of()))))
        .isEqualTo("EXPECTED_ONE_TOOL_OR_READY");
    assertThat(Workflow.actionError("REPORT",tree(Map.of("type","INVALID_JSON"))))
        .isEqualTo("INVALID_JSON");
    assertThat(Workflow.actionError("INVESTIGATE",tree(Map.of("type","tool","tool","logs","args",Map.of())))).isNull();
  }
  @Test void opaqueCursorDoesNotOpenNewToolPermissions() {
    ReadTools.validate(new Query("logs",Map.of("cursor","v1.123.456."+"a".repeat(24)+"."+"b".repeat(24))));
    assertThatThrownBy(()->ReadTools.validate(new Query("logs",Map.of("cursor","../../evaluation/private"))))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(()->ReadTools.validate(new Query("drills",Map.of())))
        .isInstanceOf(SecurityException.class);
  }
}
