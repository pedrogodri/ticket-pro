-- "My orders" read model, projected from OrderConfirmed (CQRS, arquitetura §8). It only knows confirmed
-- orders: the live status of an order (PENDING, CANCELLED...) stays in order-service (GET /orders/{id}).
CREATE TABLE my_orders (
  order_id      uuid        PRIMARY KEY,
  user_id       uuid        NOT NULL,
  event_id      uuid        NOT NULL,
  sector_id     uuid        NOT NULL,
  quantity      integer     NOT NULL CHECK (quantity >= 1),
  total_cents   bigint      NOT NULL CHECK (total_cents >= 0),
  currency      text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  confirmed_at  timestamptz NOT NULL,
  projected_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX my_orders_user_confirmed_idx ON my_orders (user_id, confirmed_at DESC, order_id DESC);

ALTER TABLE my_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE my_orders FORCE ROW LEVEL SECURITY;
-- NULLIF: see V0008 (an unset app.user_id reads as '' on a connection that has used it before).
CREATE POLICY my_orders_owner ON my_orders
  USING (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid)
  WITH CHECK (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid);
