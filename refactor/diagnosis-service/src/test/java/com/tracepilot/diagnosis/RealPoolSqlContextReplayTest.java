package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Re-pages preserved HTTP observations, not a new incident or a model capability test. */
class RealPoolSqlContextReplayTest {
 @TempDir Path temp;
 @ParameterizedTest @ValueSource(strings={"f01","f02"})
 void realObservationsRemainResolvableThroughBoundedProductionContext(String name)throws Exception {
  var fixture=JSON.readTree(getClass().getResourceAsStream("/stable-evidence/"+name+"-http.json"));
  var request=new Request("tracepilot-business","demo",Instant.parse(fixture.path("logs").path("start").asText()),Instant.parse(fixture.path("logs").path("end").asText()),"请求变慢或失败，请检查直接机制与数据缺口");
  var evidence=new ArrayList<Evidence>();var counts=new LinkedHashMap<String,Object>();
  for(String source:List.of("logs","metrics")) {
   var rows=new ArrayList<String>();for(var row:fixture.path(source).path("rows"))rows.add(encode(row));Collections.reverse(rows);
   Path archive=temp.resolve(source);Files.createDirectories(archive);
   Files.writeString(archive.resolve(source.equals("logs")?"evidence.jsonl":"metrics.2026-09-17.jsonl"),String.join("\n",rows)+"\n");
   String cursor="0";int index=0,total=0;int limit=source.equals("logs")?20:3;
   do {
    var page=tree(ArchivePage.read(archive,source.equals("logs"),request.start(),request.end(),cursor,limit,null,null,JSON));
    var e=new Evidence(source+index++,source,page.path("status").asText(),request.start().toString(),request.end().toString(),Map.of("query",Map.of("tool",source,"args",Map.of("cursor",cursor,"limit",Integer.toString(limit))),"recordKind","REPLAY_OF_PRESERVED_HTTP_ROWS"),page);
    assertThat(tree(EvidenceCompleteness.projection(e)).path("projectionComplete").asBoolean()).isTrue();
    evidence.add(e);total+=page.path("data").size();cursor=page.path("hasMore").asBoolean()?page.path("nextCursor").asText():"0";
    assertThat(index).isLessThanOrEqualTo(3);
   }while(!cursor.equals("0"));
   assertThat(total).isEqualTo(rows.size());counts.put(source,Map.of("rows",total,"pages",index));
  }
  if(fixture.has("plan"))evidence.add(new Evidence("plan","sqlPlan","AVAILABLE",request.start().toString(),request.end().toString(),Map.of(),fixture.path("plan")));
  var store=mock(TaskStore.class);when(store.evidence("replay")).thenReturn(evidence);when(store.usage("replay")).thenReturn(Map.of("model_calls",0,"tool_calls",evidence.size()));
  var model=mock(ModelGateway.class);var tools=mock(ReadTools.class);var flow=new DirectWorkflow(store,tools,model);
  var state=new LinkedHashMap<String,Object>(Map.of("phase","INVESTIGATE"));
  var claim=new Claim("replay","unused",request,state,Instant.now().plusSeconds(180));
  var source=flow.contextData(claim,state);var initial=WorkingContext.prepare(source,state);
  var delivered=new HashSet<String>();var rounds=new ArrayList<Object>();
  Path out=Path.of("target/stable-pool-sql-context",name);Files.createDirectories(out);
  // Enumerating every page measures the actual read cost. It does NOT claim these reads
  // fit the eight-model task budget or that an actual model selected relevant pages.
  for(var entry:initial.pages().entrySet())for(int page=0;page<entry.getValue().size();page++) {
   state.put("evidenceView",Map.of("evidenceId",entry.getKey(),"page",page));
   var frame=WorkingContext.prepare(source,state);delivered.addAll(frame.visible());
   assertThat(frame.text().length()).isLessThanOrEqualTo(RequestBoundary.MAX_CONTEXT_UTF16);
   assertThat(frame.delivery().get("unavailableFactIds")).isEqualTo(List.of());
   assertThat(frame.registered()).containsAll(frame.visible());
   Files.writeString(out.resolve("context-"+rounds.size()+".json"),frame.text());
   rounds.add(Map.of("evidenceId",entry.getKey(),"page",page,"utf16",frame.text().length(),"providedFacts",frame.visible().size()));
  }
  assertThat(delivered).containsExactlyInAnyOrderElementsOf(initial.registered());
  var facts=initial.facts().values();
  if(name.equals("f01"))assertThat(facts).anySatisfy(f->assertThat(f.path("value").asText()).isEqualTo("authentication_store_unavailable"));
  else {
   assertThat(facts).anySatisfy(f->assertThat(f.path("pointer").asText()).endsWith("/jdbcOperationMs"));
   assertThat(facts).anySatisfy(f->{assertThat(f.path("pointer").asText()).endsWith("/access_type");assertThat(f.path("value").asText()).isEqualTo("ALL");assertThat(f.path("causeEligible").asBoolean()).isFalse();});
  }
  // Minimal discriminating observations, selected from actual content for a deterministic
  // replay. No automatic evidence selection is being added to the model workflow.
  var needed=new LinkedHashSet<String>();
  var logEvidence=evidence.stream().filter(e->e.source().equals("logs")).toList();
  boolean chosen=false;
  for(var e:logEvidence)for(int row=0;row<e.data().path("data").size()&&!chosen;row++){
   var n=e.data().path("data").get(row);
   if(name.equals("f01")?n.path("message").asText().equals("authentication_store_unavailable"):n.path("message").asText().equals("sql_completed")&&n.path("jdbcOperationMs").asDouble()>1000){
    for(String field:List.of("message","traceId","errorType","connectionAcquired","connectionAcquireMs","jdbcOperationMs","statementFingerprint")){
     String id=FactReferences.id(e.id(),"/data/"+row+"/"+field);if(initial.registered().contains(id))needed.add(id);
    }chosen=true;
   }
  }
  assertThat(chosen).isTrue();
  boolean pool=false;
  for(var e:evidence)if(e.source().equals("metrics"))for(int row=0;row<e.data().path("data").size()&&!pool;row++){
   var n=e.data().path("data").get(row).path("pool");
   if(n.path("pending").asInt()>0){for(String field:List.of("active","pending","max")){String id=FactReferences.id(e.id(),"/data/"+row+"/pool/"+field);if(initial.registered().contains(id))needed.add(id);}pool=true;}
  }
  if(name.equals("f02"))for(var f:facts)if(f.path("evidenceId").asText().equals("plan")&&(f.path("pointer").asText().endsWith("/access_type")||f.path("pointer").asText().equals("/statementFingerprint")))needed.add(f.path("factId").asText());
  var retained=new LinkedHashSet<String>();var focused=new ArrayList<Object>();
  while(!retained.containsAll(needed)){
   String bestId=null;int bestPage=0,best=0;
   for(var entry:initial.pages().entrySet())for(int p=0;p<entry.getValue().size();p++){
    int gain=(int)entry.getValue().get(p).facts().stream().filter(id->needed.contains(id)&&!retained.contains(id)).count();
    if(gain>best){best=gain;bestId=entry.getKey();bestPage=p;}
   }
   assertThat(best).isPositive();state.put("retainedFactIds",new ArrayList<>(retained));state.put("evidenceView",Map.of("evidenceId",bestId,"page",bestPage));
   var frame=WorkingContext.prepare(source,state);
   for(String id:frame.visible())if(needed.contains(id))retained.add(id);
   assertThat(EvidenceDelivery.payloadSize(source,retained)).isLessThanOrEqualTo(EvidenceDelivery.RETAIN_UTF16);
   Files.writeString(out.resolve("focused-"+focused.size()+".json"),frame.text());
   focused.add(Map.of("evidenceId",bestId,"page",bestPage,"utf16",frame.text().length(),"retained",List.copyOf(retained)));
  }
  // 3 investigation selections + report, plus the explicitly counted page reads.
  assertThat(4+focused.size()).isLessThanOrEqualTo(8);
  assertThat(1+evidence.size()+focused.size()).isLessThanOrEqualTo(12);
  Files.writeString(out.resolve("focused-manifest.json"),encode(Map.of("recordKind","DETERMINISTIC_SELECTION_REPLAY_NOT_MODEL_BEHAVIOR","neededFacts",needed,"reads",focused,"upperModelCallsIncludingQuerySelectionsAndReport",4+focused.size(),"upperToolCallsIncludingOverview",1+evidence.size()+focused.size(),"allEvidenceReviewed",false)));
  Files.writeString(out.resolve("manifest.json"),encode(Map.of("recordKind","OFFLINE_FULL_PAGE_COST_MEASUREMENT","source",counts,"registeredFacts",initial.registered().size(),"allPages",rounds,"sourceToolCalls",evidence.size(),"modelCalls",0,"notModelVerification",true)));
  verifyNoInteractions(model,tools);
 }
}
