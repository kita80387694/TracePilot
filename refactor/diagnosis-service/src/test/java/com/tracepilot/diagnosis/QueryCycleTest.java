package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueryCycleTest {
 @Test void realAlternatingCachedSelectionsCloseBeforeBudgetAndDeliverRequestedPlan() throws Exception {
  var f=JSON.readTree(getClass().getResourceAsStream("/query-cycle-v21.json"));var req=JSON.treeToValue(f.path("request"),Request.class);var all=new ArrayList<Evidence>();for(var e:f.path("evidence"))all.add(JSON.treeToValue(e,Evidence.class));
  var helper=new BoundedActionsTest();var calls=new ArrayList<List<Map<String,Object>>>();for(var round:f.path("calls")){var batch=new ArrayList<Map<String,Object>>();for(var call:round)batch.add(map(encode(call)));calls.add(batch);}
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req);var endpoint=helper.new Endpoint(calls.get(0),calls.get(1),calls.get(2),helper.report())){
   h.evidence.clear();all.stream().filter(e->Set.of("overview","logs","metrics").contains(e.source())&&!e.status().equals("NO_DATA")).forEach(h.evidence::add);
   when(h.tools.query(any(),any(),any())).thenAnswer(invocation->{Query q=invocation.getArgument(1);var e=all.stream().filter(x->x.source().equals(q.tool())).findFirst().orElseThrow();return result(e.status(),map(encode(e.data())),e.locator());});
   var w=new DirectWorkflow(h.store,h.tools,endpoint.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.calls.get()).isEqualTo(4);assertThat(h.status.get()).isEqualTo("COMPLETED");assertThat(h.toolCalls.get()).isLessThanOrEqualTo(4);
   var input=parse(endpoint.wires.getLast().at("/messages/0/content/0/text").asText());assertThat(input.at("/checkpoint/collectionClosure").asText()).isEqualTo("REPEATED_QUERY_SELECTION_NO_NEW_EVIDENCE");
   assertThat(ContextPacking.expand(input,"backgroundFactRegistry").stream().map(Domain::tree)).anyMatch(x->x.path("source").asText().equals("sqlPlan"));
   for(var wire:endpoint.wires)assertThat(encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/query-cycle-v21.json"),encode(Map.of("modelCalls",h.calls.get(),"toolCalls",h.toolCalls.get(),"contextsUtf16",endpoint.wires.stream().map(x->x.at("/messages/0/content/0/text").asText().length()).toList(),"meaning","SCRIPTED_ACTION_REAL_EVIDENCE_REPLAY_NOT_MODEL_PASS")));
  }
 }
 @Test void nonRowBackgroundResultsAreIncludedWithoutBecomingIncidentEvidence() throws Exception {
  var f=JSON.readTree(getClass().getResourceAsStream("/query-cycle-v21.json"));var req=JSON.treeToValue(f.path("request"),Request.class);
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req)){
   h.evidence.clear();for(var e:f.path("evidence"))h.evidence.add(JSON.treeToValue(e,Evidence.class));
   var frame=WorkingContext.prepare(h.workflow.contextData(h.claim,Map.of()),Map.of("windowSample",true));var input=parse(frame.text());
   var plans=ContextPacking.expand(input,"backgroundFactRegistry").stream().map(Domain::tree).filter(x->x.path("source").asText().equals("sqlPlan")).toList();
   assertThat(plans).isNotEmpty().allSatisfy(x->assertThat(x.path("causeEligible").asBoolean()).isFalse());
   assertThat(ContextPacking.expand(input,"causeFactRegistry").stream().map(Domain::tree)).noneMatch(x->x.path("source").asText().equals("sqlPlan"));
  }
 }
}
