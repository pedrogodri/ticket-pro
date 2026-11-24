package br.furb.ticketpro.order.modules.orders.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.furb.ticketpro.order.modules.orders.application.CreateOrder;
import br.furb.ticketpro.order.platform.web.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreateOrderRequestTest {

  private static final UUID USER = UUID.randomUUID();
  private static final String KEY = UUID.randomUUID().toString();
  private static final String EVENT = UUID.randomUUID().toString();
  private static final String SECTOR = UUID.randomUUID().toString();

  @Test
  void validRequestBecomesCommand() {
    CreateOrder.Command cmd = new CreateOrderRequest(EVENT, SECTOR, 2).toCommand(USER, KEY, 6);
    assertThat(cmd)
        .isEqualTo(
            new CreateOrder.Command(
                USER, UUID.fromString(KEY), UUID.fromString(EVENT), UUID.fromString(SECTOR), 2));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 7, -1})
  void quantityOutsideLimitIsRejected(int quantity) {
    assertThatThrownBy(
            () -> new CreateOrderRequest(EVENT, SECTOR, quantity).toCommand(USER, KEY, 6))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("quantity");
  }

  @Test
  void quantityAboveConfiguredMaximumIsRejected() {
    assertThatThrownBy(() -> new CreateOrderRequest(EVENT, SECTOR, 5).toCommand(USER, KEY, 4))
        .hasMessageContaining("between 1 and 4");
  }

  @Test
  void missingOrInvalidFieldsAreRejected() {
    assertThatThrownBy(() -> new CreateOrderRequest(EVENT, SECTOR, null).toCommand(USER, KEY, 6))
        .hasMessageContaining("quantity");
    assertThatThrownBy(() -> new CreateOrderRequest(null, SECTOR, 1).toCommand(USER, KEY, 6))
        .hasMessageContaining("eventId");
    assertThatThrownBy(() -> new CreateOrderRequest(EVENT, "x", 1).toCommand(USER, KEY, 6))
        .hasMessageContaining("sectorId");
    assertThatThrownBy(() -> new CreateOrderRequest(EVENT, SECTOR, 1).toCommand(USER, null, 6))
        .hasMessageContaining("Idempotency-Key");
    assertThatThrownBy(() -> new CreateOrderRequest(EVENT, SECTOR, 1).toCommand(USER, "abc", 6))
        .hasMessageContaining("Idempotency-Key");
  }
}
