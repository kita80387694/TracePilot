package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class MetricFactsTest {
 @Test void actualV17LaterRowsKeepMetricKindsAndRejectCumulativeIncidentOutcomes() throws Exception {
  var sample=JSON.readTree(getClass().getResourceAsStream("/metric-semantics-v18.json"));var r=JSON.treeToValue(sample.path("request"),Request.class);
  var es=new ArrayList<Evidence>();for(var e:sample.path("evidence"))es.add(JSON.treeToValue(e,Evidence.class));
  var byPath=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();for(var x:EvidenceUse.catalog(es,r)){var n=tree(x);byPath.put(n.path("pointer").asText(),n);}
  assertThat(byPath.get("/data/22/pool/pending").path("kind").asText()).isEqualTo("INSTANTANEOUS");
  assertThat(byPath.get("/data/22/http/averageMs").path("kind").asText()).isEqualTo("SAMPLE_INTERVAL_MEAN");
  for(String field:List.of("errors5xx","totalMs")){
   var f=byPath.get("/data/22/http/"+field);assertThat(f.path("kind").asText()).isEqualTo("CUMULATIVE");assertThat(f.path("causeEligible").asBoolean()).isFalse();
   assertThat(f.path("purpose").asText()).isEqualTo("BACKGROUND");
  }
  var errors=new ArrayList<String>();ReviewContract.validateReferences(sample.path("action").path("hypotheses").get(0),es,r,"/hypotheses/0",errors);
  assertThat(errors).anySatisfy(error->{var e=parse(error);assertThat(e.path("code").asText()).isEqualTo("NOT_INCIDENT_OBSERVATION");assertThat(e.path("path").asText()).startsWith("/hypotheses/0/outcomeFactIds/");});
 }
 @Test void semanticsCoverTheSameArrayBoundaryAsRegistration() throws Exception {
  var r=new ContractBoundaryTest().request();var rows=new ArrayList<Object>();
  for(int i=0;i<201;i++)rows.add(Map.of("deploymentVersion",MetricFacts.BUSINESS_VERSION,"time",r.start().toString(),"http",Map.of("count",i),"pool",Map.of("active",i)));
  var e=new Evidence("boundary","metrics","AVAILABLE",r.start().toString(),r.end().toString(),Map.of(),tree(Map.of("data",rows)));
  var facts=FactReferences.catalog(List.of(e),r).stream().map(Domain::tree).toList();
  assertThat(facts).anySatisfy(f->{assertThat(f.path("pointer").asText()).isEqualTo("/data/199/http/count");assertThat(f.path("kind").asText()).isEqualTo("CUMULATIVE");});
  assertThat(facts).noneSatisfy(f->assertThat(f.path("pointer").asText()).startsWith("/data/200/"));
  assertThat(facts.stream().filter(f->f.path("pointer").asText().endsWith("/http/count"))).hasSize(200).allSatisfy(f->assertThat(f.path("kind").asText()).isEqualTo("CUMULATIVE"));
 }
 com.fasterxml.jackson.databind.JsonNode fixture() throws Exception{return JSON.readTree(getClass().getResourceAsStream("/model/d3-real-v81.json"));}
 List<Evidence> evidence(com.fasterxml.jackson.databind.JsonNode f) throws Exception {var es=new ArrayList<Evidence>();for(var e:f.path("evidence"))es.add(JSON.treeToValue(e,Evidence.class));return es;}
 @Test void realSnapshotRetainsValuesButDistinguishesCounterDeltaFractionAndGauge() throws Exception {
  var f=fixture();var es=evidence(f);var r=JSON.treeToValue(f.path("input"),Request.class);var rows=MetricFacts.generate(es,r);var byPath=new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
  for(Object row:rows){var n=tree(row);byPath.put(n.path("pointer").asText(),n);var e=es.stream().filter(x->x.id().equals(n.path("evidenceId").asText())).findFirst().orElseThrow();assertThat(e.data().at(n.path("pointer").asText())).isEqualTo(n.path("value"));}
  assertThat(byPath.get("/http/count").path("kind").asText()).isEqualTo("CUMULATIVE");
  assertThat(byPath.get("/http/intervalCount").path("kind").asText()).isEqualTo("SAMPLE_INTERVAL_DELTA");
  assertThat(byPath.get("/http/status").path("kind").asText()).isEqualTo("SAMPLE_INTERVAL_STATUS");
  assertThat(byPath.get("/http/errorRate").path("unit").asText()).isEqualTo("fraction_not_per_second");
  assertThat(byPath.get("/pool/active").path("kind").asText()).isEqualTo("INSTANTANEOUS");
  assertThat(byPath.get("/http/count").path("timeBasis").asText()).contains("delta NOT computed");
 }
 @Test void actualMissingValuesStayNullNotZeroAndSourceFailuresProduceNoMetrics() throws Exception {
  var f=fixture();var es=evidence(f);var r=JSON.treeToValue(f.path("input"),Request.class);
  var rows=MetricFacts.generate(es,r).stream().map(Domain::tree).toList();
  assertThat(rows).anySatisfy(n->{assertThat(n.path("pointer").asText()).isEqualTo("/http/errorRate");assertThat(n.path("value").isNull()).isTrue();assertThat(n.path("valueAvailability").asText()).isEqualTo("NO_VALUE_NOT_ZERO");});
  assertThat(MetricFacts.generate(es.stream().filter(e->!EvidenceRegistry.readable(e)).toList(),r)).isEmpty();
 }
 @Test void unknownDeploymentDoesNotInheritKnownMetricSemantics() throws Exception {
  var f=fixture();var e=evidence(f).get(0);var data=e.data().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)data).put("deploymentVersion","different");
  var changed=new Evidence(e.id(),e.source(),e.status(),e.start(),e.end(),e.locator(),data);
  assertThat(MetricFacts.generate(List.of(changed),JSON.treeToValue(f.path("input"),Request.class))).allSatisfy(x->assertThat(tree(x).path("kind").asText()).isEqualTo("UNKNOWN_VERSION_SEMANTICS"));
 }
}
