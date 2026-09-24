package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PartialSummaryTest {
 @Test void queuedCancellationDoesNotClaimEvidenceWasCollected(){
  var report=Reports.partial("CANCELLED_BY_USER",List.of());
  assertThat(report.get("summary")).isEqualTo("诊断已取消；尚未保存查询结果。");
  assertThat(tree(report).path("evidenceIds")).isEmpty();
 }
 @Test void failedAttemptWithoutStoredResultDoesNotClaimNoCallWasMade(){
  var text=Reports.partial("MODEL_CALL_FAILED",List.of()).get("summary").toString();
  assertThat(text).contains("尚未保存查询结果").doesNotContain("未执行","已保存实际查询证据");
 }
 @Test void unavailableSourceRecordIsNotPresentedAsCauseEvidence(){
  var e=new Evidence("source-failed","logs","UNAVAILABLE",Instant.EPOCH.toString(),Instant.EPOCH.plusSeconds(1).toString(),Map.of(),tree(Map.of("error","UNAVAILABLE")));
  var report=Reports.partial("SOURCE_FAILED",List.of(e));
  assertThat(report.get("summary").toString()).contains("数据源状态","不代表已确认故障原因");
  assertThat(tree(report).path("evidenceIds").get(0).asText()).isEqualTo("source-failed");
 }
}
