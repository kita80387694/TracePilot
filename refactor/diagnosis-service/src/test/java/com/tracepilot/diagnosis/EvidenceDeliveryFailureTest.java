package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;import java.time.*;import org.junit.jupiter.api.Test;
class EvidenceDeliveryFailureTest {
 com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {return tree(map(new String(getClass().getResourceAsStream("/model/evidence-delivery-v88.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));}
 @Test void realReviewFailureIsReferenceContractNotJsonSyntax() throws Exception {
  var f=fixture();var req=f.path("request");var r=new Request(req.path("service").asText(),req.path("environment").asText(),Instant.parse(req.path("start").asText()),Instant.parse(req.path("end").asText()),req.path("symptom").asText());var es=new ArrayList<Evidence>();
  for(var e:f.path("evidence"))es.add(new Evidence(e.path("id").asText(),e.path("source").asText(),e.path("status").asText(),e.path("start").asText(),e.path("end").asText(),map(encode(e.path("locator"))),e.path("data")));
  assertThat(Hypotheses.review(f.path("review"),f.path("workingHypotheses"),es,r).errors()).contains("REVIEW_REFERENCE_MISMATCH:H1:supportFactIds","INVALID_ARRAY:/assessments/H1/supportFactIds");
 }
 @Test void correctionUsesSameFactsOnceWithoutRaisingContextLimit() throws Exception {
  var f=fixture();var input=map(encode(f.path("input")));var checkpoint=map(encode(f.path("input").path("checkpoint")));checkpoint.put("evidenceFeedback",f.path("rejection"));input.put("checkpoint",checkpoint);
  assertThat(encode(input).length()).isGreaterThan(100000);
  var before=tree(input).path("causeFactRegistry").deepCopy();
  ContextPacking.compactFeedback(input);
  assertThat(encode(input).length()).isLessThan(100000);
  assertThat(tree(input).path("causeFactRegistry")).isEqualTo(before);
  var ids=tree(input).at("/checkpoint/evidenceFeedback/allowedCauseFactIds");
  assertThat(ids.size()).isEqualTo(f.path("rejection").path("allowedCauseFacts").size());
  for(var id:ids)assertThat(before.findValuesAsText("factId")).contains(id.asText());
  assertThat(tree(input).at("/checkpoint/evidenceFeedback/violations")).isEqualTo(f.path("rejection").path("violations"));
 }
}
