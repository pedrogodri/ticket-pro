package br.furb.ticketpro.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Boots the real application with an unreachable database: it starts anyway (live = 200) and
 * readiness reports the database down (RFC 0003 §5.9).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "SERVICE_NAME=auth-service",
      "PORT=8080",
      "LOG_LEVEL=info",
      "DATABASE_URL=postgres://auth_app:x@127.0.0.1:1/auth",
      "DATABASE_WORKER_URL=postgres://auth_worker:x@127.0.0.1:1/auth",
      "AUTH_TOPIC_ARN=arn:aws:sns:us-east-1:000000000000:ticketpro-auth",
      "AWS_REGION=us-east-1",
      "AWS_ENDPOINT_URL=http://127.0.0.1:1"
    })
class AuthApplicationTest {

  @LocalServerPort private int port;

  @Test
  void startsWithDatabaseDownAndReportsIt() {
    RestClient client = RestClient.create("http://127.0.0.1:" + port);
    assertThat(
            client.get().uri("/health/live").retrieve().toBodilessEntity().getStatusCode().value())
        .isEqualTo(200);
    try {
      client.get().uri("/health/ready").retrieve().toBodilessEntity();
    } catch (RestClientResponseException e) {
      assertThat(e.getStatusCode().value()).isEqualTo(503);
      assertThat(e.getResponseBodyAsString()).contains("\"db\":\"down\"");
      return;
    }
    throw new AssertionError("readiness must be 503 with the database down");
  }
}
