-- 편집 → 연결 → 조회 작업 DDL 이후 적용. 운영 적용 전 기존 마켓 쓰기 종료 및 백업 필요.
-- Q24 한 계정 확인 근거와 앱 참조는 새 앱 초기화에서 한 번 고정한다. SQL에서 임의 계정을 넣지 않는다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
ALTER TABLE sb_market_inspection_gate ADD COLUMN IF NOT EXISTS verified_account_reference varchar(200);
ALTER TABLE sb_market_inspection_gate ADD COLUMN IF NOT EXISTS account_confirmed_at timestamptz;
ALTER TABLE sb_market_inspection_gate ADD COLUMN IF NOT EXISTS account_confirmation_evidence varchar(300);
CREATE TABLE IF NOT EXISTS sb_market_inspection_sweep (
    id varchar(36) PRIMARY KEY,
    run_date date NOT NULL UNIQUE,
    account_reference varchar(200) NOT NULL,
    upper_registration_id bigint NOT NULL,
    cursor_registration_id bigint NOT NULL,
    enrolled_count bigint NOT NULL,
    batch_count integer NOT NULL,
    state varchar(30) NOT NULL,
    started_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    finished_at timestamptz
);
ALTER TABLE sb_market_inspection_batch ADD COLUMN IF NOT EXISTS sweep_id varchar(36) REFERENCES sb_market_inspection_sweep(id);
CREATE INDEX IF NOT EXISTS ix_market_inspection_batch_sweep ON sb_market_inspection_batch(sweep_id, created_at, id);
CREATE INDEX IF NOT EXISTS ix_market_registration_inspection ON sb_market_registration(market_type, connection_state, id);
COMMIT;
