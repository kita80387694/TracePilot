package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Field semantics verified against the fixed business deployment, never inferred from query failure. */
final class MetricFacts {
 static final String BUSINESS_VERSION="sha256-a310042746f037f5a47e2de93d2303f000926a5871d63efdd7e375df55045785";
 static final String INTERVAL_VERSION="sha256-af1ba2634569b4bb91776cd2b361b73fab771e63bcc2520a0a63f0e5544f9cd3";
 static boolean recordedIntervalVersion(String version){return Set.of(INTERVAL_VERSION,"sha256-0ebc193adcc27e39cc36c3f668b688a663e213930ba8df4f03073c15673e495c","sha256-18a70c8fd2baebc3ff5affc3921c5f7c91b91f884b3dc7b04135d88783035079").contains(version);}
 static boolean verifiedVersion(String version){return recordedIntervalVersion(version)||Set.of(BUSINESS_VERSION,"sha256-06612d191b55b8b6782e1ba009fa27b03282a1234a422a0e6e0fbd402d81108f").contains(version);}
 static final Map<String,String> KINDS=Map.ofEntries(
  Map.entry("/http/count","CUMULATIVE"),Map.entry("/http/errors5xx","CUMULATIVE"),Map.entry("/http/businessRejections","CUMULATIVE"),Map.entry("/http/totalMs","CUMULATIVE"),
  Map.entry("/http/intervalCount","SAMPLE_INTERVAL_DELTA"),Map.entry("/http/status","SAMPLE_INTERVAL_STATUS"),Map.entry("/http/errorRate","SAMPLE_INTERVAL_FRACTION"),Map.entry("/http/averageMs","SAMPLE_INTERVAL_MEAN"),
  Map.entry("/events/success","CUMULATIVE"),Map.entry("/events/failure","CUMULATIVE"),Map.entry("/events/pending","INSTANTANEOUS"),Map.entry("/events/oldestSeconds","INSTANTANEOUS"),
  Map.entry("/pool/active","INSTANTANEOUS"),Map.entry("/pool/idle","INSTANTANEOUS"),Map.entry("/pool/pending","INSTANTANEOUS"),Map.entry("/pool/total","INSTANTANEOUS"),Map.entry("/pool/max","CONFIGURED_LIMIT"));
 static List<Object> generate(List<Evidence> evidence,Request request){
  var rows=new ArrayList<Object>();
  for(Evidence e:evidence){
   if(!EvidenceRegistry.readable(e)||!Set.of("overview","metrics").contains(e.source()))continue;
   if(e.data().path("data").isArray()){
    // Semantic coverage must match the registered array boundary, not the former source page size.
    for(int i=0;i<Math.min(Reports.MAX_ARRAY_ITEMS,e.data().path("data").size());i++)sample(rows,e,e.data().path("data").get(i),"/data/"+i,request);
   }else sample(rows,e,e.data(),"",request);
  }return rows;
 }
 static void sample(List<Object> rows,Evidence e,JsonNode sample,String prefix,Request r){
  String version=sample.path("deploymentVersion").asText("");boolean verified=verifiedVersion(version);
  KINDS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
   JsonNode value=sample.at(entry.getKey());if(value.isMissingNode())return;
   String key=entry.getKey(),kind=verified?entry.getValue():"UNKNOWN_VERSION_SEMANTICS";
   var row=new LinkedHashMap<String,Object>();row.put("evidenceId",e.id());row.put("pointer",prefix+key);row.put("value",value);
   row.put("kind",kind);row.put("unit",!verified?"UNKNOWN":key.endsWith("errorRate")?"fraction_not_per_second":key.endsWith("Ms")?"milliseconds":key.endsWith("Seconds")?"seconds":key.endsWith("status")?"state":key.startsWith("/pool")?(key.endsWith("pending")?"waiting_threads":"connections"):key.startsWith("/events")?(key.endsWith("pending")?"events":"attempts"):"requests");
   row.put("sampleTime",sample.path("time").asText("UNKNOWN"));row.put("deploymentVersion",version);row.put("semanticsVersion","MetricSampler-fixed-v1");
   row.put("targetWindow",Map.of("start",r.start().toString(),"end",r.end().toString()));
   row.put("timeBasis",!verified?"unknown deployment semantics; no time classification":kind.equals("CUMULATIVE")?"process start to sample; target-window delta NOT computed":kind.startsWith("SAMPLE_INTERVAL")?"previous sample (or initial process baseline) to sample; exact start/duration not recorded; NOT the target window":"sample instant or configured limit; no whole-window claim");
   if(recordedIntervalVersion(version)){
    row.put("semanticsVersion","MetricSampler-fixed-v2");
    if(kind.startsWith("SAMPLE_INTERVAL")){
     var h=sample.path("http");
     row.put("measurementStart",h.path("intervalStart"));row.put("measurementEnd",h.path("intervalEnd"));
     row.put("intervalCoverage",h.path("intervalCoverage"));
     row.put("timeBasis","BETWEEN_SAMPLES".equals(h.path("intervalCoverage").asText())
       ?"recorded adjacent-sample interval; NOT automatically the target window"
       :"initial counter baseline with unknown interval start; NOT a known-window delta");
    }
    if(key.startsWith("/pool/"))row.put("sampleTime",sample.path("pool").path("sampledAt").asText("UNKNOWN"));
   }
   if(verified && key.equals("/events/pending"))row.put("timeBasis",row.get("timeBasis")+"; COUNT of outbox status <> DONE, including PENDING/PROCESSING/FAILED; not a count of only waiting events");
   if(verified && key.equals("/events/oldestSeconds"))row.put("timeBasis",row.get("timeBasis")+"; max age since created_at among status <> DONE; not measured queue-wait or lease duration");
   row.put("valueAvailability",value.isNull()?"NO_VALUE_NOT_ZERO":"OBSERVED");rows.add(row);
  });
 }
}
