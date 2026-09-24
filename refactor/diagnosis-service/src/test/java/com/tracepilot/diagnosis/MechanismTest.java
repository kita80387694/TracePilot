package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

/** All mechanism strings are offline test doubles, not signed H1/H2 answers. */
class MechanismTest {
 final ContractBoundaryTest fixture=new ContractBoundaryTest();
 JsonNode action(String text)throws Exception{var a=fixture.review();var p=(ObjectNode)a.at("/assessments/0/plan");if(text==null)p.putNull("mechanism");else p.put("mechanism",text);return a;}
 ReportPlan.Compiled render(JsonNode a)throws Exception{var reviewed=ReviewContract.review(a,fixture.prior(),fixture.evidence(),fixture.request());assertThat(reviewed.errors()).isEmpty();return ReviewContract.render(tree(Map.of("hypothesisIds",List.of("H1"),"checks",List.of())),tree(reviewed.records()),fixture.evidence(),fixture.request(),false);}
 @Test void explanationSurvivesInitialReviewAndReport()throws Exception{
  String text="通知写入遭遇锁等待，失败后重试并恢复；持锁原因仍未知。";var a=action(text);var plans=tree(List.of(a.at("/assessments/0/plan")));var seeded=InitialContract.seed(plans,fixture.evidence(),fixture.request());assertThat(seeded.errors()).isEmpty();assertThat(tree(seeded.records()).at("/0/initialPlan/mechanism").asText()).isEqualTo(text);
  var r=render(a);assertThat(r.errors()).isEmpty();assertThat(r.report().at("/candidates/0/mechanism").asText()).isEqualTo(text);assertThat(r.report().at("/candidates/0/inference").asBoolean()).isTrue();assertThat(r.report().at("/causalAssessment/humanReview").asText()).isEqualTo("PENDING");
 }
 @Test void nullMeansUnknownNotInventedAnalysis()throws Exception{var r=render(action(null));assertThat(r.errors()).isEmpty();assertThat(r.report().at("/candidates/0/mechanism").asText()).isEqualTo("未知");}
 @Test void missingWrongTypeBlankAndOversizeRejectedBeforeState()throws Exception{
  for(var value:List.of(tree(7),tree(" "),tree("x".repeat(ReviewContract.MAX_PLAN_UTF8_BYTES+1)))){var a=action(null);((ObjectNode)a.at("/assessments/0/plan")).set("mechanism",value);var result=ReviewContract.review(a,fixture.prior(),fixture.evidence(),fixture.request());assertThat(result.errors()).isNotEmpty();assertThat(result.records()).isEmpty();}
  var a=action(null);((ObjectNode)a.at("/assessments/0/plan")).remove("mechanism");assertThat(ReviewContract.review(a,fixture.prior(),fixture.evidence(),fixture.request()).errors().toString()).contains("MISSING_FIELD","/mechanism");
 }
 @Test void selectedFactExpansionUsesRegistryAndObservationPrecision()throws Exception{
  var a=action(null);String ref=a.at("/assessments/0/plan/premiseFactIds/3").asText();((ObjectNode)a.at("/assessments/0/plan")).put("mechanism","根据 {{fact:"+ref+"}} 推测后续写入恢复；精确提交时刻未知。");var r=render(a);assertThat(r.errors()).isEmpty();assertThat(r.report().at("/candidates/0/mechanism").asText()).contains("非精确提交时刻","推测后续写入恢复").doesNotContain("{{fact:");
 }
 @Test void unknownUnselectedAndMalformedReferencesRejected()throws Exception{
  for(String text:List.of("根据 {{fact:Funknown}} 推测异常。","根据 {{wrong}} 推测异常。")){var a=action(text);assertThat(ReviewContract.review(a,fixture.prior(),fixture.evidence(),fixture.request()).errors()).isNotEmpty();}
  var a=action(null);String unselected=FactReferences.id(fixture.evidence().get(1).id(),"/data/0/level");((ObjectNode)a.at("/assessments/0/plan")).put("mechanism","根据 {{fact:"+unselected+"}} 推测异常。");assertThat(ReviewContract.review(a,fixture.prior(),fixture.evidence(),fixture.request()).errors().toString()).contains("UNKNOWN_OR_UNSELECTED_MECHANISM_FACT");
 }
 @Test void semanticSamplesAreNotAutomaticallyCertified()throws Exception{
  // Same structurally valid evidence roles; these texts have DIFFERENT semantic quality.
  for(String text:List.of("通知出现延迟，因为通知没有及时出现。","通知写入失败后重试可能解释后续恢复，持锁来源未知。","连接泄漏导致所有通知永久丢失。","等待一段时间后恢复，原因未知。")){var r=render(action(text));assertThat(r.errors()).isEmpty();assertThat(r.report().at("/candidates/0/mechanism").asText()).isEqualTo(text);assertThat(r.report().at("/causalAssessment/doesNotEstablishCausality").asBoolean()).isTrue();}
 }
 @Test void emptyEvidenceAndOldSavedReportRemainReadable()throws Exception{
  var r=ReviewContract.render(tree(Map.of("hypothesisIds",List.of(),"checks",List.of())),tree(List.of()),List.of(),fixture.request(),true);assertThat(r.errors()).isEmpty();assertThat(r.report().path("candidates")).isEmpty();assertThat(r.report().path("resultType").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
  var legacy=JSON.readTree(getClass().getResourceAsStream("/model/fault-facts-v83.json")).path("report");assertThat(parse(encode(legacy))).isEqualTo(legacy);
 }
}
