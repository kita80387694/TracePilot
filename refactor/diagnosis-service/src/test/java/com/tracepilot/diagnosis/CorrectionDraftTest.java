package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CorrectionDraftTest {
 @Test void expiredCheckpointDraftIsHiddenWithoutErasingStructuredErrors(){
  var state=tree(Map.of("correction",Map.of("details",List.of("MALFORMED_FACT_REFERENCE"),"rejectedAction",Map.of("included",true,"expiresAtEpochMilli",1,"action",Map.of("mechanism","old draft")))));
  var cleaned=TaskStore.hideExpiredDraft(state);
  assertThat(cleaned.toString()).doesNotContain("old draft");
  assertThat(cleaned.at("/correction/rejectedActionExpired").asBoolean()).isTrue();
  assertThat(cleaned.at("/correction/details")).hasSize(1);
 }
 @Test void actualRejectedActionReachesOneCorrectionAndCurrentValidDisplayReferencesCanComplete()throws Exception{replay(false);}
 @Test void syntheticSecondMalformedResponseStillEndsPartialWithinOneCorrection()throws Exception{replay(true);}
 void replay(boolean secondMalformed) throws Exception {
  var f=JSON.readTree(getClass().getResourceAsStream("/correction-draft-v28.json"));
  var req=JSON.treeToValue(f.path("request"),Request.class);var helper=new BoundedActionsTest();
  var rounds=new ArrayList<List<Map<String,Object>>>();
  for(var round:f.path("calls")){var batch=new ArrayList<Map<String,Object>>();round.forEach(x->batch.add(map(encode(x))));rounds.add(batch);}
  if(secondMalformed)rounds.set(4,rounds.get(3)); // Synthetic duplicate invalid action; original fixture remains unchanged.
  try(var h=new DirectWorkflowTest().new Harness("NO_DATA",req);
      var endpoint=helper.new Endpoint(rounds.get(0),rounds.get(1),rounds.get(2),rounds.get(3),rounds.get(4))){
   h.evidence.clear();for(var row:f.path("evidence"))h.evidence.add(JSON.treeToValue(row,Evidence.class));
   var w=new DirectWorkflow(h.store,h.tools,endpoint.gateway("named-tools-v2"));try{w.run(h.claim);}finally{w.close();}
   assertThat(h.calls.get()).isEqualTo(5);assertThat(h.status.get()).isEqualTo(secondMalformed?"PARTIAL":"COMPLETED");
   var input=parse(endpoint.wires.getLast().at("/messages/0/content/0/text").asText());
   var correction=input.at("/checkpoint/correction");
   assertThat(correction.at("/rejectedAction/included").asBoolean()).isTrue();
   assertThat(correction.at("/rejectedAction/action/hypotheses/0/mechanism"))
       .isEqualTo(f.at("/calls/3/0/input/hypotheses/0/mechanism"));
   assertThat(correction.toString()).contains("{{fact:F...}}","MALFORMED_FACT_REFERENCE");
   assertThat(correction.at("/rejectedAction/action").has("evidence")).isFalse();
   for(var wire:endpoint.wires)assertThat(encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/replay"));
   java.nio.file.Files.writeString(java.nio.file.Path.of("target/replay/correction-draft-v35-"+(secondMalformed?"synthetic-double-failure":"actual-current-policy")+".json"),encode(Map.of("calls",h.calls.get(),"status",h.status.get(),"contextsUtf16",endpoint.wires.stream().map(x->x.at("/messages/0/content/0/text").asText().length()).toList(),"meaning",secondMalformed?"SYNTHETIC_SECOND_MALFORMED_RESPONSE; NO_PROVIDER_CALL":"SAVED_RESPONSE_CURRENT_POLICY; HISTORICAL_FAILURE_UNCHANGED; NO_MODEL_PASS")));
  }
 }
 @Test void sensitiveOrThinkingFieldsAreRemovedAndOversizeDraftIsExplicitlyOmitted(){
  var rejected=tree(Map.of("type","report","mechanism","ordinary mechanism", "thinking","private analysis", "password","do-not-retain", "authorization","Bearer secret-key"));
  var correction=tree(WorkingContext.correction(List.of("TEST_ERROR"),rejected));
  assertThat(correction.toString()).doesNotContain("private analysis","do-not-retain","secret-key");
  assertThat(correction.at("/rejectedAction/action/mechanism").asText()).isEqualTo("ordinary mechanism");
  var huge=tree(WorkingContext.correction(List.of("TEST_ERROR"),tree(Map.of("mechanism","x".repeat(20000)))));
  assertThat(huge.at("/rejectedAction/included").asBoolean()).isFalse();
  assertThat(huge.at("/rejectedAction/reason").asText()).isEqualTo("CORRECTION_SECTION_LIMIT");
  assertThat(encode(huge).length()).isLessThan(10000);
 }
}
