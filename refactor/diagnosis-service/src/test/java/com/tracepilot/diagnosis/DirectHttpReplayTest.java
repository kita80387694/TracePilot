package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.net.*;import java.time.*;
import com.sun.net.httpserver.HttpServer;import org.junit.jupiter.api.Test;

/** Real serializer, local HTTP endpoint, full archived twelve-log evidence. Synthetic responses. */
class DirectHttpReplayTest {
 @Test void fullRequestEvidenceCorrectionAndRenderedReportRemainBounded()throws Exception{
  var f=new ContractBoundaryTest();var h=new DirectWorkflowTest().new Harness("NO_DATA");h.evidence.clear();h.evidence.addAll(f.evidence());
  var requests=new ArrayList<byte[]>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var good=new DirectContractTest().report();var bad=good.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("hypotheses").get(0)).putArray("premiseFactIds").add("Funknown");
  server.createContext("/v1/messages",exchange->{requests.add(exchange.getRequestBody().readAllBytes());var action=requests.size()==1?bad:good;
   byte[] response=encode(Map.of("id","local-fixture","type","message","role","assistant","model",ModelCaptureTest.MODEL,"content",List.of(Map.of("type","text","text",encode(action))),"stop_reason","end_turn","usage",Map.of("input_tokens",1,"output_tokens",1))).getBytes(StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
  var gateway=new SpringAiGateway("local-fixture-key","http://127.0.0.1:"+server.getAddress().getPort(),ModelCaptureTest.MODEL);var workflow=new DirectWorkflow(h.store,h.tools,gateway);
  try{workflow.run(h.claim);}finally{workflow.close();h.close();server.stop(0);}
  assertThat(h.status.get()).isEqualTo("COMPLETED");assertThat(requests).hasSize(2);assertThat(h.calls.get()).isEqualTo(2);
  assertThat(h.steps).contains("ACTION:REJECTED","MODEL_TRANSPORT:SEND_ATTEMPTED","MODEL_TRANSPORT:HTTP_RESPONSE_RECEIVED");
  var sizes=new ArrayList<Object>();Set<String> ids=null;
  for(byte[] bytes:requests){assertThat(bytes.length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);var wire=parse(new String(bytes,StandardCharsets.UTF_8));String text=wire.path("messages").get(0).path("content").get(0).path("text").asText();var input=parse(text);
   assertThat(text.length()).isLessThanOrEqualTo(100000);assertThat(input.path("causeFactRegistry")).hasSize(121);assertThat(input.has("workingHypotheses")).isFalse();
   var current=new HashSet<String>();input.path("causeFactRegistry").forEach(n->current.add(n.path("factId").asText()));if(ids!=null)assertThat(current).isEqualTo(ids);ids=current;
   sizes.add(Map.of("contextUtf16",text.length(),"wireUtf8",bytes.length,"registeredCauseFacts",current.size()));
  }
  assertThat(new String(requests.get(1),StandardCharsets.UTF_8)).contains("UNKNOWN_OR_UNAUTHORIZED_FACT");
  Path dir=Path.of("target/replay");Files.createDirectories(dir);Files.writeString(dir.resolve("direct-http-sizes.json"),encode(Map.of("origin","LOCAL_TEST_DOUBLE_NOT_MODEL_VALIDATION","rounds",sizes)));
  Files.writeString(dir.resolve("direct-http-report.json"),encode(h.report.get()));
 }
}
