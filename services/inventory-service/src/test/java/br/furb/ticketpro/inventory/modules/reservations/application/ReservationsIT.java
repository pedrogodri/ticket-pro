package br.furb.ticketpro.inventory.modules.reservations.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.furb.ticketpro.inventory.modules.reservations.infrastructure.ReservationRepository;
import br.furb.ticketpro.inventory.platform.envelope.ContractValidator;
import br.furb.ticketpro.inventory.platform.envelope.Envelope;
import br.furb.ticketpro.inventory.platform.envelope.StaleEventException;
import br.furb.ticketpro.inventory.platform.messaging.ConsumeOutcome;
import br.furb.ticketpro.inventory.platform.messaging.MessageHandler;
import br.furb.ticketpro.inventory.support.InventoryDb;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reservation module against the real schema: R2 under concurrency (100 orders for a sector of
 * 10), business idempotency, upcasting, tickets and stock release (RFC 0006 §6).
 */
class ReservationsIT {

  private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:ticketpro-inventory";
  private static final String CORRELATION = "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c";
  private static final UUID USER = UUID.fromString("0193a6f0-1111-7000-8000-000000000001");
  private static final UUID EVENT = UUID.fromString("0193a6f0-3333-7000-8000-000000000003");
  private static final UUID PISTA = UUID.fromString("0193a6f0-4444-7000-8000-000000000004");
  private static final UUID F5 = UUID.fromString("0193a6f0-4444-7000-8000-0000000000f5");
  private static final ContractValidator VALIDATOR = new ContractValidator(Path.of("contracts"));

  private InventoryDb.Pools db;
  private Map<String, MessageHandler> handlers;

  @BeforeEach
  void setUp() throws Exception {
    db = InventoryDb.fresh();
    handlers =
        new InventoryHandlers(
                new ReservationRepository(), new InventoryEvents(TOPIC), Duration.ofSeconds(60))
            .all();
  }

  /** Runs a handler like SqsConsumer does: one worker TX, stale still commits. */
  private ConsumeOutcome consume(String type, int version, Map<String, Object> payload)
      throws Exception {
    Envelope envelope = Envelope.create(CORRELATION, type, version, payload);
    try (Connection c = db.worker().getConnection()) {
      c.setAutoCommit(false);
      try {
        handlers.get(type).handle(envelope, c);
        c.commit();
        return ConsumeOutcome.SUCCEEDED;
      } catch (StaleEventException e) {
        c.commit();
        return ConsumeOutcome.STALE;
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
  }

  private static Map<String, Object> created(
      UUID orderId, UUID sector, int quantity, Object channel) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("userId", USER.toString());
    p.put("eventId", EVENT.toString());
    p.put("sectorId", sector.toString());
    p.put("quantity", quantity);
    if (channel != null) {
      p.put("channel", channel);
    }
    return p;
  }

  private ConsumeOutcome reserve(UUID orderId, UUID sector, int quantity) throws Exception {
    return consume("OrderCreated", 2, created(orderId, sector, quantity, "API"));
  }

  private long count(String sql) throws SQLException {
    try (Connection c = db.owner().getConnection();
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String text(String sql) throws SQLException {
    try (Connection c = db.owner().getConnection();
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private long available(UUID sector) throws SQLException {
    return count("SELECT available FROM sectors WHERE id = '" + sector + "'");
  }

  @Test
  void r2HundredConcurrentOrdersForTenUnitsReserveExactlyTen() throws Exception {
    int orders = 100;
    ExecutorService pool = Executors.newFixedThreadPool(32);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ConsumeOutcome>> results = new ArrayList<>();
    for (int i = 0; i < orders; i++) {
      UUID orderId = UUID.randomUUID();
      results.add(
          pool.submit(() -> {
            start.await();
            return reserve(orderId, F5, 1);
          }));
    }
    start.countDown();
    for (Future<ConsumeOutcome> f : results) {
      assertThat(f.get()).isEqualTo(ConsumeOutcome.SUCCEEDED); // rejecting is a success
    }
    pool.shutdown();

    assertThat(count("SELECT count(*) FROM reservations WHERE sector_id = '" + F5 + "'"))
        .isEqualTo(10);
    assertThat(available(F5)).isZero();
    assertThat(count("SELECT count(*) FROM outbox WHERE type = 'InventoryReserved'")).isEqualTo(10);
    assertThat(
            count(
                "SELECT count(*) FROM outbox WHERE type = 'InventoryRejected'"
                    + " AND payload->>'reason' = 'OUT_OF_STOCK'"))
        .isEqualTo(90);
  }

  @Test
  void sameOrderTwiceIsStaleAndDecrementsOnce() throws Exception {
    UUID order = UUID.randomUUID();
    assertThat(reserve(order, PISTA, 2)).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(reserve(order, PISTA, 2)).isEqualTo(ConsumeOutcome.STALE);
    assertThat(count("SELECT count(*) FROM reservations WHERE order_id = '" + order + "'"))
        .isEqualTo(1);
    assertThat(available(PISTA)).isEqualTo(498);
    assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
  }

  @Test
  void v1IsUpcastToChannelApi() throws Exception {
    UUID order = UUID.randomUUID();
    assertThat(consume("OrderCreated", 1, created(order, PISTA, 1, null)))
        .isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(text("SELECT channel FROM reservations WHERE order_id = '" + order + "'"))
        .isEqualTo("API");
  }

  @Test
  void v2KeepsTheChannel() throws Exception {
    UUID order = UUID.randomUUID();
    consume("OrderCreated", 2, created(order, PISTA, 1, "MOBILE"));
    assertThat(text("SELECT channel FROM reservations WHERE order_id = '" + order + "'"))
        .isEqualTo("MOBILE");
  }

  @Test
  void invalidQuantityIsRejectedWithoutTouchingStock() throws Exception {
    UUID order = UUID.randomUUID();
    assertThat(reserve(order, PISTA, 0)).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(available(PISTA)).isEqualTo(500);
    assertThat(
            count(
                "SELECT count(*) FROM outbox WHERE type = 'InventoryRejected'"
                    + " AND payload->>'reason' = 'INVALID_QUANTITY'"))
        .isEqualTo(1);
  }

  @Test
  void unknownSectorIsRejected() throws Exception {
    assertThat(reserve(UUID.randomUUID(), UUID.randomUUID(), 1))
        .isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(
            count(
                "SELECT count(*) FROM outbox WHERE type = 'InventoryRejected'"
                    + " AND payload->>'reason' = 'SECTOR_NOT_FOUND'"))
        .isEqualTo(1);
  }

  @Test
  void producedEventsMatchTheContracts() throws Exception {
    UUID order = UUID.randomUUID();
    reserve(order, PISTA, 2);
    reserve(UUID.randomUUID(), F5, 11); // more than the sector holds
    consume("OrderConfirmed", 1, confirmed(order, 2));
    List<String> envelopes = new ArrayList<>();
    try (Connection c = db.owner().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT json_build_object('messageId', message_id, 'correlationId', correlation_id,"
                    + " 'type', type, 'version', version, 'occurredAt',"
                    + " to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'),"
                    + " 'payload', payload)::text FROM outbox ORDER BY id");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        envelopes.add(rs.getString(1));
      }
    }
    assertThat(envelopes).hasSize(3);
    for (String e : envelopes) {
      VALIDATOR.validate(e);
    }
  }

  private static Map<String, Object> confirmed(UUID orderId, int quantity) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("userId", USER.toString());
    p.put("eventId", EVENT.toString());
    p.put("sectorId", PISTA.toString());
    p.put("quantity", quantity);
    p.put("totalCents", 50000);
    p.put("currency", "BRL");
    p.put("confirmedAt", "2026-11-20T18:30:14.120Z");
    return p;
  }

  private static Map<String, Object> cancelled(UUID orderId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("userId", USER.toString());
    p.put("reason", "PAYMENT_FAILED");
    p.put("cancelledAt", "2026-11-20T18:30:15.000Z");
    return p;
  }

  @Test
  void confirmationIssuesOneTicketPerUnitOnce() throws Exception {
    UUID order = UUID.randomUUID();
    reserve(order, PISTA, 2);
    assertThat(consume("OrderConfirmed", 1, confirmed(order, 2)))
        .isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(text("SELECT status FROM reservations WHERE order_id = '" + order + "'"))
        .isEqualTo("CONFIRMED");
    assertThat(count("SELECT count(*) FROM tickets")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM outbox WHERE type = 'TicketsIssued'")).isEqualTo(1);

    assertThat(consume("OrderConfirmed", 1, confirmed(order, 2))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(count("SELECT count(*) FROM tickets")).isEqualTo(2);
    assertThat(available(PISTA)).isEqualTo(498);
  }

  @Test
  void cancellationReturnsTheStockOnce() throws Exception {
    UUID order = UUID.randomUUID();
    reserve(order, PISTA, 2);
    assertThat(available(PISTA)).isEqualTo(498);
    assertThat(consume("OrderCancelled", 1, cancelled(order))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(available(PISTA)).isEqualTo(500);
    assertThat(text("SELECT status FROM reservations WHERE order_id = '" + order + "'"))
        .isEqualTo("RELEASED");

    assertThat(consume("OrderCancelled", 1, cancelled(order))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(available(PISTA)).isEqualTo(500);
  }

  @Test
  void cancellingAConfirmedOrderChangesNothing() throws Exception {
    UUID order = UUID.randomUUID();
    reserve(order, PISTA, 2);
    consume("OrderConfirmed", 1, confirmed(order, 2));
    assertThat(consume("OrderCancelled", 1, cancelled(order))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(available(PISTA)).isEqualTo(498);
    assertThat(text("SELECT status FROM reservations WHERE order_id = '" + order + "'"))
        .isEqualTo("CONFIRMED");
  }

  @Test
  void cancellingAnUnknownOrderIsStale() throws Exception {
    assertThat(consume("OrderCancelled", 1, cancelled(UUID.randomUUID())))
        .isEqualTo(ConsumeOutcome.STALE);
  }
}
