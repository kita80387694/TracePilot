package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
class EvidenceRegistryTest {
 final Instant end=Instant.now().minusSeconds(2),start=end.minusSeconds(60);
 final Request r=new Request("tracepilot-business","demo",start,end,"fixture");
 Evidence e(String id,String source,String status,Instant s,Instant t){return new Evidence(id,source,status,s.toString(),t.toString(),Map.of("service",r.service(),"environment",r.environment()),tree(Map.of("data",List.of(Map.of("time",s.toString(),"pending",5)))));}
 com.fasterxml.jackson.databind.node.ObjectNode report(){return (com.fasterxml.jackson.databind.node.ObjectNode)parse("{\"summary\":\"fixture\",\"facts\":[],\"candidates\":[{\"cause\":\"candidate\",\"inference\":true,\"confidence\":\"LIKELY\",\"evidenceIds\":[\"e\"]}],\"conflicts\":[],\"gaps\":[],\"actions\":[]}");}
 @Test void gapsRetainsSourceCapabilitiesAndCounterScope(){
  assertThat(Workflow.prompt("GAPS")).contains("fact IDs", "not full tracing", "server renders", "real traceId");
  assertThat(Workflow.prompt("GAPS")).endsWith("Current phase:GAPS");
 }
 @Test void realFaultLegacyReferencesAreNotProofOfCandidateMechanism() throws Exception {
  var f=JSON.readTree(getClass().getResourceAsStream("/model/fault-real-v82.json"));var es=new ArrayList<Evidence>();
  for(var n:f.path("evidence"))es.add(JSON.treeToValue(n,Evidence.class));var req=JSON.treeToValue(f.path("input"),Request.class);
  assertThat(EvidenceRegistry.hasEvent(es,req)).isTrue();
  for(var draft:f.path("drafts")){
   assertThat(CandidateContract.issues(draft,es,req)).isNotEmpty();
   // Diagnostic-only counterfactual: the original persisted draft is never edited or published.
   var isolated=(com.fasterxml.jackson.databind.node.ObjectNode)draft.deepCopy();isolated.putArray("candidates").add(draft.at("/candidates/0"));
   assertThat(EvidenceRegistry.validate(isolated,es,req)).isEmpty();
   assertThat(draft.path("candidates")).hasSize(2);
  }
 }
 @Test void actualD3DraftsRejectedWithoutDeletingCandidates() throws Exception {
  var fixture=JSON.readTree(getClass().getResourceAsStream("/model/d3-real-v7.json"));
  var req=JSON.treeToValue(fixture.get("input"),Request.class);var es=new ArrayList<Evidence>();
  for(var node:fixture.path("evidence"))es.add(JSON.treeToValue(node,Evidence.class));
  assertThat(EvidenceRegistry.hasEvent(es,req)).isFalse();
  for(var draft:fixture.path("drafts")){
   assertThat(EvidenceRegistry.validate(draft,es,req)).contains("MISSING_WINDOW_EVIDENCE","NO_EVENT_ANCHOR");
   assertThat(draft.path("candidates")).hasSize(2);
  }
  assertThat(EvidenceRegistry.validate(fixture.at("/drafts/1"),es,req)).contains("INVALID_FACT_SOURCE");
 }
 @Test void unavailableAndRunbookCannotAnchorWhileValidStoredDataSurvivesLaterOutage(){
  Evidence good=e("e","metrics","STALE",start,end),bad=e("bad","metrics","UNAVAILABLE",start,end);
  assertThat(EvidenceRegistry.validate(report(),List.of(good,bad),r)).isEmpty();
  assertThat(EvidenceRegistry.validate(report(),List.of(e("e","runbook","AVAILABLE",start,end)),r)).contains("NO_EVENT_ANCHOR");
  assertThat(EvidenceRegistry.validate(report(),List.of(e("e","metrics","UNAVAILABLE",start,end)),r)).contains("NO_EVENT_ANCHOR","INVALID_CAUSE_SOURCE");
 }
 @Test void outsideScopeAndForeignServiceCannotSupportCause(){
  assertThat(EvidenceRegistry.validate(report(),List.of(e("e","logs","AVAILABLE",start.minusSeconds(100),start.minusSeconds(90))),r)).contains("NO_EVENT_ANCHOR","INVALID_CAUSE_SOURCE");
  var foreign=new Evidence("e","logs","AVAILABLE",start.toString(),end.toString(),Map.of("service","foreign"),tree(Map.of("data",List.of(Map.of("n",1)))));
  assertThat(EvidenceRegistry.validate(report(),List.of(foreign),r)).contains("NO_EVENT_ANCHOR");
 }
 @Test void partialEvidenceAndLegalRootRemainAcceptedButFakeIdAndLocationRejected(){
  var es=List.of(e("e","metrics","PARTIAL",start,end));assertThat(EvidenceRegistry.validate(report(),es,r)).isEmpty();
  var x=report();((com.fasterxml.jackson.databind.node.ObjectNode)x.at("/candidates/0")).putArray("evidenceIds").add("fake");
  assertThat(EvidenceRegistry.validate(x,es,r)).contains("UNKNOWN_REFERENCE","NO_EVENT_ANCHOR");
  x=report();x.putArray("facts").add(tree(Map.of("text","x","evidenceId","e","pointer","/fake","value",5)));
  assertThat(EvidenceRegistry.validate(x,es,r)).contains("FACT_VALUE_MISMATCH");
  x.put("filePath","/invented");assertThat(Reports.unexpectedReportFields(x)).contains("UNEXPECTED_REPORT_FIELD:filePath");
 }
 @Test void gapContractCannotSmuggleCandidatesOrFacts(){
  var a=(com.fasterxml.jackson.databind.node.ObjectNode)parse("{\"type\":\"insufficient\",\"summary\":\"missing\",\"gaps\":[\"logs unavailable\"],\"actions\":[\"collect logs\"]}");
  var es=List.of(e("e","logs","UNAVAILABLE",start,end));var out=tree(Reports.insufficient(a,es,r));
  assertThat(out.path("candidates")).isEmpty();assertThat(out.path("resultType").asText()).isEqualTo("INSUFFICIENT_EVIDENCE_SOURCE_FAILURE");
  a.putArray("candidates").add("fake");assertThat(Reports.validateGapAction(a)).contains("UNEXPECTED_GAP_FIELD:candidates");
  assertThatThrownBy(()->Reports.insufficient(a,es,r)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void normalizedFingerprintReusesDefaultButAllowsSubstantiveChanges(){
  assertThat(EvidenceRegistry.fingerprint(r,new Query("logs",Map.of()))).isEqualTo(EvidenceRegistry.fingerprint(r,new Query("logs",Map.of("cursor","0","limit","200"))));
  assertThat(EvidenceRegistry.fingerprint(r,new Query("logs",Map.of("level","WARN")))).isNotEqualTo(EvidenceRegistry.fingerprint(r,new Query("logs",Map.of("level","INFO"))));
  assertThat(EvidenceRegistry.fingerprint(r,new Query("logs",Map.of()))).isNotEqualTo(EvidenceRegistry.fingerprint(new Request(r.service(),r.environment(),start.minusSeconds(1),end,"fixture"),new Query("logs",Map.of())));
 }
}
