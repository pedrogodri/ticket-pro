-- The worker charges later, outside the PaymentRequested message. Keeping the message's traceparent
-- lets PaymentApproved/PaymentFailed continue the saga's trace (ADR-0012), as V0006 does for the
-- correlationId. Nullable: rows written before this migration simply start no trace.
ALTER TABLE payments ADD COLUMN traceparent text;
