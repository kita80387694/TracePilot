package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportMetadataTest {
 @Test void actualV35FinalReportReplaysWithoutChangingSemanticFailure()throws Exception{
  var input=JSON.readTree(getClass().getResourceAsStream("/report-metadata-v35.json"));
  var request=JSON.treeToValue(input.path("request"),Request.class);var es=new ArrayList<Evidence>();
  for(var e:input.path("evidence"))es.add(JSON.treeToValue(e,Evidence.class));
  Map<String,Object> report=JSON.convertValue(input.path("report"),Map.class);
  String before=encode(report.get("modelPlan")),evidence=encode(es);
  ReportMetadata.attach(report,es,request);
  assertThat(tree(report.get("factRegistry"))).hasSize(16);
  assertThat(tree(report.get("metricFacts"))).isEmpty();
  assertThat(encode(report.get("modelPlan"))).isEqualTo(before);assertThat(encode(es)).isEqualTo(evidence);
  int bytes=encode(report).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  assertThat(bytes).isLessThan(150000);
  java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));
  java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/report-metadata-v37.json"),encode(Map.of(
    "beforeCompactUtf8Bytes",input.path("beforeCompactUtf8Bytes"),"afterCompactUtf8Bytes",bytes,
    "facts",16,"meaning","Offline assembly replay; modelPlan unchanged; original semantic failure remains")));
 }
 @Test void finalAttachmentDoesNotReinflateActualRenderedReport()throws Exception{
  var f=new SelectedReportFactsTest().fixture();var rendered=DirectContract.render(f.action(),f.evidence(),f.request(),f.visible());
  assertThat(rendered.errors()).isEmpty();
  Map<String,Object> report=JSON.convertValue(rendered.report(),Map.class);
  String facts=encode(report.get("facts")),candidates=encode(report.get("candidates")),evidence=encode(f.evidence());
  ReportMetadata.attach(report,f.evidence(),f.request());
  assertThat(tree(report.get("factRegistry"))).hasSize(14);
  assertThat(tree(report).at("/metadataScope/registeredFacts").asInt()).isEqualTo(5060);
  assertThat(encode(report.get("facts"))).isEqualTo(facts);assertThat(encode(report.get("candidates"))).isEqualTo(candidates);
  assertThat(encode(f.evidence())).isEqualTo(evidence);
  var selected=new HashSet<String>();tree(report.get("facts")).forEach(x->selected.add(x.path("factId").asText()));
  for(var metric:tree(report.get("metricFacts")))assertThat(selected).contains(FactReferences.id(metric.path("evidenceId").asText(),metric.path("pointer").asText()));
  int bytes=encode(report).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  assertThat(bytes).isLessThan(100000);
 }
 @Test void legacyAndPartialMetadataRemainAvailable()throws Exception{
  var f=new SelectedReportFactsTest().fixture();Map<String,Object> report=new LinkedHashMap<>();
  ReportMetadata.attach(report,f.evidence(),f.request());
  assertThat(tree(report.get("factRegistry"))).hasSize(5060);assertThat(report).doesNotContainKey("metadataScope");
 }
 @Test void emptyDisplayDoesNotInventOrDeleteAuditEvidence()throws Exception{
  var f=new SelectedReportFactsTest().fixture();Map<String,Object> report=new LinkedHashMap<>();
  report.put("facts",List.of());report.put("reportFactScope",Map.of());
  ReportMetadata.attach(report,f.evidence(),f.request());
  assertThat(tree(report.get("factRegistry"))).isEmpty();assertThat(tree(report.get("metricFacts"))).isEmpty();
  assertThat(tree(report).at("/metadataScope/registeredFacts").asInt()).isEqualTo(5060);
 }
}
