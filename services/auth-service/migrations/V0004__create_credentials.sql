-- Credentials (RFC 0002 §5.1). E-mail stored lowercase; one credential per e-mail.
CREATE TABLE credentials (
  user_id        uuid        PRIMARY KEY,
  email          text        NOT NULL UNIQUE CHECK (email = lower(email)),
  password_hash  text        NOT NULL,
  status         text        NOT NULL CHECK (status IN ('ACTIVE')),
  created_at     timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE credentials FORCE ROW LEVEL SECURITY;
CREATE POLICY credentials_owner ON credentials
  USING (user_id = current_setting('app.user_id', true)::uuid)
  WITH CHECK (user_id = current_setting('app.user_id', true)::uuid);
