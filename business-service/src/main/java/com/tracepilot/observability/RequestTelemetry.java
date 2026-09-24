package com.tracepilot.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTelemetry extends OncePerRequestFilter {
  private final MeterRegistry meters;

  public RequestTelemetry(MeterRegistry meters) {
    this.meters = meters;
  }

  public static boolean control(String path) {
    return path.startsWith("/api/drills")
        || path.startsWith("/ops/")
        || path.startsWith("/actuator/");
  }

  public static String route(String path) {
    String template = path.replaceAll("/\\d+(?=/|$)", "/{id}");
    return Set.of(
                "/api/auth/login",
                "/api/resources",
                "/api/slots",
                "/api/reservations",
                "/api/waitlists",
                "/api/reservations/{id}",
                "/api/waitlists/{id}",
                "/api/reservations/{id}/cancel",
                "/api/waitlists/{id}/leave",
                "/api/me/participations",
                "/api/me/notifications",
                "/api/admin/resources",
                "/api/admin/resources/{id}",
                "/api/admin/slots",
                "/api/admin/slots/{id}",
                "/api/admin/stats",
                "/api/admin/events/failed",
                "/api/admin/events/{id}/retry",
                "/api/reports/usage")
            .contains(template)
        ? template
        : "UNMAPPED";
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String rid = UUID.randomUUID().toString(),
        trace = UUID.randomUUID().toString().replace("-", "");
    String parent = req.getHeader("traceparent");
    if (parent != null
        && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
        && !parent.substring(3, 35).equals("0".repeat(32))
        && !parent.substring(36, 52).equals("0".repeat(16))) trace = parent.substring(3, 35);
    req.setAttribute("requestId", rid);
    req.setAttribute("traceId", trace);
    res.setHeader("X-Request-Id", rid);
    res.setHeader("X-Trace-Id", trace);
    long start = System.nanoTime();
    boolean visible = !control(req.getRequestURI());
    String route = route(req.getRequestURI());
    try (var r = MDC.putCloseable("requestId", rid);
        var t = MDC.putCloseable("traceId", trace)) {
      if (visible) Evidence.emit("request_started", "method", req.getMethod(), "route", route);
      try {
        chain.doFilter(req, res);
      } catch (Exception e) {
        if (visible) Evidence.error("request_failed", e);
        if (!res.isCommitted()) res.setStatus(500);
        throw e;
      } finally {
        if (visible) {
          long nanos = System.nanoTime() - start;
          meters
              .timer(
                  "tracepilot.http",
                  "method",
                  req.getMethod(),
                  "route",
                  route,
                  "status",
                  String.valueOf(res.getStatus()))
              .record(nanos, TimeUnit.NANOSECONDS);
          Evidence.emit(
              "request_completed",
              "method",
              req.getMethod(),
              "route",
              route,
              "status",
              res.getStatus(),
              "durationMs",
              nanos / 1_000_000.0);
        }
      }
    }
  }
}
