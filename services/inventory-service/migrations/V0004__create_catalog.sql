-- Event catalog (arquitetura §8). Public to logged-in users: no RLS.
CREATE TABLE events (
  id         uuid        PRIMARY KEY,
  name       text        NOT NULL,
  starts_at  timestamptz NOT NULL,
  venue      text        NOT NULL
);
CREATE INDEX events_starts_at_idx ON events (starts_at, id);

CREATE TABLE sectors (
  id                uuid    PRIMARY KEY,
  event_id          uuid    NOT NULL REFERENCES events (id),
  name              text    NOT NULL,
  capacity          integer NOT NULL CHECK (capacity > 0),
  available         integer NOT NULL,
  unit_price_cents  bigint  NOT NULL CHECK (unit_price_cents > 0),
  currency          text    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  -- R2: the last line of defense against overselling (ADR-0007).
  CHECK (available >= 0 AND available <= capacity)
);
CREATE INDEX sectors_event_id_idx ON sectors (event_id);
