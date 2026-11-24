package br.furb.ticketpro.order.modules.orders;

import static org.assertj.core.api.Assertions.assertThat;

import br.furb.ticketpro.order.modules.orders.application.CreateOrder;
import br.furb.ticketpro.order.modules.orders.application.DeadlineScanner;
import br.furb.ticketpro.order.modules.orders.application.OrderEvents;
import br.furb.ticketpro.order.modules.orders.application.OrderQueries;
import br.furb.ticketpro.order.modules.orders.application.SagaHandlers;
import br.furb.ticketpro.order.modules.orders.domain.OrderStatus;
import br.furb.ticketpro.order.modules.orders.domain.OrderView;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository;
import br.furb.ticketpro.order.platform.db.UserScopedTransaction;
import br.furb.ticketpro.order.platform.envelope.ContractValidator;
import br.furb.ticketpro.order.platform.envelope.Envelope;
import br.furb.ticketpro.order.platform.envelope.EnvelopeCodec;
import br.furb.ticketpro.order.platform.envelope.StaleEventException;
import br.furb.ticketpro.order.platform.messaging.ConsumeOutcome;
import br.furb.ticketpro.order.platform.messaging.MessageHandler;
import br.furb.ticketpro.order.platform.messaging.SqsConsumer;
import br.furb.ticketpro.order.platform.messaging.SqsConsumerProperties;
import br.furb.ticketpro.order.platform.support.Containers;
import br.furb.ticketpro.order.support.OrdersDb;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * The orders module against the real schema (migrations, roles, RLS): API use cases, BOLA, every
 * saga guard, the deadline scanner, the late-approval refund and their races (RFC 0005 §6).
 */
class OrdersIT {

  private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:ticketpro-orders";
  private static final String CORRELATION = "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c";
  private static final UUID USER_A = UUID.fromString("0193a6f0-1111-7000-8000-00000000000a");
  private static final UUID USER_B = UUID.fromString("0193a6f0-1111-7000-8000-00000000000b");
  private static final UUID EVENT = UUID.fromString("0193a6f0-3333-7000-8000-000000000003");
  private static final UUID SECTOR = UUID.fromString("0193a6f0-4444-7000-8000-000000000004");
  private static final UUID PAYMENT = UUID.fromString("0193a6f0-6666-7000-8000-000000000006");
  private static final Duration PAYMENT_TIMEOUT = Duration.ofSeconds(15);
  private static final ContractValidator VALIDATOR = new ContractValidator(Path.of("contracts"));

  private OrdersDb.Pools db;
  private final OrderRepository repository = new OrderRepository();
  private final OrderEvents events = new OrderEvents(TOPIC, 2);
  private CreateOrder createOrder;
  private OrderQueries queries;
  private Map<String, MessageHandler> saga;
  private DeadlineScanner scanner;

  @BeforeEach
  void setUp() throws Exception {
    db = OrdersDb.fresh();
    createOrder = new CreateOrder(db.app(), repository, events);
    queries = new OrderQueries(db.app(), repository);
    saga = new SagaHandlers(repository, events, PAYMENT_TIMEOUT).all();
    scanner = new DeadlineScanner(db.worker(), repository, events, Duration.ofSeconds(1), 50);
  }

  // ---------- helpers ----------

  private UUID newOrder(UUID user) throws SQLException {
    return createOrder
        .execute(new CreateOrder.Command(user, UUID.randomUUID(), EVENT, SECTOR, 2), CORRELATION)
        .order()
        .id();
  }

  /** Runs a handler like SqsConsumer does: one worker TX, stale still commits. */
  private ConsumeOutcome consume(String type, Map<String, Object> payload) throws Exception {
    Envelope envelope = Envelope.create(CORRELATION, type, 1, payload);
    VALIDATOR.validate(EnvelopeCodec.serialize(envelope)); // inputs are contract-valid too
    try (Connection c = db.worker().getConnection()) {
      c.setAutoCommit(false);
      try {
        saga.get(type).handle(envelope, c);
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

  private static Map<String, Object> reserved(UUID orderId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("reservationId", UUID.randomUUID().toString());
    p.put("eventId", EVENT.toString());
    p.put("sectorId", SECTOR.toString());
    p.put("quantity", 2);
    p.put("unitPriceCents", 25000);
    p.put("totalCents", 50000);
    p.put("currency", "BRL");
    p.put("expiresAt", "2026-11-20T18:31:12.500Z");
    return p;
  }

  private static Map<String, Object> approved(UUID orderId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("paymentId", PAYMENT.toString());
    p.put("amountCents", 50000);
    p.put("currency", "BRL");
    p.put("providerReference", "SIM-1");
    p.put("approvedAt", "2026-11-20T18:30:13.900Z");
    return p;
  }

  private static Map<String, Object> orderOnly(UUID orderId, Object... extra) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    for (int i = 0; i < extra.length; i += 2) {
      p.put((String) extra[i], extra[i + 1]);
    }
    return p;
  }

  private static Map<String, Object> rejected(UUID orderId) {
    return orderOnly(
        orderId,
        "eventId",
        EVENT.toString(),
        "sectorId",
        SECTOR.toString(),
        "quantity",
        2,
        "reason",
        "OUT_OF_STOCK");
  }

  private static Map<String, Object> failed(UUID orderId) {
    return orderOnly(orderId, "paymentId", PAYMENT.toString(), "reason", "DECLINED");
  }

  private static Map<String, Object> expired(UUID orderId) {
    return orderOnly(orderId, "reservationId", UUID.randomUUID().toString(), "quantity", 2);
  }

  private static Map<String, Object> refunded(UUID orderId) {
    return orderOnly(
        orderId,
        "paymentId",
        PAYMENT.toString(),
        "refundId",
        UUID.randomUUID().toString(),
        "amountCents",
        50000,
        "currency",
        "BRL");
  }

  private Map<String, Object> row(UUID orderId) throws SQLException {
    try (Connection c = db.owner().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT status, cancel_reason, refund_status, total_cents, currency,"
                    + " payment_deadline_at > now() + interval '10 seconds' AS deadline_ahead"
                    + " FROM orders WHERE id = ?")) {
      ps.setObject(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("order " + orderId + " exists").isTrue();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", rs.getString(1));
        r.put("cancelReason", rs.getString(2));
        r.put("refundStatus", rs.getString(3));
        r.put("totalCents", rs.getObject(4));
        r.put("currency", rs.getString(5));
        r.put("deadlineAhead", rs.getObject(6));
        return r;
      }
    }
  }

  /**
   * Outbox events of one order (type list in insertion order), each validated against contracts.
   */
  private List<String> published(UUID orderId) throws SQLException {
    List<String> types = new ArrayList<>();
    try (Connection c = db.owner().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT message_id::text, correlation_id::text, type, version, occurred_at,"
                    + " payload::text, topic_arn FROM outbox WHERE payload->>'orderId' = ?"
                    + " ORDER BY id")) {
      ps.setString(1, orderId.toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Envelope e =
              new Envelope(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getInt(4),
                  rs.getTimestamp(5).toInstant(),
                  tools.jackson.databind.json.JsonMapper.builder()
                      .build()
                      .readTree(rs.getString(6)));
          VALIDATOR.validate(EnvelopeCodec.serialize(e));
          assertThat(rs.getString(7)).isEqualTo(TOPIC);
          types.add(e.type());
        }
      }
    }
    return types;
  }

  private void expireDeadline(UUID orderId) throws SQLException {
    try (Connection c = db.owner().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE orders SET payment_deadline_at = now() - interval '1 second' WHERE id = ?")) {
      ps.setObject(1, orderId);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
  }

  private UUID awaitingPayment() throws Exception {
    UUID id = newOrder(USER_A);
    assertThat(consume("InventoryReserved", reserved(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    return id;
  }

  private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return task.call();
            }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> f : futures) {
        results.add(f.get());
      }
      return results;
    }
  }

  // ---------- API (T09) ----------

  @Test
  void createWritesPendingOrderAndOrderCreatedV2InOneTransaction() throws Exception {
    UUID key = UUID.randomUUID();
    CreateOrder.Result first =
        createOrder.execute(new CreateOrder.Command(USER_A, key, EVENT, SECTOR, 2), CORRELATION);
    assertThat(first.created()).isTrue();
    assertThat(first.order().status()).isEqualTo(OrderStatus.PENDING);
    assertThat(published(first.order().id())).containsExactly("OrderCreated");
    assertThat(
            Containers.count(
                db.owner(),
                "SELECT count(*) FROM outbox WHERE version = 2 AND payload->>'channel' = 'API'"))
        .isEqualTo(1);

    CreateOrder.Result again =
        createOrder.execute(new CreateOrder.Command(USER_A, key, EVENT, SECTOR, 2), CORRELATION);
    assertThat(again.created()).isFalse();
    assertThat(again.order().id()).isEqualTo(first.order().id());
    assertThat(Containers.count(db.owner(), "SELECT count(*) FROM orders")).isEqualTo(1);
    assertThat(Containers.count(db.owner(), "SELECT count(*) FROM outbox")).isEqualTo(1);
  }

  @Test
  void orderCreatedV1HasNoChannel() throws Exception {
    CreateOrder v1 = new CreateOrder(db.app(), repository, new OrderEvents(TOPIC, 1));
    UUID id =
        v1.execute(
                new CreateOrder.Command(USER_A, UUID.randomUUID(), EVENT, SECTOR, 1), CORRELATION)
            .order()
            .id();
    assertThat(published(id)).containsExactly("OrderCreated");
    assertThat(
            Containers.count(
                db.owner(),
                "SELECT count(*) FROM outbox WHERE version = 1 AND payload->>'channel' IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void concurrentRequestsWithTheSameKeyCreateOneOrder() throws Exception {
    UUID key = UUID.randomUUID();
    List<UUID> ids =
        concurrently(
            8,
            () ->
                createOrder
                    .execute(new CreateOrder.Command(USER_A, key, EVENT, SECTOR, 2), CORRELATION)
                    .order()
                    .id());
    assertThat(Set.copyOf(ids)).hasSize(1);
    assertThat(Containers.count(db.owner(), "SELECT count(*) FROM orders")).isEqualTo(1);
    assertThat(Containers.count(db.owner(), "SELECT count(*) FROM outbox")).isEqualTo(1);
  }

  @Test
  void sameKeyFromAnotherUserIsAnotherOrder() throws Exception {
    UUID key = UUID.randomUUID();
    UUID a =
        createOrder
            .execute(new CreateOrder.Command(USER_A, key, EVENT, SECTOR, 2), CORRELATION)
            .order()
            .id();
    UUID b =
        createOrder
            .execute(new CreateOrder.Command(USER_B, key, EVENT, SECTOR, 2), CORRELATION)
            .order()
            .id();
    assertThat(a).isNotEqualTo(b);
  }

  /** p19 + BOLA: RLS alone hides A's order from B, even with no WHERE user_id in the SQL. */
  @Test
  void p19RowLevelSecurityHidesOtherUsersOrders() throws Exception {
    UUID idA = newOrder(USER_A);
    String all = "SELECT count(*) FROM orders";
    long seenByB = UserScopedTransaction.run(db.app(), USER_B, c -> countIn(c, all));
    long seenByA = UserScopedTransaction.run(db.app(), USER_A, c -> countIn(c, all));
    assertThat(seenByB).isZero();
    assertThat(seenByA).isEqualTo(1);
    assertThat(queries.get(USER_B, idA)).isEmpty();
    assertThat(queries.get(USER_A, idA)).map(OrderView::id).contains(idA);
  }

  private static long countIn(Connection c, String sql) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void listPaginatesNewestFirst() throws Exception {
    List<UUID> created = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      created.add(newOrder(USER_A));
    }
    newOrder(USER_B);
    OrderQueries.Page first = queries.list(USER_A, null, 2);
    assertThat(first.items()).hasSize(2);
    assertThat(first.next()).isNotNull();
    OrderQueries.Page second = queries.list(USER_A, first.next(), 2);
    assertThat(second.items()).hasSize(1);
    assertThat(second.next()).isNull();
    List<UUID> seen = new ArrayList<>();
    first.items().forEach(o -> seen.add(o.id()));
    second.items().forEach(o -> seen.add(o.id()));
    assertThat(seen).containsExactlyInAnyOrderElementsOf(created);
  }

  // ---------- saga (T10) ----------

  @Test
  void happyPathReservesThenConfirms() throws Exception {
    UUID id = newOrder(USER_A);
    assertThat(consume("InventoryReserved", reserved(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    Map<String, Object> afterReserve = row(id);
    assertThat(afterReserve)
        .containsEntry("status", "AWAITING_PAYMENT")
        .containsEntry("totalCents", 50000L)
        .containsEntry("currency", "BRL")
        .containsEntry("deadlineAhead", true);

    assertThat(consume("PaymentApproved", approved(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(row(id)).containsEntry("status", "CONFIRMED").containsEntry("refundStatus", null);
    assertThat(published(id)).containsExactly("OrderCreated", "PaymentRequested", "OrderConfirmed");
  }

  @Test
  void duplicateAndOutOfOrderEventsDoNotRegress() throws Exception {
    UUID id = newOrder(USER_A);
    // Approval before the reservation: nothing to confirm, nothing to refund.
    assertThat(consume("PaymentApproved", approved(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(consume("PaymentFailed", failed(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(consume("PaymentRefunded", refunded(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(row(id)).containsEntry("status", "PENDING");

    assertThat(consume("InventoryReserved", reserved(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(consume("InventoryReserved", reserved(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(consume("InventoryRejected", rejected(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(consume("PaymentApproved", approved(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    // Confirmed is final: nothing later moves it.
    assertThat(consume("PaymentApproved", approved(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(consume("PaymentFailed", failed(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(consume("InventoryReservationExpired", expired(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(row(id)).containsEntry("status", "CONFIRMED");
    assertThat(published(id)).containsExactly("OrderCreated", "PaymentRequested", "OrderConfirmed");
  }

  @Test
  void inventoryRejectedCancelsAsOutOfStock() throws Exception {
    UUID id = newOrder(USER_A);
    assertThat(consume("InventoryRejected", rejected(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(row(id))
        .containsEntry("status", "CANCELLED")
        .containsEntry("cancelReason", "OUT_OF_STOCK");
    assertThat(consume("InventoryReserved", reserved(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(published(id)).containsExactly("OrderCreated", "OrderCancelled");
  }

  @Test
  void paymentFailedCancelsAndCompensates() throws Exception {
    UUID id = awaitingPayment();
    assertThat(consume("PaymentFailed", failed(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(row(id))
        .containsEntry("status", "CANCELLED")
        .containsEntry("cancelReason", "PAYMENT_FAILED");
    assertThat(consume("PaymentFailed", failed(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(published(id)).containsExactly("OrderCreated", "PaymentRequested", "OrderCancelled");
  }

  @Test
  void reservationExpiredCancelsPendingOrAwaitingPayment() throws Exception {
    UUID pending = newOrder(USER_A);
    UUID awaiting = awaitingPayment();
    assertThat(consume("InventoryReservationExpired", expired(pending)))
        .isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(consume("InventoryReservationExpired", expired(awaiting)))
        .isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(row(pending)).containsEntry("cancelReason", "RESERVATION_EXPIRED");
    assertThat(row(awaiting)).containsEntry("cancelReason", "RESERVATION_EXPIRED");
    assertThat(consume("InventoryReservationExpired", expired(pending)))
        .isEqualTo(ConsumeOutcome.STALE);
  }

  // ---------- deadline scanner and late approval (T11) ----------

  @Test
  void scannerCancelsOnlyOverdueAwaitingPayment() throws Exception {
    UUID overdue = awaitingPayment();
    UUID inTime = awaitingPayment();
    UUID pending = newOrder(USER_A);
    UUID confirmed = awaitingPayment();
    consume("PaymentApproved", approved(confirmed));
    expireDeadline(overdue);
    expireDeadline(confirmed);

    assertThat(scanner.runOnce()).isEqualTo(1);
    assertThat(row(overdue))
        .containsEntry("status", "CANCELLED")
        .containsEntry("cancelReason", "PAYMENT_TIMEOUT");
    assertThat(row(inTime)).containsEntry("status", "AWAITING_PAYMENT");
    assertThat(row(pending)).containsEntry("status", "PENDING");
    assertThat(row(confirmed)).containsEntry("status", "CONFIRMED");
    assertThat(published(overdue))
        .containsExactly("OrderCreated", "PaymentRequested", "OrderCancelled");
    assertThat(scanner.runOnce()).isZero();
  }

  @Test
  void concurrentScannersCancelEachOrderOnce() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      UUID id = awaitingPayment();
      expireDeadline(id);
      ids.add(id);
    }
    DeadlineScanner small =
        new DeadlineScanner(db.worker(), repository, events, Duration.ofSeconds(1), 5);
    List<Integer> counts =
        concurrently(
            3,
            () -> {
              int total = 0;
              for (int n; (n = small.runOnce()) > 0; ) {
                total += n;
              }
              return total;
            });
    assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
    assertThat(
            Containers.count(
                db.owner(), "SELECT count(*) FROM outbox WHERE type = 'OrderCancelled'"))
        .isEqualTo(20);
  }

  @Test
  void lateApprovalAfterScannerRequestsOneRefundAndPaymentRefundedCloses() throws Exception {
    UUID id = awaitingPayment();
    expireDeadline(id);
    scanner.runOnce();

    List<ConsumeOutcome> outcomes = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      outcomes.add(consume("PaymentApproved", approved(id)));
    }
    assertThat(outcomes)
        .containsExactly(ConsumeOutcome.SUCCEEDED, ConsumeOutcome.STALE, ConsumeOutcome.STALE);
    assertThat(row(id))
        .containsEntry("status", "CANCELLED")
        .containsEntry("refundStatus", "REQUESTED");
    assertThat(published(id))
        .containsExactly("OrderCreated", "PaymentRequested", "OrderCancelled", "RefundRequested");

    assertThat(consume("PaymentRefunded", refunded(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(row(id)).containsEntry("refundStatus", "REFUNDED");
    assertThat(consume("PaymentRefunded", refunded(id))).isEqualTo(ConsumeOutcome.STALE);
    assertThat(consume("PaymentApproved", approved(id))).isEqualTo(ConsumeOutcome.STALE);
  }

  @Test
  void lateApprovalBeforeScannerCancelsAndRefundsInOneTransaction() throws Exception {
    UUID id = awaitingPayment();
    expireDeadline(id);
    assertThat(consume("PaymentApproved", approved(id))).isEqualTo(ConsumeOutcome.SUCCEEDED);
    assertThat(row(id))
        .containsEntry("status", "CANCELLED")
        .containsEntry("cancelReason", "PAYMENT_TIMEOUT")
        .containsEntry("refundStatus", "REQUESTED");
    assertThat(published(id))
        .containsExactly("OrderCreated", "PaymentRequested", "OrderCancelled", "RefundRequested");
    assertThat(scanner.runOnce()).isZero();
  }

  /** The race of RFC 0005 §5.5: whoever wins, one cancellation and one refund, never CONFIRMED. */
  @Test
  void scannerRacingLateApprovalCancelsOnceAndRefundsOnce() throws Exception {
    for (int round = 0; round < 10; round++) {
      UUID id = awaitingPayment();
      expireDeadline(id);
      CountDownLatch start = new CountDownLatch(1);
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        Future<?> scan =
            pool.submit(() -> {
              start.await();
              return scanner.runOnce();
            });
        Future<?> approve =
            pool.submit(() -> {
              start.await();
              return consume("PaymentApproved", approved(id));
            });
        start.countDown();
        scan.get();
        approve.get();
      }
      assertThat(row(id))
          .containsEntry("status", "CANCELLED")
          .containsEntry("refundStatus", "REQUESTED");
      Map<String, Long> byType =
          published(id).stream().collect(Collectors.groupingBy(t -> t, Collectors.counting()));
      assertThat(byType).containsEntry("OrderCancelled", 1L).containsEntry("RefundRequested", 1L);
      assertThat(byType).doesNotContainKey("OrderConfirmed");
    }
  }

  // ---------- through SQS (consumer wiring, R1) ----------

  @Test
  void consumerAppliesSagaFromSqsAndDropsDuplicates() throws Exception {
    UUID id = newOrder(USER_A);
    try (SqsClient sqs = Containers.sqs()) {
      String queue = Containers.queue(sqs, "orders", 3)[0];
      SqsConsumer consumer =
          new SqsConsumer(
              sqs,
              db.worker(),
              VALIDATOR,
              saga,
              new SqsConsumerProperties(queue, "order-service", 4, 1, Containers.CLIENT_TIMEOUT));
      String body =
          EnvelopeCodec.serialize(
              Envelope.create(CORRELATION, "InventoryReserved", 1, reserved(id)));
      sqs.sendMessage(b -> b.queueUrl(queue).messageBody(body));
      sqs.sendMessage(b -> b.queueUrl(queue).messageBody(body));

      List<ConsumeOutcome> outcomes = new ArrayList<>();
      for (int i = 0; i < 10 && outcomes.size() < 2; i++) {
        for (Message m :
            sqs.receiveMessage(b ->
                    b.queueUrl(queue)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1)
                        .messageSystemAttributeNames(
                            MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                .messages()) {
          outcomes.add(consumer.process(m));
        }
      }
      assertThat(outcomes)
          .containsExactlyInAnyOrder(ConsumeOutcome.SUCCEEDED, ConsumeOutcome.DUPLICATE);
      assertThat(row(id)).containsEntry("status", "AWAITING_PAYMENT");
      assertThat(published(id)).containsExactly("OrderCreated", "PaymentRequested");
      assertThat(Containers.queueDepth(sqs, queue)).isZero();
    }
  }
}
