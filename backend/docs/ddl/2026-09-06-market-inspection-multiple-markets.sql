-- 정기 확인 DDL 이후 적용. 기존 스마트스토어 작업의 마켓을 명시하여 보존한다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
ALTER TABLE sb_market_inspection_batch ADD COLUMN IF NOT EXISTS market varchar(50) NOT NULL DEFAULT 'SMART_STORE';
ALTER TABLE sb_market_inspection_task ADD COLUMN IF NOT EXISTS market varchar(50) NOT NULL DEFAULT 'SMART_STORE';
CREATE INDEX IF NOT EXISTS ix_market_inspection_due_by_market ON sb_market_inspection_task(market, state, next_run_at, id);
CREATE INDEX IF NOT EXISTS ix_market_inspection_active_product_market ON sb_market_inspection_task(market, product_id, state);
COMMIT;
