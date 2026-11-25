package br.furb.ticketpro.auth.modules.keys;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** The RSA key that signs access tokens (RFC 0002 §5.4), with its public half for the JWKS. */
public record SigningKey(String kid, RSAPrivateCrtKey privateKey, RSAPublicKey publicKey) {

  /**
   * Parses a PEM private key: PKCS#1 ("BEGIN RSA PRIVATE KEY", what Terraform's tls_private_key
   * writes) or PKCS#8 ("BEGIN PRIVATE KEY").
   */
  public static SigningKey fromPem(String kid, String pem) throws GeneralSecurityException {
    boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
    String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(base64);
    byte[] pkcs8 = pkcs1 ? wrapPkcs1(der) : der;
    KeyFactory factory = KeyFactory.getInstance("RSA");
    RSAPrivateCrtKey priv =
        (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    RSAPublicKey pub =
        (RSAPublicKey)
            factory.generatePublic(
                new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
    return new SigningKey(kid, priv, pub);
  }

  // PrivateKeyInfo ::= SEQUENCE { version 0, AlgorithmIdentifier rsaEncryption, OCTET STRING pkcs1
  // }
  private static byte[] wrapPkcs1(byte[] pkcs1) {
    byte[] algorithm = {
      0x30,
      0x0d,
      0x06,
      0x09,
      0x2a,
      (byte) 0x86,
      0x48,
      (byte) 0x86,
      (byte) 0xf7,
      0x0d,
      0x01,
      0x01,
      0x01,
      0x05,
      0x00
    };
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes(new byte[] {0x02, 0x01, 0x00});
    body.writeBytes(algorithm);
    body.write(0x04);
    body.writeBytes(derLength(pkcs1.length));
    body.writeBytes(pkcs1);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x30);
    out.writeBytes(derLength(body.size()));
    out.writeBytes(body.toByteArray());
    return out.toByteArray();
  }

  private static byte[] derLength(int length) {
    if (length < 0x80) {
      return new byte[] {(byte) length};
    }
    if (length < 0x100) {
      return new byte[] {(byte) 0x81, (byte) length};
    }
    return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
  }
}
