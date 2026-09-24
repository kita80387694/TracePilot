package com.tracepilot.diagnosis;
import java.util.*;
/** Local limits, not a provider token estimate. No body or credential is persisted here. */
final class RequestBoundary {
 static Map<String,Object> protocolMetadata(byte[] body){
  try{var wire=Domain.JSON.readTree(body);return Map.of("toolChoice",wire.path("tool_choice"),"nativeToolCount",wire.path("tools").size(),"stream",wire.path("stream").asBoolean(false),"wireSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)),"layer","FINAL_SERIALIZED_REQUEST_BEFORE_SEND");}
  catch(Exception unavailable){return Map.of("layer","FINAL_SERIALIZED_REQUEST_BEFORE_SEND","status","METADATA_UNAVAILABLE");}
 }
 static final int MAX_CONTEXT_UTF16=100000, MAX_WIRE_UTF8_BYTES=400000;
 static final class LocalFailure extends IllegalArgumentException {final Map<String,Object> detail;LocalFailure(String code,Map<String,Object> detail){super(code);this.detail=detail;}}
 static String context(String value){if(value.length()>MAX_CONTEXT_UTF16)throw new LocalFailure("CONTEXT_LIMIT",Map.of("actual",value.length(),"limit",MAX_CONTEXT_UTF16,"unit","UTF16_CODE_UNITS","requestSent",false));return value;}
 static Map<String,Object> wire(byte[] body){var d=Map.<String,Object>of("actualBytes",body.length,"limitBytes",MAX_WIRE_UTF8_BYTES,"unit","UTF8_BYTES","tokenEstimate","NOT_COMPUTED");if(body.length>MAX_WIRE_UTF8_BYTES)throw new LocalFailure("SERIALIZED_REQUEST_LIMIT",d);return d;}
 static Map<String,Object> sections(Map<String,Object> input){
  var evidence=new LinkedHashMap<String,Object>();for(String k:List.of("causeFactRegistry","backgroundFactRegistry","factContexts","evidenceRegistry","evidence","allowedCauseEvidenceIds","eventTimeline","evidenceDelivery"))if(input.containsKey(k))evidence.put(k,input.get(k));
  var working=new LinkedHashMap<>(input);evidence.keySet().forEach(working::remove);
  var checkpoint=new LinkedHashMap<>(Domain.map(Domain.encode(working.getOrDefault("checkpoint",Map.of()))));var correction=new LinkedHashMap<String,Object>();for(String k:List.of("evidenceFeedback","actionError","validationErrors","correctionInstruction","correction"))if(checkpoint.containsKey(k))correction.put(k,checkpoint.remove(k));working.put("checkpoint",checkpoint);
  var actual=Map.of("evidence",Domain.encode(evidence).length(),"working",Domain.encode(working).length(),"correction",Domain.encode(correction).length());var limits=Map.of("evidence",70000,"working",15000,"correction",10000);
  for(String k:limits.keySet())if(actual.get(k)>limits.get(k))throw new LocalFailure("CONTEXT_SECTION_LIMIT",Map.of("section",k,"actual",actual.get(k),"limit",limits.get(k),"unit","UTF16_CODE_UNITS","requestSent",false,"evidenceRetained",true));
  return Map.of("actual",actual,"limits",limits,"unit","UTF16_CODE_UNITS","reservedEnvelopeCharacters",5000,"policy","No audit step history included. No facts truncated. If a section cannot fit, end partial and retain full evidence.");
 }
}
