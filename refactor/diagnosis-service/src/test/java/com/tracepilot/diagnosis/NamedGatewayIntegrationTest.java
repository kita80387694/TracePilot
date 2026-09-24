package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SDK and HTTP, scripted supplier only. Does not measure model quality. */
class NamedGatewayIntegrationTest {
 @TempDir Path temp;
 record Reply(String name,Map<String,Object> args){}
 class Endpoint implements AutoCloseable{
  HttpServer server;List<JsonNode> requests=new ArrayList<>();List<Reply> replies;Runnable beforeReply=()->{};
  Endpoint(Reply... values)throws Exception{replies=List.of(values);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/v1/messages",e->{requests.add(JSON.readTree(e.getRequestBody()));beforeReply.run();int i=requests.size()-1;
    if(i>=replies.size()){e.sendResponseHeaders(500,-1);e.close();return;}
    var reply=replies.get(i);byte[] b=encode(Map.of("id","local-"+i,"type","message","role","assistant","model",ModelCaptureTest.MODEL,"content",List.of(
     Map.of("type","thinking","thinking","PRIVATE_NEVER_CAPTURE","signature","fixture"),Map.of("type","text","text","Accompanying explanation, not an action."),
     Map.of("type","tool_use","id","native-"+i,"name",reply.name(),"input",reply.args())),"stop_reason","tool_use","usage",Map.of("input_tokens",10,"output_tokens",5))).getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();}
  String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
  public void close(){server.stop(0);}
 }
 Reply report(){return new Reply("submit_report",Map.of("hypotheses",List.of(),"checks",List.of("COLLECT_LOGS")));}
 SpringAiGateway gateway(Endpoint e,ModelCapture c){return new SpringAiGateway("fixture-key",e.url(),ModelCaptureTest.MODEL,c,"named-tools-v1");}
 @Test void queryReadReportUsesActualNativeCallsAndCapturesBothSides()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(new Reply("query_logs",Map.of("args",Map.of())),new Reply("read_evidence_page",Map.of("evidenceId","Eprevious","page",0)),report());var cap=new ModelCapture(temp,"fixture-key")){
   var gateway=gateway(e,cap);var w=new DirectWorkflow(h.store,h.tools,gateway);try{w.run(h.claim);}finally{w.close();}cap.awaitWrites();
   assertThat(h.status.get()).isEqualTo("COMPLETED");assertThat(e.requests).hasSize(3);assertThat(h.calls.get()).isEqualTo(3);assertThat(h.toolCalls.get()).isEqualTo(2); // Source query and local read both use the existing tool budget.
   verify(h.tools,times(1)).query(any(),any(),any());
   assertThat(h.steps.stream().filter(x->x.equals("MODEL_TRANSPORT:SEND_ATTEMPTED")).count()).isEqualTo(3);
   for(var wire:e.requests){assertThat(wire.path("tools")).hasSize(8);assertThat(wire.path("tools").findValuesAsText("name")).doesNotContain("query_code");assertThat(wire.at("/tool_choice/type").asText()).isEqualTo("auto");assertThat(wire.path("max_tokens").asInt()).isEqualTo(3000);assertThat(wire.at("/thinking/type").asText()).isEqualTo("disabled");assertThat(encode(wire).getBytes(StandardCharsets.UTF_8).length).isLessThan(RequestBoundary.MAX_WIRE_UTF8_BYTES);}
   String captures;try(var files=Files.list(temp)){captures=files.filter(p->p.toString().endsWith(".json")).map(p->{try{return Files.readString(p);}catch(Exception x){throw new RuntimeException(x);}}).reduce("",String::concat);}
   assertThat(captures).contains("nativeToolCalls","nativeTools","input_schema","read_evidence_page","named-tools-v9-integration-offline").doesNotContain("PRIVATE_NEVER_CAPTURE","fixture-key");
   assertThat(tree(h.report.get()).at("/execution/promptVersion").asText()).isEqualTo(gateway.actionPromptVersion());
   Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay/named-gateway-wire.json"),encode(e.requests));
  }
 }
 @Test void oneCorrectionPreservesNativeFieldErrorAndCannotResetBudget()throws Exception{
  for(boolean fail:List.of(false,true))try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(new Reply("read_evidence_page",Map.of("evidenceId","E1","page","1")),fail?new Reply("read_evidence_page",Map.of("evidenceId","E1","page","1")):report());var cap=ModelCapture.disabled()){
   var w=new DirectWorkflow(h.store,h.tools,gateway(e,cap));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.calls.get()).isEqualTo(2);assertThat(e.requests).hasSize(2);assertThat(encode(e.requests.get(1))).contains("TYPE_MISMATCH","/arguments/page");assertThat(h.status.get()).isEqualTo(fail?"PARTIAL":"COMPLETED");
  }
 }
 @Test void lateCancelledResponseStartsNoToolOrFollowup()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(new Reply("query_logs",Map.of("args",Map.of())));var cap=ModelCapture.disabled()){
   e.beforeReply=()->h.active.set(false);var w=new DirectWorkflow(h.store,h.tools,gateway(e,cap));try{w.run(h.claim);}finally{w.close();}
   assertThat(e.requests).hasSize(1);verifyNoInteractions(h.tools);assertThat(h.status.get()).isEqualTo("PARTIAL");
  }
 }
 @Test void exhaustedBudgetExposesReportOnlyAndRemainsPartial()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(report());var cap=ModelCapture.disabled()){
   h.max=1;var w=new DirectWorkflow(h.store,h.tools,gateway(e,cap));try{w.run(h.claim);}finally{w.close();}
   assertThat(e.requests).hasSize(1);assertThat(e.requests.get(0).path("tools")).hasSize(1);assertThat(e.requests.get(0).at("/tools/0/name").asText()).isEqualTo("submit_report");assertThat(h.status.get()).isEqualTo("PARTIAL");
  }
 }
 @Test void captureFailureDoesNotAlterCallAndTextDefaultRemainsUnchanged()throws Exception{
  try(var e=new Endpoint(report());var cap=new ModelCapture(temp,"fixture-key")){
   cap.close();var g=gateway(e,cap);var result=g.callAction(true,false,"{}","task",1,x->{});assertThat(result.action().path("type").asText()).isEqualTo("report");assertThat(cap.failures.get()).isGreaterThan(0);
   var legacy=new SpringAiGateway("fixture-key",e.url(),ModelCaptureTest.MODEL);assertThat(legacy.configuration()).doesNotContainKey("actionProtocol");assertThat(legacy.actionPrompt(false,true)).isEqualTo(DirectContract.prompt(false,true));
   assertThatThrownBy(()->g.call("text","{}" )).hasMessage("EXPLICIT_ACTION_CAPABILITIES_REQUIRED");assertThat(e.requests).hasSize(1);
  }
 }
 @Test void changedProtocolResumeDoesNotSendAndKeepsHistoricalPrompt()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(report());var cap=ModelCapture.disabled()){
   var g=gateway(e,cap);h.calls.set(1);var state=Map.<String,Object>of("workflowContract",DirectContract.VERSION,"modelConfiguration",Map.of("adapterVersion","historical","promptVersion","historical-prompt"));
   var resumed=new Claim(h.claim.id(),h.claim.lease(),h.claim.request(),state,h.claim.deadline());
   var w=new DirectWorkflow(h.store,h.tools,g);try{w.run(resumed);}finally{w.close();}
   assertThat(e.requests).isEmpty();verify(h.store).finish(eq(resumed),eq("PARTIAL"),argThat(r->tree(r).at("/execution/promptVersion").asText().equals("historical-prompt")));
  }
 }
 @Test void sameProtocolCheckpointResumesWithoutRepeatingInitialQueries()throws Exception{
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA");var e=new Endpoint(report());var cap=ModelCapture.disabled()){
   var g=gateway(e,cap);h.calls.set(1);
   var resumed=new Claim(h.claim.id(),h.claim.lease(),h.claim.request(),Map.of("workflowContract",DirectContract.VERSION,"modelConfiguration",g.configuration(),"deliveredFactIds",List.of()),h.claim.deadline());
   when(h.store.active(resumed)).thenAnswer(i->h.active.get());when(h.store.reserve(resumed,"MODEL")).thenAnswer(i->h.calls.incrementAndGet()<=8);
   var w=new DirectWorkflow(h.store,h.tools,g);try{w.run(resumed);}finally{w.close();}
   assertThat(e.requests).hasSize(1);assertThat(h.calls.get()).isEqualTo(2);verifyNoInteractions(h.tools);
   verify(h.store).finish(eq(resumed),eq("COMPLETED"),argThat(r->tree(r).at("/execution/promptVersion").asText().equals(g.actionPromptVersion())));
  }
 }
}
