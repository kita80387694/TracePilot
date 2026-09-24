package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** Full workflow + actual adapter serializer + loopback HTTP. Responses are scripted, not model results. */
class EvidencePagingHttpReplayTest {
 @Test void actualQueryPagesCorrectionAndReportFitEveryFinalSerializedRequest()throws Exception{
  var fixture=new BoundedEvidenceDeliveryTest();var h=fixture.harness();var log=h.evidence.removeLast();
  when(h.store.contractRejections(h.claim.id())).thenAnswer(i->(int)h.steps.stream().filter(s->s.equals("ACTION:REJECTED")).count());
  when(h.tools.query(any(),any(),any())).thenReturn(result(log.status(),log.data(),log.locator()));
  var wires=new ArrayList<byte[]>();var inputs=new ArrayList<JsonNode>();var pagesRead=new HashSet<String>();var seen=new LinkedHashSet<String>();var retain=new LinkedHashSet<String>();
  var corrected=new AtomicBoolean();var errors=new AtomicReference<Throwable>();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v1/messages",exchange->{try{
   byte[] bytes=exchange.getRequestBody().readAllBytes();wires.add(bytes);var wire=parse(new String(bytes,StandardCharsets.UTF_8));
   String text=wire.path("messages").get(0).path("content").get(0).path("text").asText();var input=parse(text);inputs.add(input);
   assertThat(bytes.length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);assertThat(text.length()).isLessThanOrEqualTo(RequestBoundary.MAX_CONTEXT_UTF16);
   assertThat(input.at("/contextBudget/actual/evidence").asInt()).isLessThanOrEqualTo(70000);
   for(String key:List.of("causeFactRegistry","backgroundFactRegistry"))for(var f:input.path(key))seen.add(f.path("factId").asText());
   for(var raw:ContextPacking.expand(input,"causeFactRegistry")){var fact=tree(raw);if(fact.path("pointer").asText().endsWith("/message")&&Set.of("event_delivery_failed","notification_write_committed").contains(fact.path("value").asText()))retain.add(fact.path("factId").asText());}
   var delivery=input.path("evidenceDelivery");var current=delivery.path("currentPage");if(current.has("evidenceId"))pagesRead.add(current.path("evidenceId").asText()+":"+current.path("page").asInt());
   if(delivery.path("mode").asText().equals("ALL"))for(var source:delivery.path("sources"))for(int p=0;p<source.path("pageCount").asInt();p++)pagesRead.add(source.path("evidenceId").asText()+":"+p);
   JsonNode action=null;
   if(inputs.size()==1)action=tree(Map.of("type","tool","tool","logs","args",Map.of()));
   else if(inputs.size()==2){var logSource=java.util.stream.StreamSupport.stream(delivery.path("sources").spliterator(),false).filter(s->s.path("source").asText().equals("logs")).findFirst().orElseThrow();action=fixture.read(logSource.path("evidenceId").asText(),0,new ArrayList<>(retain));}
   else for(var source:delivery.path("sources")){for(int p=0;p<source.path("pageCount").asInt();p++)if(!pagesRead.contains(source.path("evidenceId").asText()+":"+p)){action=fixture.read(source.path("evidenceId").asText(),p,new ArrayList<>(retain));break;}if(action!=null)break;}
   if(action==null){
    assertThat(retain).hasSize(2);var selected=new ArrayList<>(retain); // Fixture order: later commit appears before earlier failed delivery in descending logs.
    action=tree(Map.of("type","report","hypotheses",List.of(Map.of("relation","MAY_EXPLAIN","premiseFactIds",List.of(selected.get(1)),"outcomeFactIds",List.of(selected.get(0)),"contradictionFactIds",List.of(),"mechanism","消费失败后出现后续处理；更深原因尚待核对。")),"checks",List.of("VERIFY_MECHANISM")));
    if(!corrected.getAndSet(true))((com.fasterxml.jackson.databind.node.ObjectNode)action.path("hypotheses").get(0)).remove("mechanism");
   }
   byte[] response=encode(Map.of("id","local-replay","type","message","role","assistant","model",ModelCaptureTest.MODEL,"content",List.of(Map.of("type","text","text",encode(action))),"stop_reason","end_turn","usage",Map.of("input_tokens",1,"output_tokens",1))).getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
  }catch(Throwable error){errors.set(error);exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}});server.start();
  var gateway=new SpringAiGateway("LOCAL_ONLY","http://127.0.0.1:"+server.getAddress().getPort(),ModelCaptureTest.MODEL);var workflow=new DirectWorkflow(h.store,h.tools,gateway);
  try{workflow.run(h.claim);}finally{workflow.close();h.close();server.stop(0);}
  assertThat(errors.get()).isNull();assertThat(h.status.get()).isEqualTo("COMPLETED");assertThat(wires.size()).isBetween(4,8);assertThat(h.calls.get()).isEqualTo(wires.size());
  verify(h.tools,times(1)).query(any(),any(),any());assertThat(h.toolCalls.get()).isEqualTo(1+(int)h.steps.stream().filter(s->s.equals("EVIDENCE_READ:SELECTED")).count());
  assertThat(h.steps.stream().filter(s->s.equals("ACTION:REJECTED")).count()).isEqualTo(1);
  assertThat(inputs.getLast().at("/checkpoint/correction/details").toString()).contains("MISSING_FIELD","/mechanism");
  assertThat(tree(h.report.get()).at("/evidenceDelivery/unreadRegisteredFactCount").asInt()).isZero();
  assertThat(tree(h.report.get()).at("/contractOutcome/outcome").asText()).isEqualTo("AFTER_ONE_CORRECTION");
  assertThat(tree(h.report.get()).at("/candidates/0/mechanism").asText()).isEqualTo("消费失败后出现后续处理；更深原因尚待核对。");
  for(String pin:retain)assertThat(inputs.getLast().path("causeFactRegistry").findValuesAsText("factId")).contains(pin);
  var rounds=new ArrayList<Object>();for(int i=0;i<wires.size();i++)rounds.add(Map.of("round",i+1,"wireUtf8Bytes",wires.get(i).length,"contextUtf16",parse(new String(wires.get(i),StandardCharsets.UTF_8)).at("/messages/0/content/0/text").asText().length(),"delivery",inputs.get(i).path("evidenceDelivery")));
  Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay/evidence-http-rounds.json"),encode(Map.of("origin","LOOPBACK_HTTP_SYNTHETIC_RESPONSES_NOT_MODEL_VALIDATION","rounds",rounds,"toolBudget",h.toolCalls.get(),"modelReservations",h.calls.get(),"confirmedFacts",seen.size(),"report",h.report.get())));
 }
}
