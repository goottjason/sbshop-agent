-- Independent remote state evidence; no quantity or terminal-state backfill is inferred.
ALTER TABLE sb_market_stock_task ADD COLUMN IF NOT EXISTS observed_sale_state VARCHAR(30);
ALTER TABLE sb_market_stock_task ADD COLUMN IF NOT EXISTS observed_stock_state VARCHAR(30);
