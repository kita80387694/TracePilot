package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CodeScopeTest {
 @Test void realEvidenceVersionSurvivesPackingAndConstrainsTheActualWire() throws Exception {
  var sample=JSON.readTree(getClass().getResourceAsStream("/code-scope-v20.json"));
  assertThat(sample.path("originalInputHadObservedVersion").asBoolean()).isFalse();
  var req=JSON.treeToValue(sample.path("request"),Request.class);var helper=new BoundedActionsTest();
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req)){
   h.evidence.clear();for(var e:sample.path("evidence"))h.evidence.add(JSON.treeToValue(e,Evidence.class));
   var frame=WorkingContext.prepare(h.workflow.contextData(h.claim,Map.of()),Map.of("windowSample",true));
   var input=parse(frame.text());var versions=Workflow.versions(h.evidence);assertThat(versions).hasSize(1);
   assertThat(input.at("/codeQueryScope/allowedVersions")).isEqualTo(tree(new TreeSet<>(versions)));
   try(var endpoint=helper.new Endpoint(List.of(helper.call("bad","query_code",map(encode(sample.path("actualQuery"))))))){
    var reply=endpoint.gateway("named-tools-v2").callAction(false,true,frame.text(),"LOCAL_ONLY",1,x->{});
    assertThat(reply.action().toString()).contains("INVALID_ENUM","/arguments/args/version");
    var wire=endpoint.wires.getFirst();var tools=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();wire.path("tools").forEach(tools::add);
    assertThat(tools.stream().filter(x->x.path("name").asText().equals("query_code")).findFirst().orElseThrow().at("/input_schema/properties/args/properties/version/enum")).isEqualTo(tree(new TreeSet<>(versions)));
    assertThat(encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   }
  }
 }
 @Test void noObservedVersionDoesNotOfferCodeAndCurrentScopeDoesNotLeakAcrossCalls(){
  assertThat(NamedToolProtocol.offered(false,true,"{}")).noneMatch(d->d.name().equals("query_code"));
  String input=encode(Map.of("codeQueryScope",Map.of("allowedVersions",List.of(MetricFacts.INTERVAL_VERSION))));
  assertThat(NamedToolProtocol.offered(false,true,input)).anyMatch(d->d.name().equals("query_code"));
  assertThat(NamedToolProtocol.offered(false,true,"{}")).noneMatch(d->d.name().equals("query_code"));
 }
 @Test void metadataLimitIsDisclosedAndNeverCreatesCausalFacts() throws Exception {
  var r=new ContractBoundaryTest().request();var es=new ArrayList<Evidence>();
  for(int i=0;i<17;i++)es.add(new Evidence("E"+i,"metrics","AVAILABLE",r.start().toString(),r.end().toString(),Map.of(),tree(Map.of("deploymentVersion","sha256-"+String.format("%064x",i+1)))));
  var scope=tree(Workflow.codeScope(es));assertThat(scope.path("allowedVersions")).hasSize(16);assertThat(scope.path("complete").asBoolean()).isFalse();assertThat(scope.path("observedVersionCount").asInt()).isEqualTo(17);
  assertThat(scope.path("meaning").asText()).contains("not incident-cause facts");
 }
}
