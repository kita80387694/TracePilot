package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.*;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;

class NamedToolProtocolTest {
 @Test void actualCaseTwoWrongSelectionIsPreservedNotRepaired()throws Exception{
  var actual=JSON.readTree(getClass().getResourceAsStream("/native-v7-case2.json")).path("record").path("action");
  var args=actual.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)args).remove(List.of("type","tool"));
  var decoded=NamedToolProtocol.decode("query_"+actual.path("tool").asText(),encode(args),false,true);
  assertThat(decoded.errors()).isEmpty();assertThat(decoded.action()).isEqualTo(actual);
  assertThat(decoded.action().path("type").asText()).isNotEqualTo("readEvidence");
 }
 @Test void toolDefinitionsDeriveFromSameContractWithoutRepeatedDiscriminators(){
  var defs=NamedToolProtocol.definitions(false,true);assertThat(defs).hasSize(9);
  for(var d:defs){assertThat(d.schema().has("oneOf")).isFalse();assertThat(d.schema().at("/properties/type").isMissingNode()).isTrue();assertThat(d.schema().at("/properties/tool").isMissingNode()).isTrue();assertThat(d.schema().path("additionalProperties").asBoolean()).isFalse();}
  assertThat(defs.stream().map(NamedToolProtocol.Definition::name)).contains("query_logs","read_evidence_page","submit_report");
 }
 @Test void readAndQueryHaveDistinctDeterministicMappings(){
  var read=NamedToolProtocol.decode("read_evidence_page","{\"evidenceId\":\"E_SYNTHETIC\",\"page\":1}",false,true);
  assertThat(read.errors()).isEmpty();assertThat(read.action()).isEqualTo(tree(Map.of("type","readEvidence","evidenceId","E_SYNTHETIC","page",1)));
  var query=NamedToolProtocol.decode("query_logs","{\"args\":{\"cursor\":null,\"limit\":20},\"retainFactIds\":[]}",false,true);
  assertThat(query.errors()).isEmpty();assertThat(query.action().path("type").asText()).isEqualTo("tool");assertThat(query.action().path("tool").asText()).isEqualTo("logs");
  // The real case-2 wrong choice stays a query, never silently becomes an evidence read.
  assertThat(query.action()).isNotEqualTo(read.action());
 }
 @Test void oldWrapperAndConflictingDiscriminatorsAreRejected(){
  for(String body:List.of("{\"action\":{\"type\":\"readEvidence\",\"evidenceId\":\"E1\",\"page\":1}}","{\"type\":\"readEvidence\",\"evidenceId\":\"E1\",\"page\":1}","{\"tool\":\"logs\",\"evidenceId\":\"E1\",\"page\":1}")){
   var r=NamedToolProtocol.decode("read_evidence_page",body,false,true);assertThat(r.errors().toString()).contains("UNEXPECTED_FIELD");assertThat(r.action().isNull()).isTrue();
  }
 }
 @Test void missingTypesRangesAndBadJsonStillFail(){
  for(String body:List.of("{}","{\"evidenceId\":\"E1\",\"page\":\"1\"}","{\"evidenceId\":\"E1\",\"page\":-1}","{\"evidenceId\":\"\",\"page\":0}","{\"page\":", "{} {}"))assertThat(NamedToolProtocol.decode("read_evidence_page",body,false,true).errors()).isNotEmpty();
 }
 @Test void unknownAndPhaseForbiddenToolsAreRejected(){
  for(String name:List.of("readEvidence","submit_diagnostic_action","execute_sql","fault_control"))assertThat(NamedToolProtocol.decode(name,"{}",false,true).errors().toString()).contains("UNKNOWN_OR_DISALLOWED_NATIVE_TOOL");
  assertThat(NamedToolProtocol.definitions(true,true).stream().map(NamedToolProtocol.Definition::name)).containsExactly("submit_report");
  assertThat(NamedToolProtocol.definitions(false,false).stream().map(NamedToolProtocol.Definition::name)).containsExactly("read_evidence_page","submit_report");
  assertThat(NamedToolProtocol.decode("query_logs","{\"args\":{}}",false,false).action().isNull()).isTrue();
 }
 @Test void sourceParameterPolicyRemainsStrict(){
  for(String args:List.of("{\"url\":\"http://example.invalid\"}","{\"limit\":999}","{\"level\":\"DEBUG\"}"))assertThat(NamedToolProtocol.decode("query_logs","{\"args\":"+args+"}",false,true).errors()).isNotEmpty();
 }
 @Test void reportMechanismAndUnknownReferencesUseUnchangedRenderer()throws Exception{
  var f=new DirectContractTest();var report=f.report();var args=report.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)args).remove("type");
  var r=NamedToolProtocol.decode("submit_report",encode(args),true,false);assertThat(r.errors()).isEmpty();assertThat(r.action()).isEqualTo(report);
  var data=new ContractBoundaryTest();assertThat(DirectContract.render(r.action(),data.evidence(),data.request()).errors()).isEmpty();
  ((com.fasterxml.jackson.databind.node.ObjectNode)args.path("hypotheses").get(0)).set("premiseFactIds",tree(List.of("F000000000000000000000000")));
  r=NamedToolProtocol.decode("submit_report",encode(args),true,false);assertThat(r.errors()).isEmpty();assertThat(DirectContract.render(r.action(),data.evidence(),data.request()).errors().toString()).contains("UNKNOWN_OR_UNAUTHORIZED_FACT");
 }
 @Test void emptyCandidatesRemainLegal(){
  var r=NamedToolProtocol.decode("submit_report","{\"hypotheses\":[],\"checks\":[\"COLLECT_LOGS\"]}",true,false);assertThat(r.errors()).isEmpty();assertThat(r.action().path("hypotheses")).isEmpty();
 }
 @Test void callbacksNeverExecuteBusinessOperations(){for(var c:NamedToolProtocol.callbacks(false,true))assertThatThrownBy(()->c.call("{}")).hasMessage("AUTOMATIC_DIAGNOSTIC_EXECUTION_FORBIDDEN");}
 @Test void actualSdkWireExposesIndependentNamesAndSingleToolResult()throws Exception{
  var wire=new AtomicReference<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v1/messages",e->{wire.set(JSON.readTree(e.getRequestBody()));byte[] b=encode(Map.of("id","fixture","type","message","role","assistant","model","deepseek-ai/DeepSeek-V4-Flash","content",List.of(Map.of("type","tool_use","id","c1","name","read_evidence_page","input",Map.of("evidenceId","E_SYNTHETIC","page",1))),"stop_reason","tool_use","usage",Map.of("input_tokens",1,"output_tokens",1))).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
  try{
   var options=AnthropicChatOptions.builder().model("deepseek-ai/DeepSeek-V4-Flash").maxTokens(3000).temperature(0.0).thinking(AnthropicApi.ThinkingType.DISABLED,null).toolCallbacks(NamedToolProtocol.callbacks(false,true)).toolChoice(new AnthropicApi.ToolChoiceAuto(true)).internalToolExecutionEnabled(false).build();
   var model=AnthropicChatModel.builder().anthropicApi(AnthropicApi.builder().apiKey("LOCAL_ONLY").baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build()).defaultOptions(options).retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build()).build();
   var response=model.call(new Prompt(List.of(new UserMessage("Evidence E_SYNTHETIC exists and page 1 is available. Read that local evidence page, not a new source query."))));
   assertThat(wire.get().path("tools")).hasSize(9);assertThat(wire.get().at("/tool_choice/type").asText()).isEqualTo("auto");assertThat(wire.get().at("/tool_choice/disable_parallel_tool_use").asBoolean()).isTrue();
   for(var t:wire.get().path("tools")){assertThat(t.path("input_schema").has("oneOf")).isFalse();assertThat(t.at("/input_schema/properties/action").isMissingNode()).isTrue();}
   assertThat(NamedToolProtocol.decode(response,false,true).action().path("type").asText()).isEqualTo("readEvidence");
  }finally{server.stop(0);}
 }
}
