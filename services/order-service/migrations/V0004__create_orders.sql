-- Orders and the saga state machine (arquitetura §6). Owner-only reads via RLS (padroes §6.1).
CREATE TABLE orders (
  id                   uuid        PRIMARY KEY,
  user_id              uuid        NOT NULL,
  idempotency_key      uuid        NOT NULL,
  event_id             uuid        NOT NULL,
  sector_id            uuid        NOT NULL,
  quantity             integer     NOT NULL CHECK (quantity BETWEEN 1 AND 6),
  status               text        NOT NULL CHECK (status IN ('PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'CANCELLED')),
  cancel_reason        text        CHECK (cancel_reason IN ('OUT_OF_STOCK', 'PAYMENT_FAILED', 'PAYMENT_TIMEOUT', 'RESERVATION_EXPIRED')),
  total_cents          bigint,
  currency             text,
  payment_deadline_at  timestamptz,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, idempotency_key)
);
CREATE INDEX orders_user_created_idx ON orders (user_id, created_at DESC, id DESC);
-- Deadline scanner (F3) looks for overdue AWAITING_PAYMENT orders.
CREATE INDEX orders_awaiting_deadline_idx ON orders (payment_deadline_at) WHERE status = 'AWAITING_PAYMENT';

ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;
CREATE POLICY orders_owner ON orders
  USING (user_id = current_setting('app.user_id', true)::uuid)
  WITH CHECK (user_id = current_setting('app.user_id', true)::uuid);
