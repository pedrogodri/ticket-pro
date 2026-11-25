package br.furb.ticketpro.auth.platform.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and readiness probes (RFC 0003 §5.9). Readiness is 503 only when the database is down; a
 * broker outage is "degraded" because the outbox keeps accepting work.
 */
@RestController
@RequestMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
public class HealthController {

  private final DatabaseProbe database;
  private final BrokerProbe broker;

  public HealthController(DatabaseProbe database, BrokerProbe broker) {
    this.database = database;
    this.broker = broker;
  }

  @GetMapping("/live")
  public Map<String, Object> live() {
    return Map.of("status", "up");
  }

  @GetMapping("/ready")
  public ResponseEntity<Map<String, Object>> ready() {
    boolean dbUp = database.isUp();
    Map<String, String> checks = new LinkedHashMap<>();
    checks.put("db", dbUp ? "up" : "down");
    checks.put("broker", broker.brokerUp() ? "up" : "degraded");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", dbUp ? "up" : "down");
    body.put("checks", checks);
    return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }
}
