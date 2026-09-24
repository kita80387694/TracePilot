package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;import java.time.*;import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;import com.sun.net.httpserver.HttpServer;import java.net.*;
import org.junit.jupiter.api.Test;
/** Actual Workflow + Spring AI serialization + HTTP endpoint. Provider answers are explicit test doubles. */
class SingleReferenceReplayTest {
 @Test void realTwelveRowsRejectThenCorrectThroughActualWire()throws Exception{replay(false);}
 @Test void repeatedActualViolationStopsAfterOneCorrection()throws Exception{replay(true);}
 void replay(boolean failAgain)throws Exception{
  var f=new SingleReferenceTest().fixture();var rq=f.path("request");var req=new Request(rq.path("service").asText(),rq.path("environment").asText(),Instant.parse(rq.path("start").asText()),Instant.parse(rq.path("end").asText()),rq.path("symptom").asText());
  var es=new ArrayList<Evidence>();for(var e:f.path("evidence"))es.add(new Evidence(e.path("id").asText(),e.path("source").asText(),e.path("status").asText(),e.path("start").asText(),e.path("end").asText(),map(encode(e.path("locator"))),e.path("data")));
  var requests=Collections.synchronizedList(new ArrayList<byte[]>());var endpoint=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  endpoint.createContext("/v1/messages",ex->{byte[] body=ex.getRequestBody().readAllBytes();requests.add(body);var answer=f.path("rounds").get(3).path("action").deepCopy();int call=requests.size();
   if(call==2&&!failAgain){var h=(com.fasterxml.jackson.databind.node.ObjectNode)answer.path("assessments").get(0);h.remove("supportFactIds");SingleReferenceTest.wire(answer);}
   if(call==3)answer=tree(Map.of("type","report","report",Map.of("hypothesisIds",List.of("H1"),"checks",List.of("VERIFY_MECHANISM"))));
   byte[] response=encode(Map.of("id","fixture-"+call,"type","message","role","assistant","model",ModelCaptureTest.MODEL,"content",List.of(Map.of("type","text","text",encode(answer))),"stop_reason","end_turn","usage",Map.of("input_tokens",1,"output_tokens",1))).getBytes(StandardCharsets.UTF_8);
   ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(200,response.length);ex.getResponseBody().write(response);ex.close();});endpoint.start();
  var gateway=new SpringAiGateway("fixture-key","http://127.0.0.1:"+endpoint.getAddress().getPort(),ModelCaptureTest.MODEL);
  TaskStore store=mock(TaskStore.class);ReadTools tools=mock(ReadTools.class);var count=new AtomicInteger();var finalStatus=new AtomicReference<String>();var finalReport=new AtomicReference<Map<String,Object>>();var steps=Collections.synchronizedList(new ArrayList<String>());
  var claim=new Claim("replay","lease",req,Map.of("phase","REVIEW","workingHypotheses",tree(Hypotheses.seed(f.path("rounds").get(0).path("action").path("candidates")))),Instant.now().plusSeconds(180));
  when(store.active(claim)).thenReturn(true);when(store.evidence("replay")).thenReturn(es);when(store.reserve(claim,"MODEL")).thenAnswer(i->count.incrementAndGet()<=8);
  when(store.usage("replay")).thenAnswer(i->Map.of("model_calls",count.get(),"tool_calls",2,"max_models",8,"max_tools",12,"timeout_seconds",180));
  when(store.modelUsage("replay")).thenReturn(List.of());when(store.transportAccounting("replay")).thenReturn(Map.of());
  doAnswer(i->{steps.add(i.getArgument(1)+":"+i.getArgument(2));return null;}).when(store).step(eq(claim),anyString(),anyString(),anyMap());
  doAnswer(i->{finalStatus.set(i.getArgument(1));finalReport.set(i.getArgument(2));return null;}).when(store).finish(eq(claim),anyString(),anyMap());
  var workflow=new Workflow(store,tools,gateway);
  try{workflow.run(claim);}finally{workflow.close();endpoint.stop(0);}
  assertThat(finalStatus.get()).isEqualTo(failAgain?"PARTIAL":"COMPLETED");assertThat(requests).hasSize(failAgain?2:3);
  assertThat(steps).contains("ACTION:REJECTED","MODEL_TRANSPORT:REQUEST_PREPARED","MODEL_TRANSPORT:SEND_ATTEMPTED","MODEL_TRANSPORT:HTTP_RESPONSE_RECEIVED");
  for(byte[] body:requests){assertThat(body.length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);var wire=parse(new String(body,StandardCharsets.UTF_8));String text=wire.path("messages").get(0).path("content").get(0).path("text").asText();var input=parse(text);assertThat(text.length()).isLessThanOrEqualTo(100000);assertThat(input.path("causeFactRegistry").size()).isEqualTo(121);assertThat(input.toString()).doesNotContain("allowedCauseFacts\"");}
  if(!failAgain){assertThat(tree(finalReport.get()).path("timeComparisons")).isNotEmpty();assertThat(tree(finalReport.get()).path("schemaVersion").asText()).isEqualTo(ReviewContract.VERSION);assertThat(tree(finalReport.get()).path("modelPlan").toString()).doesNotContain("timelineNodeIds");}
  var correction=parse(new String(requests.get(1),StandardCharsets.UTF_8));assertThat(correction.toString()).contains("unknown top-level field forbidden");assertThat(correction.toString()).doesNotContain("failed before first empty query","50s gap");
  var sizes=new ArrayList<Object>();for(byte[] body:requests){var wire=parse(new String(body,StandardCharsets.UTF_8));var text=wire.path("messages").get(0).path("content").get(0).path("text").asText();sizes.add(Map.of("phase",parse(text).at("/checkpoint/phase").asText(),"contextUtf16",text.length(),"wireUtf8",body.length));}java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/single-reference-"+(failAgain?"rejected":"complete")+".json"),encode(sizes));
 }

}
