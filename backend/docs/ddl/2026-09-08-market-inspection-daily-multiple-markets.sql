-- Apply after 2026-09-06-market-inspection-daily.sql. Existing daily sweeps belong to Smartstore.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
ALTER TABLE sb_market_inspection_sweep ADD COLUMN IF NOT EXISTS market varchar(50) NOT NULL DEFAULT 'SMART_STORE';
ALTER TABLE sb_market_inspection_sweep DROP CONSTRAINT IF EXISTS sb_market_inspection_sweep_run_date_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_market_inspection_sweep_market_day ON sb_market_inspection_sweep(market,run_date);
CREATE INDEX IF NOT EXISTS ix_market_inspection_sweep_market_latest ON sb_market_inspection_sweep(market,run_date DESC);
COMMIT;
