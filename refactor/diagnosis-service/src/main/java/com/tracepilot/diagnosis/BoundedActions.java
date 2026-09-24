package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.ai.chat.model.ChatResponse;

/** Internal envelope only; no model-authored batch discriminator or additional authority. */
final class BoundedActions {
 static final String VERSION="named-tools-v2-bounded";
 // At most one third of the default 12-call task allowance in a response; actual budget remains authoritative.
 static final int MAX=4;
 static NamedToolProtocol.Decoded decode(ChatResponse response,boolean reportOnly,boolean queryAllowed){
  if(response.getResults().stream().anyMatch(g->"max_tokens".equals(g.getMetadata().getFinishReason())))return error("OUTPUT_TRUNCATED",0);
  var calls=response.getResults().stream().flatMap(g->g.getOutput().getToolCalls().stream()).toList();
  if(calls.isEmpty()||calls.size()>MAX)return error("BATCH_COUNT_OUT_OF_RANGE",calls.size());
  if(calls.size()==1)return NamedToolProtocol.decode(response,reportOnly,queryAllowed);
  var actions=new ArrayList<JsonNode>();var ids=new LinkedHashSet<String>();var errors=new ArrayList<String>();
  for(int i=0;i<calls.size();i++){
   var c=calls.get(i);if(c.id()==null||c.id().isBlank()||!ids.add(c.id()))errors.add(issue("DUPLICATE_OR_MISSING_CALL_ID",i));
   var d=NamedToolProtocol.decode(c.name(),c.arguments(),reportOnly,queryAllowed);
   for(String e:d.errors()){var n=(com.fasterxml.jackson.databind.node.ObjectNode)parse(e);n.put("path","/calls/"+i+n.path("path").asText());errors.add(encode(n));}
   if(d.errors().isEmpty()){if(d.action().path("type").asText().equals("report"))errors.add(issue("REPORT_MUST_BE_ALONE",i));actions.add(d.action());}
  }
  return new NamedToolProtocol.Decoded(errors.isEmpty()?tree(Map.of("type","boundedBatch","actions",actions,"callIds",ids)):JSON.nullNode(),List.copyOf(errors));
 }
 static List<String> validate(JsonNode batch,EvidenceDelivery.Frame frame,boolean reportOnly,boolean queryAllowed){
  var errors=new ArrayList<String>();var actions=batch.path("actions");
  if(!actions.isArray()||actions.size()<2||actions.size()>MAX)return List.of(issue("BATCH_COUNT_OUT_OF_RANGE",actions.size()));
  var retained=new LinkedHashSet<String>();int i=0;
  for(var action:actions){
   if(!Set.of("tool","readEvidence").contains(action.path("type").asText()))errors.add(issue("BATCH_READ_ONLY_ACTION_REQUIRED",i));
   errors.addAll(DirectContract.errors(action,reportOnly,queryAllowed));errors.addAll(EvidenceDelivery.selectionErrors(action,frame));
   retained.addAll(EvidenceDelivery.strings(action.path("retainFactIds")));i++;
  }
  if(retained.size()>EvidenceDelivery.MAX_RETAIN||EvidenceDelivery.payloadSize(frame.source(),retained)>EvidenceDelivery.RETAIN_UTF16)errors.add(issue("COMBINED_RETAIN_BUDGET_EXCEEDED",retained.size()));
  return errors;
 }
 static String issue(String code,int actual){return encode(Map.of("code",code,"path","/calls","actual",actual,"expected",Map.of("maxCalls",MAX,"reportAlone",true),"correction","Use available read-only tools within the batch cap; submit reports alone. Invalid calls are not silently discarded."));}
 static List<String> budgetErrors(JsonNode batch,int remaining,Request request,List<Evidence> evidence){
  int minimum=0;var fresh=new HashSet<String>();
  for(var action:batch.path("actions")){
   if(action.path("type").asText().equals("readEvidence")){minimum++;continue;}
   Query q=ReadTools.boundedPage(DirectContract.query(action));
   try{ReadTools.validate(q);}catch(IllegalArgumentException|SecurityException invalid){minimum++;continue;}
   if(!EvidenceRegistry.reusableComplete(request,q,evidence).isEmpty())continue;
   String fp=EvidenceRegistry.fingerprint(request,q);
   var prior=evidence.stream().filter(e->fp.equals(e.locator().get("queryFingerprint"))).toList();
   if(!prior.isEmpty()&&(!Set.of("TIMEOUT","UNAVAILABLE").contains(prior.getLast().status())||prior.size()>=2))continue;
   if(fresh.add(fp))minimum++;
  }
  if(minimum<=remaining)return List.of();
  return List.of(encode(Map.of("code","BATCH_REMAINING_TOOL_BUDGET","path","/calls","actual",Map.of("minimumToolCalls",minimum,"selectedCalls",batch.path("actions").size()),"expected",Map.of("remainingToolCalls",remaining),"correction","No calls in this batch were executed. Select a batch within the remaining tool allowance, or submit a report using provided evidence. The original model, correction and time budgets remain unchanged.")));
 }
 static NamedToolProtocol.Decoded error(String code,int actual){return new NamedToolProtocol.Decoded(JSON.nullNode(),List.of(issue(code,actual)));}
}
