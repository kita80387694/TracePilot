package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class StableRepairReplayTest {
 JsonNode rejected()throws Exception{return JSON.readTree(getClass().getResourceAsStream("/stable-evidence/read-evidence-rejections-v3.json")).path("actions").get(0);}
 @Test void actualDiscriminatorFailureHasAccurateCorrectionAndDoesNotCoerce()throws Exception{
  var action=rejected();String before=encode(action);var errors=DirectContract.errors(action,false);
  assertThat(errors).hasSize(1);var error=parse(errors.getFirst());
  assertThat(error.path("code").asText()).isEqualTo("INVALID_ACTION_DISCRIMINATOR");
  assertThat(error.path("path").asText()).isEqualTo("/tool");
  assertThat(error.path("actual").asText()).isEqualTo("readEvidence");
  assertThat(error.path("expected").isArray()).isTrue();
  assertThat(tree(WorkingContext.correction(errors)).at("/details/0/correction").asText()).contains("type=readEvidence","not type=tool");
  assertThat(encode(action)).isEqualTo(before);
  assertThat(DirectContract.errors(tree(List.of()),false).toString()).contains("TYPE_MISMATCH");
  assertThat(DirectContract.errors(tree(Map.of()),false).toString()).contains("MISSING_FIELD");
  assertThat(parse(DirectContract.errors(action,true).getFirst()).path("path").asText()).isEqualTo("/type");
  var unknown=tree(Map.of("type","unknown"));assertThat(DirectContract.errors(unknown,false).toString()).contains("INVALID_ACTION_DISCRIMINATOR","/type");
  assertThat(DirectContract.errors(tree(Map.of("type","tool","tool","logs","args",Map.of("control",true))),false).toString()).contains("UNEXPECTED_FIELD");
 }
 @Test void realFailureThenScriptedCorrectionTravelsThroughActualHttpRequest()throws Exception{
  var fixture=new DirectWorkflowTest();try(var h=fixture.new Harness("NO_DATA")){
   var invalid=rejected();var inputs=new ArrayList<JsonNode>();var sizes=new ArrayList<Integer>();var fault=new AtomicReference<Throwable>();
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/v1/messages",x->{try{
    byte[] wire=x.getRequestBody().readAllBytes();sizes.add(wire.length);
    var input=parse(parse(new String(wire,StandardCharsets.UTF_8)).at("/messages/0/content/0/text").asText());inputs.add(input);
    var response=inputs.size()==1?invalid:fixture.empty;
    byte[] bytes=encode(Map.of("id","offline","type","message","role","assistant","model",ModelCaptureTest.MODEL,"content",List.of(Map.of("type","text","text",encode(response))),"stop_reason","end_turn","usage",Map.of("input_tokens",1,"output_tokens",1))).getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
   }catch(Throwable e){fault.set(e);x.sendResponseHeaders(500,-1);}finally{x.close();}});server.start();
   var gateway=new SpringAiGateway("LOCAL_TEST_ONLY","http://127.0.0.1:"+server.getAddress().getPort(),ModelCaptureTest.MODEL);
   var workflow=new DirectWorkflow(h.store,h.tools,gateway);
   try{workflow.run(h.claim);}finally{workflow.close();server.stop(0);}
   assertThat(fault.get()).isNull();assertThat(inputs).hasSize(2);assertThat(h.calls.get()).isEqualTo(2);
   assertThat(inputs.get(1).at("/checkpoint/correction/details/0/correction").asText()).contains("type=readEvidence");
   assertThat(sizes).allMatch(n->n<=RequestBoundary.MAX_WIRE_UTF8_BYTES);
   assertThat(h.status.get()).isEqualTo("COMPLETED");assertThat(h.steps).contains("ACTION:REJECTED");
  }
 }
 @Test void secondActualInvalidActionEndsWithoutExtraRetry()throws Exception{
  var fixture=new DirectWorkflowTest();try(var h=fixture.new Harness("NO_DATA")){
   var invalid=rejected();when(h.model.call(anyString(),anyString(),anyString(),anyInt(),any())).thenReturn(new ModelReply(invalid,"LOCAL_TEST_DOUBLE",1,1));
   h.workflow.run(h.claim);assertThat(h.calls.get()).isEqualTo(2);assertThat(h.status.get()).isEqualTo("PARTIAL");
   assertThat(h.steps.stream().filter(x->x.equals("ACTION:REJECTED")).count()).isEqualTo(2);
  }
 }
 @Test void schemaRuntimeAndFingerprintsSharePageBounds(){
  for(String tool:List.of("logs","trace","metrics")){
   var args=new LinkedHashMap<String,String>();if(tool.equals("trace"))args.put("traceId","a".repeat(32));
   var q=new Query(tool,args);ReadTools.validate(q);
   assertThat(EvidenceRegistry.normalized(q).get("limit")).isEqualTo(Integer.toString(ReadTools.pageLimit(tool)));
   args.put("limit",Integer.toString(ReadTools.pageLimit(tool)+1));
   assertThatThrownBy(()->ReadTools.validate(new Query(tool,args))).isInstanceOf(SecurityException.class);
   assertThat(ReadTools.boundedPage(new Query(tool,args)).args().get("limit")).isEqualTo(Integer.toString(ReadTools.pageLimit(tool)));
   assertThat(DirectContract.errors(tree(Map.of("type","tool","tool",tool,"args",Map.of("limit",201))),false).toString()).contains("VALUE_OUT_OF_RANGE");
  }
 }
 @Test void unknownDeploymentStillCannotGenerateTimeline()throws Exception{
  var f=JSON.readTree(getClass().getResourceAsStream("/stable-evidence/new-event-http.json"));var page=f.path("pages").get(0).deepCopy();
  for(var row:page.path("data"))((com.fasterxml.jackson.databind.node.ObjectNode)row).put("deploymentVersion","sha256-"+"f".repeat(64));
  var r=new Request("tracepilot-business","demo",java.time.Instant.parse(f.path("start").asText()),java.time.Instant.parse(f.path("end").asText()),"test");
  var e=new Evidence("E","logs","AVAILABLE",r.start().toString(),r.end().toString(),Map.of(),page);
  var c=EventTimeline.build(List.of(e),r);assertThat(c.nodes()).isEmpty();assertThat(c.gaps().toString()).contains("未核实的部署版本");
 }
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
 @Test void realSourcePagesUseBoundedDefaultsAcrossWorkflowAndPreserveContinuation()throws Exception{
  var fixture=JSON.readTree(getClass().getResourceAsStream("/stable-evidence/f01-http.json"));
  var req=new Request("tracepilot-business","demo",java.time.Instant.parse(fixture.at("/logs/start").asText()),java.time.Instant.parse(fixture.at("/logs/end").asText()),"test");
  var rows=new ArrayList<String>();for(var row:fixture.at("/logs/rows"))rows.add(encode(row));Collections.reverse(rows);
  java.nio.file.Files.writeString(temp.resolve("evidence.jsonl"),String.join("\n",rows)+"\n");
  var requests=new ArrayList<Map<String,String>>();var problem=new AtomicReference<Throwable>();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/ops/logs",x->{try{
   var args=new TreeMap<String,String>();for(String pair:x.getRequestURI().getRawQuery().split("&")){var kv=pair.split("=",2);args.put(kv[0],java.net.URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}requests.add(args);
   assertThat(args.get("limit")).isEqualTo("200");
   byte[] bytes=encode(ArchivePage.read(temp,true,req.start(),req.end(),args.get("cursor"),200,null,null,JSON)).getBytes(StandardCharsets.UTF_8);
   x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
  }catch(Throwable e){problem.set(e);x.sendResponseHeaders(500,-1);}finally{x.close();}});server.start();
  var helper=new DirectWorkflowTest();try(var h=helper.new Harness("NO_DATA",req)){
   var tools=new ReadTools("http://127.0.0.1:"+server.getAddress().getPort(),"LOCAL",temp.toString());
   var flow=new DirectWorkflow(h.store,tools,h.model);
   try{
    assertThat(flow.query(h.claim,new Query("logs",Map.of("limit","200")))).isTrue();
    assertThat(requests).hasSize(1); // One selection never silently fetches three pages.
    while(h.evidence.getLast().data().path("hasMore").asBoolean()){
     String cursor=h.evidence.getLast().data().path("nextCursor").asText();
     assertThat(flow.query(h.claim,new Query("logs",Map.of("limit","200","cursor",cursor)))).isTrue();
     assertThat(requests.size()).isLessThanOrEqualTo(3);
    }
   }finally{flow.close();}
   assertThat(problem.get()).isNull();assertThat(requests).hasSize(1);
   var es=h.evidence.stream().filter(e->e.locator().containsKey("queryFingerprint")).toList();
   assertThat(es.stream().mapToInt(e->e.data().path("data").size()).sum()).isEqualTo(rows.size());
   assertThat(EvidenceCompleteness.scanComplete(es.getFirst(),es)).isTrue();
   for(var e:es)assertThat(EvidenceCompleteness.projection(e).get("projectionComplete")).isEqualTo(true);
   assertThat(h.steps).doesNotContain("QUERY:BOUNDED_PAGE_SIZE");assertThat(h.toolCalls.get()).isEqualTo(1);assertThat(h.calls.get()).isZero();
   verifyNoInteractions(h.model);
  }finally{server.stop(0);}
 }
 @Test void errorOnlyQueryDisclosesLifecycleCoverageGap(){
  var e=new Evidence("E","logs","AVAILABLE","2026-09-21T00:00:00Z","2026-09-21T00:01:00Z",Map.of("query",Map.of("args",Map.of("level","ERROR"))),tree(Map.of("data",List.of(),"hasMore",false)));
  assertThat(EvidenceCompleteness.gaps(List.of(e)).toString()).contains("level=ERROR","其他级别未查询");
 }

}
