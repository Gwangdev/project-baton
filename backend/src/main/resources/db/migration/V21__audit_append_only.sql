-- Article 8(3) of the personal-data safety measures standard asks that access records be kept safe from forgery and
-- alteration, and the account the application uses could delete or rewrite any audit row. The system only ever adds audit
-- rows; its one change is clearing a sign-in's request address once the sign-in is past its retention (AuditService). The
-- database now refuses every other change, so a bug or an injected statement in the application cannot rewrite the
-- record. A table owner or superuser can still disable these triggers; separating that account from the application's is
-- left to the deployment design.
CREATE FUNCTION audit_events_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.action = 'SIGN_IN'
       AND NEW.remote_address IS NULL
       AND (NEW.id, NEW.actor_id, NEW.action, NEW.target_type, NEW.target_id, NEW.result, NEW.trace_id, NEW.occurred_at)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.actor_id, OLD.action, OLD.target_type, OLD.target_id, OLD.result, OLD.trace_id, OLD.occurred_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'audit_events rows are append-only (% refused)', TG_OP USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_events_append_only_rows
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();

CREATE TRIGGER audit_events_append_only_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_append_only();
