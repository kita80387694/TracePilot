package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;
/** Source coverage and follow-up observations are rendered from actual results, never model quantities. */
final class EvidenceCompleteness {
 static boolean scanComplete(Evidence start,List<Evidence> es){
  var seen=new HashSet<String>();Evidence e=start;
  while(seen.add(e.id())){
   if(!EvidenceRegistry.readable(e)||e.data().has("sourceGap")||!e.data().path("gap").asText("NONE").equals("NONE"))return false;
   if(!e.data().path("hasMore").asBoolean())return !e.status().equals("PARTIAL");
   String cursor=e.data().path("nextCursor").asText();var query=tree(e.locator()).path("query");
   var match=new ArrayList<Evidence>();
   for(var candidate:es){var q=tree(candidate.locator()).path("query");if(candidate.source().equals(e.source())&&candidate.start().equals(e.start())&&candidate.end().equals(e.end())&&q.path("args").path("cursor").asText().equals(cursor)){
    var a=q.path("args").deepCopy();var b=query.path("args").deepCopy();if(a.isObject()&&b.isObject()){((com.fasterxml.jackson.databind.node.ObjectNode)a).remove("cursor");((com.fasterxml.jackson.databind.node.ObjectNode)b).remove("cursor");if(a.equals(b))match.add(candidate);}
   }}if(match.isEmpty())return false;e=match.getLast();
  }return false;
 }
 static Map<String,Object> projection(Evidence e){
  var projected=Reports.project(e);var catalog=projected.facts();var rows=new HashSet<Integer>();
  for(var x:catalog){String p=tree(x).path("pointer").asText();if(p.startsWith("/data/"))rows.add(Integer.parseInt(p.split("/")[2]));}
  boolean complete=projected.complete();
  return Map.of("storedRows",e.data().path("data").size(),"projectedRows",rows.size(),"scalarValues",catalog.size(),"projectionComplete",complete,"maxRows",Reports.MAX_ARRAY_ITEMS,"maxScalars",Reports.MAX_SCALARS,
    "continuation","If projectionComplete=false, requery same channel/filter/window with smaller limit from original cursor; stored page is retained. Source hasMore is independent of projection completeness.");
 }
 static List<String> gaps(List<Evidence> es){
  var out=new ArrayList<String>();
  for(var e:es){var p=projection(e);
   String level=tree(e.locator()).at("/query/args/level").asText();
   if(!level.isBlank())out.add("日志结果限定level="+level+"；其他级别未查询，不能据此断言整个窗口无重试或成功记录；证据="+e.id());
   if(!EvidenceRegistry.readable(e))out.add("来源 "+e.source()+" 查询状态="+e.status()+"；未取得有效数据；证据="+e.id());
   else if(e.status().equals("NO_DATA"))out.add("来源 "+e.source()+" 成功返回空结果；不代表健康；证据="+e.id());
   if(e.data().path("hasMore").asBoolean()&&!scanComplete(e,es))out.add("来源 "+e.source()+" 尚未完成扫描；该页原剩余 "+(e.data().has("remainingBytes")?FactReferences.bytes(e.data().path("remainingBytes")):"未提供字节数")+"；保持窗口、channel和过滤条件，使用注册表 nextCursor 继续；证据="+e.id());
   if(e.data().has("sourceGap"))out.add("来源覆盖缺口="+e.data().path("sourceGap").asText()+"；sourceCoverageStart="+e.data().path("sourceCoverageStart").asText("UNKNOWN")+"；证据="+e.id());
   if(!Boolean.TRUE.equals(p.get("projectionComplete")))out.add("证据投影未完成：已投影行="+p.get("projectedRows")+"，已存行="+p.get("storedRows")+"；以较小limit重查该页；证据="+e.id());
   if(e.status().equals("PARTIAL")&&!e.data().path("hasMore").asBoolean()&&!e.data().has("sourceGap"))out.add("来源 "+e.source()+" 有未解决缺口="+e.data().path("gap").asText("UNKNOWN")+"；证据="+e.id());
  }return out;
 }
 static List<String> checks(List<Evidence> es){
  var out=new ArrayList<String>();var messages=new HashSet<String>();boolean events=false;
  for(var e:es){events|=e.data().path("channel").asText().equals("events");for(var row:e.data().path("data"))messages.add(row.path("message").asText());}
  if(es.stream().anyMatch(e->e.data().path("hasMore").asBoolean()&&!scanComplete(e,es)))out.add("按实际nextCursor补齐相同窗口/channel/过滤条件的扫描，再区分“未返回该事件”与“该范围未记录事件”；不要从缺行推断未执行。");
  if(events){
   if(!messages.contains("event_claimed"))out.add("补充对应eventId的领取时间、attemptId及状态，区分尚未领取与已经处理；当前返回中没有领取记录不证明消费者停止。");
   if(!messages.contains("event_delivery_failed")&&!messages.contains("event_delivery_completed"))out.add("补充该attemptId的完成/失败及重试时间，区分处理中、失败退避和终止失败。");
   if(!messages.contains("notification_write_committed"))out.add("按eventId/attemptId查通知事务提交及notificationId，区分未写入与已写入但outbox未确认。");
   if(!messages.contains("notification_query_result"))out.add("用原用户权限按eventId查询通知并保留notificationId与查询时间，区分尚未生成和查询归属/可见性问题。");
   out.add("核对同一eventId、attemptId的错误→重试→通知提交→确认及用户查询时间顺序；排除不同事件或不同用户混配，日志关联本身不证明最深层原因。");
  }
  if(es.stream().noneMatch(e->e.source().equals("metrics")))out.add("补查原窗口指标采样，区分瞬时异常与持续异常；累计计数须有同实例前后采样才能计算增量。");
  return out;
 }
}
