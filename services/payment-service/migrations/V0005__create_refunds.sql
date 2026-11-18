-- Refunds of late approvals (RFC 0003 §5.7, F7). One refund per payment; the simulated refund never fails.
CREATE TABLE refunds (
  id            uuid        PRIMARY KEY,
  payment_id    uuid        NOT NULL UNIQUE REFERENCES payments (id),
  amount_cents  bigint      NOT NULL CHECK (amount_cents > 0),
  currency      text        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);
