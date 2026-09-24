package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** Archived observations plus scripted actions. No provider, no causal-quality score. */
class CrossSourceReplayTest {
 JsonNode fixture()throws Exception{return JSON.readTree(getClass().getResourceAsStream("/cross-source-v5/actual.json"));}
 JsonNode tool(String source,Map<String,String> args){return tree(Map.of("type","tool","tool",source,"args",args));}
 @Test void sharedDefinitionsRoundTripLosslesslyAndMeasureSameObservationPayload()throws Exception{
  try(var h=new BoundedEvidenceDeliveryTest().harness()){
   var source=h.workflow.contextData(h.claim,new LinkedHashMap<>());var compact=new LinkedHashMap<>(source);ContextPacking.pack(compact);
   var old=new LinkedHashMap<>(source);var contexts=new LinkedHashMap<String,Object>();var keys=new HashMap<String,String>();
   var oldShared=new HashSet<>(ContextPacking.SHARED);oldShared.removeAll(Set.of("measurementMeaning","kind","unit","semanticsVersion"));
   for(String name:List.of("causeFactRegistry","backgroundFactRegistry")){
    var rows=new ArrayList<Object>();for(var original:tree(source.get(name))){var row=new LinkedHashMap<>(map(encode(original)));row.remove("display");var shared=new TreeMap<String,Object>();for(String k:oldShared)if(row.containsKey(k))shared.put(k,row.remove(k));String id=keys.computeIfAbsent(encode(shared),k->"C"+(keys.size()+1));contexts.putIfAbsent(id,shared);row.put("contextRef",id);rows.add(row);}old.put(name,rows);
    var restored=ContextPacking.expand(tree(compact),name);var originals=tree(source.get(name));assertThat(restored).hasSize(originals.size());
    for(int n=0;n<restored.size();n++){var expected=originals.get(n).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)expected).remove("display");assertThat(tree(restored.get(n))).isEqualTo(expected);}
   }
   old.put("factContexts",contexts);var before=new LinkedHashMap<String,Object>();var after=new LinkedHashMap<String,Object>();
   for(String k:List.of("causeFactRegistry","backgroundFactRegistry","factContexts")){before.put(k,old.get(k));after.put(k,compact.get(k));}
   int oldSize=encode(before).length(),newSize=encode(after).length();assertThat(newSize).isLessThan(oldSize);
   Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay/context-packing-v5.json"),encode(Map.of("sameObservationPayload",true,"unit","UTF16_CODE_UNITS_NOT_TOKENS","oldV1FactPayload",oldSize,"newV2FactPayload",newSize,"savedCharacters",oldSize-newSize,"roundTrip","ALL_FIELDS_EXCEPT_SERVER_DISPLAY_IDENTICAL","actualToolRows",18)));
  }
 }
 @Test void initialCollectionIsCrossSourceSinglePageResumableAndBudgeted()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA")){
   h.evidence.removeIf(e->!e.source().equals("overview"));var selected=new ArrayList<Query>();
   when(h.tools.query(any(),any(),any())).thenAnswer(i->{Query q=i.getArgument(1);selected.add(q);return result("PARTIAL",Map.of("data",List.of(),"hasMore",true,"nextCursor","next"),Map.of());});
   h.workflow.initialSources(h.claim);h.workflow.initialSources(h.claim);
   assertThat(selected.stream().map(Query::tool)).containsExactly("metrics","logs","logs");
   assertThat(selected.get(0).args().get("limit")).isEqualTo("200");assertThat(selected.get(1).args().get("limit")).isEqualTo("200");
   assertThat(selected.get(1).args()).doesNotContainKey("level");assertThat(selected.get(2).args()).containsEntry("level","ERROR");assertThat(h.toolCalls.get()).isEqualTo(3);verify(h.model,never()).call(anyString(),anyString(),anyString(),anyInt(),any());
  }
 }
 @Test void fourthSourceSelectionRemainsLegalAndReuseDoesNotBlockAnotherSource()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA")){
   h.answers(tool("logs",Map.of()),tool("logs",Map.of()),tool("logs",Map.of("level","ERROR")),tool("metrics",Map.of()),new DirectWorkflowTest().empty);
   h.workflow.run(h.claim);assertThat(h.inputs).hasSize(5);assertThat(h.status.get()).isEqualTo("COMPLETED");
   assertThat(h.steps).contains("QUERY:REUSED").doesNotContain("ACTION:REJECTED");assertThat(h.toolCalls.get()).isEqualTo(3);
  }
 }
 @Test void initialSourceFailureCannotInventDataAndRetriesShareBudget()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA")){
   h.evidence.removeIf(e->e.source().equals("metrics"));
   when(h.tools.query(any(),any(),any())).thenReturn(result("UNAVAILABLE",Map.of(),Map.of()));
   h.answers(new DirectWorkflowTest().empty);h.workflow.run(h.claim);
   assertThat(h.toolCalls.get()).isEqualTo(2);assertThat(h.status.get()).isEqualTo("PARTIAL");
   assertThat(tree(h.report.get()).at("/completionCoverage/failedSources")).hasSize(2);
   assertThat(tree(h.report.get()).path("candidates")).isEmpty();
  }
 }
 @Test void initialCollectionStopsBeforeModelWhenBudgetOrCancellationBlocksIt()throws Exception{
  for(boolean cancelled:List.of(false,true))try(var h=new DirectWorkflowTest().new Harness("NO_DATA")){
   h.evidence.removeIf(e->!e.source().equals("overview"));h.active.set(!cancelled);
   when(h.store.reserve(h.claim,"TOOL")).thenReturn(false);
   h.workflow.run(h.claim);assertThat(h.status.get()).isEqualTo("PARTIAL");assertThat(h.calls.get()).isZero();
   verify(h.model,never()).call(anyString(),anyString(),anyString(),anyInt(),any());verifyNoInteractions(h.tools);
  }
 }
 @Test void pageIndexIsOnlyNavigationAndOrdersInstantsNotFormattedStrings(){
  var facts=Map.of("F1",tree(Map.of("pointer","/data/0/pool/pending","eventTime","2026-09-21T00:00:00Z")),"F2",tree(Map.of("pointer","/data/1/pool/pending","eventTime","2026-09-21T00:00:00.100Z")));
  var index=EvidenceDelivery.pageIndex(0,new EvidenceDelivery.Page(List.of("F1","F2"),List.of()),facts);
  assertThat(index.get("firstObservedTime")).isEqualTo("2026-09-21T00:00:00Z");assertThat(index.get("lastObservedTime")).isEqualTo("2026-09-21T00:00:00.100Z");
  assertThat(index.get("fieldPaths")).isEqualTo(List.of("/data/*/pool/pending"));assertThat(index.get("indexOnlyNotCitable")).isEqualTo(true);assertThat(index).doesNotContainKey("value");
 }
 @Test void realCrossSourceObservationsReachInputWithExplicitBoundedCompletion()throws Exception{
  var data=fixture();var req=JSON.treeToValue(data.path("request"),Request.class);
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req)){
   h.evidence.clear();h.evidence.add(JSON.treeToValue(data.path("evidence").get(0),Evidence.class));
   var queries=new ArrayList<Query>();var rounds=new ArrayList<Object>();var retained=new LinkedHashSet<String>();
   when(h.tools.query(any(),any(),any())).thenAnswer(i->{Query q=i.getArgument(1);queries.add(q);
    JsonNode body=q.tool().equals("metrics")?data.path("metricPages").get((int)queries.stream().filter(x->x.tool().equals("metrics")).count()-1):data.path("evidence").get(1).path("data");
    return new Result(body.path("status").asText("PARTIAL"),body,Map.of("fixtureCoverage",q.tool().equals("logs")?"ARCHIVED_ERROR_FILTER_SUBSET_NOT_UNFILTERED_HISTORY":"SIX_POST_TASK_PAGES_STILL_HAS_MORE"));
   });
   when(h.model.call(anyString(),anyString(),anyString(),anyInt(),any())).thenAnswer(i->{
    String input=i.getArgument(1);h.inputs.add(input);int n=h.inputs.size();var parsed=parse(input);
    assertThat(input.length()).isLessThanOrEqualTo(RequestBoundary.MAX_CONTEXT_UTF16);
    for(var value:ContextPacking.expand(parsed,"causeFactRegistry")){var f=tree(value);if(f.path("pointer").asText().endsWith("/message")&&retained.isEmpty())retained.add(f.path("factId").asText());}
    JsonNode action;
    if(n<=5){action=tree(Map.of("type","tool","tool","metrics","args",Map.of("cursor",data.path("metricPages").get(n-1).path("nextCursor").asText()),"retainFactIds",retained));}
    else if(n==6){var latest=h.evidence.getLast();var frame=WorkingContext.prepare(h.workflow.contextData(h.claim,new LinkedHashMap<>()),new LinkedHashMap<>());action=tree(Map.of("type","readEvidence","evidenceId",latest.id(),"page",frame.pages().get(latest.id()).size()-1,"retainFactIds",retained));}
    else {assertThat(n).isEqualTo(7);action=new DirectWorkflowTest().empty;}
    rounds.add(Map.of("ordinal",n,"contextUtf16",input.length(),"actualInput",parsed,"scriptedAction",action));return new ModelReply(action,"OFFLINE_SCRIPT_NOT_MODEL_VALIDATION",null,null);
   });
   h.workflow.run(h.claim);
   assertThat(h.inputs).hasSize(7);assertThat(h.toolCalls.get()).isEqualTo(9);assertThat(h.status.get()).isEqualTo("COMPLETED");
   assertThat(tree(h.report.get()).at("/completionCoverage/coverage").asText()).isEqualTo("BOUNDED_NOT_EXHAUSTIVE");
   assertThat(tree(h.report.get()).path("candidates")).isEmpty(); // Not a cause-identification pass.
   assertThat(tree(h.report.get()).at("/completionCoverage/causalSupport").asText()).isEqualTo("PENDING_SEPARATE_REVIEW");assertThat(h.steps).doesNotContain("ACTION:REJECTED");
   var finalFacts=ContextPacking.expand(parse(h.inputs.getLast()),"causeFactRegistry").stream().map(Domain::tree).toList();
   assertThat(finalFacts.stream().anyMatch(f->f.path("pointer").asText().endsWith("/pool/pending")&&f.path("value").asInt()>0)).isTrue();
   assertThat(finalFacts.stream().anyMatch(f->f.path("pointer").asText().endsWith("/message"))).isTrue();
   assertThat(tree(h.report.get()).at("/completionCoverage/pendingSourceEvidenceIds")).isNotEmpty();
   Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay/bounded-completion-v6.json"),encode(Map.of("kind","ARCHIVED_DATA_SCRIPTED_SELECTION_NOT_CAUSAL_OR_MODEL_PASS","originalMetricsNotSentToOriginalModel",true,"savedLogSubsetOnly",true,"rounds",rounds,"queries",queries,"modelReservations",h.calls.get(),"toolReservations",h.toolCalls.get(),"status",h.status.get(),"report",h.report.get())));
  }
 }
}
