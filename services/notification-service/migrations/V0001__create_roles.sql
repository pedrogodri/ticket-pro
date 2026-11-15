-- Roles of ADR-0020: notifications_app serves the API (RLS applies), notifications_worker runs the
-- consumers and jobs (BYPASSRLS). Passwords come from Flyway placeholders (db.env.example, dev only).
CREATE ROLE notifications_app LOGIN PASSWORD '${app_password}';
CREATE ROLE notifications_worker LOGIN BYPASSRLS PASSWORD '${worker_password}';

GRANT USAGE ON SCHEMA public TO notifications_app, notifications_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO notifications_app, notifications_worker;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO notifications_app, notifications_worker;
