-- Access tokens issued by login/refresh (RFC 0007 §5.4): lets the auth service revoke the jti
-- values still alive for a family or a user. Written only by the worker role, so no RLS.
CREATE TABLE issued_access_tokens (
  jti         uuid        PRIMARY KEY,
  user_id     uuid        NOT NULL,
  family_id   uuid        NOT NULL,
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz
);
CREATE INDEX issued_access_tokens_user_idx ON issued_access_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX issued_access_tokens_family_idx ON issued_access_tokens (family_id);
CREATE INDEX issued_access_tokens_expiry_idx ON issued_access_tokens (expires_at);
