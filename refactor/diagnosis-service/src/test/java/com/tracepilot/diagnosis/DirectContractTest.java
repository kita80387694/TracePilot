package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class DirectContractTest {
 JsonNode report()throws Exception{return tree(Map.of("type","report","hypotheses",new ContractBoundaryTest().initial(),"checks",List.of("VERIFY_MECHANISM")));}
 @Test void nineRealRejectedToolActionsHaveAnExplicitNullableFirstCursor()throws Exception{
  var f=JSON.readTree(getClass().getResourceAsStream("/direct-real-tools.json"));assertThat(f.path("records")).hasSize(9);
  for(var r:f.path("records")){var a=r.path("action");assertThat(DirectContract.errors(a,false)).as(r.toString()).isEmpty();assertThat(DirectContract.query(a).args()).doesNotContainKey("cursor");ReadTools.validate(DirectContract.query(a));}
 }
 @Test void nullIsNotAUniversalCoercionAndErrorsIdentifyTheActualTool(){
  for(Object value:List.of(JSON.nullNode(),"100",0,201)){
   var a=tree(Map.of("type","tool","tool","logs","args",Map.of("limit",value)));
   assertThat(DirectContract.errors(a,false).toString()).contains("/args/limit").doesNotContain("/tool");
  }
  assertThat(DirectContract.errors(tree(Map.of("type","tool","tool","fault-control","args",Map.of())),false)).isNotEmpty();
  assertThat(DirectContract.errors(tree(Map.of("type","tool","tool","logs","args",Map.of("path","/secrets"))),false).toString()).contains("UNEXPECTED_FIELD");
  assertThat(DirectContract.errors(tree(Map.of("type","tool","tool","overview","args",Map.of())),true)).isNotEmpty();
 }
 @Test void validMechanismSurvivesRenderingAndOldReportsAreNotRewritten()throws Exception{
  var f=new ContractBoundaryTest();var action=report();String mechanism="写入尝试因锁等待失败，后续重试恢复；持锁方尚未确定。";((ObjectNode)action.path("hypotheses").get(0)).put("mechanism",mechanism);
  var result=DirectContract.render(action,f.evidence(),f.request());assertThat(result.errors()).isEmpty();
  assertThat(result.report().at("/candidates/0/mechanism").asText()).isEqualTo(mechanism);
  assertThat(result.report().path("schemaVersion").asText()).isEqualTo(DirectContract.VERSION);
  assertThat(result.report().path("semanticReview").asText()).contains("PENDING_HUMAN");
  var reviewed=ReviewContract.review(f.review(),f.prior(),f.evidence(),f.request());assertThat(ReviewContract.render(tree(Map.of("hypothesisIds",List.of("H1"),"checks",List.of())),tree(reviewed.records()),f.evidence(),f.request(),false).report().path("schemaVersion").asText()).isEqualTo(ReviewContract.VERSION);
 }
 @Test void missingUnknownAndOutOfWindowReferencesRemainRejected()throws Exception{
  var f=new ContractBoundaryTest();var a=report();var plan=(ObjectNode)a.path("hypotheses").get(0);plan.remove("mechanism");assertThat(DirectContract.errors(a,false).toString()).contains("MISSING_FIELD","/hypotheses/0/mechanism");
  plan.putNull("mechanism");plan.set("premiseFactIds",tree(List.of("Funknown")));assertThat(DirectContract.render(a,f.evidence(),f.request()).errors().toString()).contains("UNKNOWN_OR_UNAUTHORIZED_FACT");
  var background=EvidenceUse.catalog(f.evidence(),f.request()).stream().map(Domain::tree).filter(x->!x.path("causeEligible").asBoolean()).findFirst().orElseThrow().path("factId").asText();plan.set("premiseFactIds",tree(List.of(background)));assertThat(DirectContract.render(a,f.evidence(),f.request()).errors().toString()).contains("NOT_INCIDENT_OBSERVATION");
 }
 @Test void insufficientEvidenceCanFinishWithoutManufacturingCandidates()throws Exception{
  var request=new ContractBoundaryTest().request();var es=List.of(new Evidence("Eempty","logs","NO_DATA",request.start().toString(),request.end().toString(),Map.of(),tree(Map.of("data",List.of()))));
  var r=DirectContract.render(tree(Map.of("type","report","hypotheses",List.of(),"checks",List.of("COLLECT_LOGS"))),es,request);
  assertThat(r.errors()).isEmpty();assertThat(r.report().path("candidates")).isEmpty();assertThat(r.report().path("resultType").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
 }
 @Test void fullAuditErrorsStayOutsideTheBoundedCorrection(){
  var errors=new ArrayList<String>();for(int i=0;i<100;i++)errors.add(encode(Map.of("code","FACT_SCOPE","path","/hypotheses/"+i,"actual","x".repeat(2000),"expected","y".repeat(2000))));
  var correction=WorkingContext.correction(errors);assertThat(encode(correction).length()).isLessThan(5000);assertThat(tree(correction).path("omittedDetails").asInt()).isEqualTo(92);assertThat(errors).hasSize(100);assertThat(encode(correction)).contains("notEmbedded");
 }
}
