package br.furb.ticketpro.auth.modules.registration;

import br.furb.ticketpro.auth.platform.web.Requests;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** POST /auth/register (public route): {email, password, name} → 201 {userId}. */
@RestController
public class RegistrationController {

  private final RegistrationService registration;

  public RegistrationController(RegistrationService registration) {
    this.registration = registration;
  }

  public record RegisterRequest(String email, String password, String name) {}

  @PostMapping("/auth/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> register(
      @RequestBody RegisterRequest body,
      @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
    String email = Requests.email(body.email());
    String password = Requests.password(body.password());
    String name = Requests.text(body.name(), "name", 1, 100);
    String correlation = correlationId == null ? UUID.randomUUID().toString() : correlationId;
    return Map.of("userId", registration.register(email, password, name, correlation));
  }
}
