-- 소싱 콘텐츠 수집·검토·선택 반영. 기존 상품값과 외부마켓 연결을 변경하지 않는다.
-- 새 애플리케이션 이미지보다 먼저 적용한다. 기존 이력은 롤백 시에도 삭제하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
CREATE TABLE IF NOT EXISTS sb_product_content_collection (
 id varchar(36) PRIMARY KEY, request_id varchar(36) NOT NULL, actor varchar(200) NOT NULL,
 created_at timestamptz NOT NULL, product_ids text NOT NULL,
 CONSTRAINT uk_content_request UNIQUE(actor, request_id)
);
CREATE TABLE IF NOT EXISTS sb_product_content_snapshot (
 id varchar(36) PRIMARY KEY, collection_id varchar(36) NOT NULL REFERENCES sb_product_content_collection(id),
 product_id bigint NOT NULL, sb_code varchar(100), revision bigint NOT NULL,
 connection_fingerprint varchar(64), source_url varchar(1000), vendor varchar(20),
 state varchar(30) NOT NULL, reason varchar(1000), requested_at timestamptz NOT NULL,
 collected_at timestamptz, expires_at timestamptz, images_collected_at timestamptz, detail_collected_at timestamptz,
 images_applied_at timestamptz, detail_applied_at timestamptz, captured text NOT NULL, proposed text, claim_token varchar(36)
);
CREATE INDEX IF NOT EXISTS ix_content_snapshot_collection ON sb_product_content_snapshot(collection_id, requested_at, id);
CREATE INDEX IF NOT EXISTS ix_content_snapshot_queue ON sb_product_content_snapshot(state, requested_at, id);
CREATE INDEX IF NOT EXISTS ix_content_snapshot_product ON sb_product_content_snapshot(product_id, requested_at DESC, id DESC);
CREATE TABLE IF NOT EXISTS sb_product_content_lane (
 id varchar(20) PRIMARY KEY, snapshot_id varchar(36), lease_until timestamptz, next_allowed_at timestamptz
);
INSERT INTO sb_product_content_lane(id) VALUES('IHB') ON CONFLICT (id) DO NOTHING;
CREATE TABLE IF NOT EXISTS sb_product_content_review (
 id varchar(36) PRIMARY KEY, actor varchar(200) NOT NULL, created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL, payload text NOT NULL
);
COMMIT;
