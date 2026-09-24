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

/** Real store/transactions/MySQL and loopback observation HTTP. Saved model actions only; no provider client. */
class DirectWorkflowMysqlReplayTest {
 static HikariDataSource ds;static JdbcTemplate db;TransactionTemplate tx;TaskStore store;
 @TempDir Path temp;HttpServer server;JsonNode fixture;Request request;List<Evidence> original;
 final List<String> sourceCalls=new ArrayList<>();
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
  fixture=JSON.readTree(getClass().getResourceAsStream("/model/audit-state-v2.json"));request=JSON.treeToValue(fixture.path("request"),Request.class);
  original=new ArrayList<>();for(var e:fixture.path("evidence"))original.add(JSON.treeToValue(e,Evidence.class));
  // Synthetic empty metrics supplements this historical replay; original saved actions/data stay unchanged.
  original.add(new Evidence("EreplayMetrics","metrics","NO_DATA",request.start().toString(),request.end().toString(),Map.of(),tree(Map.of("status","NO_DATA","data",List.of(),"hasMore",false))));
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  for(var e:original){String route=e.source().equals("overview")?"/ops/snapshot":e.source().equals("metrics")?"/ops/samples":"/ops/logs";server.createContext(route,x->{
   sourceCalls.add(x.getRequestURI().toString());byte[] bytes=encode(e.data()).getBytes(StandardCharsets.UTF_8);
   x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}finally{x.close();}
  });}server.start();
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
    assertThat(stored.data()).isEqualTo(old.data());assertThat(stored.start()).isEqualTo(old.start());assertThat(stored.end()).isEqualTo(old.end());
    for(var fact:FactReferences.catalog(List.of(old),request)){
     var f=tree(fact);ids.put(f.path("factId").asText(),FactReferences.id(stored.id(),f.path("pointer").asText()));
    }
   }
   var saved=fixture.path("actions").get(ordinal);var action=bind(saved,ids);var reverse=new LinkedHashMap<String,String>();ids.forEach((a,b)->reverse.put(b,a));
   assertThat(bind(action,reverse)).isEqualTo(saved); // Only deterministic task-local IDs change, not wording or values.
   var provided=new HashSet<String>();for(String key:List.of("causeFactRegistry","backgroundFactRegistry"))parse(input).path(key).forEach(f->provided.add(f.path("factId").asText()));
   if(action.path("type").asText().equals("report"))for(var h:action.path("hypotheses"))for(String role:List.of("premiseFactIds","outcomeFactIds","contradictionFactIds"))for(var id:h.path(role))assertThat(provided).contains(id.asText());
   rounds.add(Map.of("ordinal",ordinal+1,"contextUtf16",input.length(),"sourceAction",saved,"idBinding",ids,"replayedAction",action));
   return new ModelReply(action,"OFFLINE_REPLAY_NOT_A_PROVIDER_CALL",null,null,Map.of("usageSource","NOT_APPLICABLE_OFFLINE","realModelCalls",0));
  }
 }
 static JsonNode bind(JsonNode n,Map<String,String> ids){
  if(n.isTextual()){String text=n.asText();for(var e:ids.entrySet())text=text.replace(e.getKey(),e.getValue());return TextNode.valueOf(text);}
  if(n.isObject()){var out=JSON.createObjectNode();n.fields().forEachRemaining(e->out.set(e.getKey(),bind(e.getValue(),ids)));return out;}
  if(n.isArray()){var out=JSON.createArrayNode();n.forEach(v->out.add(bind(v,ids)));return out;}return n.deepCopy();
 }
 Map<String,Object> replay(TaskStore actual,String name)throws Exception{
  String id=actual.create("offline-replay",request);Claim claim=actual.claim();assertThat(claim.id()).isEqualTo(id);
  var model=new SavedResponses(actual,id);var tools=new ReadTools("http://127.0.0.1:"+server.getAddress().getPort(),"offline-token",temp.toString());
  // Preserve saved historical action order; production initial cross-source collection has separate tests.
  var workflow=new DirectWorkflow(actual,tools,model){@Override protected void initialSources(Claim c)throws Exception{query(c,new Query("metrics",Map.of()));}};try{workflow.run(claim);}finally{workflow.close();}
  var persisted=actual.get(id,"offline-replay");assertThat(model.count.get()).isEqualTo(2);assertThat(sourceCalls).hasSize(3);
  assertThat(persisted.get("model_calls")).isEqualTo(2);assertThat(persisted.get("tool_calls")).isEqualTo(3);assertThat(actual.evidence(id)).hasSize(3);
  var artifact=new LinkedHashMap<String,Object>();artifact.put("origin","OFFLINE_MYSQL_FULL_WORKFLOW_REPLAY_NOT_REAL_MODEL_VALIDATION");artifact.put("realModelCalls",0);artifact.put("supplierTokenUsage","NOT_APPLICABLE");artifact.put("originalTaskStatusUnchanged",fixture.path("originalStatus"));artifact.put("rounds",model.rounds);artifact.put("sourceHttpRequests",sourceCalls);artifact.put("persistedTask",persisted);
  Path out=Path.of("target/replay");Files.createDirectories(out);Files.writeString(out.resolve(name+".json"),encode(artifact));return persisted;
 }
 @Test void actualSavedResponsesReachCommittedReportWithMechanismIntact()throws Exception{
  var row=replay(store,"audit-state-success");assertThat(row.get("status")).isEqualTo("COMPLETED");String id=row.get("id").toString();
  var report=(JsonNode)row.get("report_json");assertThat(report.path("candidates")).hasSize(2);assertThat(report.path("semanticReview").asText()).contains("PENDING_HUMAN");
  var es=store.evidence(id);var expected=DirectContract.render(report.path("modelPlan"),es,request);assertThat(expected.errors()).isEmpty();
  assertThat(report.path("candidates")).isEqualTo(expected.report().path("candidates"));
  assertThat(report.at("/modelPlan/hypotheses/1/mechanism")).isEqualTo(fixture.at("/actions/1/hypotheses/1/mechanism"));
  assertThat(report.at("/candidates/1/mechanism")).isEqualTo(fixture.at("/actions/1/hypotheses/1/mechanism"));
  var audit=db.queryForMap("SELECT status,detail_json FROM diagnosis_step WHERE task_id=? AND kind='REVIEW'",id);
  assertThat(audit.get("status")).isEqualTo("VALIDATED");var detail=parse(audit.get("detail_json").toString());assertThat(detail.path("validation").asText()).isEqualTo("STRUCTURAL_ONLY");assertThat(detail.path("semanticReview").asText()).isEqualTo("PENDING_HUMAN");
  assertThat(db.queryForObject("SELECT lease_token FROM diagnosis_task WHERE id=?",String.class,id)).isNull();
  assertThat(parse(db.queryForObject("SELECT payload_json FROM diagnosis_event WHERE task_id=? ORDER BY id DESC LIMIT 1",String.class,id)).path("status").asText()).isEqualTo("COMPLETED");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnosis_step WHERE task_id=? AND (CHAR_LENGTH(kind)>24 OR CHAR_LENGTH(status)>24)",Integer.class,id)).isZero();
 }
 @Test void oldOversizedReviewWriteStillFailsAndPersistsHonestPartial()throws Exception{
  var broken=new TaskStore(db,tx){@Override public void step(Claim c,String kind,String status,Object detail){super.step(c,kind,kind.equals("REVIEW")?"STRUCTURAL_VALIDATION_ONLY":status,detail);}};
  var row=replay(broken,"audit-state-original-failure");assertThat(row.get("status")).isEqualTo("PARTIAL");var report=(JsonNode)row.get("report_json");
  assertThat(report.path("gaps").toString()).contains("DataIntegrityViolationException");assertThat(report.path("candidates")).isEmpty();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnosis_step WHERE task_id=? AND kind='REVIEW'",Integer.class,row.get("id"))).isZero();
  assertThat(report.at("/execution/budget/model_calls").asInt()).isEqualTo(2);
 }
 @Test void databaseStatusBoundaryRejectsOverflowWithoutPartialStepOrSseWrite(){
  String id=store.create("offline-replay",request);Claim c=store.claim();assertThat(c.id()).isEqualTo(id);
  assertThat(db.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='diagnosis_step' AND COLUMN_NAME='status'",Integer.class)).isEqualTo(24);
  store.step(c,"BOUNDARY","A".repeat(24),Map.of("test","exact limit"));
  int steps=db.queryForObject("SELECT COUNT(*) FROM diagnosis_step WHERE task_id=?",Integer.class,id);int events=db.queryForObject("SELECT COUNT(*) FROM diagnosis_event WHERE task_id=?",Integer.class,id);
  assertThatThrownBy(()->store.step(c,"BOUNDARY","A".repeat(25),Map.of())).isInstanceOf(DataIntegrityViolationException.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnosis_step WHERE task_id=?",Integer.class,id)).isEqualTo(steps);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnosis_event WHERE task_id=?",Integer.class,id)).isEqualTo(events);
 }
}
