-- Demo catalog (padroes §6). Sector F5 has 10 units for the F5 race scenario (RFC 0003 §5.8).
INSERT INTO events (id, name, starts_at, venue) VALUES
  ('0193a6f0-3333-7000-8000-000000000003', 'Festival de Verão 2026', '2026-12-12T21:00:00Z', 'Arena Blumenau'),
  ('0193a6f0-3333-7000-8000-000000000013', 'Show Acústico', '2026-12-19T20:00:00Z', 'Teatro Carlos Gomes');

INSERT INTO sectors (id, event_id, name, capacity, available, unit_price_cents, currency) VALUES
  ('0193a6f0-4444-7000-8000-000000000004', '0193a6f0-3333-7000-8000-000000000003', 'Pista', 500, 500, 25000, 'BRL'),
  ('0193a6f0-4444-7000-8000-000000000014', '0193a6f0-3333-7000-8000-000000000003', 'VIP', 100, 100, 60000, 'BRL'),
  ('0193a6f0-4444-7000-8000-0000000000f5', '0193a6f0-3333-7000-8000-000000000003', 'F5', 10, 10, 15000, 'BRL'),
  ('0193a6f0-4444-7000-8000-000000000024', '0193a6f0-3333-7000-8000-000000000013', 'Plateia', 200, 200, 18000, 'BRL');
