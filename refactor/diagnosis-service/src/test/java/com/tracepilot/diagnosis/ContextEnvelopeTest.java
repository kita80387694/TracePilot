package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;import java.nio.file.*;import org.junit.jupiter.api.Test;
class ContextEnvelopeTest {
 @Test void actualGrowingPagesRespectWholeEvidenceEnvelopeAndKeepWholeRows()throws Exception{
  var data=JSON.readTree(getClass().getResourceAsStream("/context-envelope-v14/actual.json"));var request=JSON.treeToValue(data.path("request"),Request.class);var measurements=new ArrayList<Object>();
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",request)){
   for(int count=4;count<=data.path("evidence").size();count++){
    h.evidence.clear();for(int i=0;i<count;i++)h.evidence.add(JSON.treeToValue(data.path("evidence").get(i),Evidence.class));
    long started=System.nanoTime();var frame=WorkingContext.prepare(h.workflow.contextData(h.claim,Map.of()),Map.of("windowSample",true));
    var input=parse(frame.text());assertThat(input.at("/contextBudget/actual/evidence").asInt()).isLessThanOrEqualTo(70000);assertThat(frame.text().length()).isLessThanOrEqualTo(100000);
    var groups=new LinkedHashMap<String,Set<String>>();for(var f:frame.facts().values())groups.computeIfAbsent(CompletionCoverage.observationKey(f),k->new HashSet<>()).add(f.path("factId").asText());
    for(var group:groups.values())if(group.stream().anyMatch(frame.visible()::contains))assertThat(frame.visible()).containsAll(group);
    assertThat(frame.delivery().get("complete")).isEqualTo(false);
    measurements.add(Map.of("storedEvidencePages",count,"contextUtf16",frame.text().length(),"assemblyMs",(System.nanoTime()-started)/1000000,"delivery",frame.delivery()));
   }
  }
  Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay/context-envelope-v14.json"),encode(measurements));
 }
 @Test void actualPreviouslyRejectedEnvelopeReachesFinalSdkWireWithoutRaisingLimits()throws Exception{
  var data=JSON.readTree(getClass().getResourceAsStream("/context-envelope-v14/actual.json"));var request=JSON.treeToValue(data.path("request"),Request.class);var helper=new BoundedActionsTest();
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",request);var e=helper.new Endpoint(helper.report())){
   h.evidence.clear();for(var item:data.path("evidence"))h.evidence.add(JSON.treeToValue(item,Evidence.class));
   var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(e.wires).hasSize(1);assertThat(encode(e.wires.getFirst()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   assertThat(h.steps).doesNotContain("MODEL:LOCAL_FAILURE");assertThat(h.status.get()).isEqualTo("COMPLETED");
  }
 }
 @Test void maximumToolBudgetSourceCountKeepsNavigationAndSamplesBounded()throws Exception{
  var data=JSON.readTree(getClass().getResourceAsStream("/context-envelope-v14/actual.json"));var request=JSON.treeToValue(data.path("request"),Request.class);
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",request)){
   h.evidence.clear();for(var item:data.path("evidence"))h.evidence.add(JSON.treeToValue(item,Evidence.class));
   var original=h.evidence.getLast();while(h.evidence.size()<12){int n=h.evidence.size();h.evidence.add(new Evidence("synthetic-page-"+n,original.source(),original.status(),original.start(),original.end(),original.locator(),original.data()));}
   var frame=WorkingContext.prepare(h.workflow.contextData(h.claim,Map.of()),Map.of("windowSample",true));
   int previews=0;for(var source:tree(frame.delivery().get("sources")))previews+=source.path("indexPreview").size();
   assertThat(previews).isLessThanOrEqualTo(16);assertThat(tree(frame.delivery().get("sources"))).hasSize(12);
   assertThat(parse(frame.text()).at("/contextBudget/actual/evidence").asInt()).isLessThanOrEqualTo(70000);
   assertThat(frame.text().length()).isLessThanOrEqualTo(100000);assertThat(frame.delivery().get("complete")).isEqualTo(false);
  }
 }

}
