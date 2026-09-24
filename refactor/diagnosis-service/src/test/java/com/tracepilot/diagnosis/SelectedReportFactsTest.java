package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SelectedReportFactsTest {
 record Fixture(Request request,List<Evidence> evidence,JsonNode action,Set<String> visible,JsonNode original){}
 Fixture fixture()throws Exception{
  var input=JSON.readTree(getClass().getResourceAsStream("/offered-scope-v31.json"));var registry=input.at("/registries/P1");
  var es=new ArrayList<Evidence>();for(var e:registry.path("evidence"))es.add(JSON.treeToValue(e,Evidence.class));
  var visible=new HashSet<String>();for(String k:List.of("causeFactRegistry","backgroundFactRegistry"))for(var f:input.at("/samples/0/input").path(k))visible.add(f.path("factId").asText());
  var original=JSON.readTree(getClass().getResourceAsStream("/plan-bound-v32-actual-action.json"));
  return new Fixture(JSON.treeToValue(registry.path("request"),Request.class),es,original.path("action"),visible,original);
 }
 @Test void actualFailedReportKeepsSelectedFactsAndRawEvidenceWithoutCopyingWholeRegistry()throws Exception{
  var f=fixture();String before=encode(f.evidence());var result=DirectContract.render(f.action(),f.evidence(),f.request(),f.visible());assertThat(result.errors()).isEmpty();
  var report=result.report();var selected=new HashSet<String>();report.path("facts").forEach(x->selected.add(x.path("factId").asText()));
  var actual=new HashSet<String>();report.path("factUses").forEach(x->actual.add(x.path("factId").asText()));
  assertThat(selected).hasSize(14).isEqualTo(actual);assertThat(report.path("factUses")).hasSize(14);
  var catalog=new HashMap<String,JsonNode>();EvidenceUse.catalog(f.evidence(),f.request()).forEach(x->{var n=tree(x);catalog.put(n.path("factId").asText(),n);});
  for(var entry:report.path("factUses"))assertThat(entry).isEqualTo(catalog.get(entry.path("factId").asText()));
  assertThat(report.at("/reportFactScope/registeredCount").asInt()).isEqualTo(5060);
  assertThat(report.at("/reportFactScope/notDuplicatedCount").asInt()).isEqualTo(5046);
  assertThat(encode(f.evidence())).isEqualTo(before);
  assertThat(report.at("/candidates/0/mechanism")).isEqualTo(f.action().at("/hypotheses/0/mechanism"));
  assertThat(report.at("/causalAssessment/humanReview").asText()).isEqualTo("PENDING");
  int bytes=encode(report).getBytes(StandardCharsets.UTF_8).length;assertThat(bytes).isLessThan(64000);
  java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));
  java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/selected-report-facts-v33.json"),encode(Map.of("beforeCompactUtf8Bytes",f.original().path("oldCompactReportUtf8Bytes"),"afterCompactUtf8Bytes",bytes,"beforeMetadataRows",5060,"afterMetadataRows",14,"evidenceUnchanged",true,"modelTextUnchanged",true,"semanticStatus","STILL_FAILED_AUXILIARY_REVIEW; NOT_REPAIRED_BY_SIZE_FIX")));
 }
 @Test void unsupportedReferenceStillRejectsInsteadOfBeingSilentlyRemoved()throws Exception{
  var f=fixture();var action=f.action().deepCopy();((ObjectNode)action.at("/hypotheses/0")).set("premiseFactIds",tree(List.of("F"+"0".repeat(24))));
  var result=DirectContract.render(action,f.evidence(),f.request(),f.visible());assertThat(result.errors().toString()).contains("UNKNOWN_OR_UNAUTHORIZED_FACT");assertThat(result.report()).isEmpty();
 }
 @Test void emptyCandidatesOnlyIncludeDisplayedFallbackAndNoDataCanRemainEmpty()throws Exception{
  var f=fixture();var empty=tree(Map.of("type","report","hypotheses",List.of(),"checks",List.of("COLLECT_LOGS")));
  var result=DirectContract.render(empty,f.evidence(),f.request(),f.visible());assertThat(result.errors()).isEmpty();
  assertThat(result.report().path("factUses").size()).isEqualTo(result.report().path("facts").size()).isLessThanOrEqualTo(6);
  assertThat(DirectContract.render(empty,List.of(),f.request(),Set.of()).errors()).contains("MISSING_WINDOW_EVIDENCE");
  var attempted=List.of(new Evidence("Eempty","logs","NO_DATA",f.request().start().toString(),f.request().end().toString(),Map.of(),tree(Map.of("data",List.of()))));
  var noData=DirectContract.render(empty,attempted,f.request(),Set.of());assertThat(noData.errors()).isEmpty();assertThat(noData.report().path("factUses")).isEmpty();
  assertThat(noData.report().path("resultType").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
 }
}
