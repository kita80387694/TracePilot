package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;
/** Fact observations stay explicit. Repeated scope metadata is shared within each stateless request. */
final class ContextPacking {
 static final Set<String> SHARED=Set.of("measurementMeaning","kind","unit","semanticsVersion","timelineNodeId","evidenceId","start","end","source","purpose","causeEligible","eventTime","collectedAt","storedRange","queryWindow","timeBasis","reason");
 static void pack(Map<String,Object> input){
  compactFeedback(input);
  var contexts=new LinkedHashMap<String,Object>();var keys=new HashMap<String,String>();
  for(String name:List.of("causeFactRegistry","backgroundFactRegistry"))if(input.containsKey(name)){
   var rows=new ArrayList<Object>();for(var n:tree(input.get(name))){var row=new LinkedHashMap<>(map(encode(n)));row.remove("display");var shared=new TreeMap<String,Object>();for(String k:SHARED)if(row.containsKey(k))shared.put(k,row.remove(k));
    String key=encode(shared),id=keys.computeIfAbsent(key,k->"C"+(keys.size()+1));contexts.putIfAbsent(id,shared);row.put("contextRef",id);rows.add(row);
   }input.put(name,rows);
  }
  input.put("factContexts",contexts);input.put("contextEncoding","fact-context-v2: each fact inherits every field from factContexts[contextRef]. No observations removed. Redundant display sentences omitted; server renders value/unit/time. measurementMeaning preserves original metric definition. Each request is self-contained; no memory of earlier calls assumed.");
 }
 static void compactFeedback(Map<String,Object> input){
  var checkpoint=tree(input.get("checkpoint"));var feedback=checkpoint.path("evidenceFeedback");
  if(!feedback.path("allowedCauseFacts").isArray())return;
  var supplied=new HashSet<String>();for(var f:tree(input.get("causeFactRegistry")))supplied.add(f.path("factId").asText());
  var ids=new ArrayList<String>();for(var f:feedback.path("allowedCauseFacts")){String id=f.path("factId").asText();if(!supplied.contains(id))throw new IllegalArgumentException("FEEDBACK_FACT_NOT_IN_INPUT:"+id);ids.add(id);}
  var reduced=new LinkedHashMap<>(map(encode(feedback)));reduced.remove("allowedCauseFacts");reduced.put("allowedCauseFactIds",ids);reduced.put("factLookup","Resolve these IDs in this request's causeFactRegistry and factContexts; identical observations are not copied again. Violations and their referenced facts remain explicit.");
  var all=new HashSet<>(supplied);for(var f:tree(input.get("backgroundFactRegistry")))all.add(f.path("factId").asText());
  var violations=new ArrayList<Object>();for(var v:feedback.path("violations")){var row=new LinkedHashMap<>(map(encode(v)));if(v.path("references").size()>0){var refs=new ArrayList<String>();for(var ref:v.path("references")){String id=ref.path("factId").asText();if(!all.contains(id))throw new IllegalArgumentException("FEEDBACK_REFERENCE_NOT_IN_INPUT:"+id);refs.add(id);}row.remove("references");row.put("referenceFactIds",refs);}violations.add(row);}reduced.put("violations",violations);
  var next=new LinkedHashMap<>(map(encode(checkpoint)));next.put("evidenceFeedback",reduced);input.put("checkpoint",next);
 }
 static List<Object> expand(com.fasterxml.jackson.databind.JsonNode input,String name){var out=new ArrayList<Object>();for(var row:input.path(name)){var full=new LinkedHashMap<>(map(encode(input.path("factContexts").path(row.path("contextRef").asText()))));row.fields().forEachRemaining(e->{if(!e.getKey().equals("contextRef"))full.put(e.getKey(),e.getValue());});out.add(full);}return out;}
}
