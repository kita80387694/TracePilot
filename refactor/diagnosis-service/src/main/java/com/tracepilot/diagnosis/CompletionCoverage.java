package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;
/** Bounded execution is distinct from exhaustive scan and causal correctness. */
final class CompletionCoverage {
 static Map<String,Object> assess(List<Evidence> es,EvidenceDelivery.Frame frame,Set<String> provided,
    com.fasterxml.jackson.databind.JsonNode report,boolean budgetForcedReport){
  var missing=new ArrayList<String>();for(String source:List.of("logs","metrics"))if(es.stream().noneMatch(e->e.source().equals(source)))missing.add(source);
  var failed=new ArrayList<String>();var pending=new ArrayList<String>();var projection=new ArrayList<String>();var hardGaps=new ArrayList<String>();
  for(var e:es){
   if(!EvidenceRegistry.readable(e))failed.add(e.id());
   if(e.data().has("sourceGap")||!e.data().path("gap").asText("NONE").equals("NONE")||
      e.data().path("hasMore").asBoolean()&&!EvidenceCompleteness.scanComplete(e,es)||e.status().equals("PARTIAL")&&!e.data().path("hasMore").asBoolean())pending.add(e.id());
   if(!Boolean.TRUE.equals(EvidenceCompleteness.projection(e).get("projectionComplete")))projection.add(e.id());
   if(e.data().has("sourceGap")||!e.data().path("gap").asText("NONE").equals("NONE")||e.status().equals("PARTIAL")&&!e.data().path("hasMore").asBoolean())hardGaps.add(e.id());
  }
  long unread=frame.registered().stream().filter(id->!provided.contains(id)).count();
  // A row is an observation, not an arbitrary scalar. Its background fields remain part of delivery.
  var groups=new LinkedHashMap<String,Set<String>>();var incidentGroups=new HashSet<String>();
  for(var f:frame.facts().values()){
   String key=observationKey(f);groups.computeIfAbsent(key,k->new LinkedHashSet<>()).add(f.path("factId").asText());
   if(f.path("causeEligible").asBoolean())incidentGroups.add(key);
  }
  var missingSourceRead=new ArrayList<String>();var sourceReads=new LinkedHashMap<String,Object>();
  for(String source:List.of("logs","metrics")){
   var witnesses=new ArrayList<String>();
   for(var e:es)if(e.source().equals(source)&&EvidenceRegistry.readable(e)){
    if(e.status().equals("NO_DATA")&&e.data().path("data").isEmpty()&&!e.data().path("hasMore").asBoolean()&&!hardGaps.contains(e.id()))witnesses.add(e.id()+":SUCCESS_EMPTY");
    else for(String group:groups.keySet())if(group.startsWith(e.id()+"#/data/")&&incidentGroups.contains(group)&&provided.containsAll(groups.get(group)))witnesses.add(group);
   }
   sourceReads.put(source,witnesses);if(witnesses.isEmpty())missingSourceRead.add(source);
  }
  var required=new LinkedHashSet<String>();collectRefs(report.path("modelPlan"),required);
  for(var f:report.path("facts"))if(f.has("factId"))required.add(f.path("factId").asText());
  var missingRefs=new ArrayList<String>();var missingGroups=new LinkedHashSet<String>();
  for(String id:required){var f=frame.facts().get(id);
   if(f==null||!frame.visible().contains(id)||!provided.contains(id)){missingRefs.add(id);continue;}
   String group=observationKey(f);if(!provided.containsAll(groups.get(group)))missingGroups.add(group);
  }
  var reasons=new ArrayList<String>();
  if(!missing.isEmpty())reasons.add("INITIAL_SOURCE_NOT_QUERIED");if(!failed.isEmpty())reasons.add("SOURCE_FAILURE_RECORDED");
  if(!hardGaps.isEmpty())reasons.add("SOURCE_COVERAGE_FAILURE");if(!projection.isEmpty())reasons.add("PROJECTION_INCOMPLETE");
  if(!missingSourceRead.isEmpty())reasons.add("INITIAL_SOURCE_OBSERVATION_NOT_DELIVERED");
  if(!missingRefs.isEmpty())reasons.add("REPORT_REFERENCE_NOT_DELIVERED");if(!missingGroups.isEmpty())reasons.add("CITED_OBSERVATION_NOT_DELIVERED");
  if(budgetForcedReport)reasons.add("BUDGET_FORCED_REPORT");
  boolean exhaustive=missing.isEmpty()&&failed.isEmpty()&&pending.isEmpty()&&projection.isEmpty()&&unread==0;
  var out=new LinkedHashMap<String,Object>();out.put("version","completion-coverage-v2-bounded");out.put("missingInitialSources",missing);out.put("failedSources",failed);out.put("pendingSourceEvidenceIds",pending);out.put("incompleteProjectionEvidenceIds",projection);out.put("unreadRegisteredFacts",unread);
  out.put("sourceReadWitnesses",sourceReads);out.put("missingSourceObservations",missingSourceRead);out.put("requiredFactIds",required);out.put("missingRequiredFactIds",missingRefs);out.put("incompleteCitedObservations",missingGroups);out.put("blockingReasons",reasons);
  out.put("complete",reasons.isEmpty());out.put("exhaustiveCoverage",exhaustive);out.put("coverage",exhaustive?"EXHAUSTIVE_FOR_QUERIED_FILTERS":"BOUNDED_NOT_EXHAUSTIVE");
  out.put("causalSupport","PENDING_SEPARATE_REVIEW");out.put("meaning","Execution completion only: initial source observation or explicit empty result reviewed, report references supplied, cited observation groups delivered, no recorded source failure/projection loss/budget-forced stop. Remaining pages and unread facts persist as gaps. This does not prove a cause, absence of anomalies, or the whole-window state.");return out;
 }
 static String observationKey(com.fasterxml.jackson.databind.JsonNode f){String p=f.path("pointer").asText();return f.path("evidenceId").asText()+"#"+(p.matches("^/data/[0-9]+(?:/.*)?$")?p.replaceFirst("^(/data/[0-9]+).*","$1"):"metadata");}
 static void collectRefs(com.fasterxml.jackson.databind.JsonNode n,Set<String> ids){
  if(n.isContainerNode())n.elements().forEachRemaining(v->collectRefs(v,ids));
  else if(n.isTextual()){if(n.asText().matches("F[0-9a-f]{24}"))ids.add(n.asText());var m=FactReferences.REF.matcher(n.asText());while(m.find())ids.add(m.group(1));}
 }
 static void disclose(com.fasterxml.jackson.databind.node.ObjectNode report,Map<String,Object> assessment){
  report.set("completionCoverage",tree(assessment));var gaps=(com.fasterxml.jackson.databind.node.ArrayNode)report.path("gaps");
  if(!Boolean.TRUE.equals(assessment.get("exhaustiveCoverage")))gaps.add("有界诊断：未完成所有查询分页或事实阅读；详见 completionCoverage 与 evidenceDelivery。不能据此认定全窗口正常、没有其他异常或已确认根因。");
  if(!Boolean.TRUE.equals(assessment.get("complete")))gaps.add("必要采证或执行条件未满足："+assessment.get("blockingReasons")+"；保留部分报告，不认定流程完成。");
 }
}
