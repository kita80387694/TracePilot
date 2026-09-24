package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueryPolicyTest {
 @Test void actualCodeResponseGetsSpecificErrorsFromTheSameNativeSchema() throws Exception {
  var fixture=JSON.readTree(getClass().getResourceAsStream("/query-policy-v19.json"));
  var call=fixture.at("/rounds/0/calls/1");
  var definition=NamedToolProtocol.definitions(false,true).stream().filter(d->d.name().equals(call.path("name").asText())).findFirst().orElseThrow();
  var errors=new ArrayList<String>();ContractErrors.validate(call.path("arguments"),definition.schema(),"",errors);
  assertThat(errors.toString()).contains("STRING_PATTERN","/args/version","/args/query",ReadTools.CODE_VERSION_PATTERN,"One identifier");
  var args=Map.of("version",MetricFacts.INTERVAL_VERSION,"query","UsageReport.report");
  errors.clear();ContractErrors.validate(tree(Map.of("args",args)),definition.schema(),"",errors);assertThat(errors).isEmpty();
  ReadTools.validate(new Query("code",args));
  assertThat(NamedToolProtocol.callbacks(false,true).stream().filter(t->t.getToolDefinition().name().equals("query_code")).findFirst().orElseThrow().getToolDefinition().inputSchema()).contains(ReadTools.CODE_VERSION_PATTERN,ReadTools.CODE_QUERY_PATTERN);
 }
 @Test void actualSecondResponseStillCannotRetainAnInventedId() throws Exception {
  var fixture=JSON.readTree(getClass().getResourceAsStream("/query-policy-v19.json"));var actual=fixture.path("actualProvidedId").asText();var invented=fixture.path("invalidRetainId").asText();
  var action=tree(Map.of("type","tool","tool","metrics","args",Map.of("limit",200),"retainFactIds",List.of(invented)));
  var frame=new EvidenceDelivery.Frame("",Set.of(actual),Set.of(actual),Map.of(),Map.of(),Map.of(),Map.of());
  assertThat(EvidenceDelivery.selectionErrors(action,frame).toString()).contains("FACT_NOT_IN_CURRENT_INPUT",invented);
  assertThat(frame.visible()).containsExactly(actual);
 }
 @Test void traceAndSqlFingerprintPatternsMatchPolicyWithoutRelaxingAccess() {
  for(var q:List.of(new Query("trace",Map.of("traceId","not-a-trace")),new Query("sqlPlan",Map.of("statementFingerprint","wrong")))){
   var errors=DirectContract.errors(tree(Map.of("type","tool","tool",q.tool(),"args",q.args())),false);
   assertThat(errors.toString()).contains("STRING_PATTERN","/args/");
   assertThatThrownBy(()->ReadTools.validate(q)).isInstanceOf(SecurityException.class);
  }
 }
}
