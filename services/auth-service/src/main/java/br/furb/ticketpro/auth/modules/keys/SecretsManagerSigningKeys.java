package br.furb.ticketpro.auth.modules.keys;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {current:{kid,privatePem}, previous:{kid,publicPem}|null} from Secrets Manager (RFC 0002
 * §5.4). The constructor fails when the secret cannot be read, so the app does not start without a
 * key; a refresh every JWT_KEY_REFRESH_S keeps the last good key on failure.
 */
public final class SecretsManagerSigningKeys implements SigningKeys, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SecretsManagerSigningKeys.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private record Keys(SigningKey current, VerificationKey previous) {}

  private final SecretsManagerClient client;
  private final String secretId;
  private final Duration refresh;
  private final AtomicReference<Keys> keys = new AtomicReference<>();
  private volatile Thread refresher;

  public SecretsManagerSigningKeys(SecretsManagerClient client, String secretId, Duration refresh) {
    this.client = client;
    this.secretId = secretId;
    this.refresh = refresh;
    try {
      keys.set(load());
    } catch (RuntimeException e) {
      log.atError().addKeyValue("error", e.getMessage()).log("jwt_key_unavailable");
      throw new IllegalStateException("JWT signing key " + secretId + " is unavailable", e);
    }
  }

  @Override
  public SigningKey get() {
    return keys.get().current();
  }

  @Override
  public VerificationKey previous() {
    return keys.get().previous();
  }

  private Keys load() {
    try {
      JsonNode secret =
          JSON.readTree(client.getSecretValue(b -> b.secretId(secretId)).secretString());
      JsonNode current = secret.get("current");
      SigningKey signing =
          SigningKey.fromPem(current.get("kid").asString(), current.get("privatePem").asString());
      JsonNode prev = secret.get("previous");
      VerificationKey previous = null;
      if (prev != null && !prev.isNull()) {
        String der =
            prev.get("publicPem")
                .asString()
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        RSAPublicKey pk =
            (RSAPublicKey)
                KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(der)));
        previous = new VerificationKey(prev.get("kid").asString(), pk);
      }
      return new Keys(signing, previous);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("invalid key material in " + secretId, e);
    }
  }

  @Override
  public void start() {
    refresher =
        Thread.ofVirtual()
            .name("jwt-key-refresh")
            .start(() -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(refresh.toMillis());
                  keys.set(load());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                  log.atWarn().addKeyValue("error", e.getMessage()).log("jwt_key_refresh_failed");
                }
              }
            });
  }

  @Override
  public void stop() {
    Thread t = refresher;
    if (t != null) {
      t.interrupt();
    }
    refresher = null;
  }

  @Override
  public boolean isRunning() {
    return refresher != null;
  }
}
