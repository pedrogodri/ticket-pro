-- Payments and the simulated provider's charge memory (RFC 0003 §5.6).
CREATE TABLE payments (
  id                  uuid        PRIMARY KEY,
  order_id            uuid        NOT NULL UNIQUE,
  amount_cents        bigint      NOT NULL CHECK (amount_cents > 0),
  currency            text        NOT NULL,
  status              text        NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'FAILED')),
  failure_reason      text        CHECK (failure_reason IN ('DECLINED', 'PROVIDER_ERROR', 'PROVIDER_TIMEOUT')),
  provider_reference  text,
  lease_until         timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);
-- Worker claim: pending payments in arrival order.
CREATE INDEX payments_pending_idx ON payments (created_at) WHERE status = 'PENDING';

CREATE TABLE provider_charges (
  payment_id          uuid        PRIMARY KEY REFERENCES payments (id),
  result              text        NOT NULL CHECK (result IN ('APPROVED', 'DECLINED')),
  reason              text,
  provider_reference  text,
  created_at          timestamptz NOT NULL DEFAULT now()
);
