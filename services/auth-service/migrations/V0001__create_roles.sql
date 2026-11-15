-- Roles of ADR-0020: auth_app serves the API (RLS applies), auth_worker runs the outbox relay,
-- consumers and jobs (BYPASSRLS). Passwords come from Flyway placeholders (db.env.example, dev only).
CREATE ROLE auth_app LOGIN PASSWORD '${app_password}';
CREATE ROLE auth_worker LOGIN BYPASSRLS PASSWORD '${worker_password}';

GRANT USAGE ON SCHEMA public TO auth_app, auth_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO auth_app, auth_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO auth_app, auth_worker;
