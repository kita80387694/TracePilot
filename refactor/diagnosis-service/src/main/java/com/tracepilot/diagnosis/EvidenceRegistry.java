package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Task-owned immutable evidence; accessibility describes the stored result, not live-source health. */
final class EvidenceRegistry {
  static final Set<String> OBS=Set.of("metrics","logs","trace");
  static boolean readable(Evidence e){return Set.of("AVAILABLE","PARTIAL","STALE","NO_DATA").contains(e.status());}
  static boolean inScope(Evidence e,Request r){
    if(!r.service().equals(e.locator().getOrDefault("service",r.service())) || !r.environment().equals(e.locator().getOrDefault("environment",r.environment())))return false;
    try {return !Instant.parse(e.end()).isBefore(r.start()) && !Instant.parse(e.start()).isAfter(r.end());}
    catch(Exception invalid){return false;}
  }
  static boolean eventEvidence(Evidence e,Request r){
    if(!(OBS.contains(e.source()) || e.source().equals("overview")) || !readable(e) || e.status().equals("NO_DATA") || !inScope(e,r))return false;
    // Known-version overview can anchor an instant observation, not a specific mechanism.
    if(e.source().equals("overview"))return MetricFacts.verifiedVersion(e.data().path("deploymentVersion").asText())
      && CandidateContract.within(e.data().path("time").asText(""),r)
      && MetricFacts.KINDS.keySet().stream().anyMatch(k->e.data().at(k).isNumber());
    var rows=e.data().path("data");
    if(!rows.isArray() || rows.isEmpty())return false;
    // Rows with explicit timestamps must overlap the incident; endpoints already enforce request scope.
    for(JsonNode row:rows){
      String time=row.path("time").asText(row.path("timestamp").asText(""));
      if(time.isEmpty())return true;
      try{Instant t=Instant.parse(time);if(!t.isBefore(r.start())&&!t.isAfter(r.end()))return true;}catch(Exception ignored){}
    }
    return false;
  }
  static boolean hasEvent(List<Evidence> es,Request r){return es.stream().anyMatch(e->eventEvidence(e,r));}
  static String state(Evidence e){return switch(e.status()){
    case "NO_DATA" -> "SUCCESS_EMPTY";case "AVAILABLE" -> "SUCCESS_DATA";case "PARTIAL" -> "TRUNCATED";
    case "STALE" -> "STORED_SNAPSHOT";case "UNAVAILABLE" -> "SOURCE_UNAVAILABLE";case "FORBIDDEN" -> "ACCESS_DENIED";default -> "QUERY_FAILED";};}
  static List<Object> entries(List<Evidence> es,Request r){
    var rows=new ArrayList<Object>();
    for(Evidence e:es){
      var row=new LinkedHashMap<String,Object>();row.put("evidenceId",e.id());row.put("service",r.service());row.put("environment",r.environment());
      row.put("start",e.start());row.put("end",e.end());row.put("source",e.source());row.put("state",state(e));
      row.put("storedAccessible",readable(e));row.put("eventEligible",eventEvidence(e,r));row.put("locator",e.locator());
      row.put("versions",Workflow.versions(List.of(e)));row.put("snapshotVersion",e.data().path("deploymentVersion").asText(e.locator().getOrDefault("version","").toString()));
      rows.add(row);
    }return rows;
  }

  static List<String> validate(JsonNode report,List<Evidence> es,Request r){
    var errors=new ArrayList<String>(Reports.validate(report,es));var ids=new HashMap<String,Evidence>();es.forEach(e->ids.put(e.id(),e));
    if(!hasEvent(es,r)&&!report.path("candidates").isEmpty())errors.add("MISSING_WINDOW_EVIDENCE");
    for(JsonNode c:report.path("candidates")){
      boolean anchor=false;
      for(JsonNode ref:c.path("evidenceIds")){
        Evidence e=ids.get(ref.asText());
        if(e==null)continue;
        if(!readable(e) || (OBS.contains(e.source())&&!inScope(e,r)))errors.add("INVALID_CAUSE_SOURCE");
        anchor|=eventEvidence(e,r);
      }
      if(!anchor)errors.add("NO_EVENT_ANCHOR");
    }
    for(JsonNode f:report.path("facts")){
      Evidence e=ids.get(f.path("evidenceId").asText());
      if(e!=null && OBS.contains(e.source())&&!inScope(e,r))errors.add("FACT_OUTSIDE_SCOPE");
      if(e!=null && EvidenceRegistry.readable(e) && Reports.factCatalog(e).stream().map(Domain::tree).noneMatch(x->x.path("pointer").equals(f.path("pointer")) && x.path("value").equals(f.path("value"))))errors.add("FACT_NOT_IN_PROVIDED_CATALOG");
    }
    return errors.stream().distinct().toList();
  }
  static String fingerprint(Request r,Query q){
    var args=normalized(q);
    try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
      encode(List.of(r.service(),r.environment(),r.start().toString(),r.end().toString(),q.tool(),args)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    catch(Exception e){throw new IllegalArgumentException("INVALID_QUERY_FINGERPRINT",e);}
  }
  static TreeMap<String,String> normalized(Query q){
    q=ReadTools.boundedPage(q);
    var args=new TreeMap<String,String>(q.args());
    if(Set.of("metrics","logs","trace","code").contains(q.tool()))args.put("limit",String.valueOf(Integer.parseInt(args.getOrDefault("limit",Integer.toString(ReadTools.pageLimit(q.tool()))))));
    if(OBS.contains(q.tool()))args.putIfAbsent("cursor","0");
    if(Set.of("logs","trace").contains(q.tool()))args.putIfAbsent("channel","all");
    return args;
  }
  static List<String> reusableComplete(Request request,Query query,List<Evidence> es){
    if(!OBS.contains(query.tool())||!normalized(query).get("cursor").equals("0"))return List.of();
    var filter=normalized(query);filter.remove("limit");filter.remove("cursor");var ids=new ArrayList<String>();
    for(var e:es){
      if(!e.source().equals(query.tool())||!Set.of("AVAILABLE","NO_DATA").contains(e.status())||!inScope(e,request))continue;
      try{if(!Instant.parse(e.start()).equals(request.start())||!Instant.parse(e.end()).equals(request.end()))continue;}catch(Exception invalid){continue;}
      if(!e.data().has("hasMore")||e.data().path("hasMore").asBoolean()||e.data().has("sourceGap")||!e.data().path("gap").asText("NONE").equals("NONE")||!Boolean.TRUE.equals(EvidenceCompleteness.projection(e).get("projectionComplete")))continue;
      var prior=tree(e.locator()).path("query");if(!prior.path("args").isObject())continue;
      var args=new TreeMap<String,String>();prior.path("args").fields().forEachRemaining(x->args.put(x.getKey(),x.getValue().asText()));var norm=normalized(new Query(e.source(),args));
      if(!norm.get("cursor").equals("0"))continue;norm.remove("limit");norm.remove("cursor");if(norm.equals(filter))ids.add(e.id());
    }return ids;
  }
  /** Exact cached pages include PARTIAL results; complete-window reuse remains a separate policy. */
  static Map<String,Object> queryReceipt(Request request,Query query,List<Evidence> es,boolean fresh){
    Query effective=ReadTools.boundedPage(query);String fp=fingerprint(request,effective);
    var complete=reusableComplete(request,effective,es);
    var selected=es.stream().filter(e->fp.equals(e.locator().get("queryFingerprint"))||complete.contains(e.id())).toList();
    var rows=new ArrayList<Object>();
    for(var e:selected){var row=new LinkedHashMap<String,Object>();row.put("evidenceId",e.id());row.put("status",e.status());row.put("hasMore",e.data().path("hasMore").asBoolean());
      row.put("nextCursor",e.data().path("nextCursor"));row.put("sourceGap",e.data().path("sourceGap"));row.put("rowCount",e.data().path("data").size());rows.add(row);}
    return Map.of("tool",query.tool(),"args",effective.args(),"status",fresh?"RECORDED":"REUSED","queryFingerprint",fp,
      "evidenceIds",selected.stream().map(Evidence::id).toList(),"results",rows,
      "meaning","These are stored query results, not necessarily fully read or a complete window. Repeating these parameters reuses them; use returned nextCursor for source continuation or read_evidence_page for stored facts.");
  }
}
