package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
class ModelCompatibilityTest {
  @Test void realRetainedProbeTextIsNotSilentlyConvertedToJson() throws Exception {
    String text=new String(getClass().getResourceAsStream("/model/real-probe-text.txt").readAllBytes(),StandardCharsets.UTF_8);
    assertThat(text).contains("</think>");
    assertThat(SpringAiGateway.parseAnswer(text,"end_turn").path("type").asText()).isEqualTo("INVALID_JSON");
  }
  @Test void thinkingGenerationIsNotTheAnswerAndDisabledIsSerialized() throws Exception {
    // Actual observed envelope ordering; private thinking replaced, answer is a synthetic contract fixture.
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var request=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    var stage=new java.util.concurrent.atomic.AtomicReference<String>("candidates");
    server.createContext("/v1/messages", exchange->{
      request.set(JSON.readTree(exchange.getRequestBody()));
      byte[] body=encode(Map.of("id","fixture","type","message","role","assistant","model","deepseek-ai/DeepSeek-V4-Flash",
          "content",List.of(Map.of("type","thinking","thinking","REDACTED PRIVATE BLOCK","signature","fixture"),
              Map.of("type","text","text",encode(Map.of("type",stage.get())))),
          "stop_reason","end_turn","usage",Map.of("input_tokens",12,"output_tokens",20))).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
      exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    try {
      for(String phase:List.of("candidates","tool","ready","review","report")) {
      stage.set(phase);
      var reply=new SpringAiGateway("test-key","http://127.0.0.1:"+server.getAddress().getPort(),"deepseek-ai/DeepSeek-V4-Flash").call("Return JSON","fixture");
      assertThat(reply.action().path("type").asText()).isEqualTo(phase);
      assertThat(reply.protocol().get("ignoredBlocks")).isEqualTo(1);
      assertThat(encode(reply)).doesNotContain("REDACTED PRIVATE BLOCK");
      assertThat(request.get().at("/thinking/type").asText()).isEqualTo("disabled");
      assertThat(request.get().has("output_format")).isFalse();
      assertThat(request.get().has("response_format")).isFalse();
      assertThat(reply.outputTokens()).isEqualTo(20);
      assertThat(request.get().path("stream").asBoolean(false)).isFalse();
      }
    }finally{server.stop(0);}
  }
  @Test void exactFenceAllowedButTruncationTrailingObjectsAndDuplicatesRejected() {
    assertThat(SpringAiGateway.parseAnswer("```json\n{\"type\":\"ready\"}\n```","end_turn").path("type").asText()).isEqualTo("ready");
    for(String value:List.of("{\"type\":", "{\"type\":\"ready\"} prose", "{} {}", "{\"type\":1,\"type\":2}","[]"))
      assertThat(SpringAiGateway.parseAnswer(value,"end_turn").path("type").asText()).isEqualTo("INVALID_JSON");
    assertThat(SpringAiGateway.parseAnswer("{}","max_tokens").path("parseCategory").asText()).isEqualTo("OUTPUT_TRUNCATED");
  }
  @Test void reviewAndReportSyntheticSyntaxFixturesRemainStrict() {
    // Historical REVIEW/REPORT bodies were not retained. These are explicitly synthetic.
    for (String phase : List.of("review", "report")) {
      String valid = encode(Map.of("type",phase,"text","quote \" slash \\ newline\n tab\t 中文"));
      var parsed=SpringAiGateway.parseAnswer(valid,"end_turn");
      assertThat(parsed.path("type").asText()).isEqualTo(phase);
      assertThat(parsed.path("text").asText()).contains("\n","\t","\\");
      for(String broken:List.of(valid.substring(0,valid.length()-1),valid+" trailing text",valid+" {}",
          "{\"type\":\""+phase+"\",\"text\":\"raw\nnewline\"}","{\"type\":\""+phase+"\",}")) {
        assertThat(SpringAiGateway.parseAnswer(broken,"end_turn").path("type").asText()).isEqualTo("INVALID_JSON");
      }
      assertThat(SpringAiGateway.parseAnswer(valid,"max_tokens").path("parseCategory").asText()).isEqualTo("OUTPUT_TRUNCATED");
    }
  }
  List<Evidence> evidence=List.of(new Evidence("e1","metrics","AVAILABLE","start","end",Map.of(),tree(Map.of("data",List.of(Map.of("pending",0))))));
  ObjectNode valid(){return (ObjectNode)parse("""
      {"summary":"observed","facts":[{"text":"pending zero","evidenceId":"e1","pointer":"/data/0/pending","value":0}],"candidates":[{"cause":"candidate","inference":true,"confidence":"UNVERIFIED","evidenceIds":["e1"]}],"conflicts":[],"gaps":[],"actions":[]}
      """);}
  @Test void legalReportKeepsExactReferencesAndFields() {
    assertThat(Reports.validate(Reports.clean(valid()),evidence)).isEmpty();
  }
  @Test void missingFieldsWrongTypesAndEnumAreRejected() {
    var r=valid();r.remove("summary");assertThat(Reports.validate(Reports.clean(r),evidence)).contains("MISSING_SUMMARY");
    r=valid();r.put("gaps","none");assertThat(Reports.validate(Reports.clean(r),evidence)).contains("MISSING_gaps");
    r=valid();((ObjectNode)r.at("/candidates/0")).put("confidence","CERTAIN");assertThat(Reports.validate(Reports.clean(r),evidence)).contains("INVALID_CANDIDATE");
    r=valid();((ObjectNode)r.at("/candidates/0")).put("inference","true");assertThat(Reports.validate(Reports.clean(r),evidence)).contains("INVALID_CANDIDATE");
    r=valid();((ObjectNode)r.at("/facts/0")).remove("text");assertThat(Reports.validate(Reports.clean(r),evidence)).contains("INVALID_FACT_FIELDS");
  }
  @Test void nonexistentEvidenceAndWrongObservedValueAreRejected() {
    var r=valid();((ObjectNode)r.at("/facts/0")).put("evidenceId","invented");assertThat(Reports.validate(r,evidence)).contains("UNKNOWN_REFERENCE");
    r=valid();((ObjectNode)r.at("/facts/0")).put("value",99);assertThat(Reports.validate(r,evidence)).contains("FACT_VALUE_MISMATCH");
  }
  @Test void legalInsufficientEvidenceReportIsNotFormatFailure() {
    var r=valid();r.putArray("facts");r.putArray("candidates");r.putArray("gaps").add("metrics unavailable in requested window");
    var absent=List.of(new Evidence("e1","metrics","UNAVAILABLE","start","end",Map.of(),tree(Map.of())));
    assertThat(Reports.validate(r,absent)).isEmpty();assertThat(Reports.hasWindowData(absent)).isFalse();
  }
}
