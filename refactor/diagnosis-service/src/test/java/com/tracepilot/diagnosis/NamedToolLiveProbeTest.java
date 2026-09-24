package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;
import java.nio.file.*;
import java.time.*;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.anthropic.*;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/** Explicit opt-in operator probe: synthetic inputs only, no workflow, diagnosis tools or database. */
@EnabledIfEnvironmentVariable(named="TRACEPILOT_PROTOCOL_PROBE_AUTHORIZED",matches="named-tools-v8-three-synthetic-calls")
class NamedToolLiveProbeTest {
 @Test void frozenThreeCaseProbe()throws Exception{
  Path out=Path.of(System.getenv("TRACEPILOT_PROTOCOL_PROBE_OUT"));Files.createDirectories(out);
  Files.writeString(out.resolve("started.guard"),Instant.now().toString(),StandardOpenOption.CREATE_NEW);
  String key=System.getenv().getOrDefault("DIAG_MODEL_KEY",System.getenv().getOrDefault("ANTHROPIC_API_KEY",""));
  if(key.isBlank())throw new IllegalStateException("MISSING_CONFIGURED_KEY_NO_CALL");
  String url=System.getenv().getOrDefault("DIAG_MODEL_URL",System.getenv().getOrDefault("ANTHROPIC_BASE_URL",""));
  if(!Set.of("https://api.siliconflow.cn","https://api.siliconflow.cn/").contains(url))throw new IllegalStateException("UNEXPECTED_DESTINATION_NO_CALL");
  var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());factory.setReadTimeout(Duration.ofSeconds(45));
  var records=new ArrayList<Map<String,Object>>();var transport=new ArrayList<Map<String,Object>>();
  var api=AnthropicApi.builder().baseUrl(url.replaceAll("/+$","")).apiKey(key).restClientBuilder(RestClient.builder().requestFactory(factory).requestInterceptor((req,body,execute)->{
   var sizes=RequestBoundary.wire(body);transport.add(Map.of("stage","PREPARED","sizes",sizes));
   Files.writeString(out.resolve("wire-"+records.size()+".json"),new String(body,java.nio.charset.StandardCharsets.UTF_8)); // synthetic body only, no headers
   transport.add(Map.of("stage","SEND_ATTEMPTED"));var response=execute.execute(req,body);transport.add(Map.of("stage","HTTP_RESPONSE","status",response.getStatusCode().value()));return response;
  })).build();
  String[] requests={
   "Synthetic protocol test, no diagnosis. Request the logs source, channel all, level ERROR, limit 20. Do not invent facts.",
   "Synthetic protocol test, no diagnosis. Evidence E_SYNTHETIC exists and page 1 is available. Read that local evidence page, not a new source query. Do not invent facts.",
   "Synthetic protocol test, no diagnosis. Both sources successfully returned no observations. Finish with no hypotheses and check COLLECT_LOGS. No evidence supports a cause."
  };
  try(var redactor=ModelCapture.disabled()){
   for(int i:new int[]{1,0,2}){
    boolean reportOnly=i==2;var record=new LinkedHashMap<String,Object>();record.put("case",i+1);record.put("synthetic",true);record.put("started",Instant.now().toString());record.put("inputTokens",null);record.put("outputTokens",null);transport.clear();
    try{
     var options=AnthropicChatOptions.builder().model("deepseek-ai/DeepSeek-V4-Flash").maxTokens(3000).temperature(0.0).thinking(AnthropicApi.ThinkingType.DISABLED,null).toolCallbacks(NamedToolProtocol.callbacks(reportOnly,true)).toolChoice(new AnthropicApi.ToolChoiceAuto(true)).internalToolExecutionEnabled(false).build();
     var model=AnthropicChatModel.builder().anthropicApi(api).defaultOptions(options).retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build()).build();
     var response=model.call(new Prompt(List.of(new SystemMessage("Read-only protocol verification. Select one available tool matching the requested operation and return arguments conforming to its input schema. No private thinking, no factual or causal claims."),new UserMessage(requests[i]))));
     var usage=response.getMetadata().getUsage();record.put("inputTokens",usage==null?null:usage.getPromptTokens());record.put("outputTokens",usage==null?null:usage.getCompletionTokens());record.put("model",response.getMetadata().getModel());
     record.put("texts",SpringAiGateway.answers(response).stream().map(g->redactor.bounded(g.getOutput().getText())).toList());
     record.put("toolCalls",response.getResults().stream().flatMap(g->g.getOutput().getToolCalls().stream()).map(c->Map.of("name",c.name(),"arguments",redactor.bounded(c.arguments()))).toList());
     record.put("finishReasons",response.getResults().stream().map(g->String.valueOf(g.getMetadata().getFinishReason())).toList());
     var decoded=NamedToolProtocol.decode(response,reportOnly,true);var action=decoded.action();record.put("action",action);var errors=decoded.errors();record.put("errors",errors);
     boolean expected=i==0?action.path("type").asText().equals("tool")&&action.path("tool").asText().equals("logs")&&action.at("/args/level").asText().equals("ERROR")&&action.at("/args/channel").asText().equals("all")&&action.at("/args/limit").asInt()==20:
      i==1?action.path("type").asText().equals("readEvidence")&&action.path("evidenceId").asText().equals("E_SYNTHETIC")&&action.path("page").asInt()==1:
      action.path("type").asText().equals("report")&&action.path("hypotheses").isEmpty()&&action.path("checks").equals(tree(List.of("COLLECT_LOGS")));
     record.put("status",errors.isEmpty()&&expected?"PASS":"FAIL");
    }catch(Exception e){record.put("status","FAIL");record.put("errorType",e.getClass().getSimpleName());} // no provider error text or credentials
    record.put("transport",List.copyOf(transport));record.put("ended",Instant.now().toString());record.put("fees","UNKNOWN");records.add(record);Files.writeString(out.resolve("results.json"),encode(records));
    if(!record.get("status").equals("PASS"))break;
   }
  }
 }
}
