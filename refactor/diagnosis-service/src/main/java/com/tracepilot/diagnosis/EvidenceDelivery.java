package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Task-local views of immutable registered facts. Never edits stored evidence or source cursors. */
final class EvidenceDelivery {
 static final String VERSION="evidence-pages-v3-envelope";
 static final int PAGE_UTF16=24000, RETAIN_UTF16=16000, MAX_RETAIN=72;
 record Page(List<String> facts,List<String> unavailable){}
 record Frame(String text,Set<String> visible,Set<String> registered,Map<String,List<Page>> pages,
              Map<String,JsonNode> facts,Map<String,Object> source,Map<String,Object> delivery){}

 // Request-local index; never serialized as a model field or persisted as extra evidence.
 private static final class IndexedSource extends LinkedHashMap<String,Object>{
  final Map<String,List<JsonNode>> rows=new LinkedHashMap<>();
  IndexedSource(Map<String,Object> original){super(original);for(String key:List.of("causeFactRegistry","backgroundFactRegistry")){
   var list=new ArrayList<JsonNode>();for(var f:tree(original.getOrDefault(key,List.of())))list.add(f);rows.put(key,list);
  }}
 }
 private static Iterable<JsonNode> registryRows(Map<String,Object> source,String key){
  return source instanceof IndexedSource indexed?indexed.rows.get(key):tree(source.getOrDefault(key,List.of()));
 }
 static Frame prepare(Map<String,Object> source,Map<String,Object> state){
  source=new IndexedSource(source);
  var facts=new LinkedHashMap<String,JsonNode>();
  for(String key:List.of("causeFactRegistry","backgroundFactRegistry"))for(var f:registryRows(source,key))facts.put(f.path("factId").asText(),f);
  var pages=new LinkedHashMap<String,List<Page>>();
  for(var e:tree(source.getOrDefault("evidence",List.of()))){String id=e.path("id").asText();var own=facts.values().stream().filter(f->f.path("evidenceId").asText().equals(id)).toList();pages.put(id,partition(source,own));}
  Set<String> all=new LinkedHashSet<>(facts.keySet());var view=tree(state.getOrDefault("evidenceView",Map.of()));
  var pins=strings(tree(state.getOrDefault("retainedFactIds",List.of())));
  if(!all.containsAll(pins))throw new IllegalArgumentException("RETAINED_FACT_NOT_REGISTERED");
  var views=tree(state.getOrDefault("evidenceViews",List.of()));
  if(views.isArray()&&!views.isEmpty()){
   if(views.size()>BoundedActions.MAX)throw new IllegalArgumentException("TOO_MANY_EVIDENCE_VIEWS");
   var visible=new LinkedHashSet<>(pins);var unavailable=new LinkedHashSet<String>();
   for(var selectedView:views){String evidenceId=selectedView.path("evidenceId").asText();int number=selectedView.path("page").asInt(-1);
    if(!pages.containsKey(evidenceId)||number<0||number>=pages.get(evidenceId).size())throw new IllegalArgumentException("EVIDENCE_PAGE_NOT_REGISTERED");
    var selected=pages.get(evidenceId).get(number);visible.addAll(selected.facts());unavailable.addAll(selected.unavailable());
   }
   var info=manifest(source,pages,all,visible,"MULTI_PAGE_BOUNDED",List.copyOf(unavailable));info.put("currentPages",views);
   return selectedFrame(source,state,visible,all,pages,facts,info); // Never silently drop a selected page.
  }
  // Preserve the original full delivery path whenever the final serialized evidence fits.
  if(!view.has("evidenceId")){
   var info=manifest(source,pages,all,all,"ALL",List.of());
   try{return frame(source,all,all,pages,facts,info);}
   catch(RequestBoundary.LocalFailure large){if(!"evidence".equals(large.detail.get("section")))throw large;}
  }
  if(!view.has("evidenceId") && Boolean.TRUE.equals(state.get("windowSample"))){
   var selected=windowSample(source,facts);selected.addAll(pins);
   var omitted=new ArrayList<String>();
   while(true){
    var info=manifest(source,pages,all,selected,"WINDOW_OBSERVATION_SAMPLE",List.of());
    info.put("samplePolicy","At most three complete stored observations per query scope across its pages, evenly distributed by stored row order (first/middle/last). Round-robin across scopes. Final serialized metadata shares the original context limit. No value-based cause selection; unprovided observations remain available, not absent.");
    info.put("sampleObservationsOmittedForContext",omitted);
    try{return frame(source,selected,all,pages,facts,info);}
    catch(RequestBoundary.LocalFailure limit){
     if(!"evidence".equals(limit.detail.get("section")))throw limit;
     var groups=new LinkedHashMap<String,List<String>>();for(String id:selected)groups.computeIfAbsent(CompletionCoverage.observationKey(facts.get(id)),k->new ArrayList<>()).add(id);
     var removable=groups.entrySet().stream().filter(e->e.getValue().stream().noneMatch(pins::contains)).toList();
     if(removable.isEmpty())throw limit;
     var drop=removable.getLast();selected.removeAll(drop.getValue());omitted.add(drop.getKey());
    }
   }
  }
  String id=view.path("evidenceId").asText(pages.isEmpty()?"":new ArrayList<>(pages.keySet()).getLast());int page=view.path("page").asInt(0);
  if(!pages.containsKey(id)||page<0||page>=pages.get(id).size())throw new IllegalArgumentException("EVIDENCE_PAGE_NOT_REGISTERED");
  Page selected=pages.get(id).get(page);var visible=new LinkedHashSet<>(pins);visible.addAll(selected.facts());
  var info=manifest(source,pages,all,visible,"PAGED",selected.unavailable());info.put("currentPage",Map.of("evidenceId",id,"page",page));
  return selectedFrame(source,state,visible,all,pages,facts,info);
 }

 // Report synthesis keeps selected pages mandatory. Window observations are supplementary;
 // only whole supplementary groups may be omitted under the unchanged context limit.
 private static Frame selectedFrame(Map<String,Object> source,Map<String,Object> state,Set<String> mandatory,
   Set<String> all,Map<String,List<Page>> pages,Map<String,JsonNode> facts,Map<String,Object> original){
  if(!Boolean.TRUE.equals(state.get("reportOnly"))||!Boolean.TRUE.equals(state.get("windowSample")))
   return frame(source,mandatory,all,pages,facts,original);
  var selected=new LinkedHashSet<>(mandatory);var supplemental=windowSample(source,facts);
  supplemental.removeAll(strings(tree(original.getOrDefault("unavailableFactIds",List.of()))));selected.addAll(supplemental);
  var omitted=new ArrayList<String>();
  while(true){
   var info=manifest(source,pages,all,selected,"REPORT_SELECTED_WITH_WINDOW_SAMPLE",strings(tree(original.getOrDefault("unavailableFactIds",List.of()))));
   var reportSources=new ArrayList<Object>();
   for(var item:tree(info.get("sources"))){var entry=new LinkedHashMap<>(map(encode(item)));entry.remove("indexPreview");entry.put("indexPreviewComplete",false);entry.put("navigationOmitted","REPORT_ONLY_NO_FURTHER_PAGE_SELECTION; original page counts and source coverage retained");reportSources.add(entry);}
   info.put("sources",reportSources);
   for(String key:List.of("currentPage","currentPages"))if(original.containsKey(key))info.put(key,original.get(key));
   info.put("samplePolicy","Previously selected pages remain mandatory; supplementary first/middle/last observations use remaining space. This is not exhaustive coverage or causal validation.");
   info.put("sampleObservationsOmittedForContext",List.copyOf(omitted));
   try{return frame(source,selected,all,pages,facts,info);}
   catch(RequestBoundary.LocalFailure limit){
    if(!"evidence".equals(limit.detail.get("section")))throw limit;
    var groups=new LinkedHashMap<String,List<String>>();
    for(String id:selected)groups.computeIfAbsent(CompletionCoverage.observationKey(facts.get(id)),k->new ArrayList<>()).add(id);
    var removable=groups.entrySet().stream().filter(e->e.getValue().stream().noneMatch(mandatory::contains)).toList();
    if(removable.isEmpty())throw limit;
    var drop=removable.getLast();selected.removeAll(drop.getValue());omitted.add(drop.getKey());
   }
  }
 }

 static LinkedHashSet<String> windowSample(Map<String,Object> source,Map<String,JsonNode> facts){
  var perEvidence=new LinkedHashMap<String,LinkedHashMap<String,List<String>>>();
  for(var f:facts.values()){
   String pointer=f.path("pointer").asText();boolean row=pointer.matches("^/data/[0-9]+(?:/.*)?$");
   if(!row&&!Set.of("code","runbook","sqlPlan").contains(f.path("source").asText()))continue;
   perEvidence.computeIfAbsent(f.path("evidenceId").asText(),k->new LinkedHashMap<>())
    .computeIfAbsent(row?pointer.replaceFirst("^(/data/[0-9]+).*","$1"):"ROOT_QUERY_RESULT",k->new ArrayList<>()).add(f.path("factId").asText());
  }
  var scopes=new LinkedHashMap<String,LinkedHashMap<String,List<String>>>();
  for(var e:tree(source.getOrDefault("evidence",List.of()))){
   String id=e.path("id").asText();var groups=perEvidence.get(id);if(groups==null)continue;
   var args=new TreeMap<String,String>();e.at("/locator/query/args").fields().forEachRemaining(a->{if(!Set.of("cursor","limit").contains(a.getKey()))args.put(a.getKey(),a.getValue().asText());});
   if(e.path("source").asText().equals("logs"))args.putIfAbsent("channel","all");
   String scope=e.path("source").asText()+"#"+e.path("start").asText()+"#"+e.path("end").asText()+"#"+encode(args);
   var merged=scopes.computeIfAbsent(scope,k->new LinkedHashMap<>());for(var group:groups.entrySet())merged.put(id+"#"+group.getKey(),group.getValue());
  }
  var candidates=new ArrayList<List<List<String>>>();
  for(var groups:scopes.values()){
   var values=new ArrayList<>(groups.values());var chosen=new ArrayList<List<String>>();int count=Math.min(3,values.size());
   for(int i=0;i<count;i++)chosen.add(values.get(count==1?0:(int)((long)i*(values.size()-1)/(count-1))));
   candidates.add(chosen);
  }
  var selected=new LinkedHashSet<String>();
  for(int i=0;i<3;i++)for(var rows:candidates)if(i<rows.size()){
   var trial=new LinkedHashSet<>(selected);trial.addAll(rows.get(i));
   if(payloadSize(source,trial)<=48000)selected=trial;
  }
  return selected;
 }

 private static Frame frame(Map<String,Object> source,Set<String> selected,Set<String> all,Map<String,List<Page>> pages,Map<String,JsonNode> facts,Map<String,Object> info){
  var input=slice(source,selected);input.put("evidenceDelivery",info);ContextPacking.pack(input);
  input.put("contextBudget",RequestBoundary.sections(input));String text=RequestBoundary.context(encode(input));
  return new Frame(text,Set.copyOf(selected),Set.copyOf(all),pages,facts,source,info);
 }

 private static List<Page> partition(Map<String,Object> source,List<JsonNode> facts){
  var groups=new LinkedHashMap<String,List<String>>();
  for(var fact:facts){String pointer=fact.path("pointer").asText();String group=pointer.startsWith("/data/")?pointer.replaceFirst("^(/data/[0-9]+).*","$1"):"metadata";groups.computeIfAbsent(group,k->new ArrayList<>()).add(fact.path("factId").asText());}
  var out=new ArrayList<Page>();var current=new ArrayList<String>();
  for(var group:groups.values()){
   var trial=new ArrayList<>(current);trial.addAll(group);
   if(payloadSize(source,trial)<=PAGE_UTF16){current=trial;continue;}
   if(!current.isEmpty()){out.add(new Page(List.copyOf(current),List.of()));current.clear();}
   if(payloadSize(source,group)<=PAGE_UTF16){current.addAll(group);continue;}
   // A large observation may span pages, but a scalar is never silently cut or misrepresented.
   for(String fact:group){trial=new ArrayList<>(current);trial.add(fact);
    if(payloadSize(source,trial)<=PAGE_UTF16){current=trial;continue;}
    if(!current.isEmpty()){out.add(new Page(List.copyOf(current),List.of()));current.clear();}
    if(payloadSize(source,List.of(fact))>PAGE_UTF16)out.add(new Page(List.of(),List.of(fact)));else current.add(fact);
   }
  }
  if(!current.isEmpty()||out.isEmpty())out.add(new Page(List.copyOf(current),List.of()));return List.copyOf(out);
 }

 static int payloadSize(Map<String,Object> source,Collection<String> selected){
  var input=slice(source,new HashSet<>(selected));input.keySet().retainAll(Set.of("causeFactRegistry","backgroundFactRegistry","eventTimeline"));ContextPacking.pack(input);return encode(input).length();
 }
 private static LinkedHashMap<String,Object> slice(Map<String,Object> source,Set<String> ids){
  var out=new LinkedHashMap<>(source);var nodeIds=new HashSet<String>();
  for(String key:List.of("causeFactRegistry","backgroundFactRegistry")){var rows=new ArrayList<Object>();for(var fact:registryRows(source,key))if(ids.contains(fact.path("factId").asText())){rows.add(fact);if(fact.has("timelineNodeId"))nodeIds.add(fact.path("timelineNodeId").asText());}out.put(key,rows);}
  if(source.containsKey("eventTimeline")){var timeline=(ObjectNode)tree(source.get("eventTimeline"));var nodes=JSON.createArrayNode();for(var n:timeline.path("nodes"))if(nodeIds.contains(n.path("nodeId").asText()))nodes.add(n);timeline.set("nodes",nodes);timeline.put("viewMeaning","Only nodes associated with this view's facts are included. Missing nodes here are not missing operations; consult delivery page counts and source coverage.");out.put("eventTimeline",timeline);}
  return out;
 }
 private static Map<String,Object> manifest(Map<String,Object> source,Map<String,List<Page>> pages,Set<String> all,Set<String> visible,String mode,List<String> unavailable){
  var index=new ArrayList<Object>();int sourceCount=Math.max(1,tree(source.getOrDefault("evidence",List.of())).size()),sourceOrdinal=0;
  for(var e:tree(source.getOrDefault("evidence",List.of()))){String id=e.path("id").asText();var list=pages.get(id);if(list==null)continue;
   var indexed=new HashMap<String,JsonNode>();for(String key:List.of("causeFactRegistry","backgroundFactRegistry"))for(var f:registryRows(source,key))indexed.put(f.path("factId").asText(),f);
   var preview=new ArrayList<Object>();int count=Math.min(list.size(),16/sourceCount+(sourceOrdinal++<16%sourceCount?1:0));
   for(int n=0;n<count;n++){int i=count<=1?0:(int)((long)n*(list.size()-1)/(count-1));var p=list.get(i);preview.add(pageIndex(i,p,indexed));}
   index.add(Map.of("evidenceId",id,"source",e.path("source"),"status",e.path("status"),"pageCount",list.size(),"indexPreview",preview,"indexPreviewComplete",list.size()<=count));
  }
  var info=new LinkedHashMap<String,Object>();info.put("version",VERSION);info.put("navigationPreviewLimit",16);info.put("mode",mode);info.put("registeredFactCount",all.size());info.put("providedFactCount",visible.size());info.put("notProvidedFactCount",all.size()-visible.size());info.put("complete",visible.containsAll(all));info.put("sources",index);
  info.put("unavailableFactIds",unavailable);info.put("unavailableReason",unavailable.isEmpty()?"NONE":"SCALAR_EXCEEDS_SINGLE_PAGE_BUDGET: full value retained in stored evidence, not supplied or citable in this view");
  info.put("continuation","readEvidence(evidenceId,page) reads only this task's immutable registered facts. Pages are zero-based, valid up to pageCount-1 even if index preview is shorter. Optional retainFactIds keeps selected currently provided facts across pages/queries. Reading consumes the existing tool/model budgets. Original source pagination and projection are separate; inspect each evidence's coverage. Unprovided evidence is not absent, healthy, zero, or reviewed.");
  return info;
 }
 static Map<String,Object> pageIndex(int number,Page page,Map<String,JsonNode> facts){
  var paths=new TreeSet<String>();var times=new TreeMap<java.time.Instant,String>();
  for(String id:page.facts()){
   var f=facts.get(id);if(f==null)continue;
   String path=f.path("pointer").asText().replaceFirst("^/data/[0-9]+","/data/*");
   paths.add(path.length()<=160?path:"[FIELD_PATH_TOO_LONG_FOR_INDEX]");
   String time=f.path("eventTime").asText();var instant=EventTimeline.clock(time);if(!instant.equals(java.time.Instant.MAX))times.putIfAbsent(instant,time);
  }
  return Map.of("page",number,"factCount",page.facts().size(),"unavailableScalarCount",page.unavailable().size(),
   "fieldPaths",paths.stream().limit(16).toList(),"fieldIndexComplete",paths.size()<=16,
   "firstObservedTime",times.isEmpty()?"UNKNOWN":times.firstEntry().getValue(),"lastObservedTime",times.isEmpty()?"UNKNOWN":times.lastEntry().getValue(),
   "indexOnlyNotCitable",true);
 }
 static List<String> selectionErrors(JsonNode action,Frame frame){
  var errors=new ArrayList<String>();
  if(action.has("retainFactIds")){
   var ids=strings(action.path("retainFactIds"));for(String id:ids)if(!frame.visible().contains(id))errors.add(issue("FACT_NOT_IN_CURRENT_INPUT","/retainFactIds",id,"choose a fully provided fact ID"));
   if(errors.isEmpty()&&payloadSize(frame.source(),ids)>RETAIN_UTF16)errors.add(issue("RETAIN_BUDGET_EXCEEDED","/retainFactIds",payloadSize(frame.source(),ids),Map.of("maxUtf16",RETAIN_UTF16,"correction","retain fewer specific facts; full source evidence remains stored")));
  }
  if(action.path("type").asText().equals("readEvidence")){
   String id=action.path("evidenceId").asText();int page=action.path("page").asInt(-1);var registered=frame.pages().get(id);
   if(registered==null)errors.add(issue("EVIDENCE_NOT_IN_TASK","/evidenceId",id,"an evidenceId listed in this request's delivery sources"));
   else if(page<0||page>=registered.size())errors.add(issue("PAGE_OUT_OF_RANGE","/page",page,Map.of("min",0,"max",registered.size()-1)));
  }
  return errors;
 }
 static List<String> reportErrors(JsonNode action,Frame frame){
  var errors=new ArrayList<String>();checkReferences(action,"",frame,errors);return errors;
 }
 private static void checkReferences(JsonNode n,String path,Frame frame,List<String> errors){
  if(n.isObject())n.fields().forEachRemaining(e->checkReferences(e.getValue(),path+"/"+e.getKey(),frame,errors));
  else if(n.isArray()){int i=0;for(var item:n)checkReferences(item,path+"/"+i++,frame,errors);}
  else if(n.isTextual()){
   String value=n.asText();var refs=new ArrayList<String>();if(value.matches("F[0-9a-f]{24}"))refs.add(value);var matcher=FactReferences.REF.matcher(value);while(matcher.find())refs.add(matcher.group(1));
   for(String id:refs)if(frame.registered().contains(id)&&!frame.visible().contains(id)){
    String evidenceId=frame.facts().get(id).path("evidenceId").asText();var locations=new ArrayList<Integer>();var pages=frame.pages().getOrDefault(evidenceId,List.of());
    for(int i=0;i<pages.size();i++)if(pages.get(i).facts().contains(id))locations.add(i);
    errors.add(issue("FACT_NOT_IN_CURRENT_INPUT",path,id,Map.of("evidenceId",evidenceId,"pages",locations,"correction",locations.isEmpty()?"Full scalar cannot fit a page; disclose the limit or withdraw the claim":"Read the listed page and retain necessary current facts; do not cite an unprovided value")));
   }
  }
 }
 static List<String> strings(JsonNode values){var out=new ArrayList<String>();values.forEach(v->out.add(v.asText()));return out;}
 static String issue(String code,String path,Object actual,Object expected){return encode(Map.of("code",code,"path",path,"actual",actual,"expected",expected));}
 static void disclose(ObjectNode report,Frame frame,Set<String> sent){
  var info=new LinkedHashMap<>(frame.delivery());info.put("confirmedProvidedFactCount",frame.registered().stream().filter(sent::contains).count());info.put("unreadRegisteredFactCount",frame.registered().stream().filter(id->!sent.contains(id)).count());info.put("currentFactIds",new TreeSet<>(frame.visible()));info.put("serverGeneratedAppendices","Full evidence/fact registries and deterministic timeline summaries are server-generated. Their presence in the report does not mean they were supplied to or reviewed by the model; consult currentFactIds and delivery records.");report.set("evidenceDelivery",tree(info));
  if(!sent.containsAll(frame.registered()))((com.fasterxml.jackson.databind.node.ArrayNode)report.path("gaps")).add("部分已保存事实未送达模型；已提供="+sent.size()+"，注册事实="+frame.registered().size()+"。证据分页/调用预算限制不代表这些观测不存在，不能宣称已完整审查。");
 }
}
