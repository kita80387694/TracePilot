package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RealEventContextReplayTest {
 @ParameterizedTest @ValueSource(strings={"new-event","ack-event"})
 void actualHttpEvidenceUsesProductionContextAssemblerWithoutControlOrHiddenInput(String name)throws Exception{
  var fixture=JSON.readTree(getClass().getResourceAsStream("/stable-evidence/"+name+"-http.json"));
  var request=new Request("tracepilot-business","demo",Instant.parse(fixture.path("start").asText()),Instant.parse(fixture.path("end").asText()),"通知延迟，请依据证据检查");
  var evidence=new ArrayList<Evidence>();int index=0;
  for(var page:fixture.path("pages"))evidence.add(new Evidence("event-page-"+index++,"logs",page.path("status").asText(),request.start().toString(),request.end().toString(),Map.of("query",Map.of("tool","logs","args",Map.of("channel","events","limit","200","cursor","0"))),page));
  var timeline=EventTimeline.build(evidence,request);
  assertThat(timeline.nodes()).isNotEmpty();
  assertThat(timeline.gaps().toString()).doesNotContain("未核实的部署版本");
  assertThat(timeline.nodes()).anySatisfy(n->{
   assertThat(n.path("action").asText()).isEqualTo("event_operation_failed");
   assertThat(n.at("/result/operationPhase").asText()).isEqualTo(name.equals("ack-event")?"OUTBOX_ACKNOWLEDGEMENT":"NOTIFICATION_TRANSACTION");
   assertThat(n.path("attemptId").asText()).isNotEqualTo("UNKNOWN");
  });
  for(var n:timeline.nodes()){
   for(var origin:n.path("sources")){
    var e=evidence.stream().filter(x->x.id().equals(origin.path("evidenceId").asText())).findFirst().orElseThrow();
    var row=e.data().at(origin.path("pointer").asText());
    assertThat(n.path("eventTime")).isEqualTo(row.path("time"));
    assertThat(n.path("action")).isEqualTo(row.path("message"));
    assertThat(n.path("eventId").asText()).isEqualTo(row.path("eventId").asText());
   }
   if(n.path("action").asText().equals("notification_write_committed"))assertThat(n.path("timeSemantics").asText()).isEqualTo("POST_COMMIT_UPPER_BOUND");
  }
  var store=mock(TaskStore.class);when(store.evidence("replay")).thenReturn(evidence);when(store.usage("replay")).thenReturn(Map.of("model_calls",0,"tool_calls",1));
  var model=mock(ModelGateway.class);var tools=mock(ReadTools.class);var flow=new DirectWorkflow(store,tools,model);
  var state=new LinkedHashMap<String,Object>(Map.of("phase","INVESTIGATE"));
  var claim=new Claim("replay","unused",request,state,Instant.now().plusSeconds(180));
  var source=flow.contextData(claim,state);var frame=WorkingContext.prepare(source,state);
  var input=parse(frame.text());var facts=tree(ContextPacking.expand(input,"causeFactRegistry"));
  assertThat(facts).anySatisfy(f->{assertThat(f.path("pointer").asText()).endsWith("/operationPhase");assertThat(f.path("value").asText()).isEqualTo(name.equals("ack-event")?"OUTBOX_ACKNOWLEDGEMENT":"NOTIFICATION_TRANSACTION");});
  if(name.equals("ack-event"))assertThat(facts).anySatisfy(f->{assertThat(f.path("pointer").asText()).endsWith("/notificationCommitObservation");assertThat(f.path("value").asText()).isEqualTo("COMMITTED_BY_PROXY_RETURN");});
  assertThat(facts).anySatisfy(f->{assertThat(f.path("pointer").asText()).endsWith("/sqlErrorCode");assertThat(f.path("value").asInt()).isEqualTo(1205);});
  assertThat(frame.visible()).containsExactlyInAnyOrderElementsOf(frame.registered());
  assertThat(frame.text()).doesNotContain("operator-control","PROBE_DB_PASSWORD","hiddenCause","/api/drills");
  assertThat(frame.text().length()).isLessThanOrEqualTo(RequestBoundary.MAX_CONTEXT_UTF16);
  var dir=java.nio.file.Path.of("target/stable-event-context",name);java.nio.file.Files.createDirectories(dir);
  java.nio.file.Files.writeString(dir.resolve("working-context.json"),frame.text());
  java.nio.file.Files.writeString(dir.resolve("manifest.json"),encode(Map.of("contextUtf16",frame.text().length(),"providedFacts",frame.visible().size(),"registeredFacts",frame.registered().size(),"modelCalls",0,"recordKind","OFFLINE_PRODUCTION_CONTEXT_ASSEMBLY")));
  verifyNoInteractions(model,tools);
 }
}
