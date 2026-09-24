package com.tracepilot.api;

import static com.tracepilot.api.ApiSupport.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracepilot.identity.SecurityConfig;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Idempotency {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final java.util.concurrent.Semaphore writers;

  public Idempotency(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json,
      @org.springframework.beans.factory.annotation.Value("${spring.datasource.hikari.maximum-pool-size:24}") int poolSize) {
    this.db = db;
    this.tx = tx;
    this.json = json;
    // Wait outside the DB transaction rather than consuming every connection on one hot row.
    // This is local admission only: DB constraints and row locks still guarantee correctness
    // across instances. Leave connections available to reads and outbox consumers.
    this.writers = new java.util.concurrent.Semaphore(Math.max(1,poolSize*2/3),true);
  }

  public Result execute(long user, String op, String key, Object body, Supplier<Result> action) {
    // Domain rejections must occur before mutation. Infrastructure failures escape and roll back
    // the business changes, outbox and this idempotency record together.
    require(key != null && key.matches("[A-Za-z0-9._:-]{1,100}"), 400, "INVALID_IDEMPOTENCY_KEY");
    String digest = SecurityConfig.hash(encode(body));
    boolean admitted=false;
    try {
      admitted=writers.tryAcquire(8,java.util.concurrent.TimeUnit.SECONDS);
      require(admitted,503,"WRITE_ADMISSION_TIMEOUT");
      return tx.execute(
        s -> {
          db.update(
              "INSERT INTO idempotency(user_id,operation,request_key,request_hash) VALUES(?,?,?,?)"
                  + " ON DUPLICATE KEY UPDATE request_key=request_key",
              user,
              op,
              key,
              digest);
          var row =
              db.queryForMap(
                  "SELECT * FROM idempotency WHERE user_id=? AND operation=? AND request_key=? FOR"
                      + " UPDATE",
                  user,
                  op,
                  key);
          require(digest.equals(row.get("request_hash")), 409, "IDEMPOTENCY_CONFLICT");
          if (row.get("http_status") != null)
            return new Result(
                ((Number) row.get("http_status")).intValue(),
                decode((String) row.get("response_json")));
          Result result;
          try {
            result = action.get();
          } catch (Problem p) {
            result = Result.error(p.status, p.code);
          }
          db.update(
              "UPDATE idempotency SET http_status=?,response_json=? WHERE user_id=? AND operation=?"
                  + " AND request_key=?",
              result.status(),
              encode(result.body()),
              user,
              op,
              key);
          return result;
        });
    } catch(InterruptedException interrupted) {
      Thread.currentThread().interrupt();throw new Problem(503,"WRITE_ADMISSION_INTERRUPTED");
    } finally {if(admitted)writers.release();}
  }

  public String encode(Object value) {
    try {
      return json.writer()
          .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> decode(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
