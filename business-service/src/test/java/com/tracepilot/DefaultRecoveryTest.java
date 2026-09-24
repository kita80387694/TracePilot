package com.tracepilot;
import static org.assertj.core.api.Assertions.*;
import com.tracepilot.events.*;
import com.tracepilot.booking.BookingService;
import com.tracepilot.api.Idempotency;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties={"app.events-enabled=true","app.demo-seed=false","app.sample-enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DefaultRecoveryTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){M1IntegrationTest.database(r);}
  @Autowired JdbcTemplate db;
  @Autowired DeliveryGate gate;
  @Autowired BookingService booking;
  @Autowired Idempotency idem;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @Test void defaultSchedulerDrainsAfterLargeDoneHistoryWithoutOperatorConsumer() throws Exception {
    assertThat(db.queryForObject("SELECT DATABASE()",String.class)).endsWith("_test");
    gate.pauseUntil(Instant.now().plusSeconds(300));
    try {
      for(String t:List.of("notification","outbox","idempotency","participation","slot","resource","auth_token","admin_audit","app_user"))db.update("DELETE FROM "+t);
      db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(1,'recovery','unused','ADMIN')");
      db.update("INSERT INTO resource(id,owner_id,name,description) VALUES(1,1,'recovery fixture','isolated test')");
      db.update("INSERT INTO slot(id,resource_id,start_at,end_at,capacity) VALUES(1,1,NOW()+INTERVAL 1 DAY,NOW()+INTERVAL 2 DAY,1)");
      long p=reserve("history");
      // Historical fixtures are excluded from measured production/recovery time.
      String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
      db.update("INSERT INTO outbox(user_id,participation_id,type,payload,status) SELECT 1,?,'RESERVED',JSON_OBJECT('participationId',?),'DONE' FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d CROSS JOIN "+digits+" e LIMIT 50000",p,p);
      db.update("INSERT INTO notification(event_id,user_id,message) SELECT id,user_id,payload FROM outbox WHERE status='DONE'");
      cancel(p,"history");
      for(int i=0;i<500;i++){long id=reserve("new"+i);cancel(id,"new"+i);}
      var peak=db.queryForMap("SELECT COUNT(*) pending,MAX(TIMESTAMPDIFF(MICROSECOND,created_at,NOW(6)))/1000000.0 oldestSeconds FROM outbox WHERE status<>'DONE'");
      var plan=db.queryForList("EXPLAIN SELECT id,attempts FROM outbox WHERE status='PENDING' AND next_at<=CURRENT_TIMESTAMP(6) ORDER BY next_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
      long started=System.nanoTime();gate.resume();
      List<Object> samples=new ArrayList<>();long remaining;
      do {
        remaining=db.queryForObject("SELECT COUNT(*) FROM outbox WHERE status<>'DONE'",Long.class);
        samples.add(Map.of("seconds",(System.nanoTime()-started)/1e9,"pending",remaining));
        if(remaining==0)break;
        Thread.sleep(250);
      }while(System.nanoTime()-started<120_000_000_000L);
      var counts=db.queryForList("SELECT status,COUNT(*) amount FROM outbox GROUP BY status");
      var report=new LinkedHashMap<String,Object>();report.put("kind","REAL_MYSQL_DEFAULT_SCHEDULER_NO_ASSISTED_CONSUMER");
      report.put("historyDone",50000);report.put("peak",peak);report.put("samples",samples);report.put("plan",plan);report.put("final",counts);
      report.put("recoverySeconds",(System.nanoTime()-started)/1e9);report.put("remaining",remaining);
      Path target=Path.of("../evaluation/results/directed-runs/default-recovery-"+System.currentTimeMillis()+".json");Files.createDirectories(target.getParent());Files.writeString(target,json.writeValueAsString(report),java.nio.file.StandardOpenOption.CREATE_NEW);
      assertThat(remaining).isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox o LEFT JOIN notification n ON n.event_id=o.id WHERE n.id IS NULL",Long.class)).isZero();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM notification",Long.class)).isEqualTo(db.queryForObject("SELECT COUNT(*) FROM outbox",Long.class));
      assertThat(db.queryForObject("SELECT reserved_count FROM slot WHERE id=1",Integer.class)).isZero();
    } finally {gate.resume();}
  }
  long reserve(String key){var r=idem.execute(1,"reserve",key,Map.of("slotId",1),()->booking.join(1,1,false));return ((Number)((Map<?,?>)r.body().get("data")).get("id")).longValue();}
  void cancel(long id,String key){idem.execute(1,"cancel/"+id,key,Map.of("id",id),()->booking.cancel(1,id,false));}
}
