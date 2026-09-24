package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;import java.nio.file.*;import java.time.*;
import org.junit.jupiter.api.Test;

/** Reconstructs context from the 20 archived tasks, not hidden labels or expected answers. */
class BaselineContextReplayTest {
 @Test void allTwentyArchivedEvidenceSetsAndCorrectionsFitWithoutDroppingFacts()throws Exception{
  var sizes=new ArrayList<Object>();var store=mock(TaskStore.class);var model=mock(ModelGateway.class);var workflow=new DirectWorkflow(store,mock(ReadTools.class),model);
  try{for(int i=1;i<=20;i++){
   String id=String.format("H%02d",i);Path file=Path.of("../../evaluation/results/closeout-baseline-v886-"+(i==1?"r2":"r3")+"/cases/"+id+".json");var task=JSON.readTree(Files.readString(file)).path("task");
   var r=JSON.treeToValue(task.path("request_json"),Request.class);var es=new ArrayList<Evidence>();for(var e:task.path("evidence"))es.add(JSON.treeToValue(e,Evidence.class));
   var claim=new Claim(id,"offline",r,Map.of(),Instant.now().plusSeconds(180));when(store.evidence(id)).thenReturn(es);when(store.usage(id)).thenReturn(Map.of("model_calls",0,"tool_calls",es.size(),"max_models",8,"max_tools",12));
   var state=new LinkedHashMap<String,Object>();state.put("phase","INVESTIGATE");var errors=new ArrayList<String>();
   for(var step:task.path("steps")){var detail=step.path("detail");for(var e:detail.path("errors"))errors.add(e.isTextual()?e.asText():encode(e));
    for(var e:detail.path("violations"))errors.add(e.isTextual()?e.asText():encode(e));
    for(var e:detail.path("evidenceFeedback").path("violations"))if(e.has("error"))errors.add(e.path("error").asText());
    if(step.path("status").asText().equals("REJECTED")&&detail.has("errorCode"))errors.add(encode(Map.of("code",detail.path("errorCode").asText(),"path","/action","expected","current versioned contract")));
   }
   assertThat(errors).as(id+" must replay actual recorded validation diagnostics").isNotEmpty();
   var source=workflow.contextData(claim,state);String initial=WorkingContext.build(source,state);state.put("correction",WorkingContext.correction(errors));String corrected=WorkingContext.build(source,state);
   for(String input:List.of(initial,corrected)){var n=parse(input);assertThat(n.path("causeFactRegistry").size()).as(id).isEqualTo(tree(source.get("causeFactRegistry")).size());assertThat(n.path("backgroundFactRegistry").size()).as(id).isEqualTo(tree(source.get("backgroundFactRegistry")).size());assertThat(input.length()).isLessThanOrEqualTo(100000);}
   sizes.add(Map.of("case",id,"actualDiagnosticCount",errors.size(),"sourceEvidence",es.size(),"causeFacts",tree(source.get("causeFactRegistry")).size(),"initialUtf16",initial.length(),"correctionUtf16",corrected.length(),"oldFeedbackUtf16",encode(task.path("state_json").path("evidenceFeedback")).length()));
  }}finally{workflow.close();}
  Path out=Path.of("target/replay");Files.createDirectories(out);Files.writeString(out.resolve("baseline20-context.json"),encode(Map.of("meaning","Offline context reconstruction only; original PARTIAL outcomes unchanged. Not a new model evaluation.","cases",sizes)));
 }
}
