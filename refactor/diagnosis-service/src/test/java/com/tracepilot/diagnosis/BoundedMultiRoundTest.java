package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;import java.time.*;import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;import com.sun.net.httpserver.HttpServer;import java.net.*;
import org.junit.jupiter.api.Test;
/** Actual Workflow + Spring AI serialization + HTTP endpoint. Provider answers are explicit test doubles. */
class BoundedMultiRoundTest {
 @Test void rejectAliasThenReuseAndCompleteAllStages()throws Exception{replay(false);}
 @Test void aliasAgainStopsWithoutPartialHypothesisState()throws Exception{replay(true);}
 void replay(boolean failAgain)throws Exception{
  var f=new EvidenceDeliveryFailureTest().fixture();var rq=f.path("request");var req=new Request(rq.path("service").asText(),rq.path("environment").asText(),Instant.parse(rq.path("start").asText()),Instant.parse(rq.path("end").asText()),rq.path("symptom").asText());
  var es=new ArrayList<Evidence>();for(var e:f.path("evidence"))es.add(new Evidence(e.path("id").asText(),e.path("source").asText(),e.path("status").asText(),e.path("start").asText(),e.path("end").asText(),map(encode(e.path("locator"))),e.path("data")));
  var raw=parse(new String(getClass().getResourceAsStream("/model/v881-actions.json").readAllBytes(),StandardCharsets.UTF_8));
  var requests=Collections.synchronizedList(new ArrayList<byte[]>());var endpoint=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  endpoint.createContext("/v1/messages",ex->{byte[] body=ex.getRequestBody().readAllBytes();requests.add(body);var answer=f.path("review").deepCopy();int call=requests.size();
   if(call==1 || call==2&&failAgain)answer=raw.get(0).path("action");
   else if(call==2){var candidates=new ArrayList<Object>();var adapted=f.path("review").deepCopy();SingleReferenceTest.wire(adapted);for(var h:adapted.path("assessments"))candidates.add(h.path("plan").isNull()?tree(Map.of("relation","MAY_EXPLAIN","premiseFactIds",List.of(),"outcomeFactIds",List.of(),"contradictionFactIds",List.of(),"mechanism",JSON.nullNode())):h.path("plan"));answer=tree(Map.of("type","candidates","candidates",candidates));}
   else if(call==3)answer=raw.get(1).path("action");
   else if(call==4){answer=f.path("review").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)answer.path("assessments").get(0)).remove("supportFactIds");SingleReferenceTest.wire(answer);}
   else answer=tree(Map.of("type","report","report",Map.of("hypothesisIds",List.of("H1"),"checks",List.of("VERIFY_MECHANISM"))));
   byte[] response=encode(Map.of("id","fixture-"+call,"type","message","role","assistant","model",ModelCaptureTest.MODEL,"content",List.of(Map.of("type","text","text",encode(answer))),"stop_reason","end_turn","usage",Map.of("input_tokens",1,"output_tokens",1))).getBytes(StandardCharsets.UTF_8);
   ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(200,response.length);ex.getResponseBody().write(response);ex.close();});endpoint.start();
  var gateway=new SpringAiGateway("fixture-key","http://127.0.0.1:"+endpoint.getAddress().getPort(),ModelCaptureTest.MODEL);
  TaskStore store=mock(TaskStore.class);ReadTools tools=mock(ReadTools.class);var count=new AtomicInteger();var finalStatus=new AtomicReference<String>();var steps=Collections.synchronizedList(new ArrayList<String>());
  var claim=new Claim("replay","lease",req,Map.of("phase","CANDIDATES"),Instant.now().plusSeconds(180));
  when(store.active(claim)).thenReturn(true);when(store.evidence("replay")).thenReturn(es);when(store.reserve(claim,"MODEL")).thenAnswer(i->count.incrementAndGet()<=8);
  when(store.usage("replay")).thenAnswer(i->Map.of("model_calls",count.get(),"tool_calls",2,"max_models",8,"max_tools",12,"timeout_seconds",180));
  when(store.modelUsage("replay")).thenReturn(List.of());when(store.transportAccounting("replay")).thenReturn(Map.of());
  doAnswer(i->{steps.add(i.getArgument(1)+":"+i.getArgument(2));return null;}).when(store).step(eq(claim),anyString(),anyString(),anyMap());
  doAnswer(i->{finalStatus.set(i.getArgument(1));java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/debug-BoundedMultiRoundTest.java.json"),encode(Map.of("finish",(Object)i.getArgument(2),"steps",steps)));return null;}).when(store).finish(eq(claim),anyString(),anyMap());
  var workflow=new Workflow(store,tools,gateway);
  try{workflow.run(claim);}finally{workflow.close();endpoint.stop(0);}
  assertThat(finalStatus.get()).isEqualTo(failAgain?"PARTIAL":"COMPLETED");assertThat(requests).hasSize(failAgain?2:5);
  assertThat(steps).contains("ACTION:REJECTED","MODEL_TRANSPORT:REQUEST_PREPARED","MODEL_TRANSPORT:SEND_ATTEMPTED","MODEL_TRANSPORT:HTTP_RESPONSE_RECEIVED");
  verifyNoInteractions(tools);assertThat(count.get()).isEqualTo(requests.size());verify(store,never()).evidence(eq(claim),anyString(),any(Result.class));
  var sizes=new ArrayList<Object>();int round=0;
  for(byte[] body:requests){assertThat(body.length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);var wire=parse(new String(body,StandardCharsets.UTF_8));String text=wire.path("messages").get(0).path("content").get(0).path("text").asText();var input=parse(text);sizes.add(Map.of("round",++round,"phase",input.path("checkpoint").path("phase").asText(),"contextUtf16",text.length(),"wireUtf8",body.length,"causeFacts",input.path("causeFactRegistry").size()));if(round<=2)assertThat(input.path("workingHypotheses")).isEmpty();else if(round<=4)for(var h:input.path("workingHypotheses"))assertThat(h.path("initialPlan").isObject()).isTrue();assertThat(text.length()).isLessThanOrEqualTo(100000);assertThat(input.path("causeFactRegistry").size()).isEqualTo(121);assertThat(input.toString()).doesNotContain("allowedCauseFacts\"");}
  if(!failAgain)assertThat(steps).contains("QUERY:REUSED");
  java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/"+(failAgain?"alias-failed":"complete")+".json"),encode(sizes));
  var correction=parse(new String(requests.get(1),StandardCharsets.UTF_8));assertThat(correction.toString()).contains("hypothesisText","/candidates/0/hypothesis");
 }
}
