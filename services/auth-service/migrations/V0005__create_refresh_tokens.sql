-- Refresh tokens (RFC 0002 §5.3): only the SHA-256 of the opaque token is stored.
CREATE TABLE refresh_tokens (
  id          uuid        PRIMARY KEY,
  user_id     uuid        NOT NULL REFERENCES credentials (user_id) ON DELETE CASCADE,
  family_id   uuid        NOT NULL,
  token_hash  text        NOT NULL UNIQUE,
  expires_at  timestamptz NOT NULL,
  used_at     timestamptz,
  revoked_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY refresh_tokens_owner ON refresh_tokens
  USING (user_id = current_setting('app.user_id', true)::uuid)
  WITH CHECK (user_id = current_setting('app.user_id', true)::uuid);
