-- Roles of ADR-0020: orders_app serves the API (RLS applies), orders_worker runs the outbox relay,
-- consumers and jobs (BYPASSRLS). Passwords come from Flyway placeholders (db.env.example, dev only).
CREATE ROLE orders_app LOGIN PASSWORD '${app_password}';
CREATE ROLE orders_worker LOGIN BYPASSRLS PASSWORD '${worker_password}';

GRANT USAGE ON SCHEMA public TO orders_app, orders_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO orders_app, orders_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO orders_app, orders_worker;
