package com.tracepilot.observability;

import java.sql.SQLException;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.*;

public final class Evidence {
  private Evidence() {}

  public static void emit(String action, Object... pairs) {
    log(false, action, pairs);
  }

  public static void error(String action, Throwable e) {
    String sqlState = "";
    int sqlCode = 0;
    Throwable cause = e;
    for (int i = 0; i < 8 && cause != null; i++, cause = cause.getCause())
      if (cause instanceof SQLException s) {sqlState = String.valueOf(s.getSQLState());sqlCode=s.getErrorCode();}
    // Method locations are useful evidence; exception messages may contain SQL parameters/secrets.
    String frames =
        Arrays.stream(e.getStackTrace())
            .filter(
                f ->
                    f.getClassName().startsWith("com.tracepilot")
                        && !f.getClassName().contains(".drills."))
            .limit(6)
            .map(Object::toString)
            .reduce((a, b) -> a + ";" + b)
            .orElse("");
    log(
        true,
        action,
        "errorType",
        e.getClass().getSimpleName(),
        "sqlState",
        sqlState,
        "sqlErrorCode",sqlCode,
        "frames",
        frames);
  }

  private static void log(boolean error, String action, Object... pairs) {
    var logger = LoggerFactory.getLogger("tracepilot.evidence");
    var builder = error ? logger.atError() : logger.atInfo();
    for (int i = 0; i + 1 < pairs.length; i += 2)
      builder.addKeyValue(String.valueOf(pairs[i]), pairs[i + 1]);
    builder.log(action);
  }

  public static void afterCommit(String action, Object... pairs) {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              emit(action, pairs);
            }
          });
    else emit(action, pairs);
  }
}
