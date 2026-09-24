package com.tracepilot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.tracepilot.events.*;
import com.tracepilot.observability.SafeJsonEncoder;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class EventOperationEvidenceTest {
  JdbcTemplate db; NotificationConsumer consumer; EventPublisher publisher;
  Logger logger; ListAppender<ILoggingEvent> logs; SimpleMeterRegistry meters;
  EventPublisher.Lease lease = new EventPublisher.Lease(42, "private-lease-token", 0);
  @BeforeEach void setup() {
    db=mock(JdbcTemplate.class); consumer=mock(NotificationConsumer.class);
    publisher=spy(new EventPublisher(db,mock(TransactionTemplate.class),consumer,false));
    doReturn(lease).when(publisher).claim(); doNothing().when(publisher).fail(any(),any());
    when(db.queryForMap(anyString(),eq(42L))).thenReturn(new HashMap<>(Map.of(
      "user_id",2,"status","PENDING","attempts",1,"next_at",java.sql.Timestamp.from(java.time.Instant.now()))));
    meters=new SimpleMeterRegistry(); publisher.telemetry(new DeliveryGate(),meters);
    logger=(Logger)org.slf4j.LoggerFactory.getLogger("tracepilot.evidence");
    logs=new ListAppender<>();logs.setContext(logger.getLoggerContext());logs.start();logger.addAppender(logs);
  }
  @AfterEach void stop() {logger.detachAppender(logs);logs.stop();meters.close();}
  Map<String,Object> fields(String action) {
    var event=logs.list.stream().filter(e->e.getMessage().equals(action)).findFirst().orElseThrow();
    var fields=new HashMap<String,Object>();event.getKeyValuePairs().forEach(k->fields.put(k.key,k.value));return fields;
  }
  @Test void writeFailureDoesNotClaimRollbackOrAcknowledgementFailure() {
    when(consumer.deliver(42)).thenThrow(new CannotAcquireLockException("password=do-not-log"));
    assertThat(publisher.processOne()).isTrue();
    assertThat(fields("event_operation_failed")).containsEntry("operationPhase","NOTIFICATION_TRANSACTION")
      .containsEntry("notificationCommitObservation","NOT_OBSERVED");
    verify(publisher,never()).acknowledge(any());
    assertThat(logs.list).noneMatch(e->e.getMessage().equals("notification_write_committed"));
    var encoder=new SafeJsonEncoder();encoder.setContext(logger.getLoggerContext());
    String encoded=logs.list.stream().map(e->new String(encoder.encode(e),java.nio.charset.StandardCharsets.UTF_8)).reduce("",String::concat);
    assertThat(encoded).contains("NOTIFICATION_TRANSACTION","NOT_OBSERVED").doesNotContain("do-not-log","private-lease-token");
  }
  @Test void acknowledgementFailureRetainsKnownCommittedObservation() {
    when(consumer.deliver(42)).thenReturn(77L);
    doThrow(new CannotAcquireLockException("ack failed")).when(publisher).acknowledge(lease);
    publisher.processOne();
    assertThat(fields("event_operation_failed")).containsEntry("operationPhase","OUTBOX_ACKNOWLEDGEMENT")
      .containsEntry("notificationCommitObservation","COMMITTED_BY_PROXY_RETURN");
    assertThat(fields("event_delivery_failed")).containsEntry("operationPhase","OUTBOX_ACKNOWLEDGEMENT");
    assertThat(meters.counter("tracepilot.events.success").count()).isZero();
  }
  @Test void staleLeaseIsUnconfirmedNotSuccessfulDelivery() {
    when(consumer.deliver(42)).thenReturn(77L);doReturn(0).when(publisher).acknowledge(lease);
    publisher.processOne();
    assertThat(fields("event_delivery_unconfirmed")).containsEntry("outcome","ACK_NOT_APPLIED");
    assertThat(logs.list).noneMatch(e->e.getMessage().equals("event_delivery_completed"));
    assertThat(meters.counter("tracepilot.events.success").count()).isZero();
    verify(publisher,never()).fail(any(),any());
  }
  @Test void acknowledgedDeliveryStillCompletes() {
    when(consumer.deliver(42)).thenReturn(77L);doReturn(1).when(publisher).acknowledge(lease);
    publisher.processOne();
    assertThat(fields("event_delivery_completed")).containsEntry("notificationId",77L);
    assertThat(meters.counter("tracepilot.events.success").count()).isEqualTo(1);
  }
}
