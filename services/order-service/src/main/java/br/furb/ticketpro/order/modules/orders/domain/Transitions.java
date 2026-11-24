package br.furb.ticketpro.order.modules.orders.domain;

/**
 * Every saga transition as one conditional UPDATE (RFC 0005 §5.4, decision 1): the statement both
 * decides and applies, so duplicate, late or out-of-order events match zero rows and change
 * nothing. Deadlines use the database clock ({@code now()}), never the JVM's (decision 3).
 */
public final class Transitions {

  private Transitions() {}

  /** InventoryReserved. Params: totalCents, currency, paymentTimeoutMs, id. */
  public static final String TO_AWAITING_PAYMENT =
      """
      UPDATE orders SET status = 'AWAITING_PAYMENT', total_cents = ?, currency = ?,
             payment_deadline_at = now() + (? * interval '1 millisecond'), updated_at = now()
       WHERE id = ? AND status = 'PENDING'
      RETURNING id, user_id, total_cents, currency
      """;

  /** PaymentApproved within the deadline. Params: id. */
  public static final String CONFIRM =
      """
      UPDATE orders SET status = 'CONFIRMED', updated_at = now()
       WHERE id = ? AND status = 'AWAITING_PAYMENT' AND payment_deadline_at >= now()
      RETURNING id, user_id, event_id, sector_id, quantity, total_cents, currency, updated_at
      """;

  /** InventoryRejected (decision 4: any reason cancels as OUT_OF_STOCK). Params: id. */
  public static final String CANCEL_OUT_OF_STOCK =
      """
      UPDATE orders SET status = 'CANCELLED', cancel_reason = 'OUT_OF_STOCK', updated_at = now()
       WHERE id = ? AND status = 'PENDING'
      RETURNING id, user_id, updated_at
      """;

  /** PaymentFailed. Params: id. */
  public static final String CANCEL_PAYMENT_FAILED =
      """
      UPDATE orders SET status = 'CANCELLED', cancel_reason = 'PAYMENT_FAILED', updated_at = now()
       WHERE id = ? AND status = 'AWAITING_PAYMENT'
      RETURNING id, user_id, updated_at
      """;

  /** InventoryReservationExpired. Params: id. */
  public static final String CANCEL_RESERVATION_EXPIRED =
      """
      UPDATE orders SET status = 'CANCELLED', cancel_reason = 'RESERVATION_EXPIRED',
             updated_at = now()
       WHERE id = ? AND status IN ('PENDING', 'AWAITING_PAYMENT')
      RETURNING id, user_id, updated_at
      """;

  /**
   * Late PaymentApproved whose order is still AWAITING_PAYMENT past the deadline (the scanner has
   * not run yet): cancel and ask for the refund in the same statement (RFC 0005 §5.6 step 1).
   * Params: id.
   */
  public static final String CANCEL_LATE_AND_REQUEST_REFUND =
      """
      UPDATE orders SET status = 'CANCELLED', cancel_reason = 'PAYMENT_TIMEOUT',
             refund_status = 'REQUESTED', updated_at = now()
       WHERE id = ? AND status = 'AWAITING_PAYMENT' AND payment_deadline_at < now()
      RETURNING id, user_id, updated_at
      """;

  /**
   * Late PaymentApproved for an order already CANCELLED: refund_status IS NULL guarantees a single
   * refund even when the approval is redelivered (RFC 0005 §5.6 step 2, decision 10). Params: id.
   */
  public static final String REQUEST_REFUND =
      """
      UPDATE orders SET refund_status = 'REQUESTED', updated_at = now()
       WHERE id = ? AND status = 'CANCELLED' AND refund_status IS NULL
      RETURNING id
      """;

  /** PaymentRefunded. Params: id. */
  public static final String MARK_REFUNDED =
      """
      UPDATE orders SET refund_status = 'REFUNDED', updated_at = now()
       WHERE id = ? AND refund_status = 'REQUESTED'
      RETURNING id
      """;

  /** Deadline scanner (RFC 0005 §5.5), safe in N replicas. Params: batchSize. */
  public static final String CANCEL_OVERDUE =
      """
      WITH due AS (SELECT id FROM orders
                    WHERE status = 'AWAITING_PAYMENT' AND payment_deadline_at < now()
                    ORDER BY payment_deadline_at LIMIT ? FOR UPDATE SKIP LOCKED)
      UPDATE orders o SET status = 'CANCELLED', cancel_reason = 'PAYMENT_TIMEOUT', updated_at = now()
        FROM due WHERE o.id = due.id
      RETURNING o.id, o.user_id, o.updated_at
      """;
}
