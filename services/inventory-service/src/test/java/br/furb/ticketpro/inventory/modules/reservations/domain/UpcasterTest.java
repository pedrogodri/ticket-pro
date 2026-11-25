package br.furb.ticketpro.inventory.modules.reservations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.furb.ticketpro.inventory.platform.envelope.InvalidMessageException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class UpcasterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BASE =
      "\"orderId\":\"0193a6f0-2222-7000-8000-000000000002\","
          + "\"userId\":\"0193a6f0-1111-7000-8000-000000000001\","
          + "\"eventId\":\"0193a6f0-3333-7000-8000-000000000003\","
          + "\"sectorId\":\"0193a6f0-4444-7000-8000-000000000004\",\"quantity\":2";

  private static JsonNode json(String body) {
    return JSON.readTree("{" + body + "}");
  }

  @Test
  void v1GetsDefaultChannelApi() {
    OrderCreatedV2 o = Upcaster.orderCreated(1, json(BASE));
    assertThat(o.channel()).isEqualTo("API");
    assertThat(o.quantity()).isEqualTo(2);
    assertThat(o.orderId().toString()).isEqualTo("0193a6f0-2222-7000-8000-000000000002");
  }

  @Test
  void v1IgnoresAnyChannelInThePayload() {
    assertThat(Upcaster.orderCreated(1, json(BASE + ",\"channel\":\"WEB\"")).channel())
        .isEqualTo("API");
  }

  @Test
  void v2KeepsItsChannel() {
    assertThat(Upcaster.orderCreated(2, json(BASE + ",\"channel\":\"MOBILE\"")).channel())
        .isEqualTo("MOBILE");
  }

  @Test
  void v2WithoutChannelIsPoison() {
    assertThatThrownBy(() -> Upcaster.orderCreated(2, json(BASE)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void otherVersionsNeverReachTheHandler() {
    assertThatThrownBy(() -> Upcaster.orderCreated(3, json(BASE)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void malformedFieldsArePoison() {
    assertThatThrownBy(() -> Upcaster.orderCreated(1, json(BASE.replace("\"quantity\":2", "\"quantity\":\"x\""))))
        .isInstanceOf(InvalidMessageException.class);
    assertThatThrownBy(
            () -> Upcaster.orderCreated(1, json(BASE.replace("0193a6f0-2222", "nope-2222"))))
        .isInstanceOf(InvalidMessageException.class);
  }
}
