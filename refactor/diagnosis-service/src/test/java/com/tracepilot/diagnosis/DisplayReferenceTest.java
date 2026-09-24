package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class DisplayReferenceTest {
 final SelectedReportFactsTest samples=new SelectedReportFactsTest();
 static final String TIME="F876c28dc943afbbe9561a720", RECOVERY="F5684818c29ee11194a5f00c1";
 JsonNode actual(String model)throws Exception{return JSON.readTree(getClass().getResourceAsStream("/model-v34-actions.json")).path("actions").path(model);}
 JsonNode valid()throws Exception{
  var action=(ObjectNode)actual("PRO").deepCopy();var h=(ObjectNode)action.at("/hypotheses/0");
  h.set("premiseFactIds",tree(List.of("F95086ab610a1d7da08d0a5c7","Fe6e7d6eda41ed951675243c9","F81bb9bec9adbf194397aa549")));
  h.set("outcomeFactIds",tree(List.of("F1a68fb2914239f0c54a667e7")));
  h.put("mechanism","连接池缺少可用连接可能解释连接获取失败；采样时间 {{fact:"+TIME+"}}，另一个恢复观测 {{fact:"+RECOVERY+"}}。占用者未知。");return action;
 }
 @Test void displayFactsAreRenderedWithoutPromotingThemToCausalRoles()throws Exception{
  var f=samples.fixture();var a=valid();String before=encode(a);var result=DirectContract.render(a,f.evidence(),f.request(),f.visible());
  assertThat(result.errors()).isEmpty();assertThat(encode(a)).isEqualTo(before);
  assertThat(result.report().path("modelPlan")).isEqualTo(a);
  assertThat(result.report().at("/candidates/0/mechanismDisplayFactIds")).isEqualTo(tree(List.of(TIME,RECOVERY)));
  assertThat(result.report().at("/candidates/0/mechanism").asText()).doesNotContain("{{fact:").contains("2026-09-22T17:26:42");
  assertThat(result.report().at("/causalAssessment/humanReview").asText()).isEqualTo("PENDING");
  var ids=new HashSet<String>();result.report().path("facts").forEach(n->ids.add(n.path("factId").asText()));assertThat(ids).contains(TIME,RECOVERY);
  var uses=new HashSet<String>();result.report().path("factUses").forEach(n->uses.add(n.path("factId").asText()));assertThat(uses).isEqualTo(ids);
  assertThat(result.report().at("/candidates/0/support").asText()).doesNotContain("sampledAt");
 }
 @Test void realResponsesSeparateMechanicalReplayFromUnchangedSemanticFailure()throws Exception{
  var f=samples.fixture();var replay=new LinkedHashMap<String,Object>();
  for(String name:List.of("PRO","GLM")){
   var a=actual(name);var result=DirectContract.render(a,f.evidence(),f.request(),f.visible());
   assertThat(result.errors().toString()).doesNotContain("UNKNOWN_OR_UNSELECTED_MECHANISM_FACT");
   if(name.equals("PRO")){assertThat(result.errors()).isEmpty();assertThat(result.report().path("modelPlan")).isEqualTo(a);assertThat(result.report().at("/causalAssessment/humanReview").asText()).isEqualTo("PENDING");}
   else{assertThat(result.errors().toString()).contains("INVALID_BASELINE_COMPARISON");assertThat(result.report()).isEmpty();}
   replay.put(name,Map.of("errors",result.errors(),"originalResult","FAILED_UNCHANGED","meaning","OFFLINE_REPLAY_NOT_MODEL_SUCCESS"));
  }
  java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/display-reference-v35.json"),encode(replay));
 }
 @Test void unreadUnknownAndBackgroundInlineFactsStillRejectWholeReport()throws Exception{
  var f=samples.fixture();var unavailable=new HashSet<>(f.visible());unavailable.remove(TIME);
  assertThat(DirectContract.render(valid(),f.evidence(),f.request(),unavailable).errors().toString()).contains("UNKNOWN_OR_UNSELECTED_MECHANISM_FACT");
  for(String id:List.of("F"+"0".repeat(24),"F758af3770a9bb2c3528bd5e2")){
   var a=valid();((ObjectNode)a.at("/hypotheses/0")).put("mechanism","根据 {{fact:"+id+"}} 推测。");
   var result=DirectContract.render(a,f.evidence(),f.request(),f.visible());assertThat(result.errors()).isNotEmpty();assertThat(result.report()).isEmpty();
  }
 }
 @Test void outOfWindowAndWrongServiceRemainRejected()throws Exception{
  var f=samples.fixture();var r=new Request(f.request().service(),f.request().environment(),f.request().start().plusSeconds(60),f.request().end().plusSeconds(60),f.request().symptom());
  assertThat(DirectContract.render(valid(),f.evidence(),r,f.visible()).errors().toString()).contains("NOT_INCIDENT_OBSERVATION");
  assertThatThrownBy(()->new Request("another-service",f.request().environment(),f.request().start(),f.request().end(),f.request().symptom())).isInstanceOf(IllegalArgumentException.class).hasMessage("SERVICE_DENIED");
  var es=new ArrayList<Evidence>();for(var e:f.evidence()){var locator=new HashMap<>(e.locator());locator.put("service","another-service");es.add(new Evidence(e.id(),e.source(),e.status(),e.start(),e.end(),locator,e.data()));}
  assertThat(DirectContract.render(valid(),es,f.request(),f.visible()).errors()).isNotEmpty();
 }
 @Test void legacyRoleOnlyContractIsUnchangedAndCurrentSchemaDeclaresDisplaySemantics()throws Exception{
  var f=samples.fixture();var errors=new ArrayList<String>();ReviewContract.mechanism(valid().at("/hypotheses/0"),f.evidence(),f.request(),"/hypotheses/0",errors);
  assertThat(errors.toString()).contains("UNKNOWN_OR_UNSELECTED_MECHANISM_FACT");
  assertThat(ReviewContract.planSchema().at("/properties/mechanism/description").asText()).contains("from these roles");
  assertThat(DirectContract.reportSchema().at("/properties/hypotheses/items/properties/mechanism/description").asText()).contains("need not be repeated");
 }
}
