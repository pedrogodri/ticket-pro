package br.furb.ticketpro.order.modules.orders.web;

import br.furb.ticketpro.order.modules.orders.application.CreateOrder;
import br.furb.ticketpro.order.modules.orders.application.OrderQueries;
import br.furb.ticketpro.order.platform.config.AppConfig;
import br.furb.ticketpro.order.platform.web.NotFoundException;
import br.furb.ticketpro.order.platform.web.UnauthenticatedException;
import br.furb.ticketpro.order.platform.web.ValidationException;
import java.net.URI;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Async order API (R3, RFC 0005 §5.2). Identity comes only from X-User-Id, injected by the gateway
 * (RFC 0002); this controller uses only the app pool (RLS).
 */
@RestController
@RequestMapping(path = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 50;

  private final CreateOrder createOrder;
  private final OrderQueries queries;
  private final int maxQuantity;

  public OrderController(CreateOrder createOrder, OrderQueries queries, AppConfig config) {
    this.createOrder = createOrder;
    this.queries = queries;
    this.maxQuantity = config.orders().maxQuantity();
  }

  /** 202 + Location for a new order; 200 with the existing order for a repeated key. */
  @PostMapping
  public ResponseEntity<Object> create(
      @RequestHeader(value = "X-User-Id", required = false) String userId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
      @RequestBody CreateOrderRequest body)
      throws SQLException {
    UUID user = identity(userId);
    CreateOrder.Result result =
        createOrder.execute(
            body.toCommand(user, idempotencyKey, maxQuantity), correlation(correlationId));
    if (!result.created()) {
      return ResponseEntity.ok(OrderResponse.from(result.order()));
    }
    String id = result.order().id().toString();
    Map<String, Object> accepted = new LinkedHashMap<>();
    accepted.put("orderId", id);
    accepted.put("status", result.order().status().name());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(URI.create("/orders/" + id))
        .body(accepted);
  }

  @GetMapping("/{id}")
  public OrderResponse get(
      @RequestHeader(value = "X-User-Id", required = false) String userId,
      @PathVariable("id") String id)
      throws SQLException {
    UUID user = identity(userId);
    UUID orderId;
    try {
      orderId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new NotFoundException("order not found");
    }
    return queries
        .get(user, orderId)
        .map(OrderResponse::from)
        .orElseThrow(() -> new NotFoundException("order not found"));
  }

  /** The user's orders, newest first, paginated by cursor (padroes §4). */
  @GetMapping
  public Map<String, Object> list(
      @RequestHeader(value = "X-User-Id", required = false) String userId,
      @RequestParam(value = "limit", required = false) String limit,
      @RequestParam(value = "cursor", required = false) String cursor)
      throws SQLException {
    UUID user = identity(userId);
    OrderQueries.Page page = queries.list(user, CursorCodec.decode(cursor), limit(limit));
    List<OrderResponse> items = page.items().stream().map(OrderResponse::from).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("nextCursor", CursorCodec.encode(page.next()));
    return body;
  }

  private static UUID identity(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new UnauthenticatedException("missing identity");
    }
    try {
      return UUID.fromString(userId.trim());
    } catch (IllegalArgumentException e) {
      throw new UnauthenticatedException("invalid identity");
    }
  }

  /** The gateway's X-Correlation-Id, or a new one for direct calls (outbox needs a UUID). */
  private static String correlation(String header) {
    if (header != null) {
      try {
        return UUID.fromString(header.trim()).toString();
      } catch (IllegalArgumentException ignored) {
        // not a UUID: start a new correlation
      }
    }
    return UUID.randomUUID().toString();
  }

  private static int limit(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_LIMIT;
    }
    try {
      int n = Integer.parseInt(raw.trim());
      if (n >= 1 && n <= MAX_LIMIT) {
        return n;
      }
    } catch (NumberFormatException ignored) {
      // falls through to the validation error
    }
    throw new ValidationException("limit must be between 1 and " + MAX_LIMIT);
  }
}
