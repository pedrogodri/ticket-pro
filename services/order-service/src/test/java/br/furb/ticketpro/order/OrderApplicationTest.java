package br.furb.ticketpro.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Boots the real application with the database and the broker unreachable: it starts anyway (live =
 * 200), consumer, relay and scanner keep retrying, and readiness reports the database down (RFC
 * 0003 §5.9).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "SERVICE_NAME=order-service",
      "PORT=8080",
      "LOG_LEVEL=info",
      "DATABASE_URL=postgres://orders_app:x@127.0.0.1:1/orders",
      "DATABASE_WORKER_URL=postgres://orders_worker:x@127.0.0.1:1/orders",
      "ORDERS_TOPIC_ARN=arn:aws:sns:us-east-1:000000000000:ticketpro-orders",
      "QUEUE_URL=http://127.0.0.1:1/000000000000/ticketpro-order-service",
      "CONTRACTS_DIR=contracts",
      "SQS_WAIT_TIME_S=1",
      "SHUTDOWN_TIMEOUT_S=2",
      "AWS_REGION=us-east-1",
      "AWS_ENDPOINT_URL=http://127.0.0.1:1",
      "AWS_ACCESS_KEY_ID=test",
      "AWS_SECRET_ACCESS_KEY=test"
    })
class OrderApplicationTest {

  @LocalServerPort private int port;

  @Test
  void startsWithDependenciesDownAndReportsIt() {
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
