package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlanResourceBoundaryTest {
 final ContractBoundaryTest fixture=new ContractBoundaryTest();
 ObjectNode plan()throws Exception{return (ObjectNode)fixture.review().at("/assessments/0/plan").deepCopy();}
 List<String> errors(ObjectNode p)throws Exception{var out=new ArrayList<String>();ContractErrors.validate(p,ReviewContract.planSchema(),"/plan",out);ReviewContract.validateReferences(p,fixture.evidence(),fixture.request(),"/plan",out);return out;}
 @Test void exactlyWholePlanByteLimitAcceptedButNextByteRejected()throws Exception{
  var p=plan();p.put("mechanism","");int overhead=encode(p).getBytes(StandardCharsets.UTF_8).length;
  p.put("mechanism","x".repeat(ReviewContract.MAX_PLAN_UTF8_BYTES-overhead));
  assertThat(encode(p).getBytes(StandardCharsets.UTF_8)).hasSize(4096);assertThat(errors(p)).isEmpty();
  p.put("mechanism",p.path("mechanism").asText()+"x");assertThat(errors(p).toString()).contains("SERIALIZED_SIZE_LIMIT");
 }
 @Test void utf8AndJsonEscapingCountTowardSameWholePlanLimit()throws Exception{
  for(String text:List.of("汉".repeat(1400),"\"".repeat(2100))){var p=plan();p.put("mechanism",text);
   assertThat(text.codePointCount(0,text.length())).isLessThan(4096);
   assertThat(errors(p).toString()).contains("SERIALIZED_SIZE_LIMIT");
  }
 }
 @Test void referencesDoNotCreateAnotherArbitraryProseLengthQuota()throws Exception{
  var p=plan();String ref=p.path("premiseFactIds").get(0).asText();
  // Build a long but bounded synthetic explanation; it is not a signed causal answer.
  String text="A qualitative inference; uncertainty remains. ".repeat(15)+"{{fact:"+ref+"}}";
  assertThat(text.length()).isGreaterThan(600);p.put("mechanism",text);assertThat(errors(p)).isEmpty();
  var action=fixture.review();((ObjectNode)action.at("/assessments/0")).set("plan",p);
  var reviewed=ReviewContract.review(action,fixture.prior(),fixture.evidence(),fixture.request());assertThat(reviewed.errors()).isEmpty();
  var report=ReviewContract.render(tree(Map.of("hypothesisIds",List.of("H1"),"checks",List.of())),tree(reviewed.records()),fixture.evidence(),fixture.request(),false);
  assertThat(report.errors()).isEmpty();assertThat(report.report().at("/candidates/0/mechanism").asText()).startsWith("A qualitative inference;").doesNotContain("{{fact:");
  assertThat(report.report().at("/causalAssessment/humanReview").asText()).isEqualTo("PENDING");
 }
 @Test void noSilentTrimOrInventedReferenceUnderNewResourcePolicy()throws Exception{
  var p=plan();String text="x".repeat(650)+" {{fact:Funknown}}";p.put("mechanism",text);
  assertThat(errors(p).toString()).contains("MALFORMED_FACT_REFERENCE");assertThat(p.path("mechanism").asText()).isEqualTo(text);
  p.remove("mechanism");assertThat(errors(p).toString()).contains("MISSING_FIELD");
  p.putNull("mechanism");assertThat(errors(p)).isEmpty();
 }
}
