package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class StableEvidenceProjectionTest {
 @Test void realPagedMetricsPreserveRecordedIntervalsAndUnknownFirstStart()throws Exception{
  var fixture=JSON.readTree(getClass().getResourceAsStream("/stable-evidence/metrics-pages.json"));
  var r=new Request("tracepilot-business","demo",Instant.parse(fixture.path("start").asText()),Instant.parse(fixture.path("end").asText()),"test");
  int samples=0;
  for(var page:fixture.path("pages")){
   var data=page.path("response");samples+=data.path("data").size();
   var e=new Evidence("E"+samples,"metrics",data.path("status").asText(),r.start().toString(),r.end().toString(),Map.of(),data);
   var facts=MetricFacts.generate(List.of(e),r).stream().map(Domain::tree).toList();
   for(var f:EvidenceUse.catalog(List.of(e),r).stream().map(Domain::tree).filter(x->x.path("kind").asText().startsWith("SAMPLE_INTERVAL")).toList()){
    assertThat(f.path("measurementStart").asText()).isNotBlank();
    if(!Instant.parse(f.path("measurementStart").asText()).isBefore(r.start())&&!f.path("value").isNull())assertThat(f.path("causeEligible").asBoolean()).isTrue();
    else assertThat(f.path("causeEligible").asBoolean()).isFalse();
   }
   for(var f:facts){
    assertThat(data.at(f.path("pointer").asText())).isEqualTo(f.path("value"));
    assertThat(f.path("semanticsVersion").asText()).isEqualTo("MetricSampler-fixed-v2");
    if(f.path("kind").asText().startsWith("SAMPLE_INTERVAL")){
     assertThat(f.path("measurementStart").asText()).isNotBlank();
     assertThat(f.path("intervalCoverage").asText()).isEqualTo("BETWEEN_SAMPLES");
     assertThat(f.path("timeBasis").asText()).doesNotContain("not recorded");
    }
   }
  }
  assertThat(samples).isEqualTo(8);
  var sample=fixture.path("pages").get(0).path("response").path("data").get(0).deepCopy();
  var h=(com.fasterxml.jackson.databind.node.ObjectNode)sample.path("http");h.putNull("intervalStart");h.put("intervalCoverage","FIRST_SAMPLE_START_UNKNOWN");
  var e=new Evidence("initial","overview","AVAILABLE",r.start().toString(),r.end().toString(),Map.of(),sample);
  assertThat(MetricFacts.generate(List.of(e),r).stream().map(Domain::tree).filter(f->f.path("kind").asText().startsWith("SAMPLE_INTERVAL"))).allSatisfy(f->{
   assertThat(f.path("measurementStart").isNull()).isTrue();assertThat(f.path("timeBasis").asText()).contains("unknown interval start");
  });
 }
 @Test void realMysqlBoundaryFieldsReachPackedWorkingContextWithoutRewriting()throws Exception{
  for(String file:List.of("notification-transaction-lock","acknowledgement-lock")){
   var rows=JSON.createArrayNode();
   try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(getClass().getResourceAsStream("/stable-evidence/"+file+".jsonl"),java.nio.charset.StandardCharsets.UTF_8))){
    for(String line;(line=reader.readLine())!=null;)if(!line.isBlank())rows.add(JSON.readTree(line));
   }
   var data=JSON.createObjectNode();data.set("data",rows);
   String start=rows.get(0).path("time").asText(),end=rows.get(rows.size()-1).path("time").asText();
   var e=new Evidence("E","logs","AVAILABLE",start,end,Map.of("source","real-mysql-offline-replay"),data);
   // Check transport integrity, not causal eligibility: the original test deployment is 'unknown'.
   var facts=EvidenceUse.catalog(List.of(e),new Request("tracepilot-business","demo",Instant.parse(start),Instant.parse(end),"test"));var source=new LinkedHashMap<String,Object>();source.put("causeFactRegistry",List.of());source.put("backgroundFactRegistry",facts);source.put("evidence",List.of(e));
   var frame=WorkingContext.prepare(source,Map.of());
   var expanded=tree(ContextPacking.expand(parse(frame.text()),"backgroundFactRegistry"));
   assertThat(expanded).anySatisfy(f->{assertThat(f.path("pointer").asText()).endsWith("/operationPhase");assertThat(f.path("value").asText()).isEqualTo(file.startsWith("notification")?"NOTIFICATION_TRANSACTION":"OUTBOX_ACKNOWLEDGEMENT");});
   for(var f:expanded)if(!f.path("pointer").asText().startsWith("@"))assertThat(data.at(f.path("pointer").asText())).isEqualTo(f.path("value"));
   assertThat(encode(expanded)).contains("notificationCommitObservation","attemptId","eventId");
  }
 }
}
