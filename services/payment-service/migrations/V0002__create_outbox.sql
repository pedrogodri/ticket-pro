-- Transactional outbox (ADR-0005, docs/padroes.md §6.2).
CREATE TABLE outbox (
  id               bigserial PRIMARY KEY,
  message_id       uuid        NOT NULL UNIQUE,
  topic_arn        text        NOT NULL,
  type             text        NOT NULL,
  version          integer     NOT NULL CHECK (version >= 1),
  correlation_id   uuid        NOT NULL,
  traceparent      text,
  payload          jsonb       NOT NULL,
  occurred_at      timestamptz NOT NULL DEFAULT now(),
  attempts         integer     NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  published_at     timestamptz,
  last_error       text
);
CREATE INDEX outbox_pending_idx ON outbox (next_attempt_at) WHERE published_at IS NULL;
CREATE INDEX outbox_published_idx ON outbox (published_at) WHERE published_at IS NOT NULL;
