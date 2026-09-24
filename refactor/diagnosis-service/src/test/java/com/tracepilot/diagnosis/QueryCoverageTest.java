package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;import static org.assertj.core.api.Assertions.*;
import java.util.*;import org.junit.jupiter.api.Test;
class QueryCoverageTest {
 @Test void aliasesDefaultsAndCompleteCoverageDoNotDuplicateButPagesAndFiltersRemainDistinct(){
  var helper=new EvidenceDeliveryTest();var r=helper.request();var e=helper.page("E1","0","0",false);
  var repeat=new Query("logs",Map.of("channel","events","limit","50","cursor","0"));
  for(int i=0;i<100;i++)assertThat(EvidenceRegistry.reusableComplete(r,repeat,List.of(e))).containsExactly("E1");
  assertThat(EvidenceRegistry.fingerprint(r,new Query("logs",Map.of()))).isEqualTo(EvidenceRegistry.fingerprint(r,new Query("logs",Map.of("channel","all","cursor","0","limit","0200"))));
  for(var q:List.of(new Query("logs",Map.of("channel","all")),new Query("logs",Map.of("channel","events","level","ERROR")),new Query("logs",Map.of("channel","events","cursor","10"))))assertThat(EvidenceRegistry.reusableComplete(r,q,List.of(e))).isEmpty();
  assertThat(EvidenceRegistry.reusableComplete(new Request(r.service(),r.environment(),r.start().minusSeconds(1),r.end(),r.symptom()),repeat,List.of(e))).isEmpty();
  assertThat(EvidenceRegistry.reusableComplete(r,repeat,List.of(helper.page("partial","0","next",true)))).isEmpty();
 }
 @Test void invalidCandidateItemsAreRejectedBeforeSeedAndLegalTextRemains(){
  for(Object value:List.of(Map.of("hypothesisText","wrong","evidenceIds",List.of()),Map.of("hypothesis"," ","evidenceIds",List.of()),Map.of("hypothesis",4,"evidenceIds",List.of()),Map.of("hypothesis","valid","evidenceIds",List.of(),"unknown",true),Map.of("hypothesis","valid"))){var a=tree(List.of(value));assertThat(HypothesisInput.errors(a)).isNotEmpty();assertThatThrownBy(()->Hypotheses.seed(a)).isInstanceOf(IllegalArgumentException.class);}
  var valid=tree(List.of(Map.of("hypothesis","preserved exactly","evidenceIds",List.of("E1"))));assertThat(tree(Hypotheses.seed(valid)).get(0).path("hypothesis").asText()).isEqualTo("preserved exactly");
 }
 @Test void otherStagesCannotSilentlyStripUnexpectedFieldsOrNestedToolArguments(){
  assertThat(ActionDiagnostics.violations("INVESTIGATE",tree(Map.of("type","ready","hypothesisText","wrong")))).isNotEmpty();
  assertThat(ActionDiagnostics.violations("INVESTIGATE",tree(Map.of("type","tool","tool","logs","args",Map.of("limit",List.of(20)))))).isNotEmpty();
  assertThat(ActionDiagnostics.violations("REVIEW",tree(Map.of("type","review","assessments",List.of(),"gaps",List.of(),"conflicts",List.of(Map.of("wrong","lost")))))).isNotEmpty();
 }
 @Test void evidenceBudgetLeavesRoomForStateAndOneCorrectionWithoutTruncation(){
  var input=new LinkedHashMap<String,Object>();input.put("causeFactRegistry","x".repeat(69000));input.put("workingHypotheses","y".repeat(14000));input.put("checkpoint",Map.of("actionError","z".repeat(9000)));
  var before=encode(input);assertThat(RequestBoundary.sections(input)).containsKey("limits");assertThat(encode(input)).isEqualTo(before);assertThat(RequestBoundary.context(before)).hasSize(before.length());
  input.put("causeFactRegistry","x".repeat(70001));assertThatThrownBy(()->RequestBoundary.sections(input)).isInstanceOf(RequestBoundary.LocalFailure.class);
 }
}
