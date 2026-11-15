package br.furb.ticketpro.auth.modules.keys;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** RFC 0002 §5.2 / §5.4: RS256 access tokens and the JWKS built from the Secrets Manager key. */
class KeysTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static SigningKey testKey() throws Exception {
    return SigningKey.fromPem(
        "test-kid", Files.readString(Path.of("src/test/resources/keys/test-rsa-pkcs1.pem")));
  }

  @Test
  void parsesTerraformPkcs1Pem() throws Exception {
    SigningKey key = testKey();
    assertThat(key.kid()).isEqualTo("test-kid");
    assertThat(key.publicKey().getModulus().bitLength()).isEqualTo(2048);
  }

  @Test
  void issuedTokenVerifiesAndCarriesTheClaims() throws Exception {
    SigningKey key = testKey();
    Clock clock = Clock.fixed(Instant.parse("2026-11-20T18:30:00Z"), ZoneOffset.UTC);
    JwtIssuer issuer = new JwtIssuer(() -> key, "ticketpro-auth", "ticketpro-api", 900, clock);

    IssuedToken token = issuer.issue("0193a6f0-1111-7000-8000-000000000001", "ana@exemplo.com");
    String[] parts = token.value().split("\\.");
    assertThat(parts).hasSize(3);

    JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
    assertThat(header.get("alg").asString()).isEqualTo("RS256");
    assertThat(header.get("kid").asString()).isEqualTo("test-kid");

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(key.publicKey());
    verifier.update((parts[0] + "." + parts[1]).getBytes());
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

    JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    assertThat(claims.get("iss").asString()).isEqualTo("ticketpro-auth");
    assertThat(claims.get("aud").asString()).isEqualTo("ticketpro-api");
    assertThat(claims.get("sub").asString()).isEqualTo("0193a6f0-1111-7000-8000-000000000001");
    assertThat(claims.get("email").asString()).isEqualTo("ana@exemplo.com");
    assertThat(claims.get("jti").asString()).isEqualTo(token.jti());
    assertThat(claims.get("iat").asLong()).isEqualTo(1_795_199_400L);
    assertThat(claims.get("exp").asLong()).isEqualTo(1_795_199_400L + 900);
    assertThat(token.expiresIn()).isEqualTo(900);
  }

  @Test
  void jwksPublishesBase64UrlWithoutPadding() throws Exception {
    SigningKey key = testKey();
    Map<String, Object> jwks = Jwks.of(key, null);
    @SuppressWarnings("unchecked")
    Map<String, String> jwk = ((java.util.List<Map<String, String>>) jwks.get("keys")).get(0);
    assertThat(jwk)
        .containsEntry("kty", "RSA")
        .containsEntry("kid", "test-kid")
        .containsEntry("use", "sig")
        .containsEntry("alg", "RS256");
    assertThat(jwk.get("n")).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    assertThat(jwk.get("e")).isEqualTo("AQAB");
    RSAPublicKey pk = key.publicKey();
    assertThat(new java.math.BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n"))))
        .isEqualTo(pk.getModulus());
  }
}
