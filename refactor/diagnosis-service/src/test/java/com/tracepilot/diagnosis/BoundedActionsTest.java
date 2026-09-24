package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class BoundedActionsTest {
 @Test void combinedPagesCannotBypassExistingEvidenceLimit(){
  var facts=new ArrayList<Object>();var evidence=new ArrayList<Object>();var views=new ArrayList<Object>();
  for(int i=0;i<4;i++){facts.add(Map.of("factId","F"+i,"evidenceId","E"+i,"pointer","/data/0/message","value","x".repeat(19000)));evidence.add(Map.of("id","E"+i,"source","logs","status","AVAILABLE"));views.add(Map.of("evidenceId","E"+i,"page",0));}
  var source=Map.<String,Object>of("causeFactRegistry",facts,"evidence",evidence);
  assertThatThrownBy(()->WorkingContext.prepare(source,Map.of("evidenceViews",views))).isInstanceOf(RequestBoundary.LocalFailure.class).hasMessage("CONTEXT_SECTION_LIMIT");
 }
 @Test void readyCheckpointResumesOnlyRemainingRead()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(report())){
   var g=e.gateway("named-tools-v2");var actions=List.of(Map.of("type","readEvidence","evidenceId","Eprevious","page",0),Map.of("type","readEvidence","evidenceId","Emetrics","page",0));
   var pending=Map.of("status","READY","nextIndex",1,"actions",actions,"callIds",List.of("a","b"),"views",List.of(Map.of("evidenceId","Eprevious","page",0)));
   var c=new Claim(h.claim.id(),h.claim.lease(),h.claim.request(),Map.of("workflowContract",DirectContract.VERSION,"modelConfiguration",g.configuration(),"pendingBatch",pending),h.claim.deadline());
   when(h.store.active(c)).thenReturn(true);when(h.store.reserve(c,"TOOL")).thenReturn(true);when(h.store.reserve(c,"MODEL")).thenReturn(true);
   var w=new DirectWorkflow(h.store,h.tools,g);try{w.run(c);}finally{w.close();}
   verify(h.store,times(1)).reserve(c,"TOOL");verifyNoInteractions(h.tools);assertThat(e.wires).hasSize(1);
   var input=parse(e.wires.getFirst().at("/messages/0/content/0/text").asText());assertThat(input.at("/evidenceDelivery/currentPages")).hasSize(2);verify(h.store).finish(eq(c),eq("COMPLETED"),anyMap());
  }
 }
 @Test void realPagesPreserveBothFactSetsAndFinalWireMetadata()throws Exception{
  var sample=JSON.readTree(getClass().getResourceAsStream("/bounded-actions-v9-evidence.json"));
  var req=JSON.treeToValue(sample.path("request"),Request.class);
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req)){
   h.evidence.clear();for(var row:sample.path("evidence"))h.evidence.add(JSON.treeToValue(row,Evidence.class));
   var source=h.workflow.contextData(h.claim,Map.of());var base=WorkingContext.prepare(source,Map.of());
   var selected=h.evidence.stream().filter(x->x.source().equals("logs")||x.source().equals("metrics")).limit(2).map(x->Map.of("evidenceId",x.id(),"page",0)).toList();
   var combined=WorkingContext.prepare(source,Map.of("evidenceViews",selected));
   for(var view:selected)assertThat(combined.visible()).containsAll(base.pages().get(view.get("evidenceId")).getFirst().facts());
   assertThat(combined.visible()).isNotEmpty();assertThat(combined.text().length()).isLessThanOrEqualTo(100000);
  }
  var wire=encode(Map.of("tool_choice",Map.of("type","auto","disable_parallel_tool_use",true),"tools",List.of(Map.of("name","a")),"stream",false,"messages",List.of("PRIVATE_BODY"))).getBytes(StandardCharsets.UTF_8);
  var metadata=tree(RequestBoundary.protocolMetadata(wire));assertThat(metadata.path("toolChoice").path("disable_parallel_tool_use").asBoolean()).isTrue();assertThat(encode(metadata)).doesNotContain("PRIVATE_BODY");
 }
 @Test void repeatedQueriesInBatchReuseStoredResult()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(List.of(queries().getFirst(),call("b","query_logs",Map.of("args",Map.of()))),report())){
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   verify(h.tools,times(1)).query(any(),any(),any());assertThat(h.calls.get()).isEqualTo(2);assertThat(h.steps).contains("QUERY:REUSED");assertThat(h.status.get()).isEqualTo("COMPLETED");
  }
 }
 @Test void identicalBatchReadsCloseCollectionWithoutExhaustingBudget()throws Exception{
  var reads=List.of(call("a","read_evidence_page",Map.of("evidenceId","Eprevious","page",0)),call("b","read_evidence_page",Map.of("evidenceId","Emetrics","page",0)));
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(reads,reads,report())){
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.calls.get()).isEqualTo(3);assertThat(h.toolCalls.get()).isEqualTo(4);assertThat(h.status.get()).isEqualTo("COMPLETED");
   assertThat(h.steps).contains("COLLECTION:CLOSED_NO_PROGRESS");
   var finalWire=e.wires.getLast();assertThat(finalWire.path("tools")).hasSize(1);assertThat(finalWire.at("/tools/0/name").asText()).isEqualTo("submit_report");
   var input=parse(finalWire.at("/messages/0/content/0/text").asText());assertThat(input.at("/checkpoint/collectionClosure").asText()).isEqualTo("REPEATED_STORED_PAGES_NO_NEW_EVIDENCE");
   assertThat(tree(h.report.get()).at("/completionCoverage/blockingReasons")).isEmpty();
  }
 }
 @Test void consecutiveEquivalentQueryBatchesConcludeWithOneStoredBody()throws Exception{
  var normalized=List.of(call("c","query_metrics",Map.of("args",Map.of("limit",200,"cursor","0"))),call("d","query_logs",Map.of("args",Map.of("channel","all","limit",200,"cursor","0"))));
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(queries(),normalized,normalized,report())){
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.calls.get()).isEqualTo(4);assertThat(h.toolCalls.get()).isEqualTo(2);assertThat(h.status.get()).isEqualTo("COMPLETED");
   var last=e.wires.getLast();assertThat(last.path("tools")).hasSize(1);
   assertThat(parse(last.at("/messages/0/content/0/text").asText()).at("/checkpoint/collectionClosure").asText()).isEqualTo("REPEATED_QUERY_SELECTION_NO_NEW_EVIDENCE");
   verify(h.tools,times(2)).query(any(),any(),any());
   for(var wire:e.wires)assertThat(encode(wire).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
  }
 }
 @Test void changedFilterOrCursorIsNotNoProgress()throws Exception{
  for(var args:List.of(Map.<String,Object>of("level","ERROR"),Map.<String,Object>of("cursor","v1.100.200."+"a".repeat(24)+"."+"b".repeat(24)))){
   var different=List.of(call("c","query_logs",Map.of("args",args)),queries().get(1));
   try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(queries(),different,report())){
    var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
    assertThat(h.status.get()).isEqualTo("COMPLETED");assertThat(h.steps).doesNotContain("COLLECTION:CLOSED_NO_PROGRESS");
    assertThat(e.wires.getLast().path("tools").size()).isGreaterThan(1);assertThat(h.toolCalls.get()).isEqualTo(3);
   }
  }
 }
 @Test void actualV14CachedSelectionsStopWithWindowEvidenceIntact()throws Exception{
  var sample=JSON.readTree(getClass().getResourceAsStream("/cached-query-v17.json"));var req=JSON.treeToValue(sample.path("request"),Request.class);
  var first=new ArrayList<Map<String,Object>>();var second=new ArrayList<Map<String,Object>>();sample.path("calls").get(0).forEach(c->first.add(map(encode(c))));sample.path("calls").get(1).forEach(c->second.add(map(encode(c))));
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req);var e=new Endpoint(first,second,report())){
   h.evidence.clear();for(var row:sample.path("evidence"))h.evidence.add(JSON.treeToValue(row,Evidence.class));
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.calls.get()).isEqualTo(3);assertThat(h.toolCalls.get()).isZero();verifyNoInteractions(h.tools);
   assertThat(h.steps).contains("COLLECTION:CLOSED_NO_PROGRESS");assertThat(e.wires.getLast().path("tools")).hasSize(1);
   var input=parse(e.wires.getLast().at("/messages/0/content/0/text").asText());
   assertThat(ContextPacking.expand(input,"causeFactRegistry").stream().map(Domain::tree).anyMatch(f->f.path("pointer").asText().endsWith("/pool/pending")&&f.path("value").asInt()>0)).isTrue();
   for(var wire:e.wires)assertThat(encode(wire).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/cached-query-v17.json"),encode(Map.of("calls",h.calls.get(),"tools",h.toolCalls.get(),"contextUtf16",e.wires.stream().map(x->x.at("/messages/0/content/0/text").asText().length()).toList(),"status",h.status.get(),"meaning","REAL_EVIDENCE_AND_QUERY_ACTIONS_SCRIPTED_REPORT_NOT_MODEL_PASS")));
  }
 }
 @Test void realV12RepeatedReadReplayKeepsSelectedAndWindowEvidenceForReport()throws Exception{
  var sample=JSON.readTree(getClass().getResourceAsStream("/no-progress-v13/actual.json"));var req=JSON.treeToValue(sample.path("request"),Request.class);
  var reads=new ArrayList<Map<String,Object>>();for(var c:sample.path("repeatedCalls"))reads.add(map(encode(c)));
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req);var e=new Endpoint(reads,reads,report())){
   h.evidence.clear();for(var x:sample.path("evidence"))h.evidence.add(JSON.treeToValue(x,Evidence.class));
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.calls.get()).isEqualTo(3);assertThat(h.status.get()).isEqualTo("COMPLETED");
   var input=parse(e.wires.getLast().at("/messages/0/content/0/text").asText());assertThat(input.at("/evidenceDelivery/mode").asText()).isEqualTo("REPORT_SELECTED_WITH_WINDOW_SAMPLE");
   assertThat(ContextPacking.expand(input,"causeFactRegistry").stream().map(Domain::tree).anyMatch(f->f.path("pointer").asText().endsWith("/pool/pending")&&f.path("value").asInt()>0)).isTrue();
   for(var wire:e.wires)assertThat(encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/no-progress-v13.json"),encode(Map.of("calls",h.calls.get(),"tools",h.toolCalls.get(),"contextUtf16",e.wires.stream().map(x->x.at("/messages/0/content/0/text").asText().length()).toList(),"status",h.status.get(),"meaning","REAL_EVIDENCE_SCRIPTED_ACTION_REPLAY_NOT_MODEL_CAUSAL_PASS")));
  }
 }
 Map<String,Object> call(String id,String name,Map<String,Object> args){return Map.of("type","tool_use","id",id,"name",name,"input",args);}
 List<Map<String,Object>> queries(){return List.of(call("a","query_logs",Map.of("args",Map.of())),call("b","query_metrics",Map.of("args",Map.of())));}
 List<Map<String,Object>> report(){return List.of(call("r","submit_report",Map.of("hypotheses",List.of(),"checks",List.of("COLLECT_LOGS"))));}
 class Endpoint implements AutoCloseable{
  HttpServer server;List<JsonNode> wires=new ArrayList<>();List<List<Map<String,Object>>> replies;
  @SafeVarargs Endpoint(List<Map<String,Object>>... values)throws Exception{replies=List.of(values);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/v1/messages",e->{wires.add(JSON.readTree(e.getRequestBody()));int i=wires.size()-1;if(i>=replies.size()){e.sendResponseHeaders(500,-1);e.close();return;}
    var b=encode(Map.of("id","fixture","type","message","role","assistant","model",ModelCaptureTest.MODEL,"content",replies.get(i),"stop_reason","tool_use","usage",Map.of("input_tokens",1,"output_tokens",1))).getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();}
  SpringAiGateway gateway(String mode){return new SpringAiGateway("LOCAL_ONLY","http://127.0.0.1:"+server.getAddress().getPort(),ModelCaptureTest.MODEL,ModelCapture.disabled(),mode);}
  public void close(){server.stop(0);}
 }
 @Test void actualMultiCallResponsesAreDecodedOnlyUnderNewVersion()throws Exception{
  var sample=JSON.readTree(getClass().getResourceAsStream("/bounded-actions-v9-real.json"));
  for(int i:List.of(0,2)){
   List<Map<String,Object>> calls=new ArrayList<>();for(var c:sample.path("rounds").get(i).path("calls"))calls.add(call(c.path("id").asText(),c.path("name").asText(),map(encode(c.path("input")))));
   try(var e=new Endpoint(calls,calls)){
    var old=e.gateway("named-tools-v1").callAction(false,true,"{}","old",1,x->{});assertThat(old.action().path("type").asText()).isEqualTo("INVALID_NATIVE_ACTION");
    var fresh=e.gateway("named-tools-v2").callAction(false,true,"{}","new",1,x->{});assertThat(fresh.action().path("type").asText()).isEqualTo("boundedBatch");assertThat(fresh.action().path("actions")).hasSize(2);
   }
  }
 }
 @Test void invalidMemberMixedReportDuplicatesAndOverflowRejectWholeResponse()throws Exception{
  var bad=new ArrayList<List<Map<String,Object>>>();
  bad.add(List.of(queries().getFirst(),call("b","query_logs",Map.of("args",Map.of("limit",999)))));
  bad.add(List.of(queries().getFirst(),report().getFirst()));bad.add(List.of(queries().getFirst(),queries().getFirst()));
  bad.add(java.util.stream.IntStream.range(0,5).mapToObj(i->call("id"+i,"query_logs",Map.of("args",Map.of()))).toList());
  for(var calls:bad)try(var e=new Endpoint(calls)){
   var r=e.gateway("named-tools-v2").callAction(false,true,"{}","x",1,x->{});assertThat(r.action().path("type").asText()).isEqualTo("INVALID_NATIVE_ACTION");assertThat(r.action().path("errors")).isNotEmpty();
  }
 }
 @Test void serialQueriesExecuteOnceAndOneModelRoundFundsTwoTools()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(queries(),report())){
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.status.get()).isEqualTo("COMPLETED");assertThat(h.calls.get()).isEqualTo(2);assertThat(h.toolCalls.get()).isEqualTo(2);
   var order=inOrder(h.tools);order.verify(h.tools).query(any(),argThat(q->q.tool().equals("logs")),any());order.verify(h.tools).query(any(),argThat(q->q.tool().equals("metrics")),any());
   assertThat(h.steps).contains("BATCH:ACCEPTED","BATCH:COMPLETED");
  }
 }
 @Test void allReferencesValidatedBeforeFirstReadOrQuery()throws Exception{
  var calls=List.of(queries().getFirst(),call("b","read_evidence_page",Map.of("evidenceId","OTHER_TASK","page",0)));
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(calls,calls)){
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   verifyNoInteractions(h.tools);assertThat(h.toolCalls.get()).isZero();assertThat(h.status.get()).isEqualTo("PARTIAL");assertThat(encode(e.wires.get(1))).contains("EVIDENCE_NOT_IN_TASK");
  }
 }
 @Test void cancellationDuringFirstQueryDoesNotExecuteSecondOrCallModel()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(queries())){
   when(h.tools.query(any(),any(),any())).thenAnswer(i->{h.active.set(false);return result("NO_DATA",Map.of("data",List.of()),Map.of());});
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   verify(h.tools,times(1)).query(any(),any(),any());assertThat(e.wires).hasSize(1);assertThat(h.status.get()).isEqualTo("PARTIAL");
  }
 }
 @Test void exhaustedToolBudgetStopsRemainingCalls()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(queries())){
   doAnswer(i->h.toolCalls.incrementAndGet()<=1).when(h.store).reserve(h.claim,"TOOL");
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   verify(h.tools,times(1)).query(any(),any(),any());assertThat(e.wires).hasSize(1);assertThat(h.status.get()).isEqualTo("PARTIAL");assertThat(encode(h.report.get())).contains("BATCH_TOOL_BUDGET_EXHAUSTED");
  }
 }
 @Test void persistentSourceFailureKeepsEvidenceAndSkipsRemainingCalls()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(queries())){
   when(h.tools.query(any(),any(),any())).thenReturn(result("UNAVAILABLE",Map.of(),Map.of()));
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   verify(h.tools,times(2)).query(any(),argThat(q->q.tool().equals("logs")),any());assertThat(e.wires).hasSize(1);assertThat(h.status.get()).isEqualTo("PARTIAL");assertThat(encode(h.report.get())).contains("BATCH_SOURCE_FAILED");
  }
 }
 @Test void bothSelectedPagesReachNextModelInputWithoutLastPageOverwrite()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(List.of(call("a","read_evidence_page",Map.of("evidenceId","Eprevious","page",0)),call("b","read_evidence_page",Map.of("evidenceId","Emetrics","page",0))),report())){
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   var input=parse(e.wires.get(1).at("/messages/0/content/0/text").asText());assertThat(input.at("/evidenceDelivery/currentPages")).hasSize(2);assertThat(input.at("/evidenceDelivery/mode").asText()).isEqualTo("MULTI_PAGE_BOUNDED");assertThat(h.status.get()).isEqualTo("COMPLETED");
  }
 }
 @Test void inFlightCheckpointNeverBlindlyRepeatsUnconfirmedRead()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(report())){
   var g=e.gateway("named-tools-v2");h.calls.set(1);var c=new Claim(h.claim.id(),h.claim.lease(),h.claim.request(),Map.of("workflowContract",DirectContract.VERSION,"modelConfiguration",g.configuration(),"pendingBatch",Map.of("status","IN_FLIGHT")),h.claim.deadline());
   when(h.store.active(c)).thenReturn(true);var w=new DirectWorkflow(h.store,h.tools,g);try{w.run(c);}finally{w.close();}
   assertThat(e.wires).isEmpty();verifyNoInteractions(h.tools);verify(h.store).finish(eq(c),eq("PARTIAL"),argThat(r->encode(r).contains("BATCH_PREVIOUS_CALL_OUTCOME_UNCONFIRMED")));
  }
 }
}

