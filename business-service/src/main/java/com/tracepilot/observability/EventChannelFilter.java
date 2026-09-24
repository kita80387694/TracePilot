package com.tracepilot.observability;
/** Dedicated bounded process-observation channel; no control/logger state is eligible. */
public final class EventChannelFilter extends ch.qos.logback.core.filter.Filter<ch.qos.logback.classic.spi.ILoggingEvent> {
 public ch.qos.logback.core.spi.FilterReply decide(ch.qos.logback.classic.spi.ILoggingEvent e){
  String m=e.getMessage();return m!=null&&(m.startsWith("event_")||m.startsWith("notification_")||m.equals("business_event_committed"))?ch.qos.logback.core.spi.FilterReply.NEUTRAL:ch.qos.logback.core.spi.FilterReply.DENY;
 }
}
