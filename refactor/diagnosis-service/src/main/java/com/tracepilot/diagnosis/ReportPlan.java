package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Versioned report IR. Model selects relations; only registered observations become fact sentences. */
final class ReportPlan {
 static final String SCHEMA="report-plan-v5", RENDERER="report-renderer-v5";
 static final Map<String,String> RELATIONS=Map.of("MAY_EXPLAIN","可能解释","MAY_SHARE_CAUSE","可能有共同原因","MAY_CONTRADICT","可能不符合预期关系");
 static final Map<String,String> CHECKS=Map.ofEntries(
  Map.entry("VERIFY_MECHANISM","人工核对因果机制是否成立，不能仅凭同时出现认定因果。"),
  Map.entry("CHECK_ALTERNATIVES","检查其他可解释这些观察的机制。"),
  Map.entry("CHECK_PERSISTENCE","补充不同时间点的同类观察，检查是否持续；单点不代表趋势。"),
  Map.entry("COLLECT_LOGS","按原窗口与关联标识核对日志覆盖；仅在注册表显示未完成时，沿实际游标继续查询。"),
  Map.entry("COLLECT_METRICS","补充原任务窗口内的指标采样并核对采样时间。"),
  Map.entry("INSPECT_CODE","人工核对已观测部署版本对应的代码实现。"),
  Map.entry("INSPECT_STATE","人工核对相关业务及异步处理状态；本报告不执行修改或SQL。"),
  Map.entry("RESTORE_SOURCE","人工恢复不可用的证据来源后重新采集。"),
  Map.entry("CORRELATE_REQUEST","使用证据中真实traceId查询已有请求关联；不保证完整分布式链路。"));
 record Compiled(JsonNode report,List<String> errors){}
 static Map<String,Object> strings(int min,int max){return Map.of("type","array","minItems",min,"maxItems",max,"uniqueItems",true,"items",Map.of("type","string"));}
 static JsonNode schema(){
  var h=Map.of("type","object","additionalProperties",false,"required",List.of("relation","premiseFactIds","outcomeFactIds","contradictionFactIds","checks"),"properties",Map.of(
   "relation",Map.of("enum",RELATIONS.keySet().stream().sorted().toList()),"premiseFactIds",strings(1,3),"outcomeFactIds",strings(0,3),"contradictionFactIds",strings(0,3),"checks",checkSchema(),"reviewNote",Map.of("type","string","maxLength",500,"description","Non-timeline plans only. Timeline analysis is represented by relations and selected node IDs, not rewritten action/time prose."),"timelineNodeIds",strings(1,12),"comparisons",Map.of("type","array","maxItems",3,"items",Map.of("type","object","required",List.of("baselineFactId","incidentFactId"),"additionalProperties",false,"properties",Map.of("baselineFactId",Map.of("type","string"),"incidentFactId",Map.of("type","string"))))));
  return tree(Map.of("type","object","additionalProperties",false,"required",List.of("hypotheses","checks"),"properties",Map.of("hypotheses",Map.of("type","array","minItems",0,"maxItems",3,"items",h),"checks",checkSchema(),"reviewNote",Map.of("type","string","maxLength",500))));
 }
 static Map<String,Object> checkSchema(){return Map.of("type","array","maxItems",4,"uniqueItems",true,"items",Map.of("enum",CHECKS.keySet().stream().sorted().toList()));}
 static boolean eventFact(JsonNode f,Map<String,Evidence> es,Request r){
  Evidence e=es.get(f.path("evidenceId").asText());return e!=null&&Boolean.TRUE.equals(EvidenceUse.classify(f,e,r).get("causeEligible"));
 }
 static Compiled compile(JsonNode plan,List<Evidence> evidence,Request r,boolean gaps){
  return compile(plan,evidence,r,gaps,3);
 }
 static Compiled compile(JsonNode plan,List<Evidence> evidence,Request r,boolean gaps,int referenceLimit){
  return compile(plan,evidence,r,gaps,referenceLimit,null);
 }
 static Compiled compile(JsonNode plan,List<Evidence> evidence,Request r,boolean gaps,int referenceLimit,Set<String> fallbackFactIds){
  var errors=new ArrayList<String>();var es=new HashMap<String,Evidence>();evidence.forEach(e->es.put(e.id(),e));
  var timeline=EventTimeline.build(evidence,r);var nodeById=timeline.byId();
  var facts=new LinkedHashMap<String,JsonNode>();for(var v:FactReferences.catalog(evidence,r)){var n=tree(v);var node=timeline.forFact(n);if(node!=null)((com.fasterxml.jackson.databind.node.ObjectNode)n).put("display",EventTimeline.display(node)+"；原字段="+n.path("pointer").asText()+"，原值="+n.path("value"));facts.put(n.path("factId").asText(),n);}
  exact(plan,Set.of("hypotheses","checks","reviewNote"),Set.of("hypotheses","checks"),"",errors);
  if(!plan.path("hypotheses").isArray()||plan.path("hypotheses").size()>3)errors.add("INVALID_ARRAY:/hypotheses");checks(plan.path("checks"),"/checks",errors);note(plan,"",errors);
  var selected=new LinkedHashSet<String>();var candidates=new ArrayList<Object>();var contradictions=new ArrayList<Object>();var notes=new ArrayList<Object>();
  var actions=new LinkedHashSet<String>();for(var c:plan.path("checks"))if(CHECKS.containsKey(c.asText()))actions.add(CHECKS.get(c.asText()));
  if(plan.has("reviewNote"))notes.add(Map.of("path","/reviewNote","text",plan.path("reviewNote").asText(),"status","UNREVIEWED_NOT_REPORT_FACT"));
  int index=0;for(var h:plan.path("hypotheses")){
   String path="/hypotheses/"+index++;
   exact(h,Set.of("relation","premiseFactIds","outcomeFactIds","contradictionFactIds","checks","reviewNote","comparisons","timelineNodeIds","mechanism"),Set.of("relation","premiseFactIds","outcomeFactIds","contradictionFactIds","checks"),path,errors);
   String relation=h.path("relation").asText();if(!RELATIONS.containsKey(relation))errors.add("INVALID_RELATION:"+path+"/relation");
   checks(h.path("checks"),path+"/checks",errors);if(h.path("checks").isEmpty())errors.add("MISSING_VERIFICATION:"+path+"/checks");note(h,path,errors);
   for(var field:List.of("premiseFactIds","outcomeFactIds","contradictionFactIds")){
    array(h.path(field),field.equals("premiseFactIds")?1:0,referenceLimit,path+"/"+field,errors);
    for(var ref:h.path(field)){
     var f=facts.get(ref.asText());if(f==null){errors.add("UNKNOWN_OR_UNAUTHORIZED_FACT:"+path+"/"+field+":"+ref.asText());continue;}
     if(!eventFact(f,es,r))errors.add("NOT_INCIDENT_OBSERVATION:"+path+"/"+field+":"+ref.asText());
     selected.add(ref.asText());
    }
   }
   var requiredNodes=new LinkedHashSet<String>();for(String field:List.of("premiseFactIds","outcomeFactIds","contradictionFactIds"))for(var ref:h.path(field)){var f=facts.get(ref.asText());if(f!=null){var node=timeline.forFact(f);if(node!=null)requiredNodes.add(node.path("nodeId").asText());}}
   if(!requiredNodes.isEmpty()||h.has("timelineNodeIds")){
    array(h.path("timelineNodeIds"),1,referenceLimit==3?12:referenceLimit*3,path+"/timelineNodeIds",errors);
    var supplied=new LinkedHashSet<String>();h.path("timelineNodeIds").forEach(n->supplied.add(n.asText()));
    if(!supplied.equals(requiredNodes))errors.add("TIMELINE_REFERENCE_MISMATCH:"+path+":expected="+requiredNodes+"; exact nodes for selected event facts, no inferred mapping");
    if(h.has("reviewNote"))errors.add("TIMELINE_PROSE_NOT_ALLOWED:"+path+"/reviewNote; express mechanism as the selected node/fact relation; action and time are rendered by server");
   }
   if(h.has("timelineNodeIds"))for(var ref:h.path("timelineNodeIds"))if(!nodeById.containsKey(ref.asText()))errors.add("UNKNOWN_OR_UNAUTHORIZED_TIMELINE_NODE:"+path+":"+ref.asText());
   if(CausalLink.repeatsObservations(h,facts,es))errors.add("CAUSAL_SELF_REFERENCE:"+path+"; all selected premises repeat selected outcomes; no distinct premise is supplied. Withdraw or provide independently relevant observations; a distinct ID alone never establishes causality");
   if(h.has("comparisons")){
    if(!h.path("comparisons").isArray()||h.path("comparisons").size()>3)errors.add("INVALID_COMPARISONS:"+path);
    for(var comparison:h.path("comparisons")){
     exact(comparison,Set.of("baselineFactId","incidentFactId"),Set.of("baselineFactId","incidentFactId"),path+"/comparisons",errors);
     var b=facts.get(comparison.path("baselineFactId").asText());var incident=facts.get(comparison.path("incidentFactId").asText());
     boolean referenced=false;for(var ref:h.path("premiseFactIds"))referenced|=ref.asText().equals(comparison.path("incidentFactId").asText());
     if(b==null||incident==null||!referenced||!EvidenceUse.comparable(b,incident,es,r))errors.add("INVALID_BASELINE_COMPARISON:"+path+":"+comparison);
     else{selected.add(b.path("factId").asText());selected.add(incident.path("factId").asText());}
    }
   }
   if(h.has("reviewNote"))notes.add(Map.of("path",path+"/reviewNote","text",h.path("reviewNote").asText(),"status","UNREVIEWED_NOT_REPORT_FACT"));
   var ids=new LinkedHashSet<String>();for(String field:List.of("premiseFactIds","outcomeFactIds","contradictionFactIds"))for(var ref:h.path(field)){var f=facts.get(ref.asText());if(f!=null)ids.add(f.path("evidenceId").asText());}
   var displayRefs=ReviewContract.mechanismReferences(h);
   for(String id:displayRefs){var f=facts.get(id);if(f!=null){selected.add(id);ids.add(f.path("evidenceId").asText());}}
   String premise=joined(h.path("premiseFactIds"),facts),outcome=h.path("outcomeFactIds").isEmpty()?"用户报告的现象（尚未独立核实）":joined(h.path("outcomeFactIds"),facts);
   String cause="待验证因果关联："+premise+" "+RELATIONS.getOrDefault(relation,"未知关系")+" "+outcome+"。这不是已确认根因。";
   var questions=new ArrayList<String>();questions.add(CHECKS.get("VERIFY_MECHANISM"));questions.add(CHECKS.get("CHECK_ALTERNATIVES"));for(var c:h.path("checks"))if(CHECKS.containsKey(c.asText())){questions.add(CHECKS.get(c.asText()));actions.add(CHECKS.get(c.asText()));}
   candidates.add(Map.of("cause",cause,"inference",true,"confidence","UNVERIFIED","evidenceIds",ids,"mechanism",h.has("mechanism")?ReviewContract.mechanism(h,evidence,r,path,errors,fallbackFactIds==null?Set.of():fallbackFactIds):"模型选择的待验证关系；具体机制及因果强度尚待人工审核。","support",premise,"contradictions",h.path("contradictionFactIds").isEmpty()?"未选择反证，不表示不存在其他解释。":joined(h.path("contradictionFactIds"),facts),"toVerify",questions,"scope","EVENT_HYPOTHESIS","mechanismDisplayFactIds",displayRefs));
   if(!h.path("contradictionFactIds").isEmpty())contradictions.add(Map.of("text",joined(h.path("contradictionFactIds"),facts),"evidenceIds",ids));
  }
  if(gaps&&!plan.path("hypotheses").isEmpty())errors.add("NO_INCIDENT_EVIDENCE_CANDIDATES_MUST_BE_EMPTY");
  if(!errors.isEmpty())return new Compiled(tree(Map.of()),List.copyOf(errors));
  if(!gaps&&selected.isEmpty())facts.values().stream().filter(f->eventFact(f,es,r))
    .filter(f->fallbackFactIds==null||fallbackFactIds.contains(f.path("factId").asText())).limit(6).forEach(f->selected.add(f.path("factId").asText()));
  var renderedFacts=new ArrayList<Object>();for(String id:selected){var f=facts.get(id);renderedFacts.add(Map.of("factId",id,"text",f.path("display").asText(),"evidenceId",f.path("evidenceId").asText(),"pointer",f.path("pointer").asText(),"value",f.path("value")));}
  var dataGaps=new ArrayList<String>(EvidenceCompleteness.gaps(evidence));dataGaps.addAll(timeline.gaps());actions.addAll(EvidenceCompleteness.checks(evidence));
  if(gaps)dataGaps.add("没有获得可支撑本次事件因果判断的窗口证据；不生成原因候选。");
  if(!candidates.isEmpty())dataGaps.add("候选关系及替代解释仍需人工因果审核；引用存在不等于支持结论。");
  // The task registry remains complete; a report carries metadata for its actual
  // fact selection, not a second copy of every scalar in every acquired page.
  var reportUses=new ArrayList<Object>();for(String id:selected){var f=facts.get(id);var row=new LinkedHashMap<String,Object>(map(encode(f)));row.put("measurementMeaning",f.path("timeBasis"));row.putAll(EvidenceUse.classify(f,es.get(f.path("evidenceId").asText()),r));reportUses.add(row);}
  var out=new LinkedHashMap<String,Object>();out.put("schemaVersion",SCHEMA);out.put("rendererVersion",RENDERER);out.put("modelPlan",plan);out.put("factUses",reportUses);out.put("reviewNotes",notes);
  out.put("reportFactScope",Map.of("version","selected-report-facts-v33","scope","SELECTED_REPORT_FACTS_ONLY","registeredCount",facts.size(),"includedCount",reportUses.size(),"notDuplicatedCount",facts.size()-reportUses.size(),"sourceEvidenceRetained",true,"lookup","Read the task's existing owned evidence by evidenceId and stored pointer; unselected metadata is not an assertion that no other facts exist."));
  out.put("summary",gaps?"证据不足：已保存实际来源状态，暂不能确定根因。":candidates.isEmpty()?"已确认下列观测事实，根因未确定。用户报告的其他症状及其关联尚未独立证实。":"已保存窗口观察及模型选择的待验证因果关联，不能视为已确认根因。");
  out.put("causalAssessment",Map.of("status",candidates.isEmpty()?"UNDETERMINED":"UNVERIFIED_HYPOTHESES","structuralChecksOnly",true,"humanReview","PENDING","doesNotEstablishCausality",true));
  out.put("eventTimeline",timeline.nodes());out.put("eventTimelineVersion",EventTimeline.VERSION);out.put("facts",renderedFacts);out.put("candidates",candidates);out.put("conflicts",contradictions);out.put("gaps",dataGaps);out.put("actions",actions);
  var comparisons=new ArrayList<Object>();for(var h:plan.path("hypotheses"))for(var pair:h.path("comparisons")){var b=facts.get(pair.path("baselineFactId").asText());var i=facts.get(pair.path("incidentFactId").asText());comparisons.add(Map.of("baseline",b,"incident",i,"conditions","Same service/environment, deployment, instance, metric field, unit and instantaneous kind; baseline before window, paired event within window. No persistence or causality proved."));}out.put("baselineComparisons",comparisons);
  out.put("resultType",gaps?(evidence.stream().anyMatch(e->!EvidenceRegistry.readable(e))?"INSUFFICIENT_EVIDENCE_SOURCE_FAILURE":"INSUFFICIENT_EVIDENCE"):candidates.isEmpty()?"OBSERVATIONS_WITHOUT_CAUSE":"CAUSE_CANDIDATES");
  return new Compiled(tree(out),List.of());
 }
 static String joined(JsonNode refs,Map<String,JsonNode> facts){var s=new ArrayList<String>();for(var ref:refs){var f=facts.get(ref.asText());if(f!=null)s.add(f.path("display").asText());}return String.join("；",s);}
 static void exact(JsonNode n,Set<String> allowed,Set<String> required,String p,List<String> errors){if(!n.isObject()){errors.add("EXPECTED_OBJECT:"+p);return;}for(String k:required)if(!n.has(k))errors.add("MISSING_FIELD:"+p+"/"+k);n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))errors.add("UNEXPECTED_FIELD:"+p+"/"+k);});}
 static void array(JsonNode a,int min,int max,String p,List<String> errors){if(!a.isArray()||a.size()<min||a.size()>max){errors.add("INVALID_ARRAY:"+p);return;}var unique=new HashSet<String>();for(var n:a)if(!n.isTextual()||!unique.add(n.asText()))errors.add("INVALID_OR_DUPLICATE_ID:"+p);}
 static void checks(JsonNode a,String p,List<String> errors){array(a,0,4,p,errors);for(var n:a)if(!CHECKS.containsKey(n.asText()))errors.add("UNKNOWN_CHECK:"+p);}
 static void note(JsonNode n,String p,List<String> errors){if(n.has("reviewNote")&&(!n.path("reviewNote").isTextual()||n.path("reviewNote").asText().length()>500))errors.add("INVALID_REVIEW_NOTE:"+p);}
}
