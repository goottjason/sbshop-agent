-- After market-price-sync.sql; new sales quantity is independent of legacy logistics stock.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
ALTER TABLE sb_product ADD COLUMN IF NOT EXISTS sales_quantity integer NOT NULL DEFAULT 300;
ALTER TABLE sb_market_registration ADD COLUMN IF NOT EXISTS publication_operation_id varchar(36);
CREATE TABLE IF NOT EXISTS sb_market_publication_task (
 id varchar(36) PRIMARY KEY, product_id bigint NOT NULL, registration_id bigint, product_revision bigint NOT NULL,
 market varchar(50) NOT NULL, actor varchar(200) NOT NULL, sb_code varchar(255) NOT NULL,
 connection_snapshot text NOT NULL, prepared text NOT NULL, state varchar(30) NOT NULL, detail varchar(1000) NOT NULL,
 returned_identifiers text, listing_id varchar(255), created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 committed_at timestamptz, next_run_at timestamptz NOT NULL, finished_at timestamptz, checked_at timestamptz,
 lease_token varchar(36), lease_until timestamptz, attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_publication_due ON sb_market_publication_task(market,state,next_run_at,created_at);
CREATE INDEX IF NOT EXISTS ix_publication_recent ON sb_market_publication_task(created_at DESC);
CREATE INDEX IF NOT EXISTS ix_publication_product ON sb_market_publication_task(product_id,market);
COMMIT;
-- Keep task records and pending operation IDs on rollback: uncertain POSTs must not be duplicated.
