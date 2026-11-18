-- Deadline scanner filter (RFC 0003 §5.7): AWAITING_PAYMENT orders by payment_deadline_at.
CREATE INDEX orders_awaiting_payment_deadline_idx ON orders (payment_deadline_at) WHERE status = 'AWAITING_PAYMENT';
