package com.tracepilot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.tracepilot.reporting.*;
import com.tracepilot.observability.SafeJsonEncoder;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class SqlOperationEvidenceTest {
  @Test void acquireFailureIsNotMeasuredAsExecutedSql() {
    JdbcTemplate db=mock(JdbcTemplate.class);
    when(db.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Map<String,Object>>>any()))
      .thenThrow(new CannotGetJdbcConnectionException("secret=do-not-log"));
    verifyLog(db,false);
  }
  @Test void obtainedConnectionHasSeparateBoundedTimingAndFingerprint() throws Exception {
    JdbcTemplate db=mock(JdbcTemplate.class); Connection c=mock(Connection.class);
    PreparedStatement stmt=mock(PreparedStatement.class);ResultSet rs=mock(ResultSet.class);
    when(c.prepareStatement(anyString())).thenReturn(stmt);when(stmt.executeQuery()).thenReturn(rs);
    when(rs.getLong(anyInt())).thenReturn(1L);
    when(db.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Map<String,Object>>>any()))
      .thenAnswer(i->i.<ConnectionCallback<Map<String,Object>>>getArgument(0).doInConnection(c));
    verifyLog(db,true);
    verify(stmt).setQueryTimeout(4);
  }
  void verifyLog(JdbcTemplate db, boolean acquired) {
    Logger logger=(Logger)org.slf4j.LoggerFactory.getLogger("tracepilot.evidence");
    var logs=new ListAppender<ILoggingEvent>();logs.setContext(logger.getLoggerContext());logs.start();logger.addAppender(logs);
    var meters=new SimpleMeterRegistry();
    try {
      var report=new UsageReport(db,new ReportQueryPolicy(),meters);
      if(acquired)report.report();else assertThatThrownBy(report::report).isInstanceOf(CannotGetJdbcConnectionException.class);
      var event=logs.list.stream().filter(e->e.getMessage().equals("sql_completed")).findFirst().orElseThrow();
      var fields=new HashMap<String,Object>();event.getKeyValuePairs().forEach(k->fields.put(k.key,k.value));
      assertThat(fields).containsEntry("connectionAcquired",acquired);
      assertThat(fields.get("statementFingerprint").toString()).matches("[a-f0-9]{64}");
      if(acquired) {
        double total=(Double)fields.get("durationMs"), a=(Double)fields.get("connectionAcquireMs"), j=(Double)fields.get("jdbcOperationMs");
        assertThat(a).isGreaterThanOrEqualTo(0);assertThat(j).isGreaterThanOrEqualTo(0);
        assertThat(a+j).isCloseTo(total,within(0.001));
      } else {
        assertThat(fields.get("connectionAcquireMs")).isNull();assertThat(fields.get("jdbcOperationMs")).isNull();
        var encoder=new SafeJsonEncoder();encoder.setContext(logger.getLoggerContext());
        String raw=new String(encoder.encode(event),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(raw).contains("\"jdbcOperationMs\":null").doesNotContain("do-not-log");
      }
    } finally {logger.detachAppender(logs);logs.stop();meters.close();}
  }
}
