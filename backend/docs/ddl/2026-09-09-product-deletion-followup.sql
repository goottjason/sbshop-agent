ALTER TABLE sb_product ADD COLUMN IF NOT EXISTS deletion_followup TEXT;
COMMENT ON COLUMN sb_product.deletion_followup IS 'SB soft-delete snapshot of external deletion pending market IDs; not proof of live presence. Original registrations retained.';
