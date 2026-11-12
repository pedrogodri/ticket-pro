-- Roles of ADR-0020: users_app serves the API (RLS applies), users_worker runs the outbox relay,
-- consumers and jobs (BYPASSRLS). Passwords come from Flyway placeholders (db.env.example, dev only).
CREATE ROLE users_app LOGIN PASSWORD '${app_password}';
CREATE ROLE users_worker LOGIN BYPASSRLS PASSWORD '${worker_password}';

GRANT USAGE ON SCHEMA public TO users_app, users_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO users_app, users_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO users_app, users_worker;
