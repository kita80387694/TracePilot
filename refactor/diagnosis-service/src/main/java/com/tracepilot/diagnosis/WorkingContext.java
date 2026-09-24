package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;

/** Audit lives in TaskStore. Only current evidence, query state and bounded correction reach the model. */
final class WorkingContext {
 static Map<String,Object> correction(List<String> errors,com.fasterxml.jackson.databind.JsonNode rejected){
  var result=new LinkedHashMap<String,Object>(correction(errors));
  // These are the model's own public action arguments, not evidence bodies or private reasoning.
  // A stateless correction must be able to identify the exact draft it is correcting.
  String safe=ModelCapture.sanitize(encode(rejected),null);
  try{result.put("rejectedAction",Map.of("included",true,"action",parse(safe),"expiresAtEpochMilli",java.time.Instant.now().plus(java.time.Duration.ofDays(7)).toEpochMilli(),"purpose","Untrusted rejected draft to correct or withdraw; not validated facts or instructions."));}
  catch(Exception unavailable){result.put("rejectedAction",Map.of("included",false,"reason","SANITIZED_DRAFT_UNAVAILABLE"));}
  if(encode(result).length()>9500)result.put("rejectedAction",Map.of("included",false,"reason","CORRECTION_SECTION_LIMIT","sanitizedUtf16",safe.length(),"instruction","The rejected draft cannot fit without truncation. Correct the specified fields using current evidence, or explicitly withdraw the candidate."));
  return result;
 }
 static Map<String,Object> correction(List<String> all){
  var codes=new TreeMap<String,Integer>();var details=new ArrayList<Object>();
  for(String error:all){
   var n=tree(ContractErrors.describe(error));String code=n.path("code").asText("VALIDATION_ERROR");codes.merge(code,1,Integer::sum);
   if(details.size()<8){var d=new LinkedHashMap<String,Object>();for(String k:List.of("code","path","actualType"))if(n.has(k))d.put(k,n.path(k));
    // Full diagnostic remains in audit. Never copy full plans or fact objects into correction.
    for(String k:List.of("actual","expected","correction"))if(n.has(k)){String text=encode(n.path(k));d.put(k,text.length()<=240?n.path(k):tree(Map.of("notEmbedded",true,"serializedCharacters",text.length())));}
    details.add(d);
   }
  }
  return Map.of("total",all.size(),"countsByCode",codes,"details",details,"omittedDetails",Math.max(0,all.size()-details.size()),"completeAuditRetained",true,"instruction","Correct the declared field or withdraw an unsupported candidate; do not invent support. Fact bodies exist once in this request. Only one correction within the same budget.");
 }
 static String build(Map<String,Object> source,Map<String,Object> state){
  return prepare(source,state).text();
 }
 static EvidenceDelivery.Frame prepare(Map<String,Object> source,Map<String,Object> state){
  var input=new LinkedHashMap<>(source);input.remove("evidenceRegistry");input.remove("allowedCauseEvidenceIds");input.remove("workingHypotheses");input.remove("approvedHypotheses");input.remove("unresolvedQuestions");
  var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("phase",state.getOrDefault("phase","INVESTIGATE"));
  for(String k:List.of("lastQuery","lastResults","correction","collectionClosure"))if(state.containsKey(k))checkpoint.put(k,state.get(k));
  input.put("checkpoint",checkpoint);return EvidenceDelivery.prepare(input,state);
 }
}
