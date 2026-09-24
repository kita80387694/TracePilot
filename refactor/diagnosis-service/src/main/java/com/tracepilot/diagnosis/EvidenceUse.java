package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;import java.util.*;
/** Purpose and time derive from stored observation contracts, never HTTP receipt time. */
final class EvidenceUse {
 static Map<String,Object> classify(JsonNode fact,Evidence e,Request r){
  String p=fact.path("pointer").asText(),kind=fact.path("kind").asText();JsonNode row=e.data();String field=p;
  if(p.startsWith("/data/")){String[] parts=p.split("/",4);try{row=e.data().path("data").get(Integer.parseInt(parts[2]));field=parts.length>3?"/"+parts[3]:"";}catch(Exception invalid){row=null;}}
  String eventTime=row==null?"":row.path("time").asText(row.path("timestamp").asText(""));
  String collected=row==null?"":row.path("collectedAt").asText("");
  String purpose="BACKGROUND",reason="Not an incident observation",basis="UNKNOWN";
  boolean service=r.service().equals(e.locator().getOrDefault("service",r.service()))&&r.environment().equals(e.locator().getOrDefault("environment",r.environment()));
  if(row!=null)service &= row.path("service").asText(r.service()).equals(r.service())&&row.path("environment").asText(r.environment()).equals(r.environment());
  boolean obs=Set.of("logs","trace","metrics").contains(e.source())&&p.startsWith("/data/");
  boolean metric=Set.of("overview","metrics").contains(e.source());
  if(!service){purpose="ACCESS_DENIED";reason="Other service/environment";}
  else if(!EvidenceRegistry.readable(e)||e.status().equals("NO_DATA")){purpose="DATA_GAP";reason="No usable observation";}
  else if(metric && (kind.equals("CUMULATIVE") || field.startsWith("/http/routes"))){reason="Process aggregate is not a target-window increment";basis="PROCESS_START_UNKNOWN_TO_SAMPLE";}
  else if(metric && kind.startsWith("SAMPLE_INTERVAL")){
   purpose="COVERAGE_UNKNOWN";reason="Exact interval coverage unavailable; collectedAt does not supply it";basis="PREVIOUS_SAMPLE_UNKNOWN_TO_SAMPLE";
   if(MetricFacts.recordedIntervalVersion(row.path("deploymentVersion").asText())&&"BETWEEN_SAMPLES".equals(fact.path("intervalCoverage").asText()))try{
    Instant a=Instant.parse(fact.path("measurementStart").asText()),b=Instant.parse(fact.path("measurementEnd").asText());
    if(!a.isAfter(b)){
     basis="RECORDED_SAMPLE_INTERVAL";
     if(!a.isBefore(r.start())&&!b.isAfter(r.end())&&!fact.path("value").isNull()){
      purpose="WINDOW_OBSERVATION";reason="Recorded interval entirely inside query window; does not cover the whole incident";
     }else{reason="Recorded interval not wholly within query window, or no value; do not apportion counts";}
    }
   }catch(Exception invalid){reason="Invalid recorded interval; no inferred coverage";}
  }
  else if(metric && (kind.equals("UNKNOWN_VERSION_SEMANTICS")||kind.equals("CONFIGURED_LIMIT"))){reason="Unknown metric semantics or configuration, not incident outcome";}
  else if((obs||e.source().equals("overview")&&MetricFacts.verifiedVersion(row.path("deploymentVersion").asText())&&MetricFacts.KINDS.containsKey(field)) && !fact.path("value").isNull()){
   basis="EVENT_OR_SAMPLE_TIME";
   if(!eventTime.isBlank())try{Instant time=Instant.parse(eventTime);if(!time.isBefore(r.start())&&!time.isAfter(r.end())){purpose="WINDOW_OBSERVATION";reason="Actual event/sample time within query window; no duration claim";}else if(time.isBefore(r.start())&&metric&&kind.equals("INSTANTANEOUS")){purpose="HISTORICAL_BASELINE";reason="Only paired comparison with a compatible incident observation";}else reason="Actual observation outside target window";}catch(Exception invalid){reason="Invalid event time";}
   else if(!metric && EvidenceRegistry.inScope(e,r)){purpose="WINDOW_OBSERVATION";basis="SERVER_FILTERED_QUERY_WINDOW";reason="Window-filtered log result; individual event timestamp unavailable";}
  }
  var v=new LinkedHashMap<String,Object>();v.put("factId",fact.path("factId").asText());v.put("evidenceId",e.id());v.put("pointer",p);v.put("purpose",purpose);v.put("causeEligible",purpose.equals("WINDOW_OBSERVATION"));v.put("eventTime",eventTime);v.put("collectedAt",collected);v.put("storedRange",Map.of("start",e.start(),"end",e.end()));v.put("queryWindow",Map.of("start",r.start().toString(),"end",r.end().toString()));v.put("timeBasis",basis);v.put("reason",reason);return v;
 }
 static List<Object> catalog(List<Evidence> es,Request r){var byId=new HashMap<String,Evidence>();es.forEach(e->byId.put(e.id(),e));var out=new ArrayList<Object>();for(Object value:FactReferences.catalog(es,r)){var f=tree(value);var row=new LinkedHashMap<>(map(encode(f)));row.put("measurementMeaning",f.path("timeBasis"));row.putAll(classify(f,byId.get(f.path("evidenceId").asText()),r));out.add(row);}return out;}
 static boolean comparable(JsonNode baseline,JsonNode incident,Map<String,Evidence> es,Request r){
  var b=es.get(baseline.path("evidenceId").asText());var i=es.get(incident.path("evidenceId").asText());if(b==null||i==null)return false;
  if(!classify(baseline,b,r).get("purpose").equals("HISTORICAL_BASELINE")||!Boolean.TRUE.equals(classify(incident,i,r).get("causeEligible")))return false;
  String bp=baseline.path("pointer").asText().replaceFirst("^/data/[0-9]+", ""),ip=incident.path("pointer").asText().replaceFirst("^/data/[0-9]+", "");
  JsonNode br=sample(b,baseline),ir=sample(i,incident);
  return bp.equals(ip)&&baseline.path("kind").asText().equals("INSTANTANEOUS")&&baseline.path("kind").equals(incident.path("kind"))&&baseline.path("unit").equals(incident.path("unit"))&&MetricFacts.verifiedVersion(br.path("deploymentVersion").asText())&&br.path("deploymentVersion").equals(ir.path("deploymentVersion"))&&!br.path("instanceId").asText().isBlank()&&br.path("instanceId").equals(ir.path("instanceId"));
 }
 static JsonNode sample(Evidence e,JsonNode f){String p=f.path("pointer").asText();return p.startsWith("/data/")?e.data().path("data").path(Integer.parseInt(p.split("/")[2])):e.data();}
}
