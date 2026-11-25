-- After a pooled connection has run SET LOCAL app.user_id once, the setting reads as '' (not NULL) in its
-- later transactions, and ''::uuid raises an error instead of matching no row. NULLIF makes "no user" always
-- mean "no rows", whatever the connection served before. Same rule, new expression (V0004 stays untouched).
ALTER POLICY profiles_owner ON profiles
  USING (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid)
  WITH CHECK (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid);

ALTER POLICY my_tickets_owner ON my_tickets
  USING (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid)
  WITH CHECK (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid);
