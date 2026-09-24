package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real store/transactions/MySQL and loopback observation HTTP. Saved model actions, real serializer and loopback HTTP only; no provider connection. */
class CorrectionIdMysqlReplayTest {
 static HikariDataSource ds;static JdbcTemplate db;TransactionTemplate tx;TaskStore store;
 @TempDir Path temp;HttpServer server;JsonNode fixture;Request request;List<Evidence> original;
 final List<String> sourceCalls=new ArrayList<>(); List<Object> replayRounds; final List<byte[]> wireRequests=new ArrayList<>(); final java.util.concurrent.atomic.AtomicReference<JsonNode> wireAction=new java.util.concurrent.atomic.AtomicReference<>();
 @BeforeAll static void database(){
  var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv().getOrDefault("DIAG_TEST_DB_URL","jdbc:mysql://127.0.0.1:3318/diagnosis_refactor_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
  cfg.setUsername("tracepilot_diag");cfg.setPassword(System.getenv("DIAG_DB_PASSWORD"));cfg.setMaximumPoolSize(4);cfg.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
  ds=new HikariDataSource(cfg);db=new JdbcTemplate(ds);
  assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("diagnosis_refactor_test");
  Flyway.configure().dataSource(ds).load().migrate();
 }
 @AfterAll static void closeDatabase(){if(ds!=null)ds.close();}
 @BeforeEach void setup()throws Exception{
  for(String table:List.of("diagnosis_event","diagnosis_step","diagnosis_evidence","diagnosis_task"))db.update("DELETE FROM "+table);
  tx=new TransactionTemplate(new DataSourceTransactionManager(ds));store=new TaskStore(db,tx);
  fixture=JSON.readTree(getClass().getResourceAsStream("/model/correction-id-v21.json"));request=JSON.treeToValue(fixture.path("request"),Request.class);
  original=new ArrayList<>();for(var e:fixture.path("evidence"))original.add(JSON.treeToValue(e,Evidence.class));
  // Synthetic empty metrics supplements this historical replay; original saved actions/data stay unchanged.
  original.add(new Evidence("EreplayMetrics","metrics","NO_DATA",request.start().toString(),request.end().toString(),Map.of(),tree(Map.of("status","NO_DATA","data",List.of(),"hasMore",false))));
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  for(var e:original){String route=e.source().equals("overview")?"/ops/snapshot":e.source().equals("metrics")?"/ops/samples":"/ops/logs";server.createContext(route,x->{
   sourceCalls.add(x.getRequestURI().toString());byte[] bytes=encode(e.data()).getBytes(StandardCharsets.UTF_8);
   x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}finally{x.close();}
  });}
  server.createContext("/v1/messages",x->{wireRequests.add(x.getRequestBody().readAllBytes());byte[] response=encode(Map.of("id","offline-replay","type","message","role","assistant","model",ModelCaptureTest.MODEL,"content",List.of(Map.of("type","text","text",encode(wireAction.get()))),"stop_reason","end_turn","usage",Map.of("input_tokens",1,"output_tokens",1))).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,response.length);try(var out=x.getResponseBody()){out.write(response);}finally{x.close();}});server.start();
 }
 @AfterEach void closeServer(){if(server!=null)server.stop(0);}
 class SavedResponses implements ModelGateway {
  final AtomicInteger count=new AtomicInteger();final List<Object> rounds=new ArrayList<>();final TaskStore actual;final String taskId;
  SavedResponses(TaskStore actual,String taskId){this.actual=actual;this.taskId=taskId;}
  public boolean configured(){return true;}
  public Map<String,Object> configuration(){return Map.of("adapterVersion","OFFLINE_SAVED_RESPONSE_REPLAY","providerCalls",0);}
  public ModelReply call(String system,String input){
   int ordinal=count.getAndIncrement();assertThat(ordinal).isLessThan(fixture.path("actions").size());
   var ids=new LinkedHashMap<String,String>();
   for(var stored:actual.evidence(taskId)){
    var old=original.stream().filter(e->e.source().equals(stored.source())).findFirst().orElseThrow();
    ids.put(old.id(),stored.id());assertThat(stored.data()).isEqualTo(old.data());assertThat(stored.start()).isEqualTo(old.start());assertThat(stored.end()).isEqualTo(old.end());
    for(var fact:FactReferences.catalog(List.of(old),request)){
     var f=tree(fact);ids.put(f.path("factId").asText(),FactReferences.id(stored.id(),f.path("pointer").asText()));
    }
   }
   var saved=fixture.path("actions").get(ordinal);var action=bind(saved,ids);var reverse=new LinkedHashMap<String,String>();ids.forEach((a,b)->reverse.put(b,a));
   assertThat(bind(action,reverse)).isEqualTo(saved); // Only deterministic task-local IDs change, not wording or values.
   // v2 packing changes page boundaries. Explicit synthetic read selection preserves the subsequent
   // saved report's scope for this correction test; it is not replayed model selection performance.
   if(action.path("type").asText().equals("readEvidence")){
    var pins=new LinkedHashSet<>(EvidenceDelivery.strings(action.path("retainFactIds")));
    for(int next=ordinal+1;next<fixture.path("actions").size();next++){
     var report=bind(fixture.path("actions").get(next),ids);
     for(var hypothesis:report.path("hypotheses")){
      for(String role:ReviewContract.ROLES)hypothesis.path(role).forEach(id->pins.add(id.asText()));
      var matcher=FactReferences.REF.matcher(hypothesis.path("mechanism").asText());while(matcher.find())pins.add(matcher.group(1));
     }
    }
    ((ObjectNode)action).set("retainFactIds",tree(pins));
   }
   var provided=new HashSet<String>();for(String key:List.of("causeFactRegistry","backgroundFactRegistry"))parse(input).path(key).forEach(f->provided.add(f.path("factId").asText()));
   if(action.path("type").asText().equals("report"))for(var h:action.path("hypotheses"))for(String role:List.of("premiseFactIds","outcomeFactIds","contradictionFactIds"))for(var id:h.path(role))assertThat(provided).contains(id.asText());
   wireAction.set(action);var local=new SpringAiGateway("LOCAL_FIXTURE_ONLY","http://127.0.0.1:"+server.getAddress().getPort(),ModelCaptureTest.MODEL).call(system,input);
   assertThat(local.action()).isEqualTo(action);byte[] bytes=wireRequests.getLast();assertThat(bytes.length).isLessThanOrEqualTo(RequestBoundary.MAX_WIRE_UTF8_BYTES);
   var sent=parse(new String(bytes,StandardCharsets.UTF_8));assertThat(sent.at("/messages/0/content/0/text").asText()).isEqualTo(input);
   rounds.add(Map.of("ordinal",ordinal+1,"contextUtf16",input.length(),"sourceAction",saved,"idBinding",ids,"replayedAction",action,"actualInput",parse(input),"wireUtf8Bytes",bytes.length));
   return new ModelReply(action,"OFFLINE_REPLAY_NOT_A_PROVIDER_CALL",null,null,Map.of("usageSource","NOT_APPLICABLE_OFFLINE","realModelCalls",0));
  }
 }
 static JsonNode bind(JsonNode n,Map<String,String> ids){
  if(n.isTextual()){String text=n.asText();for(var e:ids.entrySet())text=text.replace(e.getKey(),e.getValue());return TextNode.valueOf(text);}
  if(n.isObject()){var out=JSON.createObjectNode();n.fields().forEachRemaining(e->out.set(e.getKey(),bind(e.getValue(),ids)));return out;}
  if(n.isArray()){var out=JSON.createArrayNode();n.forEach(v->out.add(bind(v,ids)));return out;}return n.deepCopy();
 }
 Map<String,Object> replay(TaskStore actual,String name,int expectedCalls)throws Exception{
  String id=actual.create("offline-replay",request);Claim claim=actual.claim();assertThat(claim.id()).isEqualTo(id);
  var model=new SavedResponses(actual,id);var tools=new ReadTools("http://127.0.0.1:"+server.getAddress().getPort(),"offline-token",temp.toString());
  // Preserve saved historical action order; production initial cross-source collection has separate tests.
  var workflow=new DirectWorkflow(actual,tools,model){@Override protected void initialSources(Claim c)throws Exception{query(c,new Query("metrics",Map.of()));}};try{workflow.run(claim);}finally{workflow.close();}
  replayRounds=model.rounds;var persisted=actual.get(id,"offline-replay");
  Files.createDirectories(Path.of("target/replay"));Files.writeString(Path.of("target/replay",name+"-current-debug.json"),encode(Map.of("task",persisted,"rounds",model.rounds,"steps",db.queryForList("SELECT kind,status,detail_json FROM diagnosis_step WHERE task_id=? ORDER BY id",id))));
  assertThat(model.count.get()).isEqualTo(expectedCalls);assertThat(sourceCalls).hasSize(3);
  assertThat(persisted.get("model_calls")).isEqualTo(expectedCalls);assertThat(persisted.get("tool_calls")).isEqualTo(4);assertThat(actual.evidence(id)).hasSize(3);
  var artifact=new LinkedHashMap<String,Object>();artifact.put("origin","OFFLINE_MYSQL_FULL_WORKFLOW_REPLAY_NOT_REAL_MODEL_VALIDATION");artifact.put("readSelectionAdaptation","SYNTHETIC_RETAIN_EXISTING_REPORT_REFERENCES_FOR_CHANGED_PAGE_BOUNDARIES");artifact.put("realModelCalls",0);artifact.put("supplierTokenUsage","NOT_APPLICABLE");artifact.put("originalTaskStatusUnchanged",fixture.path("originalStatus"));artifact.put("rounds",model.rounds);artifact.put("sourceHttpRequests",sourceCalls);artifact.put("persistedTask",persisted);
  Path out=Path.of("target/replay");Files.createDirectories(out);Files.writeString(out.resolve(name+".json"),encode(artifact));return persisted;
 }

 @Test void savedResponseNowCompletesWithoutUnnecessaryCorrectionButOriginalFailureIsPreserved()throws Exception{
  var row=replay(store,"correction-id-current-display-policy",4);assertThat(row.get("status")).isEqualTo("COMPLETED");
  var report=(JsonNode)row.get("report_json");assertThat(report.path("semanticReview").asText()).contains("PENDING_HUMAN");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnosis_step WHERE task_id=? AND kind='ACTION' AND status='REJECTED'",Integer.class,row.get("id"))).isZero();
  assertThat(fixture.path("actualInputs").get(4).at("/actualModelInput/checkpoint/correction/details")).hasSize(4);
  assertThat(replayRounds).hasSize(4); // Old fifth correction is kept in the fixture, not forced into this current run.
 }
 void checkCorrectionAndBounds(){
  var fourth=tree(replayRounds.get(3));var fifth=tree(replayRounds.get(4));var action=fourth.path("replayedAction");var h=action.path("hypotheses").get(0);
  var correction=fifth.at("/actualInput/checkpoint/correction");assertThat(correction.path("total").asInt()).isEqualTo(1);
  assertThat(correction.path("details").get(0).path("code").asText()).isEqualTo("MALFORMED_FACT_REFERENCE");
  assertThat(correction.path("details").get(0).path("path").asText()).isEqualTo("/hypotheses/0/mechanism");
  assertThat(correction.at("/rejectedAction/action/hypotheses/0/mechanism")).isEqualTo(h.path("mechanism"));
  assertThat(correction.at("/rejectedAction/action").has("evidence")).isFalse();
  for(var round:replayRounds){var input=tree(round).path("actualInput");assertThat(encode(input).length()).isLessThan(100000);assertThat(encode(input.path("checkpoint").path("correction")).length()).isLessThan(10000);}
  assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnosis_task WHERE status IN ('QUEUED','RUNNING')",Integer.class)).isZero();
 }
 @Test void explicitSyntheticCorrectionCanReachReviewAndPersistReportWithoutChangingValidator()throws Exception{
  // Explicit synthetic malformed response: the original valid display references no longer need repair.
  var fourth=(ObjectNode)fixture.path("actions").get(3).path("hypotheses").get(0);
  fourth.put("mechanism",fourth.path("mechanism").asText()+" {{fact:F...}}");
  var row=replay(store,"correction-id-synthetic-malformed-then-valid",5);var report=(JsonNode)row.get("report_json");
  assertThat(row.get("status")).isEqualTo("COMPLETED"); // Lossless v2 packing supplies all facts before the synthetic read selection.
  assertThat(report.at("/evidenceDelivery/unreadRegisteredFactCount").asInt()).isZero();
  assertThat(report.at("/completionCoverage/complete").asBoolean()).isTrue();
  assertThat(report.path("gaps").toString()).doesNotContain("DIRECT_CONTRACT_FAILED");assertThat(report.path("candidates")).hasSize(1);
  var step=db.queryForMap("SELECT status,detail_json FROM diagnosis_step WHERE task_id=? AND kind='REVIEW'",row.get("id"));assertThat(step.get("status")).isEqualTo("VALIDATED");assertThat(step.get("detail_json").toString()).contains("PENDING_HUMAN","STRUCTURAL_ONLY");
  assertThat(report.path("modelPlan").path("hypotheses").get(0).path("mechanism").asText()).contains("The notification write attempt started");
  assertThat(report.path("semanticReview").asText()).contains("PENDING_HUMAN");checkCorrectionAndBounds();
 }
 @Test void registeredButNotProvidedAndUnknownMechanismIdsStayRedacted(){
  var action=fixture.path("actions").get(4);var known="Fafed1393fdbddeb3be1f77db";
  var hidden=DirectContract.render(action,original,request,Set.of()).errors();assertThat(hidden).hasSize(1);assertThat(parse(hidden.get(0)).path("actual").asText()).isEqualTo("[REDACTED_TEXT]");
  var visible=DirectContract.render(action,original,request,Set.of(known));assertThat(visible.errors()).isEmpty();assertThat(visible.report().at("/causalAssessment/humanReview").asText()).isEqualTo("PENDING");
  var forged=(ObjectNode)action.deepCopy();String unknown="F000000000000000000000000";((ObjectNode)forged.path("hypotheses").get(0)).put("mechanism","Unknown {{fact:"+unknown+"}}");
  var errors=DirectContract.render(forged,original,request,Set.of(unknown)).errors();assertThat(errors).hasSize(1);assertThat(parse(errors.get(0)).path("actual").asText()).isEqualTo("[REDACTED_TEXT]");
 }
 @Test void ordinarySensitiveTextStillRedactedAndHistoricalRawCorrectionUnchanged(){
  var issue=ContractErrors.issue("MALFORMED_FACT_REFERENCE","/hypotheses/0/mechanism",tree("secret-value"),"valid reference","correct field");assertThat(encode(issue)).doesNotContain("secret-value");
  var old=fixture.path("actualInputs").get(4).at("/actualModelInput/checkpoint/correction/details");assertThat(old).hasSize(4);old.forEach(e->assertThat(e.path("actual").asText()).isEqualTo("[REDACTED_TEXT]"));
 }
}
