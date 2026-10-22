-- The retention step (config/RetentionSchedule) clears the request address of sign-ins past their window, oldest first,
-- every ten minutes. Without an index on age it would read the whole audit table each time, and that table only grows.
-- The index is partial on the rows still holding an address, so a sign-in leaves it once its address is cleared and its
-- size stays at the sign-ins inside the window. The two account-activity indexes (V18) are unchanged: the rows stay.
CREATE INDEX audit_events_sign_in_address_idx ON audit_events (occurred_at) WHERE action = 'SIGN_IN' AND remote_address IS NOT NULL;
