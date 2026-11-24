package br.furb.ticketpro.auth.platform.web;

import br.furb.ticketpro.auth.modules.registration.EmailAlreadyRegisteredException;
import br.furb.ticketpro.auth.modules.session.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** application/problem+json errors (padroes §4.1); titles in PT-BR from messages.properties. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");
  private final MessageSource messages;

  public ApiExceptionHandler() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages");
    source.setDefaultEncoding("UTF-8");
    this.messages = source;
  }

  @ExceptionHandler(ValidationException.class)
  ResponseEntity<Map<String, Object>> validation(ValidationException e, HttpServletRequest req) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "validation", e.getMessage(), req);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(
      HttpMessageNotReadableException e, HttpServletRequest req) {
    // Malformed JSON or unknown fields (fail-on-unknown-properties): never echo the parser message.
    return problem(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "validation",
        "request body is invalid or has unknown fields",
        req);
  }

  @ExceptionHandler(EmailAlreadyRegisteredException.class)
  ResponseEntity<Map<String, Object>> conflict(
      EmailAlreadyRegisteredException e, HttpServletRequest req) {
    return problem(HttpStatus.CONFLICT, "CONFLICT", "conflict", "email already registered", req);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  ResponseEntity<Map<String, Object>> unauthenticated(
      InvalidCredentialsException e, HttpServletRequest req) {
    return problem(
        HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "unauthenticated", "invalid credentials", req);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> internal(Exception e, HttpServletRequest req) {
    // Spring's own errors (405 method, 404 no route, 415 media type) keep their status.
    if (e instanceof org.springframework.web.ErrorResponse framework) {
      HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
      String code = status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : status.name();
      return problem(
          status, code, status == HttpStatus.NOT_FOUND ? "not_found" : "validation", null, req);
    }
    log.atError().setCause(e).log("request_failed");
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal", null, req);
  }

  private ResponseEntity<Map<String, Object>> problem(
      HttpStatus status, String code, String key, String detail, HttpServletRequest req) {
    String correlationId = req.getHeader("X-Correlation-Id");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "https://ticketpro.local/errors/" + key);
    body.put("title", messages.getMessage("error." + key, null, Locale.of("pt", "BR")));
    body.put("status", status.value());
    if (detail != null) {
      body.put("detail", detail);
    }
    body.put("instance", req.getRequestURI());
    body.put("code", code);
    body.put("correlationId", correlationId == null ? UUID.randomUUID().toString() : correlationId);
    return ResponseEntity.status(status).contentType(PROBLEM).body(body);
  }
}
