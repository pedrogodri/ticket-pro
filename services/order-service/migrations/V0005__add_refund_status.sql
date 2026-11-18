-- Late approval refund (RFC 0003 §5.7, F7): REQUESTED when the order publishes RefundRequested,
-- REFUNDED when PaymentRefunded arrives. NULL for orders that never needed one.
ALTER TABLE orders
  ADD COLUMN refund_status text CHECK (refund_status IN ('REQUESTED', 'REFUNDED'));
