package br.furb.ticketpro.auth.modules.session;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /auth/login (public route): {email, password} → 200 {accessToken, refreshToken, expiresIn}.
 */
@RestController
public class LoginController {

  private final LoginService login;

  public LoginController(LoginService login) {
    this.login = login;
  }

  public record LoginRequest(String email, String password) {}

  @PostMapping("/auth/login")
  public LoginResult login(@RequestBody LoginRequest body) {
    // Malformed input gets the same 401 as wrong credentials: no hints for enumeration.
    if (body.email() == null || body.password() == null) {
      throw new InvalidCredentialsException();
    }
    return login.login(body.email().trim(), body.password());
  }
}
