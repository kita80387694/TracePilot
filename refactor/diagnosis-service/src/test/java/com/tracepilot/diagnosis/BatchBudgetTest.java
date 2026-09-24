package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BatchBudgetTest {
 @Test void actualTwoReadsWithOneRemainingAreRejectedBeforeEitherReadAndOneCorrectionCanConclude() throws Exception {
  var f=JSON.readTree(getClass().getResourceAsStream("/batch-budget-v22.json"));var request=JSON.treeToValue(f.path("request"),Request.class);
  var helper=new BoundedActionsTest();var calls=new ArrayList<Map<String,Object>>();
  for(var c:f.path("calls"))calls.add(helper.call(c.path("id").asText(),c.path("name").asText(),map(encode(c.path("input")))));
  for(boolean failAgain:List.of(false,true))try(var h=new DirectWorkflowTest().new Harness("NO_DATA",request);var endpoint=helper.new Endpoint(calls,failAgain?calls:helper.report())){
   h.evidence.clear();for(var e:f.path("evidence"))h.evidence.add(JSON.treeToValue(e,Evidence.class));h.toolCalls.set(11);
   var w=new DirectWorkflow(h.store,h.tools,endpoint.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.calls.get()).isEqualTo(2);assertThat(h.toolCalls.get()).isEqualTo(11);verifyNoInteractions(h.tools);
   assertThat(h.steps).doesNotContain("BATCH:ACCEPTED","EVIDENCE_READ:SELECTED");
   assertThat(encode(endpoint.wires.get(1))).contains("BATCH_REMAINING_TOOL_BUDGET","minimumToolCalls","remainingToolCalls");
   assertThat(h.status.get()).isEqualTo(failAgain?"PARTIAL":"COMPLETED");
   for(var wire:endpoint.wires)assertThat(encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
  }
 }
 @Test void exactReadAllowanceAndReusableQueriesAreNotRejected() throws Exception {
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA")){
   var read=Map.of("type","readEvidence","evidenceId","Eprevious","page",0);var batch=tree(Map.of("actions",List.of(read,read)));
   assertThat(BoundedActions.budgetErrors(batch,2,h.claim.request(),h.evidence)).isEmpty();
   assertThat(BoundedActions.budgetErrors(batch,1,h.claim.request(),h.evidence)).hasSize(1);
   var q=new Query("logs",Map.of());var fp=EvidenceRegistry.fingerprint(h.claim.request(),q);
   h.evidence.add(new Evidence("cached","logs","NO_DATA",h.claim.request().start().toString(),h.claim.request().end().toString(),Map.of("queryFingerprint",fp),tree(Map.of("data",List.of()))));
   var query=Map.of("type","tool","tool","logs","args",Map.of());
   assertThat(BoundedActions.budgetErrors(tree(Map.of("actions",List.of(query,query))),0,h.claim.request(),h.evidence)).isEmpty();
  }
 }
}
