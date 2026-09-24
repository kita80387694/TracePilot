package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;import java.time.*;
import org.junit.jupiter.api.Test;
class ActionDiagnosticsTest {
 @Test void phaseSchemasMatchValidatorAndNormalExitExists(){
  var cases=Map.of("CANDIDATES","{\"type\":\"candidates\",\"candidates\":[]}","INVESTIGATE","{\"type\":\"ready\"}",
   "REVIEW","{\"type\":\"review\",\"assessments\":[]}","REPORT","{\"type\":\"report\",\"report\":{}}");
  cases.forEach((phase,value)->{assertThat(Workflow.actionError(phase,parse(value))).isNull();assertThat(ActionDiagnostics.violations(phase,parse(value))).isEmpty();
   assertThat(Workflow.prompt(phase)).contains("Phase "+phase+":");});
  assertThat(ActionDiagnostics.violations("INVESTIGATE",parse("{\"type\":\"report\",\"report\":{}}"))).anySatisfy(e->{assertThat(e.get("path")).isEqualTo("/type");assertThat(e.get("expected").toString()).contains("ready");});
 }
 @Test void missingArgsAndWrongTypeHaveExactPath(){
  var a=parse("{\"type\":\"tool\",\"tool\":\"logs\"}");
  assertThat(ActionDiagnostics.violations("INVESTIGATE",a)).singleElement().satisfies(e->{assertThat(e.get("path")).isEqualTo("/args");assertThat(e.get("actualType")).isEqualTo("MISSING");});
  a=parse("{\"type\":\"tool\",\"tool\":\"logs\",\"args\":[]}");
  assertThat(ActionDiagnostics.violations("INVESTIGATE",a)).singleElement().satisfies(e->assertThat(e.get("actualType")).isEqualTo("ARRAY"));
 }
 @Test void sensitiveProseAndThinkingNeverRetainedAndSizeIsExplicit(){
  var a=tree(Map.of("type","report","password","private-key","thinking","private-thought","summary","Alice phone 123","number",123));
  String sanitized=encode(ActionDiagnostics.redact(a,""));assertThat(sanitized).doesNotContain("private-key","private-thought","Alice","123").contains("report","REDACTED");
  var many=JSON.createArrayNode();for(int i=0;i<2000;i++)many.add("sensitive");
  var c=new Claim("id","lease",new Request("tracepilot-business","demo",Instant.now().minusSeconds(60),Instant.now(),""),Map.of(),Instant.now().plusSeconds(20));
  var r=ActionDiagnostics.record(c,"INVESTIGATE",tree(Map.of("type","bad","many",many)),new ModelReply(a,"model",1,2),Map.of("model_calls",2,"max_models",8,"tool_calls",1,"max_tools",12),"ERROR",1);
  assertThat(encode(r)).contains("END_PARTIAL");assertThat(tree(r).at("/actionRecord/complete").asBoolean()).isFalse();assertThat(tree(r).at("/actionRecord/body").asText().length()).isEqualTo(8192);
 }
 @Test void realCapturedPrematureReportsRemainRejectedAndPromptOffersReady() throws Exception {
  for(int i=1;i<=2;i++) {
    var input=getClass().getResourceAsStream("/model/d2-real-rejection-"+i+".json");
    assertThat(input).isNotNull();
    var a=JSON.readTree(input);
    assertThat(Workflow.actionError("INVESTIGATE",a)).isEqualTo("EXPECTED_ONE_TOOL_OR_READY");
    final String expectedPath=i==1?"/type":"/tool";
    assertThat(ActionDiagnostics.violations("INVESTIGATE",a)).anySatisfy(e->assertThat(e.get("path")).isEqualTo(expectedPath));
  }
  assertThat(Workflow.prompt("INVESTIGATE")).contains("CURRENT RESPONSE CONTRACT", "The top-level type is required", "return exactly {\"type\":\"ready\"}");
  assertThat(Workflow.actionError("INVESTIGATE",parse("{\"type\":\"ready\"}"))).isNull();
 }
 @Test void parserDiagnosticMustNotPretendToBeCompleteProviderAction(){
   var c=new Claim("id","lease",new Request("tracepilot-business","demo",Instant.now().minusSeconds(60),Instant.now(),""),Map.of(),Instant.now().plusSeconds(20));
   var a=parse("{\"type\":\"INVALID_JSON\",\"parseCategory\":\"INVALID_JSON_SYNTAX\"}");
   var r=tree(ActionDiagnostics.record(c,"REVIEW",a,new ModelReply(a,"model",1,1),Map.of("model_calls",1,"max_models",8,"tool_calls",1,"max_tools",12),"INVALID_JSON",0));
   assertThat(r.at("/actionRecord/complete").asBoolean()).isFalse();
   assertThat(r.at("/actionRecord/bodySource").asText()).isEqualTo("PARSER_DIAGNOSTIC_NOT_PROVIDER_BODY");
   assertThat(r.at("/violations/0/actualType").asText()).isEqualTo("UNPARSEABLE");
   assertThat(r.at("/parserDiagnostic/parseCategory").asText()).isEqualTo("INVALID_JSON_SYNTAX");
 }
 @Test void arbitraryKeysAreRedactedAndContainerErrorsDoNotDuplicateUnboundedBody(){
   var a=tree(Map.of("type","tool","tool",Map.of("Alice@example.com","private")));
   assertThat(encode(ActionDiagnostics.redact(a,""))).doesNotContain("Alice@example.com","private");
   assertThat(encode(ActionDiagnostics.violations("INVESTIGATE",a))).contains("count").doesNotContain("Alice@example.com");
 }
 @Test void expiredPayloadIsNotReturnedBetweenSweeps(){
  assertThat(TaskStore.readStepDetail("{\"phase\":\"INVESTIGATE\",\"actionRecord\":{\"body\":\"x\"}}",Instant.now().minus(Duration.ofDays(8))).has("actionRecord")).isFalse();
 }
}
