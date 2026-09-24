package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class OfferedFactScopeTest {
 JsonNode reportSchema(String input){return NamedToolProtocol.offered(true,false,input).getFirst().schema();}
 Map<String,Object> report(String id){var plan=new LinkedHashMap<String,Object>();
  plan.put("relation","MAY_EXPLAIN");plan.put("premiseFactIds",List.of(id));
  plan.put("outcomeFactIds",List.of());plan.put("contradictionFactIds",List.of());plan.put("mechanism",null);
  return Map.of("hypotheses",List.of(plan),"checks",List.of("VERIFY_MECHANISM"));
 }
 @Test void actualFourResponsesSeparateCurrentProtocolFromHistoricalSemanticFailure() throws Exception {
  var fixture=JSON.readTree(getClass().getResourceAsStream("/offered-scope-v31.json"));
  var helper=new BoundedActionsTest();var results=new ArrayList<Object>();
  for(var sample:fixture.path("samples")){
   String input=encode(sample.path("input"));var eligible=new TreeSet<String>();
   for(var row:ContextPacking.expand(sample.path("input"),"causeFactRegistry"))if(tree(row).path("causeEligible").asBoolean())eligible.add(tree(row).path("factId").asText());
   try(var endpoint=helper.new Endpoint(List.of(helper.call("offline","submit_report",map(encode(sample.path("responseArguments"))))))){
    var reply=endpoint.gateway("named-tools-v2").callAction(true,false,input,"OFFLINE_ONLY",1,x->{});
    var finalErrors=new ArrayList<String>();
    if(reply.action().path("type").asText().equals("INVALID_NATIVE_ACTION"))reply.action().path("errors").forEach(e->finalErrors.add(e.asText()));
    else {
     var registry=fixture.path("registries").path(sample.path("case").asText().contains("P1")?"P1":"S2");
     var evidence=new ArrayList<Evidence>();for(var row:registry.path("evidence"))evidence.add(JSON.treeToValue(row,Evidence.class));
     var request=JSON.treeToValue(registry.path("request"),Request.class);
     var visible=new HashSet<String>();for(String key:List.of("causeFactRegistry","backgroundFactRegistry"))for(var f:sample.path("input").path(key))visible.add(f.path("factId").asText());
     finalErrors.addAll(DirectContract.render(reply.action(),evidence,request,visible).errors());
    }
    if(sample.path("case").asText().equals("DEV-P1-GLM")){
     // Under the new byte boundary the IDs are legal, but its latency-as-time claim
     // remains a documented semantic failure. Structural acceptance is not a regrade.
     assertThat(finalErrors).isEmpty();
     assertThat(reply.action().at("/hypotheses/0/mechanism").asText()).isEqualTo(sample.at("/responseArguments/hypotheses/0/mechanism").asText());
    }else assertThat(finalErrors).as("Invalid references remain rejected: %s",sample.path("case").asText()).isNotEmpty();
    if(sample.path("case").asText().endsWith("PRO"))assertThat(finalErrors.toString()).contains("FACT_NOT_IN_OFFERED_SCOPE");
    var wire=endpoint.wires.getFirst();
    for(String role:ReviewContract.ROLES)assertThat(wire.at("/tools/0/input_schema/properties/hypotheses/items/properties/"+role+"/items/enum")).isEqualTo(tree(eligible));
    assertThat(wire.at("/messages/0/content/0/text").asText()).isEqualTo(input);
    int bytes=encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    assertThat(bytes).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
    results.add(Map.of("case",sample.path("case"),"wireUtf8Bytes",bytes,"eligibleIds",eligible.size(),"action",reply.action(),"finalErrors",finalErrors,"meaning","LOCAL_ENDPOINT_REPLAY_CURRENT_POLICY; ORIGINAL_RESULTS_UNCHANGED; NOT_MODEL_SUCCESS"));
   }
  }
  java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));
  java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/offered-scope-v31.json"),encode(results));
 }
 @Test void correctionRemainsBoundedAndExplicitlySelectedEligibleFactCanPassWireContract() throws Exception {
  var sample=JSON.readTree(getClass().getResourceAsStream("/offered-scope-v31.json")).path("samples").get(0);
  String input=encode(sample.path("input"));String valid=reportSchema(input).at("/properties/hypotheses/items/properties/premiseFactIds/items/enum/0").asText();
  String background=sample.at("/input/backgroundFactRegistry/0/factId").asText();assertThat(background).isNotBlank();
  var helper=new BoundedActionsTest();
  try(var endpoint=helper.new Endpoint(List.of(helper.call("bad","submit_report",report(background))),List.of(helper.call("corrected","submit_report",report(valid))))){
   var gateway=endpoint.gateway("named-tools-v2");var first=gateway.callAction(true,false,input,"OFFLINE_ONLY",1,x->{});
   assertThat(first.action().toString()).contains("FACT_NOT_IN_OFFERED_SCOPE","/premiseFactIds/0");
   assertThat(parse(first.action().path("errors").get(0).asText()).path("actual").asText()).isEqualTo(background);
   var errors=new ArrayList<String>();first.action().path("errors").forEach(e->errors.add(e.asText()));
   var correction=WorkingContext.correction(errors,tree(report(background)));
   assertThat(encode(correction).length()).isLessThan(3000);
   assertThat(encode(correction)).doesNotContain("\"expected\":[");
   var next=new LinkedHashMap<>(map(input));next.put("checkpoint",Map.of("correction",correction));
   var second=gateway.callAction(true,false,encode(next),"OFFLINE_ONLY",2,x->{});
   assertThat(second.action().path("type").asText()).isEqualTo("report");
   assertThat(second.action().at("/hypotheses/0/mechanism").isNull()).isTrue();
   assertThat(second.action().at("/hypotheses/0/premiseFactIds/0").asText()).isEqualTo(valid);
   assertThat(parse(endpoint.wires.getLast().at("/messages/0/content/0/text").asText()).at("/checkpoint/correction/details")).isNotEmpty();
  }
 }
 @Test void noIncidentFactsAllowsEmptyReportButNeverPromotesBackgroundOrPreviousRequest(){
  String id="F"+"a".repeat(24);var source=new LinkedHashMap<String,Object>();
  source.put("causeFactRegistry",List.of(Map.of("factId",id,"causeEligible",true)));
  ContextPacking.pack(source);
  assertThat(reportSchema(encode(source)).at("/properties/hypotheses/items/properties/premiseFactIds/items/enum")).isEqualTo(tree(List.of(id)));
  var noData=Map.of("backgroundFactRegistry",List.of(Map.of("factId",id,"causeEligible",false)));
  var schema=reportSchema(encode(noData));assertThat(schema.at("/properties/hypotheses/maxItems").asInt()).isZero();
  var errors=new ArrayList<String>();ContractErrors.validate(tree(report(id)),schema,"",errors);assertThat(errors.toString()).contains("COUNT_OUT_OF_RANGE");
  errors.clear();ContractErrors.validate(tree(Map.of("hypotheses",List.of(),"checks",List.of("COLLECT_LOGS"))),schema,"",errors);assertThat(errors).isEmpty();
  assertThat(reportSchema("{}").at("/properties/hypotheses/maxItems").asInt()).isZero();
 }
 @Test void malformedRegistryIdFailsLocallyWithoutGuessing(){
  assertThatThrownBy(()->reportSchema(encode(Map.of("causeFactRegistry",List.of(Map.of("factId","F18","causeEligible",true)))))).hasMessage("INVALID_OFFERED_FACT_METADATA");
  assertThatThrownBy(()->reportSchema(encode(Map.of("causeFactRegistry",List.of(Map.of("factId","F"+"a".repeat(24),"contextRef","MISSING")))))).hasMessage("INVALID_OFFERED_FACT_CONTEXT");
 }
}
