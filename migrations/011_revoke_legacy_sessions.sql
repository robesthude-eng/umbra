-- Apply ONCE when upgrading to 0.4.2, with the old server stopped.
-- Previous versions permitted unverified phone registration with a key.
-- Old tokens do not record how they were issued, so revoke them and pending
-- account-transfer codes together. Users sign in again via OTP after upgrade.
-- Accounts, profiles, chats, messages and media are preserved.
BEGIN;
DELETE FROM auth_tokens;
DELETE FROM account_transfers;
COMMIT;
