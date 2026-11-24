package br.furb.ticketpro.order.modules.orders.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Position;
import br.furb.ticketpro.order.platform.web.ValidationException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorCodecTest {

  @Test
  void roundTripKeepsMicroseconds() {
    Position p = new Position(Instant.parse("2026-11-20T18:30:12.345678Z"), UUID.randomUUID());
    String cursor = CursorCodec.encode(p);
    assertThat(cursor).doesNotContain("|", "=", "+", "/");
    assertThat(CursorCodec.decode(cursor)).isEqualTo(p);
  }

  @Test
  void emptyMeansFirstPage() {
    assertThat(CursorCodec.decode(null)).isNull();
    assertThat(CursorCodec.decode(" ")).isNull();
    assertThat(CursorCodec.encode(null)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"%%%", "bm9wZQ", "MjAyNnx4"})
  void malformedIsValidationError(String cursor) {
    assertThatThrownBy(() -> CursorCodec.decode(cursor)).isInstanceOf(ValidationException.class);
  }

  @Test
  void badUuidIsValidationError() {
    String cursor = Base64.getUrlEncoder().encodeToString("2026-11-20T18:30:12Z|nope".getBytes());
    assertThatThrownBy(() -> CursorCodec.decode(cursor)).isInstanceOf(ValidationException.class);
  }
}
