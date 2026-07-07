-- [테스트 픽스처] Flyway 도입 이전 운영 베이스라인.
--
-- 운영 DB는 Flyway 도입 전 스키마를 가진 "비어있지-않은" DB였다. 특히 sb_order_line_item에는
-- 레거시 shipping_fee 컬럼이 존재했고, V2__move_shipping_fee_to_logistics_cost.sql이 이를 참조한다
-- (UPDATE ... SET logistics_cost = shipping_fee). V1__init_schema.sql은 CREATE TABLE IF NOT EXISTS라
-- 기존 테이블에 no-op이므로 shipping_fee를 만들지 않는다 → 진짜 빈 DB에서는 V2가 실패한다.
--
-- 이 픽스처는 그 pre-Flyway 상태를 재현해, Flyway V1~V5가 운영과 동일하게 적용되는 경로를 테스트한다.
-- (V1의 CREATE TABLE IF NOT EXISTS가 이 테이블에 no-op이 되고, V2가 shipping_fee를 찾을 수 있게 함.)
-- FK 제약은 컨텍스트 로드 스모크에 불필요하므로 생략한다.
-- 주의: V1~V4가 빈 DB에서 자족적이지 않은 것은 별개 결함(원장 후보 D-015). 이 배치 범위 밖.

CREATE TABLE IF NOT EXISTS sb_order_line_item (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    order_id BIGINT NOT NULL,
    product_id BIGINT,
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2),
    shipping_fee DECIMAL(10,2)
);

-- Flyway 마이그레이션(V1~V5)이 생성하지 않는 엔티티 테이블(pre-Flyway 베이스라인).
-- 앱 기동 시 Cafe24TokenManager @PostConstruct가 sb_market_credential을 조회하므로 필수.
-- 컬럼은 MarketCredential/MarketRegistration + BaseEntity 매핑에서 도출. (별개 결함 후보 D-016)
CREATE TABLE IF NOT EXISTS sb_market_credential (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    market_type VARCHAR(50) NOT NULL UNIQUE,
    client_id VARCHAR(100),
    access_key VARCHAR(100),
    secret_key VARCHAR(255),
    refresh_token VARCHAR(1000),
    access_token VARCHAR(1000),
    token_expires_at TIMESTAMP,
    redirect_uri VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS sb_market_registration (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    product_id BIGINT NOT NULL,
    sb_product_id BIGINT,
    market_type VARCHAR(50) NOT NULL,
    market_product_name VARCHAR(255),
    market_identifiers TEXT,
    market_detailed_info TEXT,
    is_synced BOOLEAN NOT NULL DEFAULT FALSE,
    last_synced_at TIMESTAMP
);
