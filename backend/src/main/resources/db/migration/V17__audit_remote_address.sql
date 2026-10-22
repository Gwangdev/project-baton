-- The address a request came from, kept for the setup-code events. A code can be redeemed by whoever holds it, the
-- administrator who issued it included, and the audit row of the redemption names the account's owner, so the address is
-- what tells an administrator's redemption from the owner's. Other events leave the column empty.
ALTER TABLE audit_events ADD COLUMN remote_address varchar(64);
