package com.tracepilot.reporting;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class ReportQueryPolicy {
  private final AtomicLong alternateUntil = new AtomicLong();

  public boolean alternate() {
    return System.currentTimeMillis() < alternateUntil.get();
  }

  public void alternateUntil(Instant deadline) {
    alternateUntil.set(deadline.toEpochMilli());
  }

  public void restore() {
    alternateUntil.set(0);
  }
}
