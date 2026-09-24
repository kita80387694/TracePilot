package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;import java.util.regex.*;

/** Compile model prose templates against immutable task facts. Never repair numbers or unknown IDs. */
final class FactReferences {
 static final Pattern REF=Pattern.compile("\\{\\{fact:(F[0-9a-f]{24})}}"), NUM=Pattern.compile("[\\p{N}零〇一二两三四五六七八九十百千万亿半]|(?i)\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|hundred|thousand|million|billion)\\b");
 static final Set<String> TEXT=Set.of("summary","cause","mechanism","support","contradictions","toVerify","text","gaps","actions");
 record Compiled(JsonNode report,List<String> errors){}
 static String id(String evidence,String pointer){try{return "F"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((evidence+"\n"+pointer).getBytes(StandardCharsets.UTF_8))).substring(0,24);}catch(Exception e){throw new IllegalStateException(e);}}
 static List<Object> catalog(List<Evidence> es,Request request){
  var out=new ArrayList<Object>();var typed=new HashMap<String,JsonNode>();
  for(var f:MetricFacts.generate(es,request)){var n=tree(f);typed.put(n.path("evidenceId").asText()+n.path("pointer").asText(),n);}
  for(var e:es){
   for(Object f:Reports.factCatalog(e)){
    var n=tree(f);if(n.path("value").isContainerNode())continue;
    String pointer=n.path("pointer").asText();JsonNode value=n.path("value"),meta=typed.get(e.id()+pointer);
    String unit=meta!=null?meta.path("unit").asText():byteField(e,pointer)?"bytes":"UNKNOWN";
    String kind=meta!=null?meta.path("kind").asText():byteField(e,pointer)?"SCAN_REMAINING_BYTES":"OBSERVED_SCALAR";
    out.add(entry(e,pointer,value,unit,kind,meta));
   }
   // These are source availability / identity facts, not business cause evidence.
   out.add(entry(e,"@sourceStatus",tree(e.status()),"state","SOURCE_STATUS",null));
   if(e.data().has("httpStatus"))out.add(entry(e,"/httpStatus",e.data().get("httpStatus"),"HTTP_status_code","SOURCE_STATUS",null));
   if(e.data().has("deploymentVersion"))out.add(entry(e,"/deploymentVersion",e.data().get("deploymentVersion"),"version_identifier","IDENTITY",null));
  }
  var unique=new LinkedHashMap<String,Object>();for(var x:out)unique.put(tree(x).path("factId").asText(),x);return new ArrayList<>(unique.values());
 }
 static boolean byteField(Evidence e,String p){return e.source().equals("logs")&&p.equals("/remainingBytes")&&e.data().path("remainingBytes").isIntegralNumber();}
 static Map<String,Object> entry(Evidence e,String pointer,JsonNode value,String unit,String kind,JsonNode meta){
  var row=new LinkedHashMap<String,Object>();row.put("factId",id(e.id(),pointer));row.put("evidenceId",e.id());row.put("pointer",pointer);row.put("value",value);row.put("unit",unit);row.put("kind",kind);
  row.put("start",e.start());row.put("end",e.end());row.put("source",e.source());
  row.put("timeBasis",meta==null?"stored observation/query range; no duration or incident-wide inference":meta.path("timeBasis").asText());
  if(meta!=null)for(String key:List.of("measurementStart","measurementEnd","intervalCoverage","sampleTime","semanticsVersion"))if(meta.has(key))row.put(key,meta.get(key));
  row.put("display",display(e,pointer,value,unit,meta));return row;
 }
 static String bytes(JsonNode value){var b=value.decimalValue();return value.asText()+" bytes (约 "+b.divide(new BigDecimal("1000000"),2,RoundingMode.HALF_UP).toPlainString()+" MB / 约 "+b.divide(new BigDecimal("1048576"),2,RoundingMode.HALF_UP).toPlainString()+" MiB；MB=10^6 bytes，MiB=2^20 bytes；小数2位 HALF_UP)";}
 static String display(Evidence e,String pointer,JsonNode value,String unit,JsonNode meta){
  String v=value.isNull()?"无值（不等于零）":unit.equals("bytes")?bytes(value):value.asText()+" ["+unit+"]";
  String time=e.start().equals(e.end())?"采样="+e.start():"查询范围="+e.start()+" 至 "+e.end();
  return "【"+e.source()+pointer+"="+v+"；"+time+(meta==null?"":"；"+meta.path("timeBasis").asText())+"；证据="+e.id()+"】";
 }
 static Compiled compile(JsonNode input,List<Evidence> es,Request r,boolean gaps){
  var entries=new HashMap<String,JsonNode>();for(var x:catalog(es,r)){var n=tree(x);entries.put(n.path("factId").asText(),n);}
  var errors=new ArrayList<String>();JsonNode copy=input.deepCopy();
  if(!gaps&&copy.isObject()){
   var facts=JSON.createArrayNode();if(!input.path("facts").isArray())errors.add("MISSING_facts");
   int i=0;for(var f:input.path("facts")){
    String path="/facts/"+i++;if(!f.isObject()||f.size()!=1||!f.path("factId").isTextual()){errors.add("FACT_ID_ONLY:"+path);continue;}
    var n=entries.get(f.path("factId").asText());if(n==null){errors.add("UNKNOWN_FACT_ID:"+path);continue;}
    if(Set.of("SOURCE_STATUS","IDENTITY").contains(n.path("kind").asText())){errors.add("NON_BUSINESS_FACT:"+path+"; cite in gaps instead");continue;}
    facts.add(tree(Map.of("text",n.path("display").asText(),"evidenceId",n.path("evidenceId").asText(),"pointer",n.path("pointer").asText(),"value",n.path("value"))));
   }
   ((ObjectNode)copy).set("facts",facts);
  }
  render(copy,"",entries,errors);return new Compiled(copy,List.copyOf(errors));
 }
 static void render(JsonNode node,String path,Map<String,JsonNode> entries,List<String> errors){
  if(node.isObject()){
   var obj=(ObjectNode)node;var names=new ArrayList<String>();obj.fieldNames().forEachRemaining(names::add);
   for(String name:names){if(path.isEmpty()&&name.equals("facts"))continue;var v=obj.get(name);String p=path+"/"+name;
    if(v.isTextual()&&TEXT.contains(name))obj.put(name,renderText(v.asText(),p,entries,errors));
    else if(v.isArray()&&TEXT.contains(name)){for(int i=0;i<v.size();i++)if(v.get(i).isTextual())((ArrayNode)v).set(i,tree(renderText(v.get(i).asText(),p+"/"+i,entries,errors)));else render(v.get(i),p+"/"+i,entries,errors);}
    else if(v.isContainerNode())render(v,p,entries,errors);
   }
  }else if(node.isArray())for(int i=0;i<node.size();i++)render(node.get(i),path+"/"+i,entries,errors);
 }
 static String renderText(String text,String path,Map<String,JsonNode> entries,List<String> errors){
  var matcher=REF.matcher(text);StringBuffer out=new StringBuffer();
  while(matcher.find()){
   var fact=entries.get(matcher.group(1));if(fact==null){errors.add("UNKNOWN_FACT_ID:"+path+":"+matcher.group(1));matcher.appendReplacement(out,Matcher.quoteReplacement(matcher.group()));}
   else matcher.appendReplacement(out,Matcher.quoteReplacement(fact.path("display").asText()));
  }matcher.appendTail(out);
  String remainder=REF.matcher(text).replaceAll("");
  if(remainder.contains("{{")||remainder.contains("}}"))errors.add("MALFORMED_FACT_REFERENCE:"+path);
  // Validation only: common non-numeric words are not rewritten in the output.
  String inspected=remainder.replaceAll("不一定|另一方面|进一步|一致|统一|唯一|一直|一般|一旦|万一|一定|一一","");
  if(NUM.matcher(inspected).find())errors.add("LITERAL_NUMBER_FORBIDDEN:"+path+"; use a registered {{fact:ID}} including for observed error codes, versions and times");
  if(Pattern.compile("(?i)\\b(?:MB|MiB|GB|GiB|KB|KiB|milliseconds|seconds|bytes)\\b|%|百分之").matcher(inspected).find())errors.add("LITERAL_UNIT_FORBIDDEN:"+path+"; unit and conversion belong to the registered fact");
  return out.toString();
 }
 static List<Object> modelCatalog(List<Evidence> es,Request r,boolean gaps){
  var out=new ArrayList<Object>();for(var x:catalog(es,r)){
   var n=tree(x);if(gaps&&!n.path("kind").asText().equals("SOURCE_STATUS"))continue;
   var brief=new LinkedHashMap<String,Object>();for(String k:List.of("factId","evidenceId","pointer","value","unit","kind","timeBasis"))brief.put(k,n.path(k));out.add(brief);
  }return out;
 }
 static String instructions(){return "NUMERIC REPORT CONTRACT: facts is an array of objects containing ONLY factId, chosen from factRegistry. In ALL prose (summary, cause, mechanism, support, contradictions, toVerify, conflicts, gaps, actions), reference observed values only as {{fact:F...}} using exact registered IDs. No literal quantities, number words, timestamps, error-code numbers, version numbers or conversions in prose; use registered facts for these without changing their values. The server expands each placeholder with its source field, exact value, unit, conversions and time range. Unknown IDs and old text/evidenceId/pointer/value fact objects are rejected, never repaired. Do not manually append numbers or a different unit to a reference. Qualitative hypotheses remain your responsibility; references prove observations, not causal support. SOURCE_STATUS facts support data gaps only. Empty facts/candidates are legal. Do not quote server displays back into prose; output placeholders only.";}
}
