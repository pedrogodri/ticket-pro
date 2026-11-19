package br.furb.ticketpro.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/** Boots the application and checks the F0 health contract. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryApplicationTest {

  @LocalServerPort private int port;

  @Test
  void liveAndReadyReturn200() {
    RestClient client = RestClient.create("http://127.0.0.1:" + port);
    assertThat(
            client.get().uri("/health/live").retrieve().toBodilessEntity().getStatusCode().value())
        .isEqualTo(200);
    assertThat(
            client.get().uri("/health/ready").retrieve().toBodilessEntity().getStatusCode().value())
        .isEqualTo(200);
  }
}
