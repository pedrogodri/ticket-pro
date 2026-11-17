-- Purchase channel from OrderCreated v2 (RFC 0001 §5.5), kept for metrics. v1 is upcast to 'API',
-- so existing rows get the same value.
ALTER TABLE reservations
  ADD COLUMN channel text NOT NULL DEFAULT 'API' CHECK (channel IN ('API', 'WEB', 'MOBILE'));
