package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Delivery guards are deterministic; no test labels a generated causal explanation correct. */
class BoundedCompletionTest {
 record Sample(DirectWorkflowTest.Harness h,EvidenceDelivery.Frame frame) implements AutoCloseable {public void close(){h.close();}}
 Sample sample()throws Exception{
  var fixture=new CrossSourceReplayTest().fixture();var req=JSON.treeToValue(fixture.path("request"),Request.class);
  var h=new DirectWorkflowTest().new Harness("NO_DATA",req);h.evidence.clear();
  for(int i=0;i<2;i++)h.evidence.add(JSON.treeToValue(fixture.path("evidence").get(i),Evidence.class));
  h.evidence.add(new Evidence("Emetric","metrics","PARTIAL",req.start().toString(),req.end().toString(),Map.of(),fixture.path("metricPages").get(0)));
  var frame=WorkingContext.prepare(h.workflow.contextData(h.claim,Map.of()),Map.of());return new Sample(h,frame);
 }
 JsonNode emptyReport(){return tree(Map.of("modelPlan",Map.of("hypotheses",List.of()),"facts",List.of()));}
 String ref(Sample s){return s.frame.visible().stream().filter(id->s.frame.facts().get(id).path("causeEligible").asBoolean()).findFirst().orElseThrow();}
 JsonNode report(String id){return tree(Map.of("modelPlan",Map.of("hypotheses",List.of(Map.of("premiseFactIds",List.of(id)))),"facts",List.of()));}
 Map<String,Object> assess(Sample s,Set<String> delivered,JsonNode report,boolean forced){return CompletionCoverage.assess(s.h.evidence,s.frame,delivered,report,forced);}
 @Test void suppliedRequiredObservationsCanFinishWithUnscannedPagesExplicitlyRetained()throws Exception{try(var s=sample()){
  var a=assess(s,s.frame.registered(),report(ref(s)),false);
  assertThat(a.get("complete")).isEqualTo(true);assertThat(a.get("exhaustiveCoverage")).isEqualTo(false);
  assertThat(tree(a.get("pendingSourceEvidenceIds"))).isNotEmpty();assertThat(a.get("causalSupport")).isEqualTo("PENDING_SEPARATE_REVIEW");
 }}
 @Test void unreadOtherObservationsRemainCountedWithoutBlockingAnOtherwiseCompleteBundle()throws Exception{try(var s=sample()){
  var delivered=new HashSet<>(s.frame.registered());
  var notRequired=s.frame.facts().values().stream().filter(f->f.path("pointer").asText().startsWith("/data/10/")).map(f->f.path("factId").asText()).toList();assertThat(notRequired).isNotEmpty();delivered.removeAll(notRequired);
  var a=assess(s,delivered,report(ref(s)),false);assertThat(a.get("complete")).isEqualTo(true);
  assertThat(((Number)a.get("unreadRegisteredFacts")).longValue()).isEqualTo(notRequired.size());
 }}
 @Test void baselineSourceMustActuallyReachInputNotJustBeRegistered()throws Exception{try(var s=sample()){
  var delivered=new HashSet<>(s.frame.registered());s.frame.facts().values().stream().filter(f->f.path("evidenceId").asText().equals("Emetric")).forEach(f->delivered.remove(f.path("factId").asText()));
  var a=assess(s,delivered,emptyReport(),false);assertThat(a.get("complete")).isEqualTo(false);assertThat(tree(a.get("missingSourceObservations"))).contains(tree("metrics"));
 }}
 @Test void selectingOneScalarCannotHideAnUnreadFieldOfItsObservation()throws Exception{try(var s=sample()){
  String id=ref(s),group=CompletionCoverage.observationKey(s.frame.facts().get(id));var delivered=new HashSet<>(s.frame.registered());
  String omitted=s.frame.facts().values().stream().filter(f->CompletionCoverage.observationKey(f).equals(group)&&!f.path("factId").asText().equals(id)).findFirst().orElseThrow().path("factId").asText();delivered.remove(omitted);
  var a=assess(s,delivered,report(id),false);assertThat(a.get("complete")).isEqualTo(false);assertThat(tree(a.get("incompleteCitedObservations"))).contains(tree(group));
 }}
 @Test void unknownAndNotCurrentlySuppliedReferencesCannotComplete()throws Exception{try(var s=sample()){
  String hidden=s.frame.registered().stream().filter(id->!s.frame.visible().contains(id)).findFirst().orElseThrow();
  for(String id:List.of(hidden,"F000000000000000000000000")){
   var a=assess(s,s.frame.registered(),report(id),false);assertThat(a.get("complete")).isEqualTo(false);assertThat(tree(a.get("missingRequiredFactIds"))).contains(tree(id));
  }
 }}
 @Test void acquisitionFailureIsStillPartialEvenWithSomeUsableData()throws Exception{try(var s=sample()){
  var e=s.h.evidence.getLast();s.h.evidence.add(new Evidence("Efailed","metrics","UNAVAILABLE",e.start(),e.end(),Map.of(),tree(Map.of())));
  var a=assess(s,s.frame.registered(),emptyReport(),false);assertThat(a.get("complete")).isEqualTo(false);assertThat(tree(a.get("failedSources"))).contains(tree("Efailed"));
 }}
 @Test void sourceGapAndProjectionLossAreNotOrdinaryContinuation()throws Exception{
  for(boolean projection:List.of(false,true))try(var s=sample()){
   var e=s.h.evidence.getLast();var data=(com.fasterxml.jackson.databind.node.ObjectNode)e.data().deepCopy();
   if(projection){var rows=data.withArray("data");while(rows.size()<=Reports.MAX_ARRAY_ITEMS)rows.add(rows.get(0).deepCopy());}else data.put("sourceGap","ROTATED_ARCHIVE");
   s.h.evidence.set(s.h.evidence.size()-1,new Evidence(e.id(),e.source(),e.status(),e.start(),e.end(),e.locator(),data));
   var a=assess(s,s.frame.registered(),emptyReport(),false);assertThat(a.get("complete")).isEqualTo(false);
   assertThat(tree(a.get("blockingReasons"))).contains(tree(projection?"PROJECTION_INCOMPLETE":"SOURCE_COVERAGE_FAILURE"));
  }
 }
 @Test void budgetForcedFinishIsPartialDespiteValidReport()throws Exception{try(var h=new DirectWorkflowTest().new Harness("NO_DATA")){
  h.max=1;h.answers(new DirectWorkflowTest().empty);h.workflow.run(h.claim);
  assertThat(h.status.get()).isEqualTo("PARTIAL");assertThat(tree(h.report.get()).at("/completionCoverage/blockingReasons")).contains(tree("BUDGET_FORCED_REPORT"));assertThat(h.calls.get()).isEqualTo(1);
 }}
 @Test void emptySourcesMayCompleteWithoutDeclaringHealthyOrRootCause()throws Exception{try(var h=new DirectWorkflowTest().new Harness("NO_DATA")){
  h.answers(new DirectWorkflowTest().empty);h.workflow.run(h.claim);assertThat(h.status.get()).isEqualTo("COMPLETED");
  assertThat(tree(h.report.get()).path("candidates")).isEmpty();assertThat(tree(h.report.get()).path("summary").asText()).contains("不能确定根因");
 }}
 @Test void ambiguousAvailableWithoutObservationsIsNotSuccessfulEmpty()throws Exception{try(var h=new DirectWorkflowTest().new Harness("NO_DATA")){
  var e=h.evidence.get(1);h.evidence.set(1,new Evidence(e.id(),e.source(),"AVAILABLE",e.start(),e.end(),e.locator(),e.data()));
  h.answers(new DirectWorkflowTest().empty);h.workflow.run(h.claim);assertThat(h.status.get()).isEqualTo("PARTIAL");
  assertThat(tree(h.report.get()).at("/completionCoverage/missingSourceObservations")).contains(tree("metrics"));
 }}
 @Test void programFallbackDoesNotPublishFactsAbsentFromTheCurrentInput()throws Exception{try(var s=sample()){
  var compiled=DirectContract.render(new DirectWorkflowTest().empty,s.h.evidence,s.h.claim.request(),s.frame.visible());
  assertThat(compiled.errors()).isEmpty();for(var fact:compiled.report().path("facts"))assertThat(s.frame.visible()).contains(fact.path("factId").asText());
 }}
 @Test void markdownPreservesCoverageAndDeliveryInsteadOfOnlyShowingCompleted()throws Exception{try(var s=sample()){
  var compiled=DirectContract.render(new DirectWorkflowTest().empty,s.h.evidence,s.h.claim.request(),s.frame.visible());
  var report=(com.fasterxml.jackson.databind.node.ObjectNode)compiled.report();var a=assess(s,s.frame.registered(),report,false);CompletionCoverage.disclose(report,a);
  String md=ReportExport.markdown(Map.of("id","offline","status","COMPLETED","report_json",report,"evidence",s.h.evidence));
  assertThat(md).contains("BOUNDED_NOT_EXHAUSTIVE","未完成所有查询分页","PENDING_SEPARATE_REVIEW");
 }}
}
