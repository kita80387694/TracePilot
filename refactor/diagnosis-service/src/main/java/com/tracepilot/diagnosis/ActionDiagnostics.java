package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.time.*;

/** Owner-scoped rejection diagnostics. No raw prose, secrets or thinking are retained. */
final class ActionDiagnostics {
  static final int MAX_CHARS=8192;
  static List<String> allowed(String phase) {
    return switch(phase){case "CANDIDATES"->List.of("candidates");case "INVESTIGATE"->List.of("tool","ready");case "REVIEW"->List.of("review");case "GAPS"->List.of("insufficient");default->List.of("report");};
  }
  // One dispatch definition drives both the prompt schema and runtime field checks.
  // Nested report semantics and tool authorization remain separately validated.
  static Map<String,String> fields(String type) {
    return switch(type){case "candidates"->Map.of("candidates","array");case "tool"->Map.of("tool","string","args","object");
      case "review"->Map.of("assessments","array");case "report"->Map.of("report","object");case "insufficient"->Map.of("report","object");default->Map.of();};
  }
  static JsonNode schema(String phase) {
    var choices=new ArrayList<Object>();
    for(String type:allowed(phase)) {
      var props=new LinkedHashMap<String,Object>();props.put("type",Map.of("const",type));
      var required=new ArrayList<String>();required.add("type");
      var fields=fields(type);
      fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{String name=e.getKey(),kind=e.getValue();required.add(name);props.put(name,name.equals("candidates")?InitialContract.schema():Map.of("type",kind));});
      choices.add(Map.of("type","object","required",required,"properties",props,"additionalProperties",false));
    }
    return tree(Map.of("oneOf",choices));
  }
  static List<Map<String,Object>> violations(String phase,JsonNode a) {
    var errors=new ArrayList<Map<String,Object>>();
    if(a.path("type").asText().equals("INVALID_JSON")) {
      errors.add(Map.of("code","JSON_SYNTAX","correction","Return one complete JSON object; do not guess missing text or repeat evidence.","path","","actualType","UNPARSEABLE","actual","SEE_PROTECTED_CAPTURE_COMPLETENESS",
          "expected","one valid JSON object","parseCategory",a.path("parseCategory").asText("UNKNOWN")));
      return errors;
    }
    if(!a.isObject()){issue(errors,"",a,"JSON object");return errors;}
    if(!a.path("type").isTextual() || !allowed(phase).contains(a.path("type").asText())) {
      issue(errors,"/type",a.path("type"),"one of "+allowed(phase));return errors;
    }
    fields(a.path("type").asText()).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
      String name=e.getKey(),kind=e.getValue(); JsonNode value=a.path(name);
      boolean valid=switch(kind){case "array" -> value.isArray();case "object" -> value.isObject();case "string" -> value.isTextual();default -> false;};
      if(name.equals("candidates"))valid=valid && value.size()<=3;
      if(!valid)issue(errors,"/"+name,value,name.equals("candidates")?"array with 0..3 items":kind);
    });
    var known=new HashSet<>(fields(a.path("type").asText()).keySet());known.add("type");a.fieldNames().forEachRemaining(k->{if(!known.contains(k))issue(errors,"/"+k,a.path(k),"unknown top-level field forbidden");});
    if(a.path("type").asText().equals("candidates"))errors.addAll(InitialContract.errors(a.path("candidates")));
    if(a.path("type").asText().equals("tool")&&a.path("args").isObject())a.path("args").fields().forEachRemaining(e->{if(!e.getValue().isTextual()&&!e.getValue().isIntegralNumber())issue(errors,"/args/"+e.getKey(),e.getValue(),"string or integer parameter; nested objects, boolean and null forbidden");});
    return errors;
  }
  static void issue(List<Map<String,Object>> errors,String path,JsonNode value,String expected){
    errors.add(ContractErrors.issue(value.isMissingNode()?"MISSING_FIELD":expected.contains("unknown")?"UNEXPECTED_FIELD":"FORMAT_ERROR",path,value,expected,"Return the declared field and type; unknown fields are forbidden. No automatic coercion or alias mapping."));
  }
  static JsonNode redact(JsonNode value,String path){
    if(value.isMissingNode())return tree("MISSING");
    if(value.isObject()){
      var o=JSON.createObjectNode();value.fields().forEachRemaining(e->{
        String k=e.getKey();String lower=k.toLowerCase(Locale.ROOT);
        if(lower.matches(".*(thinking|reasoning|password|secret|token|authorization|api.?key).*"))o.put("[REDACTED_FIELD_"+o.size()+"]","[REDACTED]");
        else {
          boolean known=Set.of("type","tool","args","report","summary","facts","value","pointer","evidenceId","evidenceIds",
              "gaps","gap","actions","action","status","candidates","cause","hypothesis","confidence","inference","conflicts","text",
              "name","limit","cursor","level","start","end","service","environment","traceId","version","query",
              "function","function_call","tool_calls","parameters","arguments","parseCategory","characterOffset","responseCharacters").contains(k);
          o.set(known?k:"[REDACTED_KEY_"+o.size()+"]",redact(e.getValue(),path+"/"+k));
        }
      });return o;
    }
    if(value.isArray()){var a=JSON.createArrayNode();value.forEach(v->a.add(redact(v,path+"/*")));return a;}
    if(value.isTextual()){
      String text=value.asText();
      if((path.endsWith("/type") || path.endsWith("/tool") || path.endsWith("/confidence")) && text.matches("[A-Za-z_]{1,48}"))return value;
      return tree("[REDACTED_TEXT]");
    }
    if(value.isNumber())return tree("[REDACTED_NUMBER]");
    return value;
  }
  static Map<String,Object> record(Claim c,String phase,JsonNode action,ModelReply reply,Map<String,Object> budget,String error,int repairs){
    var r=new LinkedHashMap<String,Object>();
    r.put("taskId",c.id());r.put("modelCallOrdinal",budget.get("model_calls"));
    r.put("model",reply.model());r.put("configuration",reply.protocol());r.put("phase",phase);
    r.put("allowedActions",allowed(phase));r.put("violations",violations(phase,action));r.put("errorCode",error);
    r.put("finishReason",reply.protocol().getOrDefault("finishReason","UNKNOWN"));
    r.put("remainingModels",((Number)budget.get("max_models")).intValue()-((Number)budget.get("model_calls")).intValue());
    r.put("remainingTools",((Number)budget.get("max_tools")).intValue()-((Number)budget.get("tool_calls")).intValue());
    r.put("remainingMillis",Math.max(0,Duration.between(Instant.now(),c.deadline()).toMillis()));
    r.put("nextHandling",repairs>=1?"END_PARTIAL":"ONE_CORRECTION_WITHIN_REMAINING_BUDGET");
    String body=encode(redact(action,""));
    boolean parserDiagnostic=action.path("type").asText().equals("INVALID_JSON");
    if(parserDiagnostic) r.put("parserDiagnostic",Map.of("parseCategory",action.path("parseCategory").asText("UNKNOWN"),
        "characterOffset",action.path("characterOffset").asLong(-1),"responseCharacters",action.path("responseCharacters").asInt(-1)));
    r.put("actionRecord",Map.of("body",body.substring(0,Math.min(body.length(),MAX_CHARS)),"complete",!parserDiagnostic && body.length()<=MAX_CHARS,
      "bodySource",parserDiagnostic?"PARSER_DIAGNOSTIC_NOT_PROVIDER_BODY":"SANITIZED_PARSED_ACTION",
      "truncated",body.length()>MAX_CHARS,
      "redacted",true,"originalRedactedCharacters",body.length(),"retentionDays",7,"expiresAt",Instant.now().plus(Duration.ofDays(7)).toString()));
    if(action.has("syntaxCapture")) {
      var record=new LinkedHashMap<String,Object>(map(encode(r.get("actionRecord"))));
      record.put("syntaxCapture",action.get("syntaxCapture"));
      r.put("actionRecord",record);
    }
    r.put("promptVersion",PROMPT_VERSION);
    r.put("phaseSchema",schema(phase));
    r.put("inputTokens",reply.inputTokens());r.put("outputTokens",reply.outputTokens());
    return r;
  }
  private ActionDiagnostics(){}
}
