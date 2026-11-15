package br.furb.ticketpro.auth.modules.keys;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /.well-known/jwks.json — internal: the gateway fetches it and never routes it (RFC 0002
 * §5.1).
 */
@RestController
public class JwksController {

  private final SigningKeys keys;

  public JwksController(SigningKeys keys) {
    this.keys = keys;
  }

  @GetMapping("/.well-known/jwks.json")
  public Map<String, Object> jwks() {
    return Jwks.of(keys.get(), keys.previous());
  }
}
