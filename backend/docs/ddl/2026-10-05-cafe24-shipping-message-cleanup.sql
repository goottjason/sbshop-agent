-- D-325: Back up id, message, last_market_message before running in production.
-- Normalize both fields so the next sync still recognizes manual overrides.
-- Only leading Cafe24 labels are removed; the delivery instructions are retained.
BEGIN;

UPDATE sb_order
SET message = regexp_replace(message, '^([[:space:]]*\[고객배송메모\][[:space:]]*)+', ''),
    last_market_message = regexp_replace(last_market_message, '^([[:space:]]*\[고객배송메모\][[:space:]]*)+', ''),
    updated_at = CURRENT_TIMESTAMP
WHERE market_type IN ('GMARKET', 'AUCTION')
  AND (message ~ '^[[:space:]]*\[고객배송메모\]'
       OR last_market_message ~ '^[[:space:]]*\[고객배송메모\]')
RETURNING id;

COMMIT;
