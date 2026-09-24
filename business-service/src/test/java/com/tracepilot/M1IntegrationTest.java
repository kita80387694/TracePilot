package com.tracepilot;

import static org.assertj.core.api.Assertions.*;

import com.tracepilot.api.*;
import com.tracepilot.api.ApiSupport.Result;
import com.tracepilot.booking.BookingService;
import com.tracepilot.catalog.CatalogService;
import com.tracepilot.events.*;
import com.tracepilot.identity.SecurityConfig;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.events-enabled=false", "app.demo-seed=false"})
class M1IntegrationTest {
  @Test void differentSlotsDoNotSerializeOnUnchangedResource() throws Exception {
    long first=slot(2),second=slot(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)){
      var holder=pool.submit(()->tx.executeWithoutResult(t->{booking.lockSlot(first);locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
      assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
      var independent=pool.submit(()->tx.executeWithoutResult(t->booking.lockSlot(second)));
      try {independent.get(2,TimeUnit.SECONDS);} finally {release.countDown();holder.get(5,TimeUnit.SECONDS);}
    }
  }
  @Test void resourceDisableStillWaitsForInFlightBooking() throws Exception {
    long s=slot(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)){
      var holder=pool.submit(()->tx.executeWithoutResult(t->{booking.lockSlot(s);locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
      assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
      var disable=pool.submit(()->{entered.countDown();tx.executeWithoutResult(t->catalog.enable(201,resource,false));});
      assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
      try {assertThatThrownBy(()->disable.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);} finally {release.countDown();holder.get(5,TimeUnit.SECONDS);}
      disable.get(5,TimeUnit.SECONDS);assertThat(join(1,s,false,"disabled-after-lock").status()).isEqualTo(409);
    }
  }
  @Test void eventLifecycleAndCallerScopedNotificationAreCorrelated() {
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("tracepilot.evidence");
    var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.setContext(logger.getLoggerContext());appender.start();logger.addAppender(appender);
    try {
      join(1,slot(2),false,"lifecycle");long eid=count("SELECT MAX(id) FROM outbox");
      assertThat(publisher.processOne()).isTrue();long nid=count("SELECT id FROM notification WHERE event_id=?",eid);
      var actions=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getMessage).toList();
      assertThat(actions).containsSubsequence("event_claimed","notification_write_started","notification_write_committed","event_acknowledged","event_delivery_completed");
      var attempts=new HashSet<String>();for(var e:appender.list)if(e.getKeyValuePairs()!=null)for(var kv:e.getKeyValuePairs()){if(kv.key.equals("attemptId"))attempts.add(kv.value.toString());assertThat(kv.key).isNotEqualTo("lease_token");}
      assertThat(attempts).hasSize(1);assertThat(count("SELECT COUNT(*) FROM outbox WHERE id=? AND status='DONE'",eid)).isEqualTo(1);
      var mine=request(HttpMethod.GET,"/api/me/notifications?eventId="+eid,token(1),"read",null);
      var other=request(HttpMethod.GET,"/api/me/notifications?eventId="+eid,token(2),"read",null);
      assertThat(mine.getBody().toString()).contains(Long.toString(nid));assertThat((List<?>)other.getBody().get("data")).isEmpty();
      consumer.deliver(eid);assertThat(count("SELECT COUNT(*) FROM notification WHERE event_id=?",eid)).isEqualTo(1);
    }finally{logger.detachAppender(appender);appender.stop();}
  }
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () ->
            System.getenv()
                .getOrDefault(
                    "TEST_DB_URL",
                    "jdbc:mysql://127.0.0.1:3307/tracepilot_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
    r.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("TEST_DB_USER", "tracepilot"));
    r.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "tracepilot-local"));
  }

  @Autowired JdbcTemplate db;
  @Autowired Idempotency idem;
  @Autowired BookingService booking;
  @Autowired CatalogService catalog;
  @Autowired TransactionTemplate tx;
  @Autowired EventPublisher publisher;
  @Autowired NotificationConsumer consumer;
  @Autowired PasswordEncoder passwords;
  @Autowired TestRestTemplate http;
  long resource;
  String passwordHash;

  @BeforeEach
  void setup() {
    String database = db.queryForObject("SELECT DATABASE()", String.class);
    assertThat(database).endsWith("_test"); // Refuse to clear a normal schema.
    for (String table :
        List.of(
            "notification",
            "outbox",
            "idempotency",
            "participation",
            "slot",
            "resource",
            "auth_token",
            "admin_audit",
            "app_user")) db.update("DELETE FROM " + table);
    passwordHash = passwords.encode("Demo-pass-123");
    for (int i = 1; i <= 202; i++)
      db.update(
          "INSERT INTO app_user(id,username,password_hash,role) VALUES(?,?,?,?)",
          i,
          "u" + i,
          passwordHash,
          i >= 201 ? "ADMIN" : "USER");
    db.update("INSERT INTO resource(owner_id,name,description) VALUES(201,'Room','Test')");
    resource = db.queryForObject("SELECT MAX(id) FROM resource", Long.class);
  }

  long slot(int capacity) {
    db.update(
        "INSERT INTO slot(resource_id,start_at,end_at,capacity)"
            + " VALUES(?,TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP),TIMESTAMPADD(DAY,2,CURRENT_TIMESTAMP),?)",
        resource,
        capacity);
    return db.queryForObject("SELECT MAX(id) FROM slot", Long.class);
  }

  Result join(long user, long slot, boolean wait, String key) {
    return idem.execute(
        user,
        wait ? "wait" : "reserve",
        key,
        Map.of("slotId", slot),
        () -> booking.join(user, slot, wait));
  }

  Result cancel(long user, long id, String key) {
    return idem.execute(
        user, "cancel/" + id, key, Map.of("id", id), () -> booking.cancel(user, id, false));
  }

  long id(Result r) {
    return ((Number) ((Map<?, ?>) r.body().get("data")).get("id")).longValue();
  }

  long count(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  void invariant(long slot) {
    var row = db.queryForMap("SELECT * FROM slot WHERE id=?", slot);
    long active =
        count("SELECT COUNT(*) FROM participation WHERE slot_id=? AND status='RESERVED'", slot);
    assertThat(active)
        .isEqualTo(((Number) row.get("reserved_count")).longValue())
        .isLessThanOrEqualTo(((Number) row.get("capacity")).longValue());
    assertThat(
            count(
                "SELECT COUNT(*) FROM (SELECT user_id,slot_id FROM participation WHERE status IN"
                    + " ('WAITING','RESERVED') AND slot_id=? GROUP BY user_id,slot_id HAVING"
                    + " COUNT(*)>1) x",
                slot))
        .isZero();
  }

  <T> List<T> concurrent(int n, IntFunction<T> action) throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var ready = new CountDownLatch(n);
      var start = new CountDownLatch(1);
      List<Future<T>> fs = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int k = i;
        fs.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return action.apply(k);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<T> result = new ArrayList<>();
      for (var f : fs) result.add(f.get(60, TimeUnit.SECONDS));
      return result;
    }
  }

  @Test
  void twoHundredUsersCompeteForFifty() throws Exception {
    long s = slot(50);
    var results = concurrent(200, i -> join(i + 1, s, false, "race"));
    assertThat(results.stream().filter(r -> r.status() == 200).count()).isEqualTo(50);
    assertThat(results.stream().filter(r -> r.status() == 409).count()).isEqualTo(150);
    assertThat(count("SELECT COUNT(*) FROM outbox")).isEqualTo(50);
    invariant(s);
    System.out.println("M1_CAPACITY: users=200 capacity=50 accepted=50 rejected=150");
  }

  @Test
  void twentyConcurrentIdenticalRequestsReplay() throws Exception {
    long s = slot(50);
    var results = concurrent(20, i -> join(1, s, false, "same"));
    long first = id(results.getFirst());
    for (var r : results) assertThat(id(r)).isEqualTo(first);
    assertThat(count("SELECT COUNT(*) FROM participation")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM outbox")).isEqualTo(1);
    invariant(s);
    long other = slot(1);
    assertThatThrownBy(() -> join(1, other, false, "same"))
        .isInstanceOf(ApiSupport.Problem.class)
        .hasMessage("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void differentKeysStillPreventDuplicateParticipation() throws Exception {
    long s = slot(50);
    var results = concurrent(20, i -> join(1, s, false, "key" + i));
    assertThat(results.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
    invariant(s);
  }

  @Test
  void businessRejectionIsReplayedAfterCapacityBecomesFree() {
    long s = slot(1);
    long first = id(join(1, s, false, "a"));
    Result rejected = join(2, s, false, "b");
    cancel(1, first, "cancel");
    assertThat(join(2, s, false, "b")).isEqualTo(rejected);
    assertThat(join(2, s, false, "new").status()).isEqualTo(200);
    invariant(s);
  }

  @Test
  void repeatedCancellationPromotesOnlyOnceInFifoOrder() throws Exception {
    long s = slot(1);
    long first = id(join(1, s, false, "a"));
    long w1 = id(join(2, s, true, "w"));
    long w2 = id(join(3, s, true, "w"));
    var results = concurrent(20, i -> cancel(1, first, "cancel" + i));
    assertThat(results).allMatch(r -> r.status() == 200);
    assertThat(booking.detail(w1).get("status")).isEqualTo("RESERVED");
    assertThat(booking.detail(w2).get("status")).isEqualTo("WAITING");
    assertThat(count("SELECT COUNT(*) FROM outbox WHERE type='PROMOTED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM outbox WHERE type='CANCELLED'")).isEqualTo(1);
    invariant(s);
  }

  @Test
  void cancellationAndNewReservationsCannotBypassWaitingUser() throws Exception {
    long s = slot(1);
    long first = id(join(1, s, false, "a"));
    long waiter = id(join(2, s, true, "w"));
    concurrent(50, i -> i == 0 ? cancel(1, first, "c") : join(i + 2, s, false, "new"));
    assertThat(booking.detail(waiter).get("status")).isEqualTo("RESERVED");
    invariant(s);
  }

  @Test
  void leaveAndPromotionRaceHasOneValidOutcome() throws Exception {
    long s = slot(1);
    long first = id(join(1, s, false, "a"));
    long waiter = id(join(2, s, true, "w"));
    long next = id(join(3, s, true, "w"));
    concurrent(
        2,
        i ->
            i == 0
                ? cancel(1, first, "c")
                : idem.execute(2, "leave", "l", Map.of(), () -> booking.cancel(2, waiter, true)));
    String state = (String) booking.detail(waiter).get("status");
    assertThat(state).isIn("LEFT", "RESERVED");
    assertThat(booking.detail(next).get("status"))
        .isEqualTo(state.equals("LEFT") ? "RESERVED" : "WAITING");
    invariant(s);
  }

  @Test
  void simultaneousWaitlistJoinsHaveUniqueSequenceAndFifoPromotion() throws Exception {
    long s = slot(1);
    long first = id(join(1, s, false, "a"));
    concurrent(30, i -> join(i + 2, s, true, "w"));
    long earliest = count("SELECT MIN(id) FROM participation WHERE status='WAITING'");
    cancel(1, first, "c");
    assertThat(booking.detail(earliest).get("status")).isEqualTo("RESERVED");
    assertThat(count("SELECT COUNT(*) FROM participation WHERE status='WAITING'")).isEqualTo(29);
    invariant(s);
  }

  @Test
  void rollbackIncludesParticipationCounterEventAndIdempotency() {
    long s = slot(1);
    assertThatThrownBy(
            () ->
                idem.execute(
                    1,
                    "reserve",
                    "fail",
                    Map.of(),
                    () -> {
                      booking.join(1, s, false);
                      throw new IllegalStateException("commit interrupted");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("SELECT COUNT(*) FROM participation")).isZero();
    assertThat(count("SELECT COUNT(*) FROM outbox")).isZero();
    assertThat(count("SELECT COUNT(*) FROM idempotency")).isZero();
    invariant(s);
  }

  @Test
  void cancellationRollbackDoesNotLoseSeatOrPromotion() {
    long s = slot(1);
    long first = id(join(1, s, false, "a"));
    long waiter = id(join(2, s, true, "w"));
    assertThatThrownBy(
            () ->
                idem.execute(
                    1,
                    "cancel",
                    "broken",
                    Map.of(),
                    () -> {
                      booking.cancel(1, first, false);
                      throw new IllegalStateException("failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(booking.detail(first).get("status")).isEqualTo("RESERVED");
    assertThat(booking.detail(waiter).get("status")).isEqualTo("WAITING");
    assertThat(count("SELECT COUNT(*) FROM outbox")).isEqualTo(1);
    invariant(s);
    assertThat(cancel(1, first, "retry").status()).isEqualTo(200);
    invariant(s);
  }

  @Test
  void mysqlEnforcesCheckForeignKeyAndActiveUniqueness() {
    long s = slot(1);
    join(1, s, false, "a");
    assertThatThrownBy(() -> db.update("UPDATE slot SET reserved_count=2 WHERE id=?", s))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> db.update("UPDATE slot SET capacity=0 WHERE id=?", s))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> db.update("UPDATE slot SET start_at=end_at WHERE id=?", s))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO participation(user_id,slot_id,status) VALUES(1,?,'WAITING')", s))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO participation(user_id,slot_id,status) VALUES(9999,?,'WAITING')",
                    s))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    invariant(s);
  }

  @Test
  void expiredClosedAndDisabledSlotsRejectNewWork() {
    long s = slot(1);
    db.update("UPDATE resource SET enabled=false WHERE id=?", resource);
    assertThat(join(1, s, false, "disabled").status()).isEqualTo(409);
    db.update("UPDATE resource SET enabled=true WHERE id=?", resource);
    db.update("UPDATE slot SET open=false WHERE id=?", s);
    assertThat(join(1, s, true, "closed").status()).isEqualTo(409);
    db.update(
        "UPDATE slot SET"
            + " open=true,start_at=TIMESTAMPADD(DAY,-2,CURRENT_TIMESTAMP),end_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP)"
            + " WHERE id=?",
        s);
    assertThat(join(1, s, false, "past").status()).isEqualTo(409);
  }

  @Test
  void noPromotionAfterStartAndNoCancellationOfStartedReservation() {
    long s = slot(1);
    long first = id(join(1, s, false, "a"));
    long w = id(join(2, s, true, "w"));
    db.update("UPDATE slot SET start_at=TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP) WHERE id=?", s);
    assertThat(cancel(1, first, "c").status()).isEqualTo(409);
    tx.executeWithoutResult(t -> booking.promote(s));
    assertThat(booking.detail(w).get("status")).isEqualTo("WAITING");
    invariant(s);
  }

  @Test
  void increasingCapacityAndReopeningPrioritizeQueue() {
    long s = slot(1);
    long first = id(join(1, s, false, "a"));
    long w = id(join(2, s, true, "w"));
    tx.executeWithoutResult(t -> catalog.enable(201, resource, false));
    cancel(1, first, "c");
    assertThat(booking.detail(w).get("status")).isEqualTo("WAITING");
    tx.executeWithoutResult(t -> catalog.enable(201, resource, true));
    assertThat(booking.detail(w).get("status")).isEqualTo("RESERVED");
    long w2 = id(join(3, s, true, "w"));
    tx.executeWithoutResult(t -> catalog.updateSlot(201, s, 2, true));
    assertThat(booking.detail(w2).get("status")).isEqualTo("RESERVED");
    invariant(s);
    assertThatThrownBy(() -> tx.execute(t -> catalog.updateSlot(201, s, 1, true)))
        .hasMessage("CAPACITY_BELOW_RESERVED");
  }

  @Test
  void committedEventSurvivesPublisherAndConsumerRestart() {
    long s = slot(1);
    join(1, s, false, "a");
    assertThat(count("SELECT COUNT(*) FROM notification")).isZero();
    var lease = publisher.claim();
    assertThat(lease).isNotNull(); // Process dies after claiming, before delivery.
    db.update("UPDATE outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
    var restarted = new EventPublisher(db, tx, new NotificationConsumer(db), false);
    assertThat(restarted.processOne()).isTrue();
    assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM outbox WHERE status='DONE'")).isEqualTo(1);
    invariant(s);
  }

  @Test
  void crashAfterDeliveryBeforeAckAndConcurrentDuplicateConsumption() throws Exception {
    long s = slot(1);
    join(1, s, false, "a");
    var old = publisher.claim();
    consumer.deliver(old.id());
    db.update("UPDATE outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
    var fresh = publisher.claim();
    publisher.acknowledge(old);
    assertThat(db.queryForObject("SELECT status FROM outbox WHERE id=?", String.class, old.id()))
        .isEqualTo("PROCESSING");
    concurrent(
        20,
        i -> {
          consumer.deliver(fresh.id());
          return true;
        });
    publisher.acknowledge(fresh);
    assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM participation")).isEqualTo(1);
    invariant(s);
  }

  @Test
  void competingPublishersClaimDistinctEvents() throws Exception {
    long s = slot(20);
    for (int i = 1; i <= 20; i++) join(i, s, false, "a");
    var plan=db.queryForMap("EXPLAIN SELECT id,attempts FROM outbox FORCE INDEX (status) WHERE status='PENDING' AND next_at<=CURRENT_TIMESTAMP(6) ORDER BY next_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
    System.out.println("CLAIM_QUERY_PLAN="+plan);assertThat(plan.get("key")).isEqualTo("status");assertThat(String.valueOf(plan.get("Extra"))).doesNotContain("filesort");
    var leases = concurrent(20, i -> publisher.claim());
    assertThat(leases).doesNotContainNull();
    assertThat(leases.stream().map(EventPublisher.Lease::id).distinct().count()).isEqualTo(20);
    for (var lease : leases) {
      consumer.deliver(lease.id());
      publisher.acknowledge(lease);
    }
    assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(20);
  }

  String token(int user) {
    String token = "test-token-" + user;
    db.update(
        "INSERT INTO auth_token(token_hash,user_id,expires_at)"
            + " VALUES(?,?,TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP)) ON DUPLICATE KEY UPDATE"
            + " expires_at=VALUES(expires_at)",
        SecurityConfig.hash(token),
        user);
    return token;
  }

  ResponseEntity<Map> request(
      HttpMethod method, String path, String token, String key, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.setBearerAuth(token);
    if (key != null) headers.set("Idempotency-Key", key);
    return http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
  }

  @Test
  void failuresBackoffBecomeFailedAndAdminCanRetry() {
    long s = slot(1);
    join(1, s, false, "a");
    long eid = count("SELECT MAX(id) FROM outbox");
    var offline =
        new EventPublisher(
            db,
            tx,
            new NotificationConsumer(db) {
              @Override
              public long deliver(long id) {
                throw new IllegalStateException("offline");
              }
            },
            false);
    for (int i = 0; i < 5; i++) {
      assertThat(offline.processOne()).isTrue();
      if (i < 4) {
        assertThat(publisher.claim()).isNull();
        db.update("UPDATE outbox SET next_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
      }
    }
    assertThat(count("SELECT attempts FROM outbox WHERE id=?", eid)).isEqualTo(5);
    assertThat(publisher.claim()).isNull();
    invariant(s);
    assertThat(
            request(HttpMethod.POST, "/api/admin/events/" + eid + "/retry", token(202), "bad", null)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            request(
                    HttpMethod.POST,
                    "/api/admin/events/" + eid + "/retry",
                    token(201),
                    "retry",
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    publisher.processOne();
    assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(1);
  }

  @Test
  void httpLoginRolesOwnershipValidationAndIdempotency() {
    assertThat(request(HttpMethod.GET, "/api/slots", null, null, null).getStatusCode().value())
        .isEqualTo(401);
    assertThat(
            request(
                    HttpMethod.POST,
                    "/api/auth/login",
                    null,
                    null,
                    Map.of("username", "u1", "password", "wrong"))
                .getStatusCode()
                .value())
        .isEqualTo(401);
    var login =
        request(
            HttpMethod.POST,
            "/api/auth/login",
            null,
            null,
            Map.of("username", "u1", "password", "Demo-pass-123"));
    assertThat(login.getStatusCode().value()).isEqualTo(200);
    String user = (String) login.getBody().get("token");
    assertThat(
            request(
                    HttpMethod.POST,
                    "/api/admin/resources",
                    user,
                    "r",
                    Map.of("name", "r", "description", ""))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    long s = slot(1);
    var first = request(HttpMethod.POST, "/api/reservations", user, "one", Map.of("slotId", s));
    assertThat(first.getStatusCode().value()).isEqualTo(200);
    var replay = request(HttpMethod.POST, "/api/reservations", user, "one", Map.of("slotId", s));
    assertThat(replay.getBody()).isEqualTo(first.getBody());
    assertThat(
            request(HttpMethod.POST, "/api/reservations", user, "one", Map.of("slotId", s + 1))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    long pid = ((Number) ((Map) first.getBody().get("data")).get("id")).longValue();
    assertThat(
            request(HttpMethod.GET, "/api/reservations/" + pid, token(2), null, null)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            request(HttpMethod.POST, "/api/reservations/" + pid + "/cancel", token(2), "bad", null)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            request(HttpMethod.POST, "/api/reservations", user, null, Map.of("slotId", s))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    var invalid =
        request(
            HttpMethod.POST,
            "/api/admin/slots",
            token(201),
            "badcap",
            Map.of(
                "resourceId",
                resource,
                "startAt",
                Instant.now().plusSeconds(3600).toString(),
                "endAt",
                Instant.now().plusSeconds(7200).toString(),
                "capacity",
                0));
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(invalid.getBody()).containsKeys("code", "message", "requestId");
    assertThat(
            request(HttpMethod.GET, "/api/slots?size=0", user, null, null).getStatusCode().value())
        .isEqualTo(400);
    db.update(
        "UPDATE auth_token SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE"
            + " token_hash=?",
        SecurityConfig.hash(user));
    assertThat(request(HttpMethod.GET, "/api/slots", user, null, null).getStatusCode().value())
        .isEqualTo(401);
  }

  @Test
  void httpAdminOwnershipTimesAndCancelBodyConflicts() {
    // No demo profile: even an administrator has no injection controller.
    assertThat(
            request(HttpMethod.GET, "/api/drills", token(201), null, null).getStatusCode().value())
        .isEqualTo(404);
    String admin = token(201), other = token(202), user = token(1);
    var body =
        Map.of(
            "resourceId",
            resource,
            "startAt",
            Instant.now().plusSeconds(3600).toString(),
            "endAt",
            Instant.now().plusSeconds(7200).toString(),
            "capacity",
            1);
    assertThat(
            request(HttpMethod.POST, "/api/admin/slots", other, "wrong-owner", body)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    var invalid =
        Map.of(
            "resourceId",
            resource,
            "startAt",
            "2030-01-02T00:00:00Z",
            "endAt",
            "2030-01-01T00:00:00Z",
            "capacity",
            1);
    assertThat(
            request(HttpMethod.POST, "/api/admin/slots", admin, "bad-time", invalid)
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            request(
                    HttpMethod.PATCH,
                    "/api/admin/resources/" + resource,
                    other,
                    "disable",
                    Map.of("enabled", false))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    long s = slot(1), p = id(join(1, s, false, "reserve"));
    assertThat(
            request(
                    HttpMethod.PATCH,
                    "/api/admin/slots/" + s,
                    admin,
                    "cap",
                    Map.of("capacity", 1, "open", false))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            request(HttpMethod.POST, "/api/reservations/" + p + "/cancel", user, "cancel", null)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            request(
                    HttpMethod.POST,
                    "/api/reservations/" + p + "/cancel",
                    user,
                    "cancel",
                    Map.of("extra", true))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            request(
                    HttpMethod.POST,
                    "/api/reservations/" + p + "/cancel",
                    user,
                    "new-key",
                    Map.of("extra", true))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    invariant(s);
  }
}

