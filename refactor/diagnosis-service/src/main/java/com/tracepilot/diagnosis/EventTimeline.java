package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;import java.util.*;
/** Existing verified log contract only. No inferred operations or transition timestamps. */
final class EventTimeline {
 static final String VERSION="event-timeline-v3-verified-operations";
 static final String BUSINESS="sha256-06612d191b55b8b6782e1ba009fa27b03282a1234a422a0e6e0fbd402d81108f";
 static final String BUSINESS_OPERATIONS="sha256-0ebc193adcc27e39cc36c3f668b688a663e213930ba8df4f03073c15673e495c";
 static final String BUSINESS_PARALLEL="sha256-18a70c8fd2baebc3ff5affc3921c5f7c91b91f884b3dc7b04135d88783035079";
 // Explicit verified source contracts; an arbitrary deployment SHA never grants trust.
 static boolean supported(String version,String action){return BUSINESS_OPERATIONS.equals(version)||BUSINESS_PARALLEL.equals(version)||(BUSINESS.equals(version)&&!Set.of("event_operation_failed","event_delivery_unconfirmed").contains(action));}
 static final Map<String,String> ACTIONS=Map.ofEntries(
  Map.entry("business_event_committed","业务事务提交后观测（非事件创建时刻）"),
  Map.entry("event_operation_failed","已记录消费操作异常（失败阶段见operationPhase；不是精确失败/提交时刻）"),
  Map.entry("event_delivery_unconfirmed","通知事务返回后事件确认未生效（不表示通知未写入）"),
  Map.entry("event_claimed","消费领取已返回（不表示写入成功）"),
  Map.entry("notification_write_started","开始调用通知写入事务（不表示写入成功或提交）"),
  Map.entry("notification_attempt_error","消费处理异常（具体失败操作须核对调用栈）"),
  Map.entry("event_delivery_failed","消费失败后状态已记录"),
  Map.entry("notification_write_committed","通知事务代理已成功返回，提交后观测（非精确提交时刻）"),
  Map.entry("event_acknowledged","事件确认更新已返回"),
  Map.entry("event_ack_not_applied","事件确认更新未生效"),
  Map.entry("event_delivery_completed","消费完成已记录"),
  Map.entry("notification_query_result","当前调用者按事件查询通知的结果"));
 record Catalog(List<JsonNode> nodes,List<String> gaps) {
  Map<String,JsonNode> byId(){var m=new LinkedHashMap<String,JsonNode>();nodes.forEach(n->m.put(n.path("nodeId").asText(),n));return m;}
  JsonNode forFact(JsonNode f){String p=f.path("pointer").asText();for(var n:nodes)for(var s:n.path("sources"))if(s.path("evidenceId").asText().equals(f.path("evidenceId").asText())&&p.startsWith(s.path("pointer").asText()+"/"))return n;return null;}
 }
 static Catalog build(List<Evidence> es,Request r){
  var unique=new TreeMap<String,com.fasterxml.jackson.databind.node.ObjectNode>();var gaps=new LinkedHashSet<String>();
  // Use the same bounded projection as the model. Do not secretly read omitted rows.
  for(var e:es){if(!Set.of("logs","trace").contains(e.source())||!EvidenceRegistry.readable(e))continue;
   var projected=new HashSet<String>();for(var f:Reports.factCatalog(e))projected.add(tree(f).path("pointer").asText());
   int i=0;for(var row:e.data().path("data")){String p="/data/"+i++;String action=row.path("message").asText();if(!ACTIONS.containsKey(action)||!row.path("logger").asText().equals("tracepilot.evidence"))continue;
    if(action.equals("notification_write_committed")&&(!row.path("outcome").asText().equals("COMMITTED")||!row.path("notificationId").isIntegralNumber())){gaps.add("提交动作缺少一致的结果/通知标识，不能生成成功节点；证据="+e.id()+p);continue;}
    if(!supported(row.path("deploymentVersion").asText(),action)){gaps.add("未核实的部署版本：不生成动作语义节点；证据="+e.id());continue;}
    if(!projected.contains(p+"/message")){gaps.add("动作或时间未投影：节点未知；证据="+e.id()+p);continue;}
    var use=EvidenceUse.classify(tree(Map.of("pointer",p+"/message","value",action)),e,r);if(!Boolean.TRUE.equals(use.get("causeEligible")))continue;
    String event=row.path("eventId").asText(),attempt=row.path("attemptId").asText();if(event.isBlank()){gaps.add("缺eventId：不能关联；证据="+e.id()+p);continue;}
    String rowId=row.path("evidenceId").asText();String identity=rowId.isBlank()?e.id()+p:row.path("service").asText()+row.path("environment").asText()+row.path("deploymentVersion").asText()+rowId;
    // Content participates in identity: conflicting records are not silently collapsed.
    String id="T"+FactReferences.id(identity,encode(row)).substring(1);
    var n=unique.get(id);if(n==null){n=JSON.createObjectNode();n.put("nodeId",id);n.put("eventId",event);n.put("attemptId",attempt.isBlank()?"UNKNOWN":attempt);n.put("action",action);n.put("eventTime",row.path("time").asText("UNKNOWN"));n.put("timeSemantics",timeKind(action,row.path("time").asText("UNKNOWN")));n.put("timePrecision",clock(row.path("time").asText()).equals(Instant.MAX)?"UNKNOWN":"LOG_MILLISECOND");n.put("collectedAt",row.path("collectedAt").asText("UNKNOWN"));n.put("instanceId",row.path("instanceId").asText("UNKNOWN"));n.put("notificationId",row.path("notificationId").asText("UNKNOWN"));
     var result=JSON.createObjectNode();for(String k:List.of("operationPhase","notificationCommitObservation","outcome","status","errorType","sqlState","sqlErrorCode","notificationCount","rows","attempts"))if(row.has(k)&&projected.contains(p+"/"+k))result.set(k,row.get(k));n.set("result",result);n.set("sources",JSON.createArrayNode());unique.put(id,n);}
    var sources=(com.fasterxml.jackson.databind.node.ArrayNode)n.path("sources");var origin=tree(Map.of("evidenceId",e.id(),"pointer",p));if(!sources.toString().contains(origin.toString()))sources.add(origin);
   }
  }
  var nodes=new ArrayList<JsonNode>(unique.values());nodes.sort(Comparator.comparing((JsonNode n)->clock(n.path("eventTime").asText())).thenComparing(n->n.path("nodeId").asText()));
  var events=new TreeSet<String>();nodes.forEach(n->events.add(n.path("eventId").asText()));
  for(String event:events){var group=nodes.stream().filter(n->n.path("eventId").asText().equals(event)).toList();var first=new HashMap<String,String>();var instances=new HashSet<String>();boolean unknown=false;
   for(var n:group){instances.add(n.path("instanceId").asText());String a=n.path("attemptId").asText(),t=n.path("eventTime").asText();try{Instant.parse(t);}catch(Exception invalid){unknown=true;}if(!a.equals("UNKNOWN"))first.merge(a,t,(x,y)->clock(x).compareTo(clock(y))<=0?x:y);}
   boolean ordered=!unknown&&instances.size()==1&&!instances.contains("UNKNOWN")&&new HashSet<>(first.values()).size()==first.size()&&group.stream().map(n->clock(n.path("eventTime").asText())).distinct().count()==group.size();var attempts=first.keySet().stream().sorted(Comparator.comparing(a->clock(first.get(a)))).toList();
   for(var n:group){var obj=(com.fasterxml.jackson.databind.node.ObjectNode)n;String a=n.path("attemptId").asText();obj.put("observedAttemptOrdinal",ordered&&attempts.contains(a)?Integer.toString(attempts.indexOf(a)+1):"UNKNOWN");obj.put("orderBasis",ordered?"LOG_CLOCK_ORDER_NOT_LIFETIME_FIRST_OR_CAUSAL_ORDER":"UNCERTAIN_CLOCK_OR_TIE");}
   gaps.add("eventId="+event+"：创建时刻、生命周期首次尝试、写入调用单独成功时刻及数据库精确提交时刻均未知；仅列当前证据中的观测。日志时钟排序不证明因果先后。attempts字段是失败计数，不是生命周期尝试序号。");
   for(String action:List.of("event_claimed","notification_write_started","notification_attempt_error","notification_write_committed"))if(group.stream().noneMatch(n->n.path("action").asText().equals(action)))gaps.add("eventId="+event+"：当前证据未观测 "+action+"，不能推断该操作没有发生。");
  }
  return new Catalog(List.copyOf(nodes),List.copyOf(gaps));
 }
 static Instant clock(String value){try{return Instant.parse(value);}catch(Exception invalid){return Instant.MAX;}}
 static String timeKind(String action,String time){if(clock(time).equals(Instant.MAX))return "UNKNOWN";return Set.of("notification_write_committed","business_event_committed").contains(action)?"POST_COMMIT_UPPER_BOUND":"LOG_ACTION_OBSERVATION";}
 static String timeStatement(JsonNode n){String time=n.path("eventTime").asText("UNKNOWN");return switch(timeKind(n.path("action").asText(),time)){case "POST_COMMIT_UPPER_BOUND"->"截至该日志观测已经提交（毫秒精度记录值="+time+"）；精确提交时刻未知，记录值不是更细精度的提交时间上界";case "LOG_ACTION_OBSERVATION"->"日志观测时刻="+time+"；不是数据库状态转换时刻";default->"观测及精确提交时刻未知";};}
 static String display(JsonNode n){return "【节点="+n.path("nodeId").asText()+"；eventId="+n.path("eventId").asText()+"；attemptId="+n.path("attemptId").asText()+"；观测尝试序号="+n.path("observedAttemptOrdinal").asText()+"（非生命周期首次）；日志事件时间="+n.path("eventTime").asText()+"；时间边界="+timeStatement(n)+"；动作="+ACTIONS.get(n.path("action").asText())+"；结果="+n.path("result")+"；notificationId="+n.path("notificationId").asText()+"】";}
 static JsonNode context(Catalog c){var nodes=new ArrayList<Object>();for(var n:c.nodes()){var x=(com.fasterxml.jackson.databind.node.ObjectNode)n.deepCopy();x.remove(List.of("collectedAt","instanceId","notificationId","result","sources"));nodes.add(x);}return tree(Map.of("version",VERSION,"actions",ACTIONS,"nodes",nodes,"meaning","IDs resolve only this task. Full metadata retained in final timeline and evidence registry. eventTime is log emission, not precise transition/commit time. Observed ordinal never proves first lifetime attempt. Missing operations remain unknown; equal/missing/cross-instance timestamps do not establish order."));}
}
