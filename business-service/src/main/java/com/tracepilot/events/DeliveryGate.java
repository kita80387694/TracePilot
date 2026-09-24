package com.tracepilot.events;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class DeliveryGate {
  private final AtomicLong resumeAt = new AtomicLong(0);

  public boolean open() {
    return System.currentTimeMillis() >= resumeAt.get();
  }

  public void pauseUntil(Instant deadline) {
    resumeAt.set(deadline.toEpochMilli());
  }

  public void resume() {
    resumeAt.set(0);
  }
}
