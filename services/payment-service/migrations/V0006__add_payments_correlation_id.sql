-- The saga's correlationId arrives on PaymentRequested but the charge happens later, in the worker,
-- outside that message. Kept here so PaymentApproved/PaymentFailed carry the same correlationId
-- (RFC 0001 §5.1). Nullable: rows written before this migration fall back to a new correlation.
ALTER TABLE payments ADD COLUMN correlation_id uuid;
