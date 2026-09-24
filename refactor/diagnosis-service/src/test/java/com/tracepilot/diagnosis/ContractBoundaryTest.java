package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;import java.util.*;import java.time.*;
/** Real v884 inputs. Any changed response is an explicit offline fixture, never provider success. */
class ContractBoundaryTest {
 JsonNode fixture()throws Exception{return JSON.readTree(getClass().getResourceAsStream("/model/review-v884.json"));}
 Request request()throws Exception{var r=fixture().path("request");return new Request(r.path("service").asText(),r.path("environment").asText(),Instant.parse(r.path("start").asText()),Instant.parse(r.path("end").asText()),r.path("symptom").asText());}
 List<Evidence> evidence()throws Exception{var es=new ArrayList<Evidence>();for(var e:fixture().path("evidence"))es.add(new Evidence(e.path("id").asText(),e.path("source").asText(),e.path("status").asText(),e.path("start").asText(),e.path("end").asText(),map(encode(e.path("locator"))),e.path("data")));return es;}
 JsonNode review()throws Exception{var action=fixture().path("rounds").get(3).path("action").deepCopy();for(var h:action.path("assessments"))((ObjectNode)h.path("plan")).putNull("mechanism");return action;}
 JsonNode initial()throws Exception{var plans=JSON.createArrayNode();for(var a:review().path("assessments"))plans.add(a.path("plan"));return plans;}
 JsonNode prior()throws Exception{var seed=InitialContract.seed(initial(),evidence(),request());assertThat(seed.errors()).isEmpty();return tree(seed.records());}
 @Test void actualFourRefsAreNotProductCandidateCountAndLegacyResultStaysRejected()throws Exception{
  var a=review();var es=evidence();var r=request();var old=ReviewContract.internalPlan(a.path("assessments").get(0).path("plan"),tree(List.of()),es,r);
  assertThat(ReportPlan.compile(tree(Map.of("hypotheses",List.of(old),"checks",List.of())),es,r,false).errors()).contains("INVALID_ARRAY:/hypotheses/0/premiseFactIds");
  var result=ReviewContract.review(a,prior(),es,r);assertThat(result.errors()).isEmpty();assertThat(tree(result.records()).get(0).at("/plan/premiseFactIds")).hasSize(4);
  var report=ReviewContract.render(tree(Map.of("hypothesisIds",List.of("H1"),"checks",List.of())),tree(result.records()),es,r,false);
  assertThat(report.errors()).isEmpty();assertThat(report.report().at("/causalAssessment/humanReview").asText()).isEqualTo("PENDING");assertThat(report.report().path("schemaVersion").asText()).isEqualTo(ReviewContract.VERSION);
 }
 @Test void declaredResourceBoundaryIsIndependentOfFourAndNoTrimmingOccurs(){
  for(int count:List.of(0,1,4,24,25)){var refs=new ArrayList<String>();for(int i=0;i<count;i++)refs.add("F"+i);var p=tree(Map.of("relation","MAY_EXPLAIN","premiseFactIds",refs,"outcomeFactIds",List.of(),"contradictionFactIds",List.of(),"mechanism",JSON.nullNode()));var errors=new ArrayList<String>();ContractErrors.validate(p,ReviewContract.planSchema(),"/assessments/0/plan",errors);
   if(count==0||count==25){assertThat(errors).singleElement().satisfies(e->{var d=parse(e);assertThat(d.path("code").asText()).isEqualTo("COUNT_OUT_OF_RANGE");assertThat(d.at("/actual/count").asInt()).isEqualTo(count);assertThat(d.at("/expected/maxItems").asInt()).isEqualTo(24);assertThat(d.path("path").asText()).isEqualTo("/assessments/0/plan/premiseFactIds");});}else assertThat(errors).isEmpty();assertThat(p.path("premiseFactIds")).hasSize(count);
  }
 }
 @Test void actualFreeCommitProseCannotEnterInitialStateAndNoAliasIsGuessed()throws Exception{
  var original=fixture().path("rounds").get(0).path("action").path("candidates");assertThat(original.toString()).contains("08:00:16.338");assertThat(HypothesisInput.errors(original)).isEmpty();var result=InitialContract.seed(original,evidence(),request());assertThat(result.errors().toString()).contains("UNEXPECTED_FIELD","hypothesis","MISSING_FIELD");assertThat(result.records()).isEmpty();
  var working=tree(ReviewContract.working(prior()));assertThat(working.toString()).doesNotContain("committed the write at","hypothesis\":");assertThat(working.get(0).path("initialPlan")).isEqualTo(initial().get(0));
 }
 @Test void missingTypeEnumAndReferencesAreDistinctAndScopeIsNotCount()throws Exception{
  var a=review().deepCopy();var p=(ObjectNode)a.path("assessments").get(0).path("plan");p.remove("relation");p.set("outcomeFactIds",tree("wrong"));p.set("premiseFactIds",tree(List.of("Funknown")));var errors=ReviewContract.review(a,prior(),evidence(),request()).errors().toString();assertThat(errors).contains("MISSING_FIELD","TYPE_MISMATCH","/assessments/0/plan");
  p.put("relation","UNKNOWN");assertThat(ReviewContract.review(a,prior(),evidence(),request()).errors().toString()).contains("INVALID_ENUM");
  p.put("relation","MAY_EXPLAIN");p.set("outcomeFactIds",tree(List.of()));assertThat(ReviewContract.review(a,prior(),evidence(),request()).errors().toString()).contains("UNKNOWN_OR_UNAUTHORIZED_FACT");var catalog=EvidenceUse.catalog(evidence(),request());String background=catalog.stream().map(Domain::tree).filter(f->!f.path("causeEligible").asBoolean()).findFirst().orElseThrow().path("factId").asText();p.set("premiseFactIds",tree(List.of(background)));assertThat(ReviewContract.review(a,prior(),evidence(),request()).errors().toString()).contains("NOT_INCIDENT_OBSERVATION");
 }
 @Test void trueButUnrelatedObservationIsNotAutomaticallyCertifiedAsSupport()throws Exception{
  var a=review().deepCopy();var p=(ObjectNode)a.path("assessments").get(0).path("plan");String ref=FactReferences.id(evidence().get(1).id(),"/data/0/level");p.set("premiseFactIds",tree(List.of(ref)));var checked=ReviewContract.review(a,prior(),evidence(),request());assertThat(checked.errors()).isEmpty();var rendered=ReviewContract.render(tree(Map.of("hypothesisIds",List.of("H1"),"checks",List.of())),tree(checked.records()),evidence(),request(),false);assertThat(rendered.report().at("/causalAssessment/doesNotEstablishCausality").asBoolean()).isTrue();assertThat(rendered.report().at("/candidates/0/confidence").asText()).isEqualTo("UNVERIFIED"); // existence/scope are automatic, relevance remains manual
 }
 @Test void commitPrecisionAndUnknownTimeRemainDistinctInRenderedReport()throws Exception{
  var timeline=EventTimeline.build(evidence(),request());var commit=timeline.nodes().stream().filter(n->n.path("action").asText().equals("notification_write_committed")).findFirst().orElseThrow();assertThat(commit.path("timeSemantics").asText()).isEqualTo("POST_COMMIT_UPPER_BOUND");assertThat(EventTimeline.timeStatement(commit)).contains("截至该日志观测已经提交","毫秒精度","精确提交时刻未知");var unknown=commit.deepCopy();((ObjectNode)unknown).put("eventTime","UNKNOWN");assertThat(EventTimeline.timeStatement(unknown)).isEqualTo("观测及精确提交时刻未知");
  var checked=ReviewContract.review(review(),prior(),evidence(),request());var report=ReviewContract.render(tree(Map.of("hypothesisIds",List.of("H1"),"checks",List.of())),tree(checked.records()),evidence(),request(),false).report();assertThat(report.at("/candidates/0/support").asText()).contains("截至该日志观测已经提交（毫秒精度记录值=2026-09-15T08:00:16.338Z）","精确提交时刻未知");
 }
 @Test void gapsCanFinishAndOversizePlanCannotBeSilentlyShrunk()throws Exception{
  var p=initial().get(0).deepCopy();((ObjectNode)p).put("unknown","x".repeat(4200));var errors=new ArrayList<String>();ReviewContract.validateReferences(p,evidence(),request(),"/candidates/0",errors);assertThat(errors.toString()).contains("SERIALIZED_SIZE_LIMIT","maxUtf8Bytes");assertThat(p.path("unknown").asText()).hasSize(4200);
  var result=ReviewContract.render(tree(Map.of("hypothesisIds",List.of(),"checks",List.of("COLLECT_LOGS"))),tree(List.of()),List.of(),request(),true);assertThat(result.errors()).isEmpty();assertThat(result.report().path("candidates")).isEmpty();assertThat(result.report().path("resultType").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
 }
 @Test void evidenceInsufficiencyHasAnActionablePathWithoutChangingState()throws Exception{
  var a=review().deepCopy();var p=(ObjectNode)a.path("assessments").get(0).path("plan");p.set("outcomeFactIds",p.path("premiseFactIds").deepCopy());var before=prior();var checked=ReviewContract.review(a,before,evidence(),request());assertThat(checked.records()).isEmpty();assertThat(checked.errors()).anySatisfy(e->{var d=parse(e);assertThat(d.path("code").asText()).isEqualTo("CAUSAL_SELF_REFERENCE");assertThat(d.path("category").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");assertThat(d.path("path").asText()).isEqualTo("/assessments/0/plan");assertThat(d.path("actual")).isEqualTo(p);assertThat(d.path("correction").asText()).contains("Withdraw");});assertThat(before).isEqualTo(prior());
 }
}
