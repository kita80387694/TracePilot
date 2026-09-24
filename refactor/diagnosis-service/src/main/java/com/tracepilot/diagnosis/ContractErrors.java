package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Deterministic, bounded diagnostics. Never copy observation bodies into a correction. */
final class ContractErrors {
 static Map<String,Object> issue(String code,String path,JsonNode actual,Object expected,String correction){
  return Map.of("code",code,"path",path,"actualType",actual.getNodeType().name(),"actual",actual.isContainerNode()?Map.of("count",actual.size()):ActionDiagnostics.redact(actual,path),"expected",expected,"correction",correction);
 }
 static void validate(JsonNode value,JsonNode schema,String path,List<String> errors){
  if(value.isMissingNode()){add(errors,"MISSING_FIELD",path,value,"required field","Supply the required field with its declared type; do not invent analysis.");return;}
  if(schema.has("oneOf")){for(var option:schema.path("oneOf")){var trial=new ArrayList<String>();validate(value,option,path,trial);if(trial.isEmpty())return;} // retain the useful non-null branch diagnostics
   // Prefer the explicitly discriminated action/tool branch for actionable errors.
   for(var option:schema.path("oneOf")){var p=option.path("properties");if(p.has("type")&&p.path("type").has("const")&&p.path("type").path("const").equals(value.path("type"))&&(!p.path("tool").has("const")||p.path("tool").path("const").equals(value.path("tool")))){validate(value,option,path,errors);return;}}
   var choices=new ArrayList<Object>();
   for(var option:schema.path("oneOf")){var props=option.path("properties");if(props.path("type").has("const")){
    var choice=new LinkedHashMap<String,Object>();choice.put("type",props.path("type").path("const"));
    if(props.path("tool").has("const"))choice.put("tool",props.path("tool").path("const"));choices.add(choice);
   }}
   if(!choices.isEmpty()){
    if(!value.isObject()){add(errors,"TYPE_MISMATCH",path,value,"object","Return one JSON action object from the current Schema.");return;}
    boolean toolAllowed=false;for(var option:schema.path("oneOf"))toolAllowed|=option.at("/properties/type/const").asText().equals("tool");
    String field=toolAllowed&&value.path("type").asText().equals("tool")?"tool":"type";
    var allowed=new ArrayList<String>();for(var option:schema.path("oneOf")){var props=option.path("properties");
     if(field.equals("type")&&props.path("type").has("const"))allowed.add(props.path("type").path("const").asText());
     if(field.equals("tool")&&props.path("type").path("const").asText().equals("tool")&&props.path("tool").has("const"))allowed.add(props.path("tool").path("const").asText());
    }
    add(errors,value.path(field).isMissingNode()?"MISSING_FIELD":"INVALID_ACTION_DISCRIMINATOR",path+"/"+field,value.path(field),allowed.stream().distinct().toList(),
      "Select an allowed action shape from the current Schema. readEvidence is type=readEvidence with evidenceId and page, not type=tool; no ID or action alias is inferred.");return;
   }
   for(var option:schema.path("oneOf"))if(!option.path("type").asText().equals("null")){validate(value,option,path,errors);return;}

  }
  String type=schema.path("type").asText();boolean valid=switch(type){case "array"->value.isArray();case "object"->value.isObject();case "string"->value.isTextual();case "null"->value.isNull();case "integer"->value.isIntegralNumber();case "number"->value.isNumber();case "boolean"->value.isBoolean();default->true;};
  if(!valid){add(errors,"TYPE_MISMATCH",path,value,type,"Return the declared JSON type, not a coerced value.");return;}
  if(schema.has("const")&&!schema.path("const").equals(value))add(errors,"INVALID_CONST",path,value,schema.path("const"),"Use the declared action/tool identifier.");
  if(value.isIntegralNumber()||value.isNumber())if((schema.has("minimum")&&value.asDouble()<schema.path("minimum").asDouble())||(schema.has("maximum")&&value.asDouble()>schema.path("maximum").asDouble()))add(errors,"VALUE_OUT_OF_RANGE",path,value,schema,"Use the bounded numeric range.");
  if(value.isTextual()){int length=value.asText().codePointCount(0,value.asText().length());if(length<schema.path("minLength").asInt(0)||length>schema.path("maxLength").asInt(Integer.MAX_VALUE))add(errors,"STRING_LENGTH",path,value,Map.of("min",schema.path("minLength").asInt(0),"max",schema.path("maxLength").asInt(Integer.MAX_VALUE)),"Use the specified Unicode code-point length; no truncation.");}
  if(value.isTextual()&&schema.has("pattern")&&!java.util.regex.Pattern.compile(schema.path("pattern").asText()).matcher(value.asText()).find())add(errors,"STRING_PATTERN",path,value,schema.path("pattern"),schema.path("description").asText("Use the declared string pattern; aliases and guessed replacements are not accepted."));
  if(schema.has("enum")){boolean found=false;for(var allowed:schema.path("enum"))found|=allowed.equals(value);if(!found)add(errors,"INVALID_ENUM",path,value,schema.path("enum"),"Select an explicitly allowed enum value.");}
  if(value.isObject()){
   for(var required:schema.path("required"))if(!value.has(required.asText()))validate(value.path(required.asText()),schema.path("properties").path(required.asText()),path+"/"+required.asText(),errors);
   value.fields().forEachRemaining(e->{String p=path+"/"+e.getKey();if(!schema.path("properties").has(e.getKey())){if(!schema.path("additionalProperties").asBoolean(true))add(errors,"UNEXPECTED_FIELD",p,e.getValue(),"declared fields only","Remove this unknown field; no alias mapping is performed.");}else validate(e.getValue(),schema.path("properties").path(e.getKey()),p,errors);});
  }
  if(value.isArray()){
   int min=schema.path("minItems").asInt(0),max=schema.path("maxItems").asInt(Integer.MAX_VALUE);
   if(value.size()<min||value.size()>max)add(errors,"COUNT_OUT_OF_RANGE",path,value,Map.of("minItems",min,"maxItems",max),"Return a selection within this count range; choose relevant observations or explicitly withdraw the candidate. No references are silently removed.");
   var seen=new HashSet<JsonNode>();int i=0;for(var item:value){String p=path+"/"+i++;if(schema.path("uniqueItems").asBoolean()&&!seen.add(item))add(errors,"DUPLICATE_ITEM",p,item,"unique entries","Remove the duplicate entry explicitly.");validate(item,schema.path("items"),p,errors);}
  }
 }
 static void add(List<String> errors,String code,String path,JsonNode value,Object expected,String correction){errors.add(encode(issue(code,path,value,expected,correction)));}
 static Object describe(String error){
  if(error.startsWith("{"))return parse(error);
  String code=error.split(":",2)[0];String correction=code.contains("FACT")||code.contains("REFERENCE")?"Use only the registered IDs with the permitted scope; Withdraw an unsupported selection instead of inventing a replacement.":code.contains("INCIDENT")||error.contains("NOT_INCIDENT")?"Use window observations in causal roles; keep other observations as background, or withdraw the candidate.":code.contains("CAUSAL")||error.contains("CAUSAL_SELF")?"Evidence does not support this structural relation. Withdraw or select independently relevant observations; causal support still requires review.":"Correct the stated rule using the current Schema. Withdraw an invalid selection explicitly; do not fabricate support.";
  return Map.of("code",code,"path","SEE_LEGACY_RULE","actual","NOT_RETAINED_BY_LEGACY_VALIDATOR","expected",error,"correction",correction);
 }
}
