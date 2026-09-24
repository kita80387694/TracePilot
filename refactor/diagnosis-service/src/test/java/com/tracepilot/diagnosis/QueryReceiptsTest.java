package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.file.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Real failed-task evidence; scripted model selections are not causal-quality scores. */
class QueryReceiptsTest {
 JsonNode fixture()throws Exception{return JSON.readTree(getClass().getResourceAsStream("/query-receipts-v11/actual.json"));}
 DirectWorkflowTest.Harness harness()throws Exception{
  var f=fixture();var h=new DirectWorkflowTest().new Harness("NO_DATA",JSON.treeToValue(f.path("request"),Request.class));h.evidence.clear();
  for(var e:f.path("evidence"))h.evidence.add(JSON.treeToValue(e,Evidence.class));return h;
 }
 @Test void actualPartialPagesReturnResolvableIdsAndCursors()throws Exception{
  try(var h=harness()){for(var source:List.of("logs","metrics")){
   var e=h.evidence.stream().filter(x->x.source().equals(source)).findFirst().orElseThrow();
   var args=tree(e.locator().get("query")).path("args");var qargs=new LinkedHashMap<String,String>();args.fields().forEachRemaining(x->qargs.put(x.getKey(),x.getValue().asText()));
   var receipt=tree(EvidenceRegistry.queryReceipt(h.claim.request(),new Query(source,qargs),h.evidence,false));
   assertThat(receipt.path("evidenceIds")).contains(tree(e.id()));assertThat(receipt.at("/results/0/status").asText()).isEqualTo("PARTIAL");assertThat(receipt.at("/results/0/nextCursor")).isEqualTo(e.data().path("nextCursor"));
  }}
 }
 @Test void fullWireReplayRetainsBothViewsAndBothPartialQueryReceipts()throws Exception{
  var helper=new BoundedActionsTest();
  try(var h=harness()){
   var logs=h.evidence.stream().filter(x->x.source().equals("logs")).findFirst().orElseThrow();var metrics=h.evidence.stream().filter(x->x.source().equals("metrics")).findFirst().orElseThrow();
   var reads=List.of(helper.call("a","read_evidence_page",Map.of("evidenceId",logs.id(),"page",0)),helper.call("b","read_evidence_page",Map.of("evidenceId",metrics.id(),"page",0)));
   var queries=List.of(helper.call("c","query_logs",Map.of("args",Map.of("limit",20))),helper.call("d","query_metrics",Map.of("args",Map.of("limit",3))));
   try(var e=helper.new Endpoint(reads,queries,helper.report())){
    var w=new DirectWorkflow(h.store,h.tools,e.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
    assertThat(e.wires).hasSize(3);verifyNoInteractions(h.tools);assertThat(h.toolCalls.get()).isEqualTo(2);
    var second=parse(e.wires.get(1).at("/messages/0/content/0/text").asText());var third=parse(e.wires.get(2).at("/messages/0/content/0/text").asText());
    assertThat(third.at("/evidenceDelivery/currentPages")).isEqualTo(second.at("/evidenceDelivery/currentPages"));assertThat(third.at("/checkpoint/lastResults")).hasSize(2);
    for(var result:third.at("/checkpoint/lastResults")){assertThat(result.path("status").asText()).isEqualTo("REUSED");assertThat(result.path("evidenceIds")).isNotEmpty();assertThat(result.at("/results/0/hasMore").asBoolean()).isTrue();}
    for(var wire:e.wires)assertThat(encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   }
  }
 }
 @Test void existingSourceCapCanAcquireActualAnomalyWindowWithoutIncreasingModelPageBudget()throws Exception{
  try(var h=harness()){
   var metricRows=new ArrayList<JsonNode>();JSON.readTree(getClass().getResourceAsStream("/query-receipts-v11/metrics.json")).path("rows").forEach(metricRows::add);Collections.reverse(metricRows);var metrics=tree(metricRows);
   var errorRows=new ArrayList<JsonNode>();JSON.readTree(getClass().getResourceAsStream("/query-receipts-v11/errors.json")).path("rows").forEach(errorRows::add);Collections.reverse(errorRows);var errors=tree(errorRows);
   assertThat(metrics.size()).isGreaterThan(3).isLessThanOrEqualTo(ReadTools.pageLimit("metrics"));
   // Preserve every actual row; ERROR filtering is explicit and is not full unfiltered-log coverage.
   h.evidence.clear();h.evidence.add(new Evidence("metricWindow","metrics","AVAILABLE",h.claim.request().start().toString(),h.claim.request().end().toString(),Map.of(),tree(Map.of("data",metrics,"hasMore",false))));
   h.evidence.add(new Evidence("errorWindow","logs","AVAILABLE",h.claim.request().start().toString(),h.claim.request().end().toString(),Map.of("query",Map.of("tool","logs","args",Map.of("level","ERROR"))),tree(Map.of("data",errors,"hasMore",false))));
   var source=h.workflow.contextData(h.claim,Map.of());var frame=WorkingContext.prepare(source,Map.of());
   var selected=new ArrayList<Object>();var expected=new HashSet<String>();
   for(var entry:frame.pages().entrySet()){
    int index=-1;
    for(int n=0;n<entry.getValue().size();n++){
     var page=entry.getValue().get(n);
     if(page.facts().stream().map(frame.facts()::get).anyMatch(f->entry.getKey().equals("metricWindow")?f.path("pointer").asText().endsWith("/pool/pending")&&f.path("value").asInt()>0:f.path("pointer").asText().endsWith("/message"))){index=n;expected.addAll(page.facts());break;}
    }
    assertThat(index).isGreaterThanOrEqualTo(0);selected.add(Map.of("evidenceId",entry.getKey(),"page",index));
   }
   var delivered=WorkingContext.prepare(source,Map.of("evidenceViews",selected));assertThat(delivered.visible()).containsAll(expected);assertThat(delivered.text().length()).isLessThanOrEqualTo(RequestBoundary.MAX_CONTEXT_UTF16);
   Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay/query-receipts-window.json"),encode(Map.of("sourceMetricRows",metrics.size(),"sourceErrorRows",errors.size(),"selectedPages",selected,"deliveredFacts",delivered.visible().size(),"contextUtf16",delivered.text().length(),"selection","OFFLINE_CONTENT_INSPECTION_NOT_MODEL_RESULT")));
  }
 }
 @Test void defaultWindowSampleProvidesCompleteDistributedObservationsWithinUnchangedBudget()throws Exception{
  try(var h=harness()){
   var metricRows=new ArrayList<JsonNode>();JSON.readTree(getClass().getResourceAsStream("/query-receipts-v11/metrics.json")).path("rows").forEach(metricRows::add);Collections.reverse(metricRows);
   var errorRows=new ArrayList<JsonNode>();JSON.readTree(getClass().getResourceAsStream("/query-receipts-v11/errors.json")).path("rows").forEach(errorRows::add);Collections.reverse(errorRows);
   h.evidence.clear();h.evidence.add(new Evidence("metrics","metrics","AVAILABLE",h.claim.request().start().toString(),h.claim.request().end().toString(),Map.of(),tree(Map.of("data",metricRows,"hasMore",false))));
   h.evidence.add(new Evidence("errors","logs","AVAILABLE",h.claim.request().start().toString(),h.claim.request().end().toString(),Map.of("query",Map.of("tool","logs","args",Map.of("level","ERROR"))),tree(Map.of("data",errorRows,"hasMore",false))));
   var source=h.workflow.contextData(h.claim,Map.of());var frame=WorkingContext.prepare(source,Map.of("windowSample",true));
   assertThat(frame.delivery().get("mode")).isEqualTo("WINDOW_OBSERVATION_SAMPLE");
   var groups=new LinkedHashMap<String,Set<String>>();for(var f:frame.facts().values())groups.computeIfAbsent(CompletionCoverage.observationKey(f),k->new HashSet<>()).add(f.path("factId").asText());
   for(var group:groups.values())if(group.stream().anyMatch(frame.visible()::contains))assertThat(frame.visible()).containsAll(group);
   assertThat(frame.visible().stream().map(frame.facts()::get).anyMatch(f->f.path("pointer").asText().endsWith("/pool/pending")&&f.path("value").asInt()>0)).isTrue();
   assertThat(frame.visible().stream().map(frame.facts()::get).anyMatch(f->f.path("pointer").asText().endsWith("/pool/pending")&&f.path("value").asInt()==0)).isTrue();
   assertThat(frame.text().length()).isLessThanOrEqualTo(RequestBoundary.MAX_CONTEXT_UTF16);
   assertThat(frame.delivery().get("complete")).isEqualTo(false);
   Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay/window-sample.json"),encode(Map.of("providedFacts",frame.visible().size(),"registeredFacts",frame.registered().size(),"contextUtf16",frame.text().length(),"meaning","OFFLINE_REPLAY_NOT_MODEL_DIAGNOSIS")));
  }
 }

}
