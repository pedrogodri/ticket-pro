package br.furb.ticketpro.auth.modules.keys;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issues RS256 access tokens (RFC 0002 §5.2) with the JDK only: header.claims.signature, base64url.
 */
public final class JwtIssuer {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final Supplier<SigningKey> key;
  private final String issuer;
  private final String audience;
  private final long ttlSeconds;
  private final Clock clock;

  public JwtIssuer(
      Supplier<SigningKey> key, String issuer, String audience, long ttlSeconds, Clock clock) {
    this.key = key;
    this.issuer = issuer;
    this.audience = audience;
    this.ttlSeconds = ttlSeconds;
    this.clock = clock;
  }

  public IssuedToken issue(String userId, String email) {
    SigningKey k = key.get();
    long now = clock.instant().getEpochSecond();
    String jti = UUID.randomUUID().toString();
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    header.put("kid", k.kid());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", issuer);
    claims.put("aud", audience);
    claims.put("sub", userId);
    claims.put("jti", jti);
    claims.put("iat", now);
    claims.put("exp", now + ttlSeconds);
    claims.put("email", email);
    String signingInput =
        B64.encodeToString(JSON.writeValueAsBytes(header))
            + "."
            + B64.encodeToString(JSON.writeValueAsBytes(claims));
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(k.privateKey());
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return new IssuedToken(
          signingInput + "." + B64.encodeToString(signer.sign()), jti, ttlSeconds);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot sign access token", e);
    }
  }
}
