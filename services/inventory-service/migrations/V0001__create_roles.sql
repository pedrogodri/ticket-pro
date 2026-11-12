-- Roles of ADR-0020: inventory_app serves the API (RLS applies), inventory_worker runs the outbox relay,
-- consumers and jobs (BYPASSRLS). Passwords come from Flyway placeholders (db.env.example, dev only).
CREATE ROLE inventory_app LOGIN PASSWORD '${app_password}';
CREATE ROLE inventory_worker LOGIN BYPASSRLS PASSWORD '${worker_password}';

GRANT USAGE ON SCHEMA public TO inventory_app, inventory_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO inventory_app, inventory_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO inventory_app, inventory_worker;
