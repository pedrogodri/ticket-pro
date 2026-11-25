package br.furb.ticketpro.auth.modules.session;

import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** POST /auth/refresh (public route): {refreshToken} → 200 new pair, or the generic 401. */
@RestController
public class RefreshController {

  private final RefreshService refresh;

  public RefreshController(RefreshService refresh) {
    this.refresh = refresh;
  }

  public record RefreshRequest(String refreshToken) {}

  @PostMapping("/auth/refresh")
  public LoginResult refresh(
      @RequestBody RefreshRequest body,
      @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
    if (body.refreshToken() == null || body.refreshToken().isBlank()) {
      throw new InvalidCredentialsException();
    }
    String correlation = correlationId == null ? UUID.randomUUID().toString() : correlationId;
    return refresh.refresh(body.refreshToken().trim(), correlation);
  }
}
