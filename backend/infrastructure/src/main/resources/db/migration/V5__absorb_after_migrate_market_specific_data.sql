-- V5: after-migrate.sql 흡수 — Flyway 외부 스키마 변경 단일화 (D-005)
--
-- 기존 after-migrate.sql은 앱 기동 시마다 실행되던 비-Flyway 스크립트로, 대부분의 DDL이
-- V4와 중복이었다. 유일한 델타는 sb_order.market_specific_data 컬럼(V1~V4 어디에도 생성되지
-- 않고 엔티티에만 매핑, after-migrate.sql이 조용히 타입만 확장)이므로 이 컬럼만 V5로 이전한다.
-- V4와 중복인 항목(jsonb 변환·detail_html·신규 컬럼/테이블)은 이미 V4가 커버하므로 넣지 않는다.
--
-- 타입은 TEXT로 정규화한다: 엔티티 Order.java의 @Column(columnDefinition = "TEXT")와 정합.
-- (구 after-migrate는 varchar(50000)로 확장했으나, PostgreSQL에서 varchar→text는 무손실이며
--  테이블 재작성이 없다.)
--
-- 멱등: 컬럼 부재(빈 DB) / 컬럼 존재-TEXT / 컬럼 존재-varchar(50000) 등 다른 타입, 세 경우 모두
--       최종적으로 TEXT로 수렴한다.

-- (1) 부재 시 TEXT로 신규 생성. 이미 존재하면 no-op.
ALTER TABLE sb_order ADD COLUMN IF NOT EXISTS market_specific_data TEXT;

-- (2) 이미 varchar(50000) 등 다른 타입으로 존재하는 운영 DB를 TEXT로 수렴(멱등 가드).
--     TEXT→TEXT 재실행도 무해. varchar→text는 무손실·테이블 재작성 없음.
DO $$
BEGIN
    ALTER TABLE sb_order ALTER COLUMN market_specific_data TYPE TEXT;
END $$;
