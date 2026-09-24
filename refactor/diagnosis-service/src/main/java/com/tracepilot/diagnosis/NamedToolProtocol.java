package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Opt-in protocol codec, not a Spring component. No production routing or tool execution. */
final class NamedToolProtocol {
 static final String VERSION="named-tools-v1-offline";
 static final String SCOPE_VERSION="offered-fact-scope-v31";
 record Definition(String name,String description,JsonNode schema,String actionType,String source){}
 record Decoded(JsonNode action,List<String> errors){}
 static List<Definition> definitions(boolean reportOnly,boolean queryAllowed){
  var result=new ArrayList<Definition>();
  for(var branch:DirectContract.schema(reportOnly,queryAllowed).path("oneOf")){
   String type=branch.at("/properties/type/const").asText();
   String source=branch.at("/properties/tool/const").asText();
   String name=switch(type){case "tool"->"query_"+source;case "readEvidence"->"read_evidence_page";case "report"->"submit_report";default->throw new IllegalStateException("UNKNOWN_CONTRACT_BRANCH");};
   String description=switch(type){
    case "tool"->"Query the authorized "+source+" source for new evidence. Source cursor is not a stored evidence page. Existing time, service, version and permission limits apply.";
    case "readEvidence"->"Read a zero-based page of evidence already stored in this task using its actual evidenceId. Does not query a source or collect new data.";
    default->"Submit the final supported report. Empty hypotheses are valid when evidence is insufficient. References and causal scope remain server validated.";
   };
   ObjectNode schema=branch.deepCopy();((ObjectNode)schema.path("properties")).remove(List.of("type","tool"));
   var required=JSON.createArrayNode();for(var field:branch.path("required"))if(!Set.of("type","tool").contains(field.asText()))required.add(field);
   schema.set("required",required);result.add(new Definition(name,description,schema,type,source));
  }
  return List.copyOf(result);
 }
 static List<ToolCallback> callbacks(boolean reportOnly,boolean queryAllowed){
  return callbacks(definitions(reportOnly,queryAllowed));
 }
 static List<Definition> offered(boolean reportOnly,boolean queryAllowed,String input){
  var context=parse(input);
  var versions=new TreeSet<String>();for(var v:context.at("/codeQueryScope/allowedVersions"))if(v.isTextual()&&v.asText().matches(ReadTools.CODE_VERSION_PATTERN))versions.add(v.asText());
  if(versions.size()>16)throw new IllegalArgumentException("CODE_VERSION_METADATA_UNBOUNDED");
  var eligible=new TreeSet<String>();
  for(var fact:context.path("causeFactRegistry")){
   var scope=fact.has("contextRef")?context.path("factContexts").path(fact.path("contextRef").asText()):fact;
   if(!scope.isObject())throw new IllegalArgumentException("INVALID_OFFERED_FACT_CONTEXT");
   if(!scope.path("causeEligible").asBoolean())continue;
   String id=fact.path("factId").asText();
   if(!id.matches("F[0-9a-f]{24}"))throw new IllegalArgumentException("INVALID_OFFERED_FACT_METADATA");
   eligible.add(id);
  }
  var offered=new ArrayList<Definition>();
  for(var d:definitions(reportOnly,queryAllowed)){
   if(d.source().equals("code")){
    if(versions.isEmpty())continue;
    ObjectNode schema=d.schema().deepCopy();((ObjectNode)schema.at("/properties/args/properties/version")).set("enum",tree(versions));
    offered.add(new Definition(d.name(),d.description(),schema,d.actionType(),d.source()));
   }else if(d.actionType().equals("report")){
    ObjectNode schema=d.schema().deepCopy();
    var hypotheses=(ObjectNode)schema.at("/properties/hypotheses");
    if(eligible.isEmpty())hypotheses.put("maxItems",0);
    else for(String role:ReviewContract.ROLES){
     var items=(ObjectNode)hypotheses.at("/items/properties/"+role+"/items");
     items.set("enum",tree(eligible));
     items.put("description","Select a currently provided incident fact ID. Eligibility is scope only, not causal proof; withdraw unsupported candidates rather than guessing references.");
    }
    offered.add(new Definition(d.name(),d.description(),schema,d.actionType(),d.source()));
   }else offered.add(d);
  }
  return List.copyOf(offered);
 }
 static List<String> offeredErrors(ChatResponse response,List<Definition> offered){
  var errors=new ArrayList<String>();int i=0;
  for(var call:response.getResults().stream().flatMap(g->g.getOutput().getToolCalls().stream()).toList()){
   var d=offered.stream().filter(x->x.name().equals(call.name())).findFirst();String path="/calls/"+i++;
   if(d.isEmpty())ContractErrors.add(errors,"TOOL_NOT_OFFERED",path+"/name",tree(call.name()),offered.stream().map(Definition::name).toList(),"Use a tool available for the currently collected scope; first collect missing version metadata.");
   else {var args=SpringAiGateway.parseAnswer(call.arguments(),"tool_use");if(!args.path("type").asText().equals("INVALID_JSON")){
    int before=errors.size();String prefix=path+"/arguments";ContractErrors.validate(args,d.get().schema(),prefix,errors);
    for(int j=before;j<errors.size();j++){
     var issue=parse(errors.get(j));String p=issue.path("path").asText();
     if(issue.path("code").asText().equals("INVALID_ENUM")&&p.startsWith(prefix+"/")&&ReviewContract.ROLES.stream().anyMatch(role->p.contains("/"+role+"/"))){
      var actual=args.at(p.substring(prefix.length()));if(actual.isTextual()&&actual.asText().matches("F[0-9a-f]{24}")){ObjectNode exact=issue.deepCopy();exact.set("actual",actual);errors.set(j,encode(exact));}
     }
    }
   }}
  }
  // Do not copy the same large permitted-ID set into every correction error.
  var compact=new ArrayList<String>();for(String error:errors){
   var issue=parse(error);String path=issue.path("path").asText();
   if(issue.path("code").asText().equals("INVALID_ENUM")&&ReviewContract.ROLES.stream().anyMatch(role->path.contains("/"+role+"/"))){
    ObjectNode copy=issue.deepCopy();copy.put("code","FACT_NOT_IN_OFFERED_SCOPE");
    copy.set("expected",tree(Map.of("allowedCount",issue.path("expected").size(),"lookup","Current request causeFactRegistry and submit_report role enum")));
    copy.put("correction","Use a currently provided incident fact in this role, or explicitly withdraw the unsupported candidate. Registered background and unread facts are not interchangeable. No replacement is guessed.");
    compact.add(encode(copy));
   }else compact.add(error);
  }
  return List.copyOf(compact);
 }
 static List<ToolCallback> callbacks(List<Definition> definitions){
  return definitions.stream().map(d->(ToolCallback)new ToolCallback(){
   public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name(d.name()).description(d.description()).inputSchema(encode(d.schema())).build();}
   public String call(String input){throw new IllegalStateException("AUTOMATIC_DIAGNOSTIC_EXECUTION_FORBIDDEN");}
  }).toList();
 }
 static Decoded decode(String name,String arguments,boolean reportOnly,boolean queryAllowed){
  var allowed=definitions(reportOnly,queryAllowed);var selected=allowed.stream().filter(d->d.name().equals(name)).findFirst();
  var errors=new ArrayList<String>();
  if(selected.isEmpty()){
   ContractErrors.add(errors,"UNKNOWN_OR_DISALLOWED_NATIVE_TOOL","/name",tree(name),allowed.stream().map(Definition::name).toList(),"Select an available tool; no alias mapping is performed.");return new Decoded(JSON.nullNode(),List.copyOf(errors));
  }
  JsonNode args=SpringAiGateway.parseAnswer(arguments,"tool_use");
  if(args.path("type").asText().equals("INVALID_JSON"))return new Decoded(JSON.nullNode(),DirectContract.errors(args,reportOnly,queryAllowed));
  var d=selected.get();ContractErrors.validate(args,d.schema(),"/arguments",errors);
  if(!errors.isEmpty())return new Decoded(JSON.nullNode(),List.copyOf(errors));
  ObjectNode action=args.deepCopy();action.put("type",d.actionType());if(d.actionType().equals("tool"))action.put("tool",d.source());
  errors.addAll(DirectContract.errors(action,reportOnly,queryAllowed));
  return new Decoded(errors.isEmpty()?action:JSON.nullNode(),List.copyOf(errors));
 }
 static Decoded decode(ChatResponse response,boolean reportOnly,boolean queryAllowed){
  if(response.getResults().stream().anyMatch(g->"max_tokens".equals(g.getMetadata().getFinishReason())))return envelopeError("OUTPUT_TRUNCATED");
  var calls=response.getResults().stream().flatMap(g->g.getOutput().getToolCalls().stream()).toList();
  if(calls.size()!=1)return envelopeError("NATIVE_TOOL_CALL_COUNT");
  return decode(calls.get(0).name(),calls.get(0).arguments(),reportOnly,queryAllowed);
 }
 static Decoded envelopeError(String code){return new Decoded(JSON.nullNode(),List.of(encode(Map.of("code",code,"path","/content","expected","exactly one complete native tool call","correction","Return one permitted complete tool call. Do not infer an action from accompanying text."))));}
}
