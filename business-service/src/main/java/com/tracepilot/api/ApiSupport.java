package com.tracepilot.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiSupport {
  public static class Problem extends RuntimeException {
    public final int status;
    public final String code;

    public Problem(int status, String code) {
      super(code);
      this.status = status;
      this.code = code;
    }
  }

  public record Result(int status, Map<String, Object> body) {
    public static Result ok(Object value) {
      return new Result(200, Map.of("data", value));
    }

    public static Result error(int status, String code) {
      return new Result(status, Map.of("code", code, "message", code));
    }
  }

  public static void require(boolean valid, int status, String code) {
    if (!valid) throw new Problem(status, code);
  }

  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem e, HttpServletRequest r) {
    return error(e.status, e.code, r);
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest r) {
    return error(400, "INVALID_REQUEST", r);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e, HttpServletRequest r) {
    if (!com.tracepilot.observability.RequestTelemetry.control(r.getRequestURI()))
      com.tracepilot.observability.Evidence.error("operation_failed", e);
    return error(500, "INTERNAL_ERROR", r);
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e, HttpServletRequest r) {
    return error(404, "NOT_FOUND", r);
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> unsupportedMethod(Exception e, HttpServletRequest r) {
    return error(405, "METHOD_NOT_ALLOWED", r);
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<?> unsupportedType(Exception e, HttpServletRequest r) {
    return error(415, "UNSUPPORTED_MEDIA_TYPE", r);
  }

  private ResponseEntity<?> error(int status, String code, HttpServletRequest r) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                code,
                "requestId",
                String.valueOf(r.getAttribute("requestId"))));
  }
}
