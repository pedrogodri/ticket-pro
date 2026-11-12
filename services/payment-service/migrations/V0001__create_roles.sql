-- Roles of ADR-0020: payments_app serves the API (RLS applies), payments_worker runs the outbox relay,
-- consumers and jobs (BYPASSRLS). Passwords come from Flyway placeholders (db.env.example, dev only).
CREATE ROLE payments_app LOGIN PASSWORD '${app_password}';
CREATE ROLE payments_worker LOGIN BYPASSRLS PASSWORD '${worker_password}';

GRANT USAGE ON SCHEMA public TO payments_app, payments_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO payments_app, payments_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO payments_app, payments_worker;
