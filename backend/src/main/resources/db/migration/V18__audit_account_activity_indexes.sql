-- The account activity a user sees about their own account (sign-ins, and the events that change or hand out the password:
-- a setup code issued or used, an operator's reset) is read on every /me, so it must not scan the audit table, which only
-- grows. Each index holds the rows of one lookup, newest first.
CREATE INDEX audit_events_sign_in_idx ON audit_events (actor_id, occurred_at DESC) WHERE action = 'SIGN_IN';
CREATE INDEX audit_events_credential_idx ON audit_events (target_id, occurred_at DESC)
    WHERE action IN ('ISSUE_CREDENTIAL_TOKEN', 'SET_CREDENTIAL', 'RESET_SYSTEM_ADMIN_CREDENTIAL');
