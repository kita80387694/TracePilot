package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** Real archived tool data, synthetic local actions. No hidden labels or provider calls. */
class BoundedEvidenceDeliveryTest {
 JsonNode fixture()throws Exception{return JSON.readTree(getClass().getResourceAsStream("/model/evidence-delivery-v1.json"));}
 Request request()throws Exception{return JSON.treeToValue(fixture().path("request"),Request.class);}
 List<Evidence> evidence()throws Exception{var es=new ArrayList<Evidence>();for(var e:fixture().path("evidence"))es.add(JSON.treeToValue(e,Evidence.class));return es;}
 DirectWorkflowTest.Harness harness()throws Exception{var h=new DirectWorkflowTest().new Harness("NO_DATA",request());var metrics=h.evidence.get(1);h.evidence.clear();h.evidence.addAll(evidence());h.evidence.add(h.evidence.size()-1,metrics);return h;}
 Map<String,Object> state(){return new LinkedHashMap<>(Map.of("phase","INVESTIGATE"));}
 JsonNode read(String id,int page,List<String> retain){return tree(Map.of("type","readEvidence","evidenceId",id,"page",page,"retainFactIds",retain));}
 JsonNode empty(){return new DirectWorkflowTest().empty;}

 @Test void actualEighteenLogsNowFitAndEveryPagePreservesValuesDefinitionsAndScope()throws Exception{
  try(var h=harness()){
   var state=state();var source=h.workflow.contextData(h.claim,state);var old=new LinkedHashMap<>(source);
   for(String k:List.of("evidenceRegistry","allowedCauseEvidenceIds","workingHypotheses","approvedHypotheses","unresolvedQuestions"))old.remove(k);
   ContextPacking.pack(old);
   RequestBoundary.sections(old); // Shared metric definitions now make this real fixture fit without deleting observations.
   var frame=WorkingContext.prepare(source,state);assertThat(frame.delivery().get("mode")).isEqualTo("ALL");
   assertThat(evidence().getLast().data().path("data")).hasSize(18);
   Set<String> union=new HashSet<>();var sizes=new ArrayList<Object>();String raw=encode(h.evidence);
   for(var entry:frame.pages().entrySet())for(int page=0;page<entry.getValue().size();page++){
    state.put("evidenceView",Map.of("evidenceId",entry.getKey(),"page",page));var next=WorkingContext.prepare(source,state);
    assertThat(next.text().length()).isLessThan(100000);var parsed=parse(next.text());
    assertThat(parsed.at("/contextBudget/actual/evidence").asInt()).isLessThanOrEqualTo(70000);
    for(String name:List.of("causeFactRegistry","backgroundFactRegistry"))for(var value:ContextPacking.expand(parsed,name)){
     var f=tree(value);var original=frame.facts().get(f.path("factId").asText());
     for(String field:List.of("value","pointer","evidenceId","eventTime","queryWindow","timeBasis","causeEligible","kind","unit","measurementMeaning","semanticsVersion"))assertThat(f.path(field)).as(field).isEqualTo(original.path(field));
    }
    union.addAll(next.visible());sizes.add(Map.of("evidenceId",entry.getKey(),"page",page,"facts",next.visible().size(),"contextUtf16",next.text().length(),"sections",parsed.path("contextBudget")));
   }
   assertThat(union).isEqualTo(frame.registered());assertThat(encode(h.evidence)).isEqualTo(raw);
   Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay/evidence-pages.json"),encode(Map.of("origin","ACTUAL_18_LOGS_OFFLINE_REPLAY","registeredFacts",union.size(),"pages",sizes)));
  }
 }
 @Test void pageAndRetentionReferencesCannotEscapeTaskOrGuessUnprovidedFacts()throws Exception{try(var h=harness()){
  var selected=state();selected.put("evidenceView",Map.of("evidenceId",h.evidence.getLast().id(),"page",0));
  var f=WorkingContext.prepare(h.workflow.contextData(h.claim,selected),selected);String id=h.evidence.getLast().id();
  assertThat(EvidenceDelivery.selectionErrors(read("other-task-evidence",0,List.of()),f).toString()).contains("EVIDENCE_NOT_IN_TASK");
  assertThat(EvidenceDelivery.selectionErrors(read(id,f.pages().get(id).size(),List.of()),f).toString()).contains("PAGE_OUT_OF_RANGE");
  String unseen=f.registered().stream().filter(x->!f.visible().contains(x)).findFirst().orElseThrow();
  assertThat(EvidenceDelivery.selectionErrors(read(id,1,List.of(unseen)),f).toString()).contains("FACT_NOT_IN_CURRENT_INPUT");
  var action=tree(Map.of("mechanism","{{fact:"+unseen+"}}"));assertThat(EvidenceDelivery.reportErrors(action,f).toString()).contains("FACT_NOT_IN_CURRENT_INPUT");
  assertThat(DirectContract.errors(read(id,-1,List.of()),false)).isNotEmpty();
  assertThat(DirectContract.errors(read(id,0,List.of()),true)).isNotEmpty();
  assertThat(DirectContract.errors(read(id,0,List.of()),false,false)).isEmpty();
 }}
 @Test void selectedCurrentFactsPersistAcrossPagesAndResumeWithoutDuplicatingBodies()throws Exception{try(var h=harness()){
  var state=state();var source=h.workflow.contextData(h.claim,state);var first=WorkingContext.prepare(source,state);String id=first.pages().keySet().stream().toList().getLast();
  String retained=first.visible().stream().filter(x->!first.pages().get(id).get(1).facts().contains(x)).findFirst().orElseThrow();
  var action=read(id,1,List.of(retained));assertThat(EvidenceDelivery.selectionErrors(action,first)).isEmpty();
  state.put("evidenceView",Map.of("evidenceId",id,"page",1));state.put("retainedFactIds",List.of(retained));
  var second=WorkingContext.prepare(source,state);var resumed=WorkingContext.prepare(source,map(encode(state)));
  assertThat(second.text()).isEqualTo(resumed.text());assertThat(second.visible()).contains(retained);
  assertThat(EvidenceDelivery.reportErrors(tree(Map.of("mechanism","{{fact:"+retained+"}}")),second)).isEmpty();
  long count=0;for(String key:List.of("causeFactRegistry","backgroundFactRegistry"))for(var f:parse(second.text()).path(key))if(f.path("factId").asText().equals(retained))count++;
  assertThat(count).isEqualTo(1);
 }}
 @Test void oversizedScalarIsNeverSilentlyCutOrAllowedAsCitation(){
  String id="F"+"a".repeat(24);var source=new LinkedHashMap<String,Object>();source.put("evidence",List.of(Map.of("id","E","source","logs","status","AVAILABLE")));
  source.put("causeFactRegistry",List.of(Map.of("factId",id,"evidenceId","E","pointer","/data/0/message","value","中".repeat(80000))));
  var f=WorkingContext.prepare(source,state());assertThat(f.visible()).isEmpty();assertThat(f.registered()).contains(id);
  assertThat(f.delivery().get("unavailableFactIds")).isEqualTo(List.of(id));assertThat(f.text()).contains("SCALAR_EXCEEDS_SINGLE_PAGE_BUDGET");
  assertThat(EvidenceDelivery.reportErrors(tree(Map.of("premiseFactIds",List.of(id))),f)).isNotEmpty();
  assertThat(tree(source.get("causeFactRegistry")).get(0).path("value").asText()).hasSize(80000);
 }
 @Test void sourcePagingProjectionAndInputDeliveryAreSeparate()throws Exception{try(var h=harness()){
  var e=h.evidence.getLast();var data=(ObjectNode)e.data().deepCopy();var rows=data.putArray("data");for(int i=0;i<=Reports.MAX_ARRAY_ITEMS;i++)rows.add(e.data().path("data").get(0));data.put("hasMore",true);data.put("nextCursor","opaque-source-cursor");
  h.evidence.set(h.evidence.size()-1,new Evidence(e.id(),e.source(),e.status(),e.start(),e.end(),e.locator(),data));
  var f=WorkingContext.prepare(h.workflow.contextData(h.claim,state()),state());var metadata=parse(f.text()).path("evidence").get(2);
  assertThat(metadata.path("projection").path("projectionComplete").asBoolean()).isFalse();assertThat(metadata.path("scanChainComplete").asBoolean()).isFalse();
  assertThat(metadata.path("nextCursor").asText()).isEqualTo("opaque-source-cursor");assertThat(f.delivery().get("complete")).isEqualTo(false);
 }}
 @Test void repeatedPageReadConsumesToolBudgetAndConcludesWithUnreadDisclosure()throws Exception{try(var h=harness()){
  String id=h.evidence.getLast().id();h.answers(read(id,0,List.of()),read(id,0,List.of()),empty());
  h.workflow.run(h.claim);assertThat(h.inputs).hasSize(3);assertThat(h.toolCalls.get()).isEqualTo(2);assertThat(h.status.get()).isEqualTo("COMPLETED");
  assertThat(h.steps).contains("EVIDENCE_READ:REUSED");assertThat(tree(h.report.get()).at("/evidenceDelivery/unreadRegisteredFactCount").asInt()).isZero();verifyNoInteractions(h.tools);
 }}
 @Test void noToolBudgetCannotStartLocalReadAndDoesNotBecomeCompleted()throws Exception{try(var h=harness()){
  when(h.store.reserve(h.claim,"TOOL")).thenReturn(false);var f=WorkingContext.prepare(h.workflow.contextData(h.claim,state()),state());String id=f.pages().keySet().stream().toList().getLast();h.answers(read(id,1,List.of()));
  h.workflow.run(h.claim);assertThat(h.inputs).hasSize(1);assertThat(h.status.get()).isEqualTo("PARTIAL");assertThat(tree(h.report.get()).path("gaps").toString()).contains("TOOL_BUDGET_OR_DEADLINE");assertThat(tree(h.report.get()).at("/evidenceDelivery/unreadRegisteredFactCount").asInt()).isZero();verifyNoInteractions(h.tools);
 }}
 @Test void retentionBudgetIsMeasuredInUtf16AndNeverSilentlyTrims(){
  var source=new LinkedHashMap<String,Object>();source.put("evidence",List.of(Map.of("id","E","source","logs","status","AVAILABLE")));
  var ids=List.of("F"+"a".repeat(24),"F"+"b".repeat(24));var facts=new ArrayList<Object>();
  for(int i=0;i<2;i++)facts.add(Map.of("factId",ids.get(i),"evidenceId","E","pointer","/data/"+i+"/message","value","中".repeat(9000)));
  source.put("causeFactRegistry",facts);var f=WorkingContext.prepare(source,state());assertThat(f.visible()).containsAll(ids);
  var errors=EvidenceDelivery.selectionErrors(read("E",0,ids),f);assertThat(errors.toString()).contains("RETAIN_BUDGET_EXCEEDED","maxUtf16","16000");
  assertThat(tree(source.get("causeFactRegistry")).get(0).path("value").asText()).hasSize(9000);
 }
 @Test void localSendBoundaryFailureDoesNotClaimDeliveryOrSupplierUsage()throws Exception{try(var h=harness()){
  when(h.model.call(anyString(),anyString(),anyString(),anyInt(),any())).thenThrow(new RequestBoundary.LocalFailure("SERIALIZED_REQUEST_LIMIT",Map.of("unit","UTF8_BYTES","requestSent",false)));
  h.workflow.run(h.claim);assertThat(h.calls.get()).isEqualTo(1);assertThat(h.status.get()).isEqualTo("PARTIAL");
  assertThat(h.steps).contains("MODEL:LOCAL_FAILURE").doesNotContain("MODEL:SUCCEEDED");
  assertThat(tree(h.report.get()).at("/evidenceDelivery/confirmedProvidedFactCount").asInt()).isZero();verifyNoInteractions(h.tools);
 }}
 @Test void cancellationDiscardsLatePageSelectionWithoutLocalRead()throws Exception{try(var h=harness()){
  var f=WorkingContext.prepare(h.workflow.contextData(h.claim,state()),state());String id=f.pages().keySet().stream().toList().getLast();
  when(h.model.call(anyString(),anyString(),anyString(),anyInt(),any())).thenAnswer(i->{h.active.set(false);return new ModelReply(read(id,1,List.of()),"LOCAL_TEST_DOUBLE",1,1);});
  h.workflow.run(h.claim);assertThat(h.calls.get()).isEqualTo(1);assertThat(h.toolCalls.get()).isZero();assertThat(h.status.get()).isEqualTo("PARTIAL");
  assertThat(h.steps).doesNotContain("EVIDENCE_READ:SELECTED");verifyNoInteractions(h.tools);
 }}
}
