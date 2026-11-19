package br.furb.ticketpro.inventory.health;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Probes of the F0 contract (RFC 0003 §5.9). Placeholder: readiness has no db/broker checks until
 * the platform layer exists.
 */
@RestController
@RequestMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
public class HealthController {

  @GetMapping("/live")
  public Map<String, Object> live() {
    return Map.of("status", "up");
  }

  @GetMapping("/ready")
  public Map<String, Object> ready() {
    return Map.of("status", "up", "checks", Map.of());
  }
}
