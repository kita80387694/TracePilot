package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;import java.util.*;
/** Durable public hypothesis state, not private model reasoning. */
final class Hypotheses {
 static final String SCHEMA="reviewed-report-v5";
 static final Set<String> STATES=Set.of("PENDING","SUPPORTED","REFUTED","INSUFFICIENT_EVIDENCE");
 record Review(List<Object> records,List<String> errors){}
 static List<Object> seed(JsonNode candidates){if(!HypothesisInput.errors(candidates).isEmpty())throw new IllegalArgumentException("INVALID_HYPOTHESIS_ITEMS");var rows=new ArrayList<Object>();int i=0;for(var c:candidates){rows.add(Map.of("hypothesisId","H"+(++i),"hypothesis",c.path("hypothesis"),"initialEvidenceIds",c.path("evidenceIds"),"status","PENDING","supportFactIds",List.of(),"contradictionFactIds",List.of(),"gaps",List.of("Not reviewed; initial references are not causal support")));}return rows;}
 static JsonNode reviewSchema(){return tree(Map.of("type","object","required",List.of("hypothesisId","status","plan","gaps"),"additionalProperties",false,"properties",Map.of("hypothesisId",Map.of("type","string"),"status",Map.of("enum",STATES.stream().sorted().toList()),"plan",Map.of("description","null unless SUPPORTED; SUPPORTED requires one ReportPlan hypothesis","oneOf",List.of(Map.of("type","null"),ReportPlan.schema().at("/properties/hypotheses/items"))),"gaps",ReportPlan.strings(0,6),"supportFactIds",referenceSchema("Optional. For SUPPORTED omit this redundant field; server derives it from plan.premiseFactIds. If supplied it MUST equal premiseFactIds exactly, NOT premise plus outcome IDs. For other states only actual fact IDs, at most 3."),"contradictionFactIds",referenceSchema("Optional. For SUPPORTED omit; if supplied MUST equal plan.contradictionFactIds exactly. For other states only actual fact IDs, at most 3."))));}
 static JsonNode referenceSchema(String description){var node=(com.fasterxml.jackson.databind.node.ObjectNode)tree(ReportPlan.strings(0,3));node.put("description",description);return node;}
 static Review review(JsonNode action,JsonNode previous,List<Evidence> es,Request r){
  return review(action,previous,es,r,3);
 }
 static Review review(JsonNode action,JsonNode previous,List<Evidence> es,Request r,int referenceLimit){
  var errors=new ArrayList<String>();var rows=new ArrayList<Object>();var prior=new LinkedHashMap<String,JsonNode>();previous.forEach(h->prior.put(h.path("hypothesisId").asText(),h));var seen=new HashSet<String>();
  JsonNode all=action.path("assessments");if(!all.isArray()||all.size()>3)return new Review(List.of(),List.of("INVALID_ASSESSMENTS"));
  for(var a:all){String id=a.path("hypothesisId").asText();String path="/assessments/"+id;
   ReportPlan.exact(a,Set.of("hypothesisId","status","plan","gaps","supportFactIds","contradictionFactIds"),Set.of("hypothesisId","status","plan","gaps"),path,errors);
   if(!prior.containsKey(id)||!seen.add(id)){errors.add("UNKNOWN_OR_DUPLICATE_HYPOTHESIS:"+id);continue;}
   String status=a.path("status").asText();if(!STATES.contains(status))errors.add("INVALID_HYPOTHESIS_STATUS:"+id);
   ReportPlan.array(a.path("gaps"),0,6,path+"/gaps",errors);
   if(status.equals("SUPPORTED")){
    var validation=ReportPlan.compile(tree(Map.of("hypotheses",List.of(a.path("plan")),"checks",List.of())),es,r,false,referenceLimit);
    validation.errors().forEach(e->errors.add("HYPOTHESIS:"+id+":"+e));
    if(a.has("supportFactIds")&&!a.path("supportFactIds").equals(a.path("plan").path("premiseFactIds")))errors.add("REVIEW_REFERENCE_MISMATCH:"+id+":supportFactIds");
    if(a.has("contradictionFactIds")&&!a.path("contradictionFactIds").equals(a.path("plan").path("contradictionFactIds")))errors.add("REVIEW_REFERENCE_MISMATCH:"+id+":contradictionFactIds");
   }else if(!a.path("plan").isNull())errors.add("NON_SUPPORTED_PLAN_MUST_BE_NULL:"+id);
   for(String field:List.of("supportFactIds","contradictionFactIds"))if(a.has(field)){ReportPlan.array(a.path(field),0,3,path+"/"+field,errors);var known=new HashSet<String>();EvidenceUse.catalog(es,r).forEach(v->known.add(tree(v).path("factId").asText()));for(var ref:a.path(field))if(!known.contains(ref.asText()))errors.add("UNKNOWN_ASSESSMENT_FACT:"+id+":"+ref.asText());}
   var row=new LinkedHashMap<>(map(encode(prior.get(id))));row.put("status",status);row.put("plan",a.path("plan"));row.put("gaps",a.path("gaps"));row.put("supportFactIds",a.has("supportFactIds")?a.path("supportFactIds"):a.path("plan").has("premiseFactIds")?a.path("plan").path("premiseFactIds"):List.of());row.put("contradictionFactIds",a.has("contradictionFactIds")?a.path("contradictionFactIds"):a.path("plan").has("contradictionFactIds")?a.path("plan").path("contradictionFactIds"):List.of());row.put("supportMeaning","Model assessment plus scope/reference checks; NOT causal proof");rows.add(row);
  }
  for(String id:prior.keySet())if(!seen.contains(id))errors.add("MISSING_REVIEW_ASSESSMENT:"+id);
  return new Review(rows,List.copyOf(errors));
 }
 static List<Object> approved(JsonNode records){var out=new ArrayList<Object>();for(var h:records)if(h.path("status").asText().equals("SUPPORTED"))out.add(Map.of("hypothesisId",h.path("hypothesisId").asText(),"plan",h.path("plan"),"gaps",h.path("gaps"),"causality","UNVERIFIED"));return out;}
 static List<Object> unresolved(JsonNode records){var out=new ArrayList<Object>();for(var h:records)if(!h.path("status").asText().equals("SUPPORTED"))out.add(Map.of("hypothesisId",h.path("hypothesisId").asText(),"status",h.path("status").asText(),"gaps",h.path("gaps")));return out;}
 static JsonNode selectionSchema(){return tree(Map.of("type","object","additionalProperties",false,"required",List.of("hypothesisIds","checks"),"properties",Map.of("hypothesisIds",ReportPlan.strings(0,3),"checks",ReportPlan.checkSchema())));}
 static ReportPlan.Compiled render(JsonNode selection,JsonNode reviewed,List<Evidence> es,Request r,boolean gaps){
  return render(selection,reviewed,es,r,gaps,3);
 }
 static ReportPlan.Compiled render(JsonNode selection,JsonNode reviewed,List<Evidence> es,Request r,boolean gaps,int referenceLimit){
  var errors=new ArrayList<String>();ReportPlan.exact(selection,Set.of("hypothesisIds","checks"),Set.of("hypothesisIds","checks"),"/report",errors);ReportPlan.array(selection.path("hypothesisIds"),0,3,"/report/hypothesisIds",errors);ReportPlan.checks(selection.path("checks"),"/report/checks",errors);
  var approved=new LinkedHashMap<String,JsonNode>();for(var h:reviewed)if(h.path("status").asText().equals("SUPPORTED"))approved.put(h.path("hypothesisId").asText(),h.path("plan"));
  var plans=new ArrayList<Object>();for(var id:selection.path("hypothesisIds")){if(!approved.containsKey(id.asText()))errors.add("NOT_APPROVED_HYPOTHESIS:"+id.asText());else plans.add(approved.get(id.asText()));}
  if(!errors.isEmpty())return new ReportPlan.Compiled(tree(Map.of()),errors);
  var compiled=ReportPlan.compile(tree(Map.of("hypotheses",plans,"checks",selection.path("checks"))),es,r,gaps,referenceLimit);if(!compiled.errors().isEmpty())return compiled;
  var report=(com.fasterxml.jackson.databind.node.ObjectNode)compiled.report();report.put("schemaVersion",SCHEMA);report.put("rendererVersion","report-renderer-v5");report.set("selection",selection);var statuses=new ArrayList<Object>();for(var h:reviewed)statuses.add(Map.of("hypothesisId",h.path("hypothesisId"),"status",h.path("status"),"supportFactIds",h.path("supportFactIds"),"contradictionFactIds",h.path("contradictionFactIds"),"rawAuditLocation","task steps: HYPOTHESES/REVIEWED; raw model prose is not a rendered measurement"));report.set("hypothesisAssessments",tree(statuses));report.set("unresolvedQuestions",tree(statuses.stream().filter(h->!tree(h).path("status").asText().equals("SUPPORTED")).map(h->Map.of("hypothesisId",tree(h).path("hypothesisId"),"status",tree(h).path("status"),"gaps",EvidenceCompleteness.gaps(es),"checks",EvidenceCompleteness.checks(es))).toList()));return compiled;
 }
 static Map<String,Object> feedback(List<String> errors,JsonNode reviewed,List<Evidence> es,Request r){
  var catalog=EvidenceUse.catalog(es,r);var violations=new ArrayList<Object>();
  for(String error:errors){var refs=new ArrayList<Object>();for(var v:catalog){var f=tree(v);if(error.contains(f.path("factId").asText()))refs.add(v);}violations.add(Map.of("error",error,"diagnostic",ContractErrors.describe(error),"references",refs));}
  return Map.of("violations",violations,"allowedCauseFacts",catalog.stream().filter(f->Boolean.TRUE.equals(((Map<?,?>)f).get("causeEligible"))).toList(),"approvedHypothesisIds",approved(reviewed).stream().map(x->tree(x).path("hypothesisId").asText()).toList(),"resolution","Correct each diagnostic according to its code, path, actual count and permitted range. A size error does not establish evidence insufficiency. Withdraw an unsupported candidate or mark INSUFFICIENT_EVIDENCE/REFUTED with plan=null; do not invent substitutes. One existing correction only.");
 }
}
