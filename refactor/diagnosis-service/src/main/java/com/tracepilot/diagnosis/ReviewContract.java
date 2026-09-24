package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
/** Versioned v7 boundary. Legacy v5 compiler overload preserves old limits for historical replay. */
final class ReviewContract {
 static final String VERSION="reviewed-report-v9-resource-bounded";
 static final int MAX_ROLE_REFERENCES=24, MAX_PLAN_UTF8_BYTES=4096;
 static final List<String> ROLES=List.of("premiseFactIds","outcomeFactIds","contradictionFactIds");
 static JsonNode directPlanSchema(){
  var schema=planSchema();((ObjectNode)schema.at("/properties/mechanism")).put("description","Required: null means unknown; otherwise concise qualitative mechanism inference. Use {{fact:F...}} for values/times/units from currently provided incident observations. A display reference need not be repeated in a causal role array; it is not automatically causal support. Unknown, unread, background and out-of-window references are rejected. Role arrays still identify independent causal evidence. Whole candidate <=4096 serialized UTF8 bytes; no causal correctness guarantee.");return schema;
 }
 static JsonNode planSchema(){var n=ReportPlan.schema().at("/properties/hypotheses/items").deepCopy();var props=(ObjectNode)n.path("properties");props.remove(List.of("timelineNodeIds","reviewNote","checks"));props.set("mechanism",tree(Map.of("oneOf",List.of(Map.of("type","null"),Map.of("type","string","minLength",1,"maxLength",MAX_PLAN_UTF8_BYTES)),"description","Required: null means unknown; otherwise concise qualitative inference linking the existing premiseFactIds to outcomeFactIds. No new reference set. Values/times/units/locators use {{fact:F...}} from these roles, never copied literals. The entire candidate including references must fit 4096 serialized UTF8 bytes. Not causal proof.")));for(String role:ROLES)props.set(role,tree(ReportPlan.strings(role.equals("premiseFactIds")?1:0,MAX_ROLE_REFERENCES)));((ObjectNode)n).put("description","Resource policy v9: each role <=24 unique IDs, serialized plan <=4096 UTF8 bytes; not a causal support quota. At most three candidate plans. No silent trimming.");((ObjectNode)n).set("required",tree(List.of("relation","premiseFactIds","outcomeFactIds","contradictionFactIds","mechanism")));return n;}
 static JsonNode schema(){return tree(Map.of("type","object","additionalProperties",false,"required",List.of("hypothesisId","status","plan","issues"),"properties",Map.of("hypothesisId",Map.of("type","string"),"status",Map.of("enum",Hypotheses.STATES.stream().sorted().toList()),"plan",Map.of("oneOf",List.of(Map.of("type","null"),planSchema())),"issues",ReportPlan.checkSchema())));}
 static Hypotheses.Review review(JsonNode action,JsonNode previous,List<Evidence> es,Request r){
  var errors=new ArrayList<String>();ContractErrors.validate(action,tree(Map.of("type","object","additionalProperties",false,"required",List.of("type","assessments"),"properties",Map.of("type",Map.of("enum",List.of("review")),"assessments",Map.of("type","array","maxItems",3)))),"",errors);
  var converted=JSON.createArrayNode();var priorIds=new HashSet<String>();previous.forEach(h->priorIds.add(h.path("hypothesisId").asText()));var seenIds=new HashSet<String>();
  int ordinal=0;for(var a:action.path("assessments")){String path="/assessments/"+ordinal++;int before=errors.size();ContractErrors.validate(a,schema(),path,errors);
   String id=a.path("hypothesisId").asText();if(!priorIds.contains(id)||!seenIds.add(id))ContractErrors.add(errors,"UNKNOWN_OR_DUPLICATE_HYPOTHESIS",path+"/hypothesisId",a.path("hypothesisId"),priorIds,"Select each existing hypothesis ID exactly once; do not create another ID in REVIEW.");
   if(a.path("status").asText().equals("SUPPORTED")&&!a.path("plan").isObject())ContractErrors.add(errors,"MISSING_SUPPORTED_PLAN",path+"/plan",a.path("plan"),"registered fact relation object","Supply an independently supported relation or explicitly choose a non-supported status with plan=null.");
   if(!a.path("status").asText().equals("SUPPORTED")&&!a.path("plan").isNull())ContractErrors.add(errors,"NON_SUPPORTED_PLAN_MUST_BE_NULL",path+"/plan",a.path("plan"),"null","Return null for a pending, refuted or insufficient hypothesis.");
if(errors.size()==before&&a.path("plan").isObject())validateReferences(a.path("plan"),es,r,path+"/plan",errors);if(!a.isObject())continue;
   var copy=(ObjectNode)a.deepCopy();copy.remove("issues");copy.set("gaps",a.path("issues"));
   if(a.path("plan").isObject()){
    if(errors.isEmpty())copy.set("plan",internalPlan(a.path("plan"),a.path("issues"),es,r));
   }converted.add(copy);
  }
  for(String id:priorIds)if(!seenIds.contains(id))ContractErrors.add(errors,"MISSING_REVIEW_ASSESSMENT","/assessments",action.path("assessments"),Map.of("requiredHypothesisId",id),"Assess this existing hypothesis, including refutation or insufficiency if appropriate.");
  if(!errors.isEmpty())return new Hypotheses.Review(List.of(),List.copyOf(errors));
  var result=Hypotheses.review(tree(Map.of("assessments",converted)),previous,es,r,MAX_ROLE_REFERENCES);
  var diagnosed=new ArrayList<String>();for(String error:result.errors()){
   if(error.startsWith("HYPOTHESIS:")){String[] parts=error.split(":",4);int index=0;for(var a:action.path("assessments")){if(a.path("hypothesisId").asText().equals(parts[1]))break;index++;}String path="/assessments/"+index+"/plan";
    diagnosed.add(encode(Map.of("code",parts[2],"category",parts[2].equals("CAUSAL_SELF_REFERENCE")?"EVIDENCE_INSUFFICIENT":"REFERENCE_SCOPE_ERROR","path",path,"actualType","OBJECT","actual",action.path("assessments").path(index).path("plan"),"expected",parts.length>3?parts[3]:parts[2],"correction","Withdraw this unsupported relation or select independently relevant observations. Existence and window checks alone do not establish causality; do not invent replacements.")));
   }else diagnosed.add(encode(ContractErrors.describe(error)));
  }
  return new Hypotheses.Review(diagnosed.isEmpty()?result.records():List.of(),List.copyOf(diagnosed));
 }
 static void validateReferences(JsonNode plan,List<Evidence> es,Request r,String path,List<String> errors){
  validateReferences(plan,es,r,path,errors,Set.of());
 }
 static void validateReferences(JsonNode plan,List<Evidence> es,Request r,String path,List<String> errors,Set<String> provided){
  mechanism(plan,es,r,path,errors,provided);
  int bytes=encode(plan).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  if(bytes>MAX_PLAN_UTF8_BYTES)errors.add(encode(Map.of("code","SERIALIZED_SIZE_LIMIT","path",path,"actualType","SERIALIZED_JSON","actual",Map.of("utf8Bytes",bytes),"expected",Map.of("maxUtf8Bytes",MAX_PLAN_UTF8_BYTES),"correction","Select a smaller relevant plan or explicitly withdraw; never silently trim observations.")));
  var catalog=new HashMap<String,JsonNode>();EvidenceUse.catalog(es,r).forEach(f->catalog.put(tree(f).path("factId").asText(),tree(f)));
  for(String role:ROLES){int i=0;for(var ref:plan.path(role)){String p=path+"/"+role+"/"+i++;var fact=catalog.get(ref.asText());if(fact==null){errors.add(encode(Map.of("code","UNKNOWN_OR_UNAUTHORIZED_FACT","path",p,"actualType",ref.getNodeType().name(),"actual",ref.asText().matches("F[a-zA-Z0-9]{1,64}")?ref.asText():"REDACTED","expected","ID in this task registry","correction","Use an existing permitted reference or explicitly withdraw; no ID mapping is guessed.")));}else if(!fact.path("causeEligible").asBoolean())errors.add(encode(Map.of("code","NOT_INCIDENT_OBSERVATION","path",p,"actual",ref.asText(),"actualType","STRING","expected","incident observation in this task window","scope",Map.of("factId",ref.asText(),"purpose",fact.path("purpose"),"eventTime",fact.path("eventTime"),"queryWindow",fact.path("queryWindow"),"timeBasis",fact.path("timeBasis")),"correction","This registered fact has the stated scope; use as background only or withdraw the causal selection.")));}}
 }
 static String mechanism(JsonNode plan,List<Evidence> es,Request r,String path,List<String> errors){
  return mechanism(plan,es,r,path,errors,Set.of());
 }
 static String mechanism(JsonNode plan,List<Evidence> es,Request r,String path,List<String> errors,Set<String> provided){
  // Display-only references may use current incident observations without being
  // promoted into premise/outcome roles. Legacy calls without a provided frame
  // retain their role-only rule; production delivery validates visibility too.
  var value=plan.path("mechanism");if(value.isNull())return "未知";
  if(!value.isTextual()){ContractErrors.add(errors,value.isMissingNode()?"MISSING_FIELD":"TYPE_MISMATCH",path+"/mechanism",value,"string or explicit null","Provide an explanation or null; no analysis is invented.");return "";}
  String text=value.asText();if(text.isBlank()||text.codePointCount(0,text.length())>MAX_PLAN_UTF8_BYTES)ContractErrors.add(errors,"INVALID_MECHANISM_LENGTH",path+"/mechanism",value,"nonblank text or null; whole candidate <=4096 serialized UTF8 bytes","Use concise analysis or explicit null; no truncation.");
  var facts=new HashMap<String,JsonNode>();FactReferences.catalog(es,r).forEach(f->facts.put(tree(f).path("factId").asText(),tree(f)));var permitted=new HashSet<String>();for(String role:ROLES)plan.path(role).forEach(id->permitted.add(id.asText()));
  var evidenceById=new HashMap<String,Evidence>();es.forEach(e->evidenceById.put(e.id(),e));
  var timeline=EventTimeline.build(es,r);var matcher=FactReferences.REF.matcher(text);var out=new StringBuffer();
  while(matcher.find()){String id=matcher.group(1);var f=facts.get(id);boolean displayAllowed=f!=null&&provided.contains(id)&&ReportPlan.eventFact(f,evidenceById,r);
   if(f==null||(!permitted.contains(id)&&!displayAllowed)){var issue=new LinkedHashMap<String,Object>(ContractErrors.issue("UNKNOWN_OR_UNSELECTED_MECHANISM_FACT",path+"/mechanism",tree(id),"selected causal fact or currently provided incident display fact","Use a provided in-window observation for display; do not promote background or unread facts into causal roles to bypass scope checks."));if(f!=null&&provided.contains(id))issue.put("actual",id);errors.add(encode(issue));matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(matcher.group()));}
   else{var node=timeline.forFact(f);String display=node==null?f.path("display").asText():EventTimeline.display(node)+"；原字段="+f.path("pointer").asText()+"，原值="+f.path("value");matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(display));}}
  matcher.appendTail(out);String rest=FactReferences.REF.matcher(text).replaceAll("");if(rest.contains("{{")||rest.contains("}}"))ContractErrors.add(errors,"MALFORMED_FACT_REFERENCE",path+"/mechanism",value,"{{fact:registeredId}}","Correct the reference syntax; do not supply paths or guessed replacements.");return out.toString();
 }
 static Set<String> mechanismReferences(JsonNode plan){var refs=new LinkedHashSet<String>();var matcher=FactReferences.REF.matcher(plan.path("mechanism").asText(""));while(matcher.find())refs.add(matcher.group(1));return refs;}
 static JsonNode internalPlan(JsonNode wire,JsonNode issues,List<Evidence> es,Request r){
  var plan=(ObjectNode)wire.deepCopy();plan.set("checks",issues.isEmpty()?tree(List.of("VERIFY_MECHANISM")):issues);
  var facts=new HashMap<String,JsonNode>();FactReferences.catalog(es,r).forEach(f->facts.put(tree(f).path("factId").asText(),tree(f)));var timeline=EventTimeline.build(es,r);var nodes=new LinkedHashSet<String>();
  for(String field:List.of("premiseFactIds","outcomeFactIds","contradictionFactIds"))for(var ref:wire.path(field)){var f=facts.get(ref.asText());if(f!=null){var n=timeline.forFact(f);if(n!=null)nodes.add(n.path("nodeId").asText());}}
  if(!nodes.isEmpty())plan.set("timelineNodeIds",tree(nodes));return plan;
 }
 static JsonNode publicPlan(JsonNode plan){var copy=plan.deepCopy();if(copy.isObject())((ObjectNode)copy).remove(List.of("timelineNodeIds","checks","reviewNote"));return copy;}
 static List<Object> working(JsonNode records){var out=new ArrayList<Object>();for(var h:records)out.add(Map.of("hypothesisId",h.path("hypothesisId"),"initialPlan",h.path("initialPlan"),"initialEvidenceIds",h.path("initialEvidenceIds"),"status",h.path("status"),"legacyTextPolicy","Old free text remains in audit only, not factual working context"));return out;}
 static List<Object> approved(JsonNode records){var out=new ArrayList<Object>();for(var h:records)if(h.path("status").asText().equals("SUPPORTED"))out.add(Map.of("hypothesisId",h.path("hypothesisId"),"plan",publicPlan(h.path("plan")),"issues",h.path("gaps"),"causality","UNVERIFIED"));return out;}
 static ReportPlan.Compiled render(JsonNode selection,JsonNode reviewed,List<Evidence> es,Request r,boolean gaps){
  var errors=new ArrayList<String>();ContractErrors.validate(selection,Hypotheses.selectionSchema(),"/report",errors);
  var approved=new HashSet<String>();reviewed.forEach(h->{if(h.path("status").asText().equals("SUPPORTED"))approved.add(h.path("hypothesisId").asText());});int i=0;for(var id:selection.path("hypothesisIds")){if(!approved.contains(id.asText()))ContractErrors.add(errors,"NOT_APPROVED_HYPOTHESIS","/report/hypothesisIds/"+i,id,approved,"Withdraw this non-approved hypothesis ID or select an existing approved ID.");i++;}
  if(!errors.isEmpty())return new ReportPlan.Compiled(tree(Map.of()),errors);
  var selectedIds=new HashSet<String>();selection.path("hypothesisIds").forEach(id->selectedIds.add(id.asText()));for(var h:reviewed)if(selectedIds.contains(h.path("hypothesisId").asText())){ContractErrors.validate(publicPlan(h.path("plan")),planSchema(),"/report/plan",errors);mechanism(h.path("plan"),es,r,"/report/plan",errors);}
  if(!errors.isEmpty())return new ReportPlan.Compiled(tree(Map.of()),errors);
  var compiled=Hypotheses.render(selection,reviewed,es,r,gaps,MAX_ROLE_REFERENCES);if(!compiled.errors().isEmpty())return compiled;
  var report=(ObjectNode)compiled.report();report.put("schemaVersion",VERSION);report.put("rendererVersion","report-renderer-v9-selected-facts");
  var plans=new ArrayList<Object>();for(var h:report.path("modelPlan").path("hypotheses"))plans.add(publicPlan(h));report.set("modelPlan",tree(Map.of("hypotheses",plans,"checks",selection.path("checks"))));
  var unresolved=new ArrayList<Object>();for(var h:reviewed){var checks=new ArrayList<String>();for(var code:h.path("gaps"))if(ReportPlan.CHECKS.containsKey(code.asText()))checks.add(ReportPlan.CHECKS.get(code.asText()));if(!checks.isEmpty())unresolved.add(Map.of("hypothesisId",h.path("hypothesisId"),"status",h.path("status"),"issueCodes",h.path("gaps"),"questions",checks,"meaning","Next checks, not assertions that data or an operation is absent"));}report.set("unresolvedQuestions",tree(unresolved));
  report.set("timeComparisons",tree(TimeComparisons.generate(EventTimeline.build(es,r))));return compiled;
 }
}
