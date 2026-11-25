package br.furb.ticketpro.inventory.platform.messaging;

import java.time.Duration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

/** AWS client settings shared by every service (RFC 0003 §5.5). */
public final class AwsClients {

  private AwsClients() {}

  /**
   * No SDK retries (our backoff owns retrying) and a per-call timeout of HTTP_CLIENT_TIMEOUT_MS.
   */
  public static ClientOverrideConfiguration overrideConfiguration(Duration timeout) {
    return ClientOverrideConfiguration.builder()
        .retryStrategy(b -> b.maxAttempts(1))
        .apiCallTimeout(timeout)
        .apiCallAttemptTimeout(timeout)
        .build();
  }
}
