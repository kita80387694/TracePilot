package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Component;

/** Reuses the existing lease, query, cancellation and finishing machinery; one active worker bean. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="diag.worker-enabled",havingValue="true")
public class DirectWorkflow extends Workflow {
 public DirectWorkflow(TaskStore store,ReadTools tools,ModelGateway model){super(store,tools,model);}
 // A source selection executes one bounded page. Continuations remain model choices.
 @Override protected boolean query(Claim c,Query q)throws Exception{return super.query(c,q,1);}
 protected void initialSources(Claim c)throws Exception{
  if(!model.configured())return;
  for(String source:List.of("metrics","logs")){
   if(!store.active(c))throw new CancellationException();
   if(store.evidence(c.id()).stream().noneMatch(e->e.source().equals(source))){
    store.step(c,"COLLECTION","BASELINE_SOURCE",Map.of("source",source,"policy","cross-source-v1","scope","one page in task window; no health or cause inferred"));
    query(c,new Query(source,Map.of()));
    // Bounded error-channel acquisition when unfiltered history has remaining pages.
    // It is a filtered observation, never a full-log completion or a cause label.
    if(source.equals("logs") && store.evidence(c.id()).stream().anyMatch(e->e.source().equals("logs")&&e.data().path("hasMore").asBoolean()))
      query(c,new Query("logs",Map.of("level","ERROR")));
   }
  }
 }
 @Override public void run(Claim c){
  var state=new LinkedHashMap<String,Object>(c.state());
  var current=new java.util.concurrent.atomic.AtomicReference<EvidenceDelivery.Frame>();
  var delivered=new LinkedHashSet<>(EvidenceDelivery.strings(tree(state.getOrDefault("deliveredFactIds",List.of()))));
  try{
   int used=((Number)store.usage(c.id()).get("model_calls")).intValue();
   if(used>0&&!DirectContract.VERSION.equals(state.get("workflowContract"))){incomplete(c,current.get(),delivered,"WORKFLOW_VERSION_CHANGED_ON_RESUME");return;}
   if(state.containsKey("modelConfiguration")&&!tree(state.get("modelConfiguration")).equals(tree(model.configuration()))){incomplete(c,current.get(),delivered,"MODEL_CONFIGURATION_CHANGED_ON_RESUME");return;}
   state.put("workflowContract",DirectContract.VERSION);state.put("modelConfiguration",model.configuration());store.checkpoint(c,state);
   if(store.evidence(c.id()).stream().noneMatch(e->e.source().equals("overview")))query(c,new Query("overview",Map.of()));
   initialSources(c);
   state.put("collectionPolicy","cross-source-v2-window-sample");state.put("windowSample",true);
   state.put("phase","INVESTIGATE");store.checkpoint(c,state);
   while(store.active(c)){
    if(state.containsKey("pendingBatch")){
     String stopped=drainBatch(c,state);if(stopped!=null){incomplete(c,current.get(),delivered,stopped);return;}
    }
    if(!model.configured()){incomplete(c,current.get(),delivered,"MODEL_NOT_CONFIGURED");return;}
    var budget=store.usage(c.id());
    boolean budgetForced=((Number)budget.get("max_tools")).intValue()-((Number)budget.get("tool_calls")).intValue()<1
       ||((Number)budget.get("max_models")).intValue()-((Number)budget.get("model_calls")).intValue()<=1;
    if(budgetForced)state.put("budgetForcedReport",true);
    boolean reportOnly=Boolean.TRUE.equals(state.get("reportOnly"))
       ||((Number)budget.get("max_tools")).intValue()-((Number)budget.get("tool_calls")).intValue()<1
       ||((Number)budget.get("max_models")).intValue()-((Number)budget.get("model_calls")).intValue()<=1;
    boolean queryAllowed=!reportOnly; // Shared task budgets bound all selections; no hidden three-query ceiling.
    state.put("reportOnly",reportOnly);store.checkpoint(c,state);
    if(!store.reserve(c,"MODEL")){incomplete(c,current.get(),delivered,"MODEL_BUDGET_OR_DEADLINE");return;}
    ModelReply reply;
    try{
     reply=bounded(()->{
      if(!store.active(c))throw new CancellationException();
      var frame=WorkingContext.prepare(contextData(c,state),state);current.set(frame);String input=frame.text();
      store.step(c,"ACTION","INPUT_SCOPE",Map.of("contract",DirectContract.VERSION,"contextUtf16",input.length(),"reportOnly",reportOnly,"auditHistoryIncluded",false,"delivery",frame.delivery(),"providedFactIds",new TreeSet<>(frame.visible())));
      int ordinal=((Number)store.usage(c.id()).get("model_calls")).intValue();
      java.util.function.Consumer<Map<String,Object>> observer=event->store.step(c,"MODEL_TRANSPORT",event.get("status").toString(),event);
      return model instanceof ActionModelGateway gateway?gateway.callAction(reportOnly,queryAllowed,input,c.id(),ordinal,observer):model.call(DirectContract.prompt(reportOnly,queryAllowed),input,c.id(),ordinal,observer);
     },Math.min(50000,Math.max(1,Duration.between(Instant.now(),c.deadline()).toMillis())));
     var usage=new LinkedHashMap<String,Object>();usage.put("model",reply.model());usage.put("protocol",reply.protocol());usage.put("promptVersion",model instanceof ActionModelGateway gateway?gateway.actionPromptVersion():PROMPT_VERSION);usage.put("inputTokens",reply.inputTokens());usage.put("outputTokens",reply.outputTokens());
     usage.put("promptSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((model instanceof ActionModelGateway gateway?gateway.actionPrompt(reportOnly,queryAllowed):DirectContract.prompt(reportOnly,queryAllowed)).getBytes(java.nio.charset.StandardCharsets.UTF_8))));store.step(c,"MODEL","SUCCEEDED",usage);
    }catch(Exception failure){
     if(rootError(failure) instanceof RequestBoundary.LocalFailure local){store.step(c,"MODEL","LOCAL_FAILURE",Map.of("boundary",local.detail,"requestSent",false,"tokenUsage","NOT_RETURNED"));incomplete(c,current.get(),delivered,"LOCAL_REQUEST_BUILD_FAILED");return;}
     store.step(c,"MODEL",timedOut(failure)?"TIMEOUT":"UNAVAILABLE",Map.of("errorType",rootError(failure).getClass().getSimpleName(),"tokenUsage","UNKNOWN"));
     int retries=((Number)state.getOrDefault("modelFailures",0)).intValue()+1;state.put("modelFailures",retries);store.checkpoint(c,state);
     if(retries>1||!transientFailure(failure)||Boolean.TRUE.equals(model.configuration().get("captureStopOnError"))){incomplete(c,current.get(),delivered,"MODEL_CALL_FAILED");return;}continue;
    }
    if(!store.active(c)){incomplete(c,current.get(),delivered,"CANCELLED_OR_DEADLINE");return;}
    var frame=current.get();delivered.addAll(frame.visible());state.put("deliveredFactIds",new ArrayList<>(delivered));store.checkpoint(c,state);
    var action=reply.action();boolean batch=action.path("type").asText().equals("boundedBatch");
    var errors=new ArrayList<String>();
    if(batch){
     if(!BoundedActions.VERSION.equals(model.configuration().get("actionProtocol")))errors.add(BoundedActions.issue("BATCH_PROTOCOL_NOT_ENABLED",0));
     else errors.addAll(BoundedActions.validate(action,frame,reportOnly,queryAllowed));
     if(errors.isEmpty())errors.addAll(BoundedActions.budgetErrors(action,((Number)budget.get("max_tools")).intValue()-((Number)budget.get("tool_calls")).intValue(),c.request(),store.evidence(c.id())));
     if(errors.isEmpty()){
      var prospective=new LinkedHashMap<>(state);setBatchView(action,prospective);
      try{WorkingContext.prepare(frame.source(),prospective);}catch(RequestBoundary.LocalFailure tooLarge){errors.add(BoundedActions.issue("COMBINED_CONTEXT_LIMIT",action.path("actions").size()));}
     }
    }else errors.addAll(DirectContract.errors(action,reportOnly,queryAllowed));ReportPlan.Compiled compiled=null;
    if(!batch&&errors.isEmpty())errors.addAll(action.path("type").asText().equals("report")?EvidenceDelivery.reportErrors(action,frame):EvidenceDelivery.selectionErrors(action,frame));
    if(errors.isEmpty()&&action.path("type").asText().equals("report")){compiled=DirectContract.render(action,store.evidence(c.id()),c.request(),frame.visible());errors.addAll(compiled.errors());}
    if(!errors.isEmpty()){
     int repairs=((Number)state.getOrDefault("actionRepairs",0)).intValue();
     var remaining=store.usage(c.id());boolean repairBudget=((Number)remaining.get("model_calls")).intValue()<((Number)remaining.get("max_models")).intValue()&&Instant.now().isBefore(c.deadline());
     store.step(c,"ACTION","REJECTED",Map.of("schemaVersion",DirectContract.VERSION,"errors",errors,"actionRecord",ActionDiagnostics.redact(action,""),"nextHandling",repairs==0&&repairBudget?"ONE_CORRECTION":"END_PARTIAL","correctionSent",false));
     if(!repairBudget){incomplete(c,current.get(),delivered,"DIRECT_CONTRACT_FAILED_NO_REPAIR_BUDGET");return;}
     if(repairs>=1||(action.path("type").asText().equals("INVALID_JSON")&&Boolean.TRUE.equals(model.configuration().get("captureStopOnError")))){incomplete(c,current.get(),delivered,"DIRECT_CONTRACT_FAILED");return;}
     state.put("actionRepairs",repairs+1);state.put("correction",WorkingContext.correction(errors,action));store.checkpoint(c,state);continue;
    }
    state.remove("correction");
    if(batch){
     var pins=new LinkedHashSet<String>();boolean hasPins=false;for(var a:action.path("actions"))if(a.has("retainFactIds")){hasPins=true;pins.addAll(EvidenceDelivery.strings(a.path("retainFactIds")));}
     if(hasPins)state.put("retainedFactIds",new ArrayList<>(pins));
     state.put("pendingBatch",new LinkedHashMap<>(Map.of("actions",action.path("actions"),"callIds",action.path("callIds"),"nextIndex",0,"status","READY","views",List.of())));
     store.checkpoint(c,state);store.step(c,"BATCH","ACCEPTED",Map.of("count",action.path("actions").size(),"policy",BoundedActions.VERSION));continue;
    }
    if(compiled!=null){
     store.step(c,"REVIEW","VALIDATED",Map.of("validation","STRUCTURAL_ONLY","semanticReview","PENDING_HUMAN","candidateCount",action.path("hypotheses").size()));
     state.put("phase","REPORT");store.checkpoint(c,state);
     EvidenceDelivery.disclose((com.fasterxml.jackson.databind.node.ObjectNode)compiled.report(),frame,delivered);
     var assessment=CompletionCoverage.assess(store.evidence(c.id()),frame,delivered,compiled.report(),Boolean.TRUE.equals(state.get("budgetForcedReport")));
     CompletionCoverage.disclose((com.fasterxml.jackson.databind.node.ObjectNode)compiled.report(),assessment);
     finish(c,Boolean.TRUE.equals(assessment.get("complete"))?"COMPLETED":"PARTIAL",new LinkedHashMap<>(map(encode(compiled.report()))));return;
    }
    if(action.has("retainFactIds"))state.put("retainedFactIds",EvidenceDelivery.strings(action.path("retainFactIds")));
    if(action.path("type").asText().equals("readEvidence")){
     state.remove("previousQuerySelection");
     state.remove("evidenceViews");
     if(!store.reserve(c,"TOOL"))throw new BudgetEnded();
     var view=Map.of("evidenceId",action.path("evidenceId").asText(),"page",action.path("page").asInt());
     boolean repeated=tree(view).equals(tree(frame.delivery().getOrDefault("currentPage",Map.of())));
     var previousPages=tree(frame.delivery().getOrDefault("currentPages",List.of()));
     for(var previous:previousPages)if(previous.equals(tree(view)))repeated=true;
     state.put("evidenceView",view);
     if(repeated){if(!previousPages.isEmpty()){state.remove("evidenceView");state.put("evidenceViews",previousPages);}closeRepeatedRead(state);}
     store.step(c,"EVIDENCE_READ",repeated?"REUSED":"SELECTED",Map.of("view",view,"toolBudgetConsumed",true,"reason",repeated?"Same page already provided; next action must conclude within remaining budget":"Immutable task-local page selected for next request; no new source query"));
     store.checkpoint(c,state);continue;
    }
    Query query=DirectContract.query(action);boolean fresh=query(c,query);
    if(fresh){state.remove("evidenceView");state.remove("evidenceViews");}
    state.put("queries",((Number)state.getOrDefault("queries",0)).intValue()+1);
    var receipt=EvidenceRegistry.queryReceipt(c.request(),query,store.evidence(c.id()),fresh);
    state.put("lastQuery",receipt);state.put("lastResults",List.of(receipt));
    closeRepeatedQueries(c,state,List.of(EvidenceRegistry.fingerprint(c.request(),query)),fresh);
    store.checkpoint(c,state); // Reusing one query must not close other sources. Model budget still advances.
   }
   incomplete(c,current.get(),delivered,"CANCELLED_OR_DEADLINE");
  }catch(BudgetEnded ended){incomplete(c,current.get(),delivered,"TOOL_BUDGET_OR_DEADLINE");}
   catch(Exception failure){incomplete(c,current.get(),delivered,"WORKFLOW_ERROR: "+failure.getClass().getSimpleName());}
 }
 private static void setBatchView(com.fasterxml.jackson.databind.JsonNode action,Map<String,Object> state){
  var views=new ArrayList<Object>();var pins=new LinkedHashSet<String>();boolean supplied=false;
  for(var a:action.path("actions")){
   if(a.path("type").asText().equals("readEvidence"))views.add(Map.of("evidenceId",a.path("evidenceId").asText(),"page",a.path("page").asInt()));
   if(a.has("retainFactIds")){supplied=true;pins.addAll(EvidenceDelivery.strings(a.path("retainFactIds")));}
  }
  if(!views.isEmpty()){state.remove("evidenceView");state.put("evidenceViews",views);}
  if(supplied)state.put("retainedFactIds",new ArrayList<>(pins));
 }
 private String drainBatch(Claim c,Map<String,Object> state)throws Exception{
  var pending=new LinkedHashMap<>(map(encode(state.get("pendingBatch"))));
  if(!"READY".equals(pending.get("status")))return "BATCH_PREVIOUS_CALL_OUTCOME_UNCONFIRMED";
  var actions=tree(pending.get("actions"));var views=new ArrayList<Object>();tree(pending.getOrDefault("views",List.of())).forEach(views::add);
  var receipts=new ArrayList<Object>();tree(pending.getOrDefault("receipts",List.of())).forEach(receipts::add);
  boolean freshQueries=Boolean.TRUE.equals(pending.get("freshQueries"));
  int start=((Number)pending.get("nextIndex")).intValue();
  for(int i=start;i<actions.size();i++){
   if(!store.active(c))return "CANCELLED_OR_DEADLINE";
   var action=actions.get(i);pending.put("status","IN_FLIGHT");pending.put("nextIndex",i);state.put("pendingBatch",pending);store.checkpoint(c,state);
   store.step(c,"BATCH","STARTED",Map.of("index",i,"callId",tree(pending.get("callIds")).get(i).asText(),"remaining",actions.size()-i));
   try{
    if(action.path("type").asText().equals("readEvidence")){
     if(!store.reserve(c,"TOOL"))throw new BudgetEnded();
     var view=tree(Map.of("evidenceId",action.path("evidenceId").asText(),"page",action.path("page").asInt()));
     boolean repeated=views.stream().map(Domain::tree).anyMatch(view::equals);if(!repeated)views.add(view);
     store.step(c,"EVIDENCE_READ",repeated?"REUSED":"SELECTED",Map.of("view",view,"toolBudgetConsumed",true,"batchIndex",i));
    }else{
      Query q=DirectContract.query(action);boolean fresh=query(c,q);
     if(!store.active(c))return "CANCELLED_OR_DEADLINE";
     var receipt=EvidenceRegistry.queryReceipt(c.request(),q,store.evidence(c.id()),fresh);receipts.add(receipt);
     state.put("lastQuery",receipt);freshQueries|=fresh;
     var es=store.evidence(c.id());String fingerprint=EvidenceRegistry.fingerprint(c.request(),ReadTools.boundedPage(q));
     var matching=es.stream().filter(e->fingerprint.equals(e.locator().get("queryFingerprint"))).toList();
     if(!matching.isEmpty()&&!Set.of("AVAILABLE","PARTIAL","NO_DATA").contains(matching.getLast().status())){
      pending.put("status","SOURCE_FAILED");state.put("pendingBatch",pending);store.checkpoint(c,state);return "BATCH_SOURCE_FAILED_REMAINING_NOT_EXECUTED";
     }
    }
   }catch(BudgetEnded budget){pending.put("status","BUDGET_EXHAUSTED");state.put("pendingBatch",pending);store.checkpoint(c,state);return "BATCH_TOOL_BUDGET_EXHAUSTED_REMAINING_NOT_EXECUTED";}
   if(!store.active(c))return "CANCELLED_OR_DEADLINE";
   pending.put("status","READY");pending.put("nextIndex",i+1);pending.put("views",new ArrayList<>(views));pending.put("receipts",new ArrayList<>(receipts));pending.put("freshQueries",freshQueries);state.put("pendingBatch",pending);store.checkpoint(c,state);
   store.step(c,"BATCH","COMPLETED_CALL",Map.of("index",i,"remaining",actions.size()-i-1));
  }
  var previous=new HashSet<String>();var previousViews=new ArrayList<Object>();
  for(var v:tree(state.getOrDefault("evidenceViews",List.of()))){previous.add(viewKey(v));previousViews.add(v);}
  var single=tree(state.getOrDefault("evidenceView",Map.of()));if(single.has("evidenceId")){previous.add(viewKey(single));previousViews.add(single);}
  boolean repeatedRead=!freshQueries&&!views.isEmpty()&&views.stream().map(Domain::tree).allMatch(v->previous.contains(viewKey(v)));
  if(views.isEmpty()){
   var signatures=new ArrayList<String>();for(var a:actions)signatures.add(EvidenceRegistry.fingerprint(c.request(),DirectContract.query(a)));
   closeRepeatedQueries(c,state,signatures,freshQueries);
  }else state.remove("previousQuerySelection");
  if(freshQueries||!views.isEmpty()){state.remove("evidenceView");state.remove("evidenceViews");if(!views.isEmpty())state.put("evidenceViews",views);}
  if(repeatedRead){state.put("evidenceViews",previousViews);closeRepeatedRead(state);store.step(c,"COLLECTION","CLOSED_NO_PROGRESS",Map.of("reason",state.get("collectionClosure"),"views",previousViews,"budgetReset",false));}
  state.put("lastResults",receipts);
  state.remove("pendingBatch");store.checkpoint(c,state);store.step(c,"BATCH","COMPLETED",Map.of("count",actions.size()));return null;
 }
 private static String viewKey(com.fasterxml.jackson.databind.JsonNode v){return v.path("evidenceId").asText()+"#"+v.path("page").asInt(-1);}
 private void closeRepeatedQueries(Claim c,Map<String,Object> state,List<String> signatures,boolean fresh){
  var selection=new TreeSet<>(signatures);
  var seen=new LinkedHashSet<String>();tree(state.getOrDefault("cachedQuerySelections",List.of())).forEach(v->seen.add(v.asText()));
  String key=encode(selection);
  boolean repeated=!fresh&&!selection.isEmpty()&&seen.contains(key);
  // A fresh result for this exact selection invalidates its old cached observation. An
  // unrelated query does not make an unchanged cached selection new evidence again.
  if(fresh)seen.remove(key);else if(!selection.isEmpty())seen.add(key);
  state.put("cachedQuerySelections",new ArrayList<>(seen));
  state.put("previousQuerySelection",new ArrayList<>(selection));
  state.put("previousQueryWasCached",!fresh);
  if(repeated){
   closeRepeatedRead(state);state.put("collectionClosure","REPEATED_QUERY_SELECTION_NO_NEW_EVIDENCE");
   store.step(c,"COLLECTION","CLOSED_NO_PROGRESS",Map.of("reason",state.get("collectionClosure"),"queryFingerprints",selection,"budgetReset",false));
  }
 }
 private static void closeRepeatedRead(Map<String,Object> state){
  state.put("reportOnly",true);state.put("collectionClosure","REPEATED_STORED_PAGES_NO_NEW_EVIDENCE");
  // Closing acquisition must not erase the model's already-selected observation pages.
  // EvidenceDelivery adds bounded window context without replacing these selected facts.
 }
 private void incomplete(Claim c,EvidenceDelivery.Frame frame,Set<String> delivered,String reason){
  var report=(com.fasterxml.jackson.databind.node.ObjectNode)tree(Reports.partial(reason,store.evidence(c.id())));
  if(frame!=null)EvidenceDelivery.disclose(report,frame,delivered);
  var notAssembled=store.evidence(c.id()).stream().map(Evidence::id).filter(id->frame==null||!frame.pages().containsKey(id)).toList();
  if(!notAssembled.isEmpty()){
   report.set("notAssembledEvidenceIds",tree(notAssembled));
   ((com.fasterxml.jackson.databind.node.ArrayNode)report.path("gaps")).add("Some stored source results were never assembled into a model input; see notAssembledEvidenceIds. No delivery or review is claimed.");
  }
  finish(c,"PARTIAL",new LinkedHashMap<>(map(encode(report))));
 }
}
