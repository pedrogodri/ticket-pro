package br.furb.ticketpro.auth.modules.keys;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Web Key Set with the current key and, after a rotation, the previous one (RFC 0002 §5.4).
 */
public final class Jwks {

  private Jwks() {}

  public static Map<String, Object> of(SigningKey current, VerificationKey previous) {
    List<Map<String, String>> keys = new ArrayList<>();
    keys.add(jwk(current.kid(), current.publicKey()));
    if (previous != null) {
      keys.add(jwk(previous.kid(), previous.publicKey()));
    }
    return Map.of("keys", keys);
  }

  private static Map<String, String> jwk(String kid, RSAPublicKey key) {
    Map<String, String> jwk = new LinkedHashMap<>();
    jwk.put("kty", "RSA");
    jwk.put("kid", kid);
    jwk.put("use", "sig");
    jwk.put("alg", "RS256");
    jwk.put("n", unsigned(key.getModulus()));
    jwk.put("e", unsigned(key.getPublicExponent()));
    return jwk;
  }

  // JWK integers are unsigned big-endian, base64url without padding (RFC 7518 §6.3.1).
  private static String unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
