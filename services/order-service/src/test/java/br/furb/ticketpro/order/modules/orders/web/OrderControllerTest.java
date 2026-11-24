package br.furb.ticketpro.order.modules.orders.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.furb.ticketpro.order.modules.orders.application.CreateOrder;
import br.furb.ticketpro.order.modules.orders.application.OrderQueries;
import br.furb.ticketpro.order.modules.orders.domain.CancelReason;
import br.furb.ticketpro.order.modules.orders.domain.OrderStatus;
import br.furb.ticketpro.order.modules.orders.domain.OrderView;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Position;
import br.furb.ticketpro.order.platform.config.AppConfig;
import br.furb.ticketpro.order.platform.web.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of the order API (RFC 0005 §5.2) with the use cases mocked. */
@WebMvcTest(OrderController.class)
@Import({ApiExceptionHandler.class, OrderControllerTest.Config.class})
class OrderControllerTest {

  private static final String USER = "0193a6f0-1111-7000-8000-000000000001";
  private static final String KEY = "0193a6f0-9999-7000-8000-000000000009";
  private static final String BODY =
      "{\"eventId\":\"0193a6f0-3333-7000-8000-000000000003\","
          + "\"sectorId\":\"0193a6f0-4444-7000-8000-000000000004\",\"quantity\":2}";

  @TestConfiguration
  static class Config {
    @Bean
    AppConfig appConfig() {
      return AppConfig.from(
          Map.of(
                  "SERVICE_NAME", "order-service",
                  "PORT", "8080",
                  "LOG_LEVEL", "info",
                  "DATABASE_URL", "postgres://a:b@h:5432/orders",
                  "DATABASE_WORKER_URL", "postgres://a:b@h:5432/orders",
                  "ORDERS_TOPIC_ARN", "arn:aws:sns:us-east-1:000000000000:ticketpro-orders",
                  "QUEUE_URL", "http://ministack:4566/000000000000/q",
                  "CONTRACTS_DIR", "contracts",
                  "AWS_REGION", "us-east-1")
              ::get);
    }
  }

  @Autowired private MockMvc mvc;
  @MockitoBean private CreateOrder createOrder;
  @MockitoBean private OrderQueries queries;

  private static OrderView order(OrderStatus status) {
    return new OrderView(
        UUID.fromString("0193a6f0-2222-7000-8000-000000000002"),
        UUID.fromString(USER),
        UUID.fromString("0193a6f0-3333-7000-8000-000000000003"),
        UUID.fromString("0193a6f0-4444-7000-8000-000000000004"),
        2,
        status,
        status == OrderStatus.CANCELLED ? CancelReason.PAYMENT_FAILED : null,
        status == OrderStatus.PENDING ? null : 50000L,
        status == OrderStatus.PENDING ? null : "BRL",
        Instant.parse("2026-11-20T18:30:12.345Z"));
  }

  @Test
  void createReturns202WithLocation() throws Exception {
    when(createOrder.execute(any(), anyString()))
        .thenReturn(new CreateOrder.Result(order(OrderStatus.PENDING), true));
    mvc.perform(
            post("/orders")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/orders/0193a6f0-2222-7000-8000-000000000002"))
        .andExpect(jsonPath("$.orderId").value("0193a6f0-2222-7000-8000-000000000002"))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void repeatedKeyReturns200WithTheOrder() throws Exception {
    when(createOrder.execute(any(), anyString()))
        .thenReturn(new CreateOrder.Result(order(OrderStatus.CONFIRMED), false));
    mvc.perform(
            post("/orders")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value("0193a6f0-2222-7000-8000-000000000002"))
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.totalCents").value(50000));
  }

  @Test
  void correlationIdFromGatewayIsKept() throws Exception {
    when(createOrder.execute(any(), anyString()))
        .thenReturn(new CreateOrder.Result(order(OrderStatus.PENDING), true));
    String correlation = "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c";
    mvc.perform(
            post("/orders")
                .header("X-User-Id", USER)
                .header("X-Correlation-Id", correlation)
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted());
    verify(createOrder).execute(any(), eq(correlation));
  }

  @Test
  void missingIdentityIs401() throws Exception {
    mvc.perform(
            post("/orders")
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get("/orders/0193a6f0-2222-7000-8000-000000000002").header("X-User-Id", "x"))
        .andExpect(status().isUnauthorized());
    verify(createOrder, never()).execute(any(), anyString());
  }

  @Test
  void invalidInputIs400ProblemJson() throws Exception {
    for (String body :
        List.of(
            BODY.replace("2}", "7}"),
            BODY.replace("}", ",\"userId\":\"" + USER + "\"}"),
            "{not json")) {
      mvc.perform(
              post("/orders")
                  .header("X-User-Id", USER)
                  .header("Idempotency-Key", KEY)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(header().string("Content-Type", "application/problem+json"))
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.title").value("Requisição inválida"));
    }
    mvc.perform(
            post("/orders")
                .header("X-User-Id", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isBadRequest());
    verify(createOrder, never()).execute(any(), anyString());
  }

  @Test
  void getReturnsOrderOr404() throws Exception {
    UUID id = UUID.fromString("0193a6f0-2222-7000-8000-000000000002");
    when(queries.get(UUID.fromString(USER), id))
        .thenReturn(Optional.of(order(OrderStatus.CANCELLED)));
    mvc.perform(get("/orders/" + id).header("X-User-Id", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelReason").value("PAYMENT_FAILED"))
        .andExpect(jsonPath("$.createdAt").value("2026-11-20T18:30:12.345Z"))
        .andExpect(jsonPath("$.userId").doesNotExist());

    String missing = UUID.randomUUID().toString();
    String correlation = UUID.randomUUID().toString();
    // padroes §4.1: the problem+json shape, with code and correlationId at the top level.
    mvc.perform(
            get("/orders/" + missing)
                .header("X-User-Id", USER)
                .header("X-Correlation-Id", correlation))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.type").value("https://ticketpro.local/errors/not_found"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.instance").value("/orders/" + missing))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.correlationId").value(correlation))
        .andExpect(jsonPath("$.properties").doesNotExist());
    mvc.perform(get("/orders/not-a-uuid").header("X-User-Id", USER))
        .andExpect(status().isNotFound());
  }

  @Test
  void listPaginatesByCursor() throws Exception {
    Position next = new Position(Instant.parse("2026-11-20T18:30:12.345Z"), UUID.randomUUID());
    when(queries.list(eq(UUID.fromString(USER)), isNull(), eq(20)))
        .thenReturn(new OrderQueries.Page(List.of(order(OrderStatus.PENDING)), next));
    mvc.perform(get("/orders").header("X-User-Id", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].orderId").value("0193a6f0-2222-7000-8000-000000000002"))
        .andExpect(jsonPath("$.nextCursor").value(CursorCodec.encode(next)));

    when(queries.list(eq(UUID.fromString(USER)), eq(next), anyInt()))
        .thenReturn(new OrderQueries.Page(List.of(), null));
    mvc.perform(
            get("/orders")
                .param("cursor", CursorCodec.encode(next))
                .param("limit", "50")
                .header("X-User-Id", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").isEmpty());

    for (String limit : List.of("0", "51", "x")) {
      mvc.perform(get("/orders").param("limit", limit).header("X-User-Id", USER))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(get("/orders").param("cursor", "%%%").header("X-User-Id", USER))
        .andExpect(status().isBadRequest());
  }
}
