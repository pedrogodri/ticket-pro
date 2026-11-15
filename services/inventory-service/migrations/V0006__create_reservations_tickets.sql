-- Reservations and tickets (arquitetura §6, §8). One reservation per order; one ticket per unit.
CREATE TABLE reservations (
  id                uuid        PRIMARY KEY,
  order_id          uuid        NOT NULL UNIQUE,
  sector_id         uuid        NOT NULL REFERENCES sectors (id),
  quantity          integer     NOT NULL CHECK (quantity > 0),
  unit_price_cents  bigint      NOT NULL,
  total_cents       bigint      NOT NULL,
  status            text        NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED')),
  expires_at        timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reservations_sector_id_idx ON reservations (sector_id);
-- Expiration job (F3) scans pending reservations by deadline.
CREATE INDEX reservations_pending_expires_idx ON reservations (expires_at) WHERE status = 'PENDING';

CREATE TABLE tickets (
  id              uuid        PRIMARY KEY,
  reservation_id  uuid        NOT NULL REFERENCES reservations (id),
  code            text        NOT NULL UNIQUE,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tickets_reservation_id_idx ON tickets (reservation_id);
