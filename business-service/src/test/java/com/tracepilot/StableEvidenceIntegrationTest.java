package com.tracepilot;

import static org.assertj.core.api.Assertions.*;
import com.tracepilot.events.*;
import com.tracepilot.observability.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.*;
import java.sql.Connection;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL lock errors at the two production operation boundaries; no model calls. */
@SpringBootTest(properties={"app.events-enabled=false","app.demo-seed=false","app.sample-enabled=false",
    "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=1",
    "app.data-dir=target/stable-evidence-test"})
@org.springframework.test.annotation.DirtiesContext
class StableEvidenceIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){M1IntegrationTest.database(r);}
  @Autowired JdbcTemplate db;
  @Autowired javax.sql.DataSource ds;
  @Autowired TransactionTemplate tx;
  @Autowired NotificationConsumer consumer;
  @Autowired AuxiliaryDatabase auxiliary;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  Logger logger; ListAppender<ILoggingEvent> logs; long eventId;
  @BeforeEach void prepare(){
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).endsWith("_test");
    // Only the dedicated test schema is reset; never the live development database.
    for(String table:List.of("notification","outbox","idempotency","participation","slot","resource","auth_token","admin_audit","app_user"))db.update("DELETE FROM "+table);
    db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(1,'evidence-test','unused','ADMIN')");
    db.update("INSERT INTO resource(id,owner_id,name,description) VALUES(1,1,'test','test')");
    db.update("INSERT INTO slot(id,resource_id,start_at,end_at,capacity,reserved_count) VALUES(1,1,UTC_TIMESTAMP(),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP()),1,1)");
    db.update("INSERT INTO participation(id,user_id,slot_id,status) VALUES(1,1,1,'RESERVED')");
    db.update("INSERT INTO outbox(user_id,participation_id,type,payload) VALUES(1,1,'RESERVED',JSON_OBJECT('kind','test'))");
    eventId=db.queryForObject("SELECT MAX(id) FROM outbox",Long.class);
    logger=(Logger)org.slf4j.LoggerFactory.getLogger("tracepilot.evidence");
    logs=new ListAppender<>();logs.setContext(logger.getLoggerContext());logs.start();logger.addAppender(logs);
  }
  @AfterEach void detach(){logger.detachAppender(logs);logs.stop();}
  Map<String,Object> fields(String name){
    var e=logs.list.stream().filter(x->name.equals(x.getMessage())).findFirst().orElseThrow();
    var result=new LinkedHashMap<String,Object>();e.getKeyValuePairs().forEach(k->result.put(k.key,k.value));return result;
  }
  Connection lock(String sql)throws Exception{
    var c=ds.getConnection();c.setAutoCommit(false);
    try(var s=c.prepareStatement(sql)){s.setLong(1,eventId);s.executeQuery().close();}return c;
  }
  void recover(EventPublisher publisher)throws Exception{
    // Honor the production next_at backoff instead of forcing an early retry.
    long deadline=System.nanoTime()+8_000_000_000L;
    while(System.nanoTime()<deadline){
      if(publisher.processOne())break;
      Thread.sleep(25);
    }
    assertThat(db.queryForObject("SELECT status FROM outbox WHERE id=?",String.class,eventId)).isEqualTo("DONE");
    consumer.deliver(eventId);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,eventId)).isEqualTo(1);
  }
  @Test void realNotificationLockFailureAndRetryRemainDistinctFromAcknowledgement()throws Exception{
    // Lock an existing notification, avoiding an INSERT's additional parent FK lock.
    // This is an idempotent redelivery: NOT_OBSERVED must not imply notification absence.
    consumer.deliver(eventId);
    var publisher=new EventPublisher(db,tx,consumer,false);
    try(var lock=lock("SELECT id FROM notification WHERE event_id=? FOR UPDATE")){
      try{assertThat(publisher.processOne()).isTrue();}finally{lock.rollback();}
    }
    assertThat(fields("event_operation_failed")).containsEntry("operationPhase","NOTIFICATION_TRANSACTION").containsEntry("notificationCommitObservation","NOT_OBSERVED");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,eventId)).isEqualTo(1);
    assertThat(fields("event_delivery_failed")).containsEntry("attempts",1);
    recover(publisher); archive("notification-transaction-lock");
  }
  @Test void realAcknowledgementLockFailurePreservesCommittedNotificationAndRecovers()throws Exception{
    var publisher=new EventPublisher(db,tx,consumer,false){
      boolean once=true;
      @Override public int acknowledge(Lease lease){
        if(!once)return super.acknowledge(lease);once=false;
        try(var lock=lock("SELECT id FROM outbox WHERE id=? FOR UPDATE")){
          try{return super.acknowledge(lease);}finally{lock.rollback();}
        }catch(java.sql.SQLException e){throw new IllegalStateException(e);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
      }
    };
    assertThat(publisher.processOne()).isTrue();
    assertThat(fields("event_operation_failed")).containsEntry("operationPhase","OUTBOX_ACKNOWLEDGEMENT").containsEntry("notificationCommitObservation","COMMITTED_BY_PROXY_RETURN");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?",Integer.class,eventId)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT status FROM outbox WHERE id=?",String.class,eventId)).isEqualTo("PENDING");
    recover(publisher);archive("acknowledgement-lock");
  }
  @Test void metricIntervalsSeparateInitialCumulativeCountAndSubsequentWindow()throws Exception{
    var meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    try{
      var timer=meters.timer("tracepilot.http","route","/test","method","GET","status","200");
      timer.record(10,java.util.concurrent.TimeUnit.MILLISECONDS);
      var sampler=new MetricSampler(ds,auxiliary,meters,json,"target/stable-evidence-test","test","stable-evidence-v1",true);
      sampler.sample();var first=sampler.latest();var h1=(Map<?,?>)first.get("http");
      assertThat(h1.get("intervalStart")).isNull();assertThat(h1.get("intervalCoverage")).isEqualTo("FIRST_SAMPLE_START_UNKNOWN");
      timer.record(20,java.util.concurrent.TimeUnit.MILLISECONDS);sampler.sample();
      var second=sampler.latest();var h2=(Map<?,?>)second.get("http");
      assertThat(h2.get("count")).isEqualTo(2L);assertThat(h2.get("intervalCount")).isEqualTo(1L);
      assertThat(h2.get("intervalStart")).isEqualTo(h1.get("intervalEnd"));assertThat(h2.get("intervalCoverage")).isEqualTo("BETWEEN_SAMPLES");
      assertThat(h2.get("averageMs")).isEqualTo(20.0);
      assertThat(java.time.Instant.parse(((Map<?,?>)second.get("pool")).get("sampledAt").toString())).isBetween(java.time.Instant.parse(second.get("time").toString()),java.time.Instant.parse(second.get("collectedAt").toString()));
      sampler.sample();var empty=(Map<?,?>)sampler.latest().get("http");
      assertThat(empty.get("intervalCount")).isEqualTo(0L);assertThat(empty.get("averageMs")).isNull();assertThat(empty.get("status")).isEqualTo("NO_REQUESTS");
    }finally{meters.close();}
  }
  void archive(String name)throws Exception{
    var encoder=new SafeJsonEncoder();encoder.setContext(logger.getLoggerContext());
    var text=new StringBuilder();for(var e:logs.list)text.append(new String(encoder.encode(e),java.nio.charset.StandardCharsets.UTF_8));
    var dir=java.nio.file.Path.of("target/stable-evidence-test");java.nio.file.Files.createDirectories(dir);
    java.nio.file.Files.writeString(dir.resolve(name+".jsonl"),text);
  }
}
