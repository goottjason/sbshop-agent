-- 신규 상품 등록 자동화 — 스키마
--
-- 이 프로젝트는 Flyway를 쓰지 않고 운영 DB가 스키마 단일 원본이다(CLAUDE.md).
-- api의 ddl-auto=update가 테이블·컬럼은 자동 생성하므로, 이 파일의 역할은 두 가지다:
--   1) 스키마 의도를 문서로 남긴다(컬럼 의미·제약)
--   2) ddl-auto가 만들어주지 않는 **인덱스**를 수동 적용한다
--
-- 적용:  docker exec -i projects-postgres-1 psql -U canagent -d sbshop < sourcing-automation.ddl.sql
-- 전부 IF NOT EXISTS 이므로 반복 실행해도 안전하다.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 소싱 후보
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sb_sourcing_candidate (
    id                    BIGSERIAL PRIMARY KEY,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',   -- BaseEntity(RecordStatus)
    created_at            TIMESTAMP,
    updated_at            TIMESTAMP,

    vendor                VARCHAR(10)  NOT NULL,
    external_id           VARCHAR(50)  NOT NULL,                    -- iHerb product id
    source_url            TEXT         NOT NULL,
    part_number           VARCHAR(50),

    brand                 VARCHAR(100),
    brand_code            VARCHAR(20),
    name_ko               VARCHAR(500),
    category_slug         VARCHAR(50),
    image_url             TEXT,

    -- 가격은 전부 원화(kr.iherb.com 표기 그대로). MarginCalculator의 buyPrice와 단위 일치.
    list_price            NUMERIC(15,2),
    discount_price        NUMERIC(15,2),
    discount_pct          INTEGER,

    rating                NUMERIC(3,2),
    review_count          INTEGER,
    sales_30d             INTEGER,                                  -- "30일 동안 N개 판매"
    rank_position         INTEGER,
    is_sponsored          BOOLEAN      NOT NULL DEFAULT FALSE,
    is_out_of_stock       BOOLEAN      NOT NULL DEFAULT FALSE,
    is_discontinued       BOOLEAN      NOT NULL DEFAULT FALSE,

    monthly_search_volume INTEGER,                                  -- 네이버 검색광고
    competitor_count      INTEGER,                                  -- 네이버 쇼핑검색 total
    domestic_low_price    NUMERIC(15,2),                            -- 네이버 lprice (표시용)
    domestic_median_price NUMERIC(15,2),                            -- 네이버 결과 중앙값 (가격 판정 기준)
    demand_keyword        VARCHAR(200),

    customs_verdict       VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',  -- PASS/REVIEW/BLOCKED/UNKNOWN
    customs_reason        TEXT,
    ingredients_raw       TEXT,
    customs_checked_at    TIMESTAMP,

    total_score           NUMERIC(6,2),
    score_breakdown       TEXT,                                     -- 서브스코어 근거 JSON
    estimated_sale_price  NUMERIC(15,0),
    estimated_margin_rate NUMERIC(6,2),

    candidate_status      VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    exclude_reason        VARCHAR(200),
    rejected_at           TIMESTAMP,
    discovered_at         TIMESTAMP,
    last_seen_at          TIMESTAMP,

    CONSTRAINT uk_candidate_vendor_external UNIQUE (vendor, external_id)
);

-- 추천 목록 조회: candidate_status='SCORED' ORDER BY total_score DESC
CREATE INDEX IF NOT EXISTS idx_candidate_status_score
    ON sb_sourcing_candidate (candidate_status, total_score DESC);
-- 쿨다운 해제 스캔
CREATE INDEX IF NOT EXISTS idx_candidate_rejected_at
    ON sb_sourcing_candidate (rejected_at) WHERE rejected_at IS NOT NULL;
-- 통관 BLOCKED 목록 열람
CREATE INDEX IF NOT EXISTS idx_candidate_customs
    ON sb_sourcing_candidate (customs_verdict);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 해외직구식품 반입차단 원료·성분 (식약처 공공데이터 15132686)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sb_banned_ingredient (
    id            BIGSERIAL PRIMARY KEY,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,

    name_ko       VARCHAR(300),
    name_en       VARCHAR(300),
    aliases       TEXT,                    -- 기타명칭 원문(쉼표 구분)
    norm_keys     TEXT,                    -- 정규화 매칭키(파이프 구분)
    designated_on DATE,
    released_on   DATE,                    -- NULL이면 현재 차단중
    reason        TEXT,
    source        VARCHAR(30)              -- MFDS_OPENAPI / MANUAL
);

CREATE INDEX IF NOT EXISTS idx_banned_active
    ON sb_banned_ingredient (released_on);
CREATE INDEX IF NOT EXISTS idx_banned_name_ko
    ON sb_banned_ingredient (name_ko);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 등록 초안
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sb_product_draft (
    id             BIGSERIAL PRIMARY KEY,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP,

    candidate_id   BIGINT,
    base_name_ko   VARCHAR(255),
    original_name  VARCHAR(500),
    brand          VARCHAR(100),
    bundle_qty     INTEGER     NOT NULL DEFAULT 1,
    margin_rate    NUMERIC(5,2),
    cost_price     NUMERIC(15,2),          -- 단품 매입가(원)
    source_url     TEXT,
    vendor         VARCHAR(10),
    origin         VARCHAR(100),
    hs_code        VARCHAR(30),
    barcode        VARCHAR(50),
    weight_g       NUMERIC(10,2),
    capacity       NUMERIC(10,2),
    measure_unit   VARCHAR(20),
    category       VARCHAR(50),

    detail_html    TEXT,
    source_images  TEXT,                   -- JSON 배열
    hosted_images  TEXT,                   -- JSON 배열
    ingredients_ko TEXT,
    usage_ko       TEXT,
    caution_ko     TEXT,

    customs_ack    BOOLEAN     NOT NULL DEFAULT FALSE,
    draft_status   VARCHAR(20) NOT NULL DEFAULT 'ENRICHING',
    enrich_note    TEXT,
    product_id     BIGINT
);

CREATE INDEX IF NOT EXISTS idx_draft_status ON sb_product_draft (draft_status);
CREATE INDEX IF NOT EXISTS idx_draft_candidate ON sb_product_draft (candidate_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. 마켓별 초안
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sb_market_draft (
    id                 BIGSERIAL PRIMARY KEY,
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMP,
    updated_at         TIMESTAMP,

    draft_id           BIGINT      NOT NULL,
    market_type        VARCHAR(30) NOT NULL,
    product_name       VARCHAR(500),
    category_id        VARCHAR(50),
    category_path      VARCHAR(300),
    sale_price         NUMERIC(15,0),
    channel_fee_rate   NUMERIC(5,2),
    keywords           TEXT,                -- JSON 배열
    notice_fields      TEXT,                -- 상품정보제공고시 JSON
    extra_fields       TEXT,                -- 마켓 고유 필드 JSON
    missing_fields     TEXT,                -- 미충족 필수필드 JSON 배열
    is_valid           BOOLEAN     NOT NULL DEFAULT FALSE,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    publish_error      TEXT,
    market_identifiers TEXT,

    CONSTRAINT uk_market_draft UNIQUE (draft_id, market_type)
);

CREATE INDEX IF NOT EXISTS idx_market_draft_draft ON sb_market_draft (draft_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. 소싱 설정 (단일 행)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sb_sourcing_config (
    id                   BIGSERIAL PRIMARY KEY,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMP,
    updated_at           TIMESTAMP,

    recommend_count      INTEGER      NOT NULL DEFAULT 20,
    categories           VARCHAR(500) NOT NULL DEFAULT 'supplements,grocery,sports-nutrition,herbs-homeopathy',
    pages_per_category   INTEGER      NOT NULL DEFAULT 3,
    score_weights        TEXT,

    profit_guard_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    target_margin_rate   NUMERIC(5,2)  DEFAULT 20.00,
    min_margin_price     NUMERIC(15,2) DEFAULT 3000,
    max_price_ratio      NUMERIC(4,2)  DEFAULT 1.30,
    coupon_rate          NUMERIC(5,2)  DEFAULT 0,

    reject_cooldown_days INTEGER      NOT NULL DEFAULT 90,
    exclude_sponsored    BOOLEAN      NOT NULL DEFAULT TRUE,
    min_review_count     INTEGER      NOT NULL DEFAULT 50,
    min_rating           NUMERIC(3,2)  DEFAULT 4.0,

    schedule_enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    schedule_cron        VARCHAR(50)  NOT NULL DEFAULT '0 0 3 * * *'
);
