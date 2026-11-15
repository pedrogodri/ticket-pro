-- Idempotent consumers (ADR-0006, docs/padroes.md §6.2).
CREATE TABLE processed_messages (
  consumer      text        NOT NULL,
  message_id    uuid        NOT NULL,
  processed_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, message_id)
);
CREATE INDEX processed_messages_processed_at_idx ON processed_messages (processed_at);
