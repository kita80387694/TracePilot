package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportRetentionTest {
 @Test void realSingleReadOfAlreadyDeliveredMultiPageViewClosesWithoutBudgetExhaustion() throws Exception {
  var f=JSON.readTree(getClass().getResourceAsStream("/single-multi-read-v27.json"));
  var req=JSON.treeToValue(f.path("request"),Request.class);var all=new ArrayList<Evidence>();
  for(var row:f.path("evidence"))all.add(JSON.treeToValue(row,Evidence.class));
  var helper=new BoundedActionsTest();var rounds=new ArrayList<List<Map<String,Object>>>();
  for(var round:f.path("calls")){var batch=new ArrayList<Map<String,Object>>();round.forEach(x->batch.add(map(encode(x))));rounds.add(batch);}
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req);
      var endpoint=helper.new Endpoint(rounds.get(0),rounds.get(1),rounds.get(2),rounds.get(3),rounds.get(4),helper.report())){
   h.evidence.clear();all.forEach(h.evidence::add);
   var workflow=new DirectWorkflow(h.store,h.tools,endpoint.gateway("named-tools-v2"));try{workflow.run(h.claim);}finally{workflow.close();}
   assertThat(h.calls.get()).isEqualTo(6);assertThat(h.toolCalls.get()).isLessThan(12);
   assertThat(h.steps).contains("EVIDENCE_READ:REUSED");
   var input=parse(endpoint.wires.getLast().at("/messages/0/content/0/text").asText());
   assertThat(endpoint.wires.getLast().path("tools")).hasSize(1);
   assertThat(input.at("/checkpoint/collectionClosure").asText()).isEqualTo("REPEATED_STORED_PAGES_NO_NEW_EVIDENCE");
   assertThat(input.at("/evidenceDelivery/currentPages")).hasSize(2);
   assertThat(tree(h.report.get()).at("/completionCoverage/blockingReasons").toString()).doesNotContain("BUDGET_FORCED_REPORT");
   for(var wire:endpoint.wires)assertThat(encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
  }
 }
 @Test void reportCannotBypassSelectedPageLimitOrHideUnavailableScalar(){
  var evidence=new ArrayList<Object>();var facts=new ArrayList<Object>();var views=new ArrayList<Object>();
  for(int i=0;i<4;i++){evidence.add(Map.of("id","E"+i,"source","logs","status","AVAILABLE"));facts.add(Map.of("factId","F"+i,"evidenceId","E"+i,"pointer","/data/0/message","value","x".repeat(19000)));views.add(Map.of("evidenceId","E"+i,"page",0));}
  var source=Map.<String,Object>of("evidence",evidence,"causeFactRegistry",facts);
  assertThatThrownBy(()->WorkingContext.prepare(source,Map.of("reportOnly",true,"windowSample",true,"evidenceViews",views))).isInstanceOf(RequestBoundary.LocalFailure.class);
  var huge=Map.<String,Object>of("evidence",List.of(evidence.getFirst()),"causeFactRegistry",List.of(Map.of("factId","Fhuge","evidenceId","E0","pointer","/data/0/message","value","x".repeat(30000))));
  var frame=WorkingContext.prepare(huge,Map.of("reportOnly",true,"windowSample",true,"evidenceView",Map.of("evidenceId","E0","page",0)));
  assertThat(frame.delivery().get("unavailableFactIds")).isEqualTo(List.of("Fhuge"));
  assertThat(frame.visible()).doesNotContain("Fhuge");
 }
 @Test void registeredParallelConsumerVersionKeepsOperationSemanticsAndRejectsUnknownVersion() throws Exception {
  var f=JSON.readTree(getClass().getResourceAsStream("/report-retention-v27.json"));
  var req=JSON.treeToValue(f.path("request"),Request.class);var es=new ArrayList<Evidence>();
  for(var row:f.path("evidence"))es.add(JSON.treeToValue(row,Evidence.class));
  var timeline=EventTimeline.build(es,req);
  assertThat(timeline.nodes()).anyMatch(n->n.path("action").asText().equals("event_operation_failed")&&n.at("/result/operationPhase").asText().equals("NOTIFICATION_TRANSACTION"));
  assertThat(timeline.nodes()).anyMatch(n->n.path("action").asText().equals("notification_write_committed")&&n.path("timeSemantics").asText().equals("POST_COMMIT_UPPER_BOUND"));
  assertThat(EventTimeline.supported("sha256-unknown","event_operation_failed")).isFalse();
 }
 @Test void actualEventReadThenSubsetRepeatKeepsFailureAndRecoveryInFinalWire() throws Exception {
  var f=JSON.readTree(getClass().getResourceAsStream("/report-retention-v27.json"));
  var req=JSON.treeToValue(f.path("request"),Request.class);var all=new ArrayList<Evidence>();
  for(var row:f.path("evidence"))all.add(JSON.treeToValue(row,Evidence.class));
  var helper=new BoundedActionsTest();var rounds=new ArrayList<List<Map<String,Object>>>();
  for(var round:f.path("calls")){var calls=new ArrayList<Map<String,Object>>();round.forEach(x->calls.add(map(encode(x))));rounds.add(calls);}
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req);
      var endpoint=helper.new Endpoint(rounds.get(0),rounds.get(1),rounds.get(2),rounds.get(3),rounds.get(4),helper.report())){
   h.evidence.clear();all.stream().filter(e->Set.of("overview","logs","metrics").contains(e.source())).forEach(h.evidence::add);
   when(h.tools.query(any(),any(),any())).thenAnswer(i->{Query q=i.getArgument(1);var e=all.stream().filter(x->x.source().equals(q.tool())).findFirst().orElseThrow();return result(e.status(),map(encode(e.data())),e.locator());});
   var workflow=new DirectWorkflow(h.store,h.tools,endpoint.gateway("named-tools-v2"));try{workflow.run(h.claim);}finally{workflow.close();}
   assertThat(endpoint.wires).hasSize(6);assertThat(h.calls.get()).isEqualTo(6);
   var input=parse(endpoint.wires.getLast().at("/messages/0/content/0/text").asText());
   assertThat(input.at("/evidenceDelivery/mode").asText()).isEqualTo("REPORT_SELECTED_WITH_WINDOW_SAMPLE");
   assertThat(input.at("/evidenceDelivery/currentPages")).hasSize(2);
   var facts=ContextPacking.expand(input,"causeFactRegistry").stream().map(Domain::tree).toList();
   assertThat(facts).anyMatch(x->x.path("value").asText().equals("NOTIFICATION_TRANSACTION"));
   assertThat(facts).anyMatch(x->x.path("value").asText().equals("notification_write_committed"));
   assertThat(input.at("/evidenceDelivery/complete").asBoolean()).isFalse();
   for(var wire:endpoint.wires)assertThat(encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));
   java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/report-retention-v27.json"),encode(Map.of("modelCalls",h.calls.get(),"toolCalls",h.toolCalls.get(),"contextsUtf16",endpoint.wires.stream().map(x->x.at("/messages/0/content/0/text").asText().length()).toList(),"meaning","REAL_ACTIONS_AND_EVIDENCE_SCRIPTED_REPORT_NOT_MODEL_PASS")));
  }
 }
}


