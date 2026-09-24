package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;import java.time.*;import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Scripted responses verify control flow, not model quality. No supplier connection. */
class DirectWorkflowTest {
 final JsonNode empty=tree(Map.of("type","report","hypotheses",List.of(),"checks",List.of("COLLECT_LOGS")));
 class Harness implements AutoCloseable {
  TaskStore store=mock(TaskStore.class);ReadTools tools=mock(ReadTools.class);ModelGateway model=mock(ModelGateway.class);
  List<Evidence> evidence=new ArrayList<>();List<String> inputs=new ArrayList<>(),steps=new ArrayList<>();AtomicInteger calls=new AtomicInteger(),toolCalls=new AtomicInteger();AtomicBoolean active=new AtomicBoolean(true);
  AtomicReference<String> status=new AtomicReference<>();AtomicReference<Map<String,Object>> report=new AtomicReference<>();Claim claim;DirectWorkflow workflow;int max=8;
  Harness(String sourceStatus)throws Exception{this(sourceStatus,new ContractBoundaryTest().request());}
  Harness(String sourceStatus,Request req)throws Exception{
   claim=new Claim("direct-replay","lease",req,Map.of(),Instant.now().plusSeconds(180));
   evidence.add(new Evidence("Eoverview","overview",sourceStatus,req.start().toString(),req.end().toString(),Map.of(),tree(Map.of())));
   // This harness starts after initial collection. CrossSourceReplayTest exercises initial collection separately.
   evidence.add(new Evidence("Emetrics","metrics","NO_DATA",req.start().toString(),req.end().toString(),Map.of(),tree(Map.of("data",List.of(),"hasMore",false))));
   evidence.add(new Evidence("Eprevious","logs",sourceStatus,req.start().toString(),req.end().toString(),Map.of(),tree(Map.of("data",List.of()))));
   when(store.active(claim)).thenAnswer(i->active.get());when(store.evidence(claim.id())).thenReturn(evidence);
   when(store.reserve(claim,"MODEL")).thenAnswer(i->calls.incrementAndGet()<=max);when(store.reserve(claim,"TOOL")).thenAnswer(i->{toolCalls.incrementAndGet();return true;});
   when(store.usage(claim.id())).thenAnswer(i->Map.of("model_calls",calls.get(),"tool_calls",toolCalls.get(),"max_models",max,"max_tools",12,"timeout_seconds",180));
   when(store.modelUsage(claim.id())).thenReturn(List.of());when(store.transportAccounting(claim.id())).thenReturn(Map.of());
   when(model.configured()).thenReturn(true);when(model.configuration()).thenReturn(Map.of("adapterVersion","LOCAL_TEST_DOUBLE"));
   doAnswer(i->{steps.add(i.getArgument(1)+":"+i.getArgument(2));return null;}).when(store).step(eq(claim),anyString(),anyString(),anyMap());
   doAnswer(i->{status.set(i.getArgument(1));report.set(i.getArgument(2));return null;}).when(store).finish(eq(claim),anyString(),anyMap());
   when(tools.query(any(),any(),any())).thenReturn(result("NO_DATA",Map.of("data",List.of(),"hasMore",false),Map.of()));
   doAnswer(i->{Result r=i.getArgument(2);evidence.add(new Evidence("E"+evidence.size(),i.getArgument(1),r.status(),req.start().toString(),req.end().toString(),r.locator(),r.data()));return null;}).when(store).evidence(eq(claim),anyString(),any(Result.class));
   workflow=new DirectWorkflow(store,tools,model);
  }
  void answers(JsonNode... actions){when(model.call(anyString(),anyString(),anyString(),anyInt(),any())).thenAnswer(i->{inputs.add(i.getArgument(1));int n=inputs.size()-1;if(n>=actions.length)throw new AssertionError("Unexpected extra model call");return new ModelReply(actions[n],"LOCAL_TEST_DOUBLE",1,1);});}
  public void close(){workflow.close();}
 }
 @Test void noDataCompletesButSourceFailureRemainsPartial()throws Exception{for(String status:List.of("NO_DATA","UNAVAILABLE"))try(var h=new Harness(status)){h.answers(empty);h.workflow.run(h.claim);assertThat(h.status.get()).isEqualTo(status.equals("NO_DATA")?"COMPLETED":"PARTIAL");assertThat(h.inputs).hasSize(1);assertThat(tree(h.report.get()).path("candidates")).isEmpty();}}
 @Test void exactlyOneCorrectionThenStopOrAccept()throws Exception{for(boolean fail:List.of(false,true))try(var h=new Harness("NO_DATA")){var invalid=tree(Map.of("type","report","hypotheses",List.of()));h.answers(invalid,fail?invalid:empty);h.workflow.run(h.claim);assertThat(h.inputs).hasSize(2);assertThat(h.status.get()).isEqualTo(fail?"PARTIAL":"COMPLETED");assertThat(h.inputs.get(1)).contains("MISSING_FIELD","/checks");assertThat(h.steps.stream().filter(s->s.equals("ACTION:REJECTED")).count()).isEqualTo(fail?2:1);}}
 @Test void repeatedQueriesReuseBodiesAndConsumeModelSteps()throws Exception{try(var h=new Harness("NO_DATA")){var q=tree(Map.of("type","tool","tool","logs","args",Map.of()));h.answers(q,q,empty);h.workflow.run(h.claim);assertThat(h.status.get()).isEqualTo("COMPLETED");assertThat(h.calls.get()).isEqualTo(3);assertThat(h.toolCalls.get()).isEqualTo(1);verify(h.tools,times(1)).query(any(),any(),any());assertThat(h.steps).contains("QUERY:REUSED");assertThat(parse(h.inputs.get(2)).at("/checkpoint/lastQuery/status").asText()).isEqualTo("REUSED");assertThat(h.inputs.get(2).length()).isLessThan(h.inputs.get(1).length()+1000);}}
 @Test void cancellationDiscardsLateActionAndMakesNoFollowup()throws Exception{try(var h=new Harness("NO_DATA")){when(h.model.call(anyString(),anyString(),anyString(),anyInt(),any())).thenAnswer(i->{h.active.set(false);return new ModelReply(empty,"LOCAL_TEST_DOUBLE",1,1);});h.workflow.run(h.claim);verify(h.model,times(1)).call(anyString(),anyString(),anyString(),anyInt(),any());assertThat(h.status.get()).isEqualTo("PARTIAL");verifyNoInteractions(h.tools);}}
 @Test void modelBudgetCannotBeResetByCorrection()throws Exception{try(var h=new Harness("NO_DATA")){h.max=1;h.answers(tree(Map.of("type","tool","tool","logs","args",Map.of())));h.workflow.run(h.claim);assertThat(h.inputs).hasSize(1);assertThat(h.status.get()).isEqualTo("PARTIAL");verifyNoInteractions(h.tools);}}
 @Test void legacyInFlightTaskCannotBeReinterpretedUnderNewContract()throws Exception{try(var h=new Harness("NO_DATA")){h.calls.set(1);h.workflow.run(h.claim);assertThat(h.status.get()).isEqualTo("PARTIAL");verify(h.model,never()).call(anyString(),anyString(),anyString(),anyInt(),any());}}
}
