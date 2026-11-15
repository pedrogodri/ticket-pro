-- Notifications sent per order (arquitetura §8). One per (order, kind).
CREATE TABLE notifications (
  id          uuid        PRIMARY KEY,
  order_id    uuid        NOT NULL,
  kind        text        NOT NULL CHECK (kind IN ('TICKETS_ISSUED', 'ORDER_CANCELLED')),
  payload     jsonb       NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (order_id, kind)
);
