package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Structural support obligations, not a semantic proof of causation. */
final class CandidateContract {
 static final Set<String> FIELDS=Set.of("cause","inference","confidence","evidenceIds","mechanism","support","contradictions","toVerify","scope");
 static final Set<String> SCOPES=Set.of("SNAPSHOT_HYPOTHESIS","EVENT_HYPOTHESIS","TEMPORAL_HYPOTHESIS");
 static Map<String,Object> schema(){
  var p=new TreeMap<String,Object>();
  for(String s:List.of("cause","mechanism","support","contradictions"))p.put(s,Map.of("type","string","minLength",1));
  p.put("inference",Map.of("const",true));p.put("confidence",Map.of("enum",List.of("SUPPORTED","LIKELY","UNVERIFIED")));
  p.put("scope",Map.of("enum",SCOPES.stream().sorted().toList()));
  p.put("evidenceIds",Map.of("type","array","minItems",1,"items",Map.of("type","string")));
  p.put("toVerify",Map.of("type","array","minItems",1,"items",Map.of("type","string","minLength",1)));
  return Map.of("type","array","minItems",0,"maxItems",3,"items",Map.of("type","object","additionalProperties",false,"required",FIELDS.stream().sorted().toList(),"properties",p));
 }
 static List<Map<String,Object>> issues(JsonNode report,List<Evidence> es,Request r){
  var out=new ArrayList<Map<String,Object>>();var ids=new HashMap<String,Evidence>();es.forEach(e->ids.put(e.id(),e));int i=0;
  for(JsonNode c:report.path("candidates")){
   String path="/candidates/"+i++;String assertion=c.path("cause").asText();
   for(String field:List.of("mechanism","support","contradictions"))if(!c.path(field).isTextual()||c.path(field).asText().isBlank())add(out,path+"/"+field,assertion,"MISSING_SUPPORT_EXPLANATION","nonempty explanation; explicitly say unknown when not observed");
   if(!c.path("toVerify").isArray()||c.path("toVerify").isEmpty()||!allText(c.path("toVerify")))add(out,path+"/toVerify",assertion,"MISSING_VERIFICATION","nonempty list of unresolved checks; do not invent established causes");
   String scope=c.path("scope").asText();if(!SCOPES.contains(scope))add(out,path+"/scope",assertion,"INVALID_SUPPORT_SCOPE",SCOPES.toString());
   var referenced=new ArrayList<Evidence>();for(var id:c.path("evidenceIds"))if(ids.containsKey(id.asText()))referenced.add(ids.get(id.asText()));
   boolean event=referenced.stream().anyMatch(e->EvidenceRegistry.eventEvidence(e,r));
   if(!event)add(out,path+"/evidenceIds",assertion,"NO_EVENT_ANCHOR","accessible in-window observations supporting THIS assertion, not an unrelated log; withdraw candidate if unavailable");
   if(scope.equals("EVENT_HYPOTHESIS")&&!referenced.stream().anyMatch(e->EvidenceRegistry.OBS.contains(e.source())&&EvidenceRegistry.eventEvidence(e,r)))add(out,path+"/scope",assertion,"SNAPSHOT_ONLY","snapshot supports only its instant, not a request/event mechanism; withdraw or state a bounded hypothesis with unresolved checks");
   if(scope.equals("TEMPORAL_HYPOTHESIS")&&times(referenced,r).size()<2)add(out,path+"/scope",assertion,"MISSING_TEMPORAL_SUPPORT","at least two distinct in-window observations; duration and causal relationship still require semantic review");
  }return out;
 }
 static boolean allText(JsonNode a){for(var n:a)if(!n.isTextual()||n.asText().isBlank())return false;return true;}
 static Set<String> times(List<Evidence> es,Request r){
  var ts=new HashSet<String>();for(var e:es)if(EvidenceRegistry.eventEvidence(e,r)){
   if(e.data().path("data").isArray())for(var row:e.data().path("data")){String t=row.path("time").asText(row.path("timestamp").asText(""));if(within(t,r))ts.add(t);}
   else {String t=e.data().path("time").asText("");if(within(t,r))ts.add(t);}
  }return ts;
 }
 static boolean within(String t,Request r){try{var x=java.time.Instant.parse(t);return !x.isBefore(r.start())&&!x.isAfter(r.end());}catch(Exception ex){return false;}}
 static void add(List<Map<String,Object>> out,String path,String assertion,String code,String expected){out.add(Map.of("path",path,"assertion",assertion,"code",code,"expected",expected,"resolution","Withdraw unsupported candidate or correct using actual evidence; zero or one candidates are valid. Never fabricate references."));}
}
