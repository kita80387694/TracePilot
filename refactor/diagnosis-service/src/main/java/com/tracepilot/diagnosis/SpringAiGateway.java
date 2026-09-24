package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.anthropic.*;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SpringAiGateway implements ActionModelGateway {
  static final String ADAPTER_VERSION = "deepseek-causal-boundary-v8.7-offline";
  private final AnthropicChatModel model;
  private final java.util.Map<String,Object> configuration;
  private final ModelCapture capture;
  private final boolean namedTools;
  private final boolean boundedTools;
  private final ThreadLocal<java.util.function.Consumer<java.util.Map<String,Object>>> transportObserver=new ThreadLocal<>();
  private void transport(java.util.Map<String,Object> data){var observer=transportObserver.get();if(observer!=null)observer.accept(data);}

  @org.springframework.beans.factory.annotation.Autowired
  public SpringAiGateway(
      @Value("${diag.model-key}") String key,
      @Value("${diag.model-url}") String url,
      @Value("${diag.model-name}") String name,
      @Value("${diag.capture-dir:}") String captureDir,
      @Value("${diag.action-protocol:text-json}") String protocol) {
    this(key,url,name,new ModelCapture(captureDir.isBlank()?null:java.nio.file.Path.of(captureDir),key),protocol);
  }
  public SpringAiGateway(String key,String url,String name){this(key,url,name,ModelCapture.disabled());}
  SpringAiGateway(String key,String url,String name,ModelCapture capture) {this(key,url,name,capture,"text-json");}
  SpringAiGateway(String key,String url,String name,ModelCapture capture,String protocol) {
    if(!java.util.Set.of("text-json","named-tools-v1","named-tools-v2").contains(protocol))throw new IllegalArgumentException("UNKNOWN_ACTION_PROTOCOL");
    namedTools=!protocol.equals("text-json");
    boundedTools=protocol.equals("named-tools-v2");
    this.capture=capture;
    var config = new java.util.LinkedHashMap<String,Object>(java.util.Map.of("adapterVersion", ADAPTER_VERSION,"maintenanceVersion","refactor-v2.2-correction-id-offline", "requestedModel", name,
        "thinking", name.equals("deepseek-ai/DeepSeek-V4-Flash") ? "disabled" : "provider-default",
        "captureStopOnError", Boolean.parseBoolean(System.getenv().getOrDefault("DIAG_CAPTURE_STOP_ON_ERROR","false")), "maxOutputTokens", 3000, "temperature", 0.0, "protocol", "anthropic-messages"));
    if(namedTools){config.put("actionProtocol",boundedTools?BoundedActions.VERSION:NamedToolProtocol.VERSION);config.put("promptVersion",actionPromptVersion());config.put("maintenanceVersion",boundedTools?"report-metadata-v37":"named-tools-integration-v9");config.put("toolSchemaVersion",NamedToolProtocol.SCOPE_VERSION);config.put("reportContractVersion",DirectContract.VERSION);config.put("resourcePolicy",ReviewContract.VERSION);config.put("causalCheckVersion",CausalLink.VERSION);}
    configuration=java.util.Map.copyOf(config);
    if (key.isBlank() || name.isBlank()) {
      model = null;
      return;
    }
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(45));
    var api =
        AnthropicApi.builder()
            .baseUrl(url.replaceAll("/+$", ""))
            .apiKey(key)
            .restClientBuilder(RestClient.builder().requestFactory(factory).requestInterceptor((request,body,execution)->{
              var sizes=RequestBoundary.wire(body);
              transport(java.util.Map.of("status","REQUEST_PREPARED","sizes",sizes,"wireProtocol",RequestBoundary.protocolMetadata(body)));
              transport(java.util.Map.of("status","SEND_ATTEMPTED","upstreamReceipt","UNKNOWN_UNTIL_RESPONSE"));
              var response=execution.execute(request,body);
              transport(java.util.Map.of("status","HTTP_RESPONSE_RECEIVED","httpStatus",response.getStatusCode().value()));return response;
            }))
            .build();
    var options = AnthropicChatOptions.builder().model(name).maxTokens(3000).temperature(0.0)
        .internalToolExecutionEnabled(false);
    if (name.equals("deepseek-ai/DeepSeek-V4-Flash"))
      options.thinking(AnthropicApi.ThinkingType.DISABLED, null);
    model =
        AnthropicChatModel.builder()
            .anthropicApi(api)
            .defaultOptions(options.build())
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build())
            .build();
  }

  public boolean configured() {
    return model != null;
  }

  public ModelReply call(String system, String user) {
    return call(system,user,"UNASSOCIATED",0);
  }
  @Override public ModelReply call(String system,String user,String taskId,int ordinal) {
    if(namedTools)throw new IllegalStateException("EXPLICIT_ACTION_CAPABILITIES_REQUIRED");
    if (model == null) throw new IllegalStateException("MODEL_NOT_CONFIGURED");
    String receipt=capture.begin(taskId,ordinal,system,user,configuration);
    try {
    var response =
        model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
    capture.response(receipt,response);
    return decode(response, configuration);
    }catch(Exception e){capture.error(receipt,e);throw e;}
  }
  @Override public ModelReply call(String system,String user,String taskId,int ordinal,java.util.function.Consumer<java.util.Map<String,Object>> observer){
    transportObserver.set(observer);try{return call(system,user,taskId,ordinal);}finally{transportObserver.remove();}
  }

  @Override public String actionPromptVersion(){return boundedTools?"no-progress-v13-offline":namedTools?"named-tools-v9-integration-offline":PROMPT_VERSION;}
  @Override public String actionPrompt(boolean reportOnly,boolean queryAllowed){
    return namedTools?DirectContract.guidance(reportOnly).replace("readEvidence","read_evidence_page")+(boundedTools?"Select up to four independent read-only tools; they execute serially within the unchanged task budget. All calls are validated before execution. Reports must be submitted alone. Selected evidence pages are combined only within existing context limits. ":"Select exactly one available native tool. ")+"Its name determines the operation; return only its declared arguments. Do not repeat action, type or tool discriminators inside arguments. Accompanying text is not used as a diagnostic action.":DirectContract.prompt(reportOnly,queryAllowed);
  }
  @Override public ModelReply callAction(boolean reportOnly,boolean queryAllowed,String input,String taskId,int ordinal,java.util.function.Consumer<java.util.Map<String,Object>> observer){
    if(!namedTools)return call(actionPrompt(reportOnly,queryAllowed),input,taskId,ordinal,observer);
    if(model==null)throw new IllegalStateException("MODEL_NOT_CONFIGURED");
    var definitions=NamedToolProtocol.offered(reportOnly,queryAllowed,input);
    var options=AnthropicChatOptions.builder().toolCallbacks(NamedToolProtocol.callbacks(definitions)).toolChoice(new AnthropicApi.ToolChoiceAuto(true)).internalToolExecutionEnabled(false).build();
    var details=new java.util.LinkedHashMap<String,Object>(configuration);
    details.put("nativeTools",definitions.stream().map(d->java.util.Map.of("name",d.name(),"description",d.description(),"input_schema",d.schema())).toList());
    details.put("toolChoice",java.util.Map.of("type","auto","disable_parallel_tool_use",true));
    details.put("reportOnly",reportOnly);details.put("queryAllowed",queryAllowed);
    String system=actionPrompt(reportOnly,queryAllowed);String receipt=capture.begin(taskId,ordinal,system,input,details);
    transportObserver.set(observer);
    try{
      var response=model.call(new Prompt(List.of(new SystemMessage(system),new UserMessage(input)),options));
      capture.response(receipt,response);
      var decoded=boundedTools?BoundedActions.decode(response,reportOnly,queryAllowed):NamedToolProtocol.decode(response,reportOnly,queryAllowed);
      if(decoded.errors().isEmpty()){var errors=NamedToolProtocol.offeredErrors(response,definitions);if(!errors.isEmpty())decoded=new NamedToolProtocol.Decoded(JSON.nullNode(),errors);}
      var meta=new java.util.LinkedHashMap<String,Object>(configuration);
      meta.put("finishReason",response.getResults().isEmpty()?"UNKNOWN":String.valueOf(response.getResults().get(0).getMetadata().getFinishReason()));
      meta.put("stream",false);meta.put("parseCategory",decoded.errors().isEmpty()?"VALID_NATIVE_ACTION":"INVALID_NATIVE_ACTION");
      meta.put("reportOnly",reportOnly);meta.put("queryAllowed",queryAllowed);
      var action=decoded.errors().isEmpty()?decoded.action():tree(java.util.Map.of("type","INVALID_NATIVE_ACTION","errors",decoded.errors()));
      var usage=response.getMetadata().getUsage();return new ModelReply(action,response.getMetadata().getModel(),usage==null?null:usage.getPromptTokens(),usage==null?null:usage.getCompletionTokens(),meta);
    }catch(Exception e){capture.error(receipt,e);throw e;}finally{transportObserver.remove();}
  }

  static java.util.List<org.springframework.ai.chat.model.Generation> answers(org.springframework.ai.chat.model.ChatResponse response){
    return response.getResults().stream().filter(g -> !g.getOutput().getMetadata().containsKey("signature")
        && !g.getOutput().getMetadata().containsKey("data") && g.getOutput().getToolCalls().isEmpty()
        && g.getOutput().getText()!=null).toList();
  }

  @Override public java.util.Map<String,Object> configuration() { return configuration; }

  static ModelReply decode(org.springframework.ai.chat.model.ChatResponse response,
      java.util.Map<String,Object> configuration) {
    // Spring AI 1.1.8 exposes thinking as a Generation with a signature property.
    // Never parse or persist that block, even if the provider ignores thinking=disabled.
    var answers = answers(response);
    String finish = response.getResults().isEmpty() ? "UNKNOWN"
        : String.valueOf(response.getResults().get(0).getMetadata().getFinishReason());
    String text = answers.size()==1 ? answers.get(0).getOutput().getText() : "";
    var metadata = new java.util.LinkedHashMap<String,Object>(configuration);
    metadata.put("finishReason", finish);
    metadata.put("stream", false);
    metadata.put("providerResponseId", response.getMetadata().getId()==null?"UNKNOWN":response.getMetadata().getId());
    metadata.put("responseCharacters", text.length());
    metadata.put("textBlocks", answers.size());
    metadata.put("ignoredBlocks", response.getResults().size()-answers.size());
    var action = answers.size()!=1 ? invalid("TEXT_BLOCK_COUNT",0,text.length()) : parseAnswer(text, finish);
    metadata.put("parseCategory", action.path("parseCategory").asText("VALID_JSON"));
    var usage = response.getMetadata().getUsage();
    return new ModelReply(action, response.getMetadata().getModel(),
        usage==null?null:usage.getPromptTokens(), usage==null?null:usage.getCompletionTokens(), metadata);
  }

  static com.fasterxml.jackson.databind.JsonNode parseAnswer(String raw, String finish) {
    if ("max_tokens".equals(finish)) return invalid("OUTPUT_TRUNCATED",0,raw.length());
    String text=raw.trim();
    if (text.startsWith("```json\n") && text.endsWith("\n```")) text=text.substring(8,text.length()-4).trim();
    else if(text.startsWith("```\n") && text.endsWith("\n```")) text=text.substring(4,text.length()-4).trim();
    if(text.isEmpty()) return invalid("EMPTY_TEXT",0,raw.length());
    try {
      var action=JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(text);
      if(!action.isObject()) return invalid("EXPECTED_JSON_OBJECT",0,raw.length());
      return action;
    } catch(com.fasterxml.jackson.core.JsonProcessingException e) {
      String message=e.getOriginalMessage();
      String code=e instanceof com.fasterxml.jackson.core.io.JsonEOFException ? "TRUNCATED_JSON"
          : message.startsWith("Illegal unquoted character") ? "UNESCAPED_CONTROL"
          : message.startsWith("Unrecognized token") ? "UNRECOGNIZED_TOKEN"
          : message.startsWith("Duplicate field") ? "DUPLICATE_JSON_FIELD" : "INVALID_JSON_SYNTAX";
      var result=(com.fasterxml.jackson.databind.node.ObjectNode)invalid(code,e.getLocation()==null?-1:e.getLocation().getCharOffset(),raw.length());
      result.set("syntaxCapture",tree(SyntaxCapture.capture(raw,text,e.getLocation()==null?-1:e.getLocation().getCharOffset(),
          e.getLocation()==null?-1:e.getLocation().getLineNr(),e.getLocation()==null?-1:e.getLocation().getColumnNr())));
      return result;
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode invalid(String code,long offset,int length) {
    return tree(java.util.Map.of("type","INVALID_JSON","parseCategory",code,
        "characterOffset",offset,"responseCharacters",length));
  }
}

