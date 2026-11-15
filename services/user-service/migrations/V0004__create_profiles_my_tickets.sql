-- Profiles and the "my tickets" read model, both projected from events (CQRS, arquitetura §5, §8).
CREATE TABLE profiles (
  user_id     uuid        PRIMARY KEY,
  email       text        NOT NULL,
  name        text        NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE my_tickets (
  ticket_id        uuid        PRIMARY KEY,
  ticket_code      text        NOT NULL UNIQUE,
  user_id          uuid        NOT NULL,
  order_id         uuid        NOT NULL,
  event_id         uuid        NOT NULL,
  event_name       text        NOT NULL,
  event_starts_at  timestamptz NOT NULL,
  sector_name      text        NOT NULL,
  issued_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX my_tickets_user_issued_idx ON my_tickets (user_id, issued_at DESC, ticket_id DESC);

ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY profiles_owner ON profiles
  USING (user_id = current_setting('app.user_id', true)::uuid)
  WITH CHECK (user_id = current_setting('app.user_id', true)::uuid);

ALTER TABLE my_tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE my_tickets FORCE ROW LEVEL SECURITY;
CREATE POLICY my_tickets_owner ON my_tickets
  USING (user_id = current_setting('app.user_id', true)::uuid)
  WITH CHECK (user_id = current_setting('app.user_id', true)::uuid);
