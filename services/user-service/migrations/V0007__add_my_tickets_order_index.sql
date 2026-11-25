-- GET /me/orders counts the tickets already issued for each order (my_tickets.order_id).
CREATE INDEX my_tickets_order_idx ON my_tickets (order_id);
