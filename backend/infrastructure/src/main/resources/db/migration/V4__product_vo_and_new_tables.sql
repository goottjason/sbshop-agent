-- V4: Product VO 구조 전환 및 신규 테이블 추가 (Postgres)

-- source_images / hosted_images를 jsonb로 변환
ALTER TABLE sb_product ALTER COLUMN source_images TYPE jsonb USING
    CASE
        WHEN source_images IS NULL OR source_images = '' THEN '[]'::jsonb
        WHEN source_images ~ '^\[' THEN source_images::jsonb
        ELSE '[]'::jsonb
    END;

ALTER TABLE sb_product ALTER COLUMN hosted_images TYPE jsonb USING
    CASE
        WHEN hosted_images IS NULL OR hosted_images = '' THEN '[]'::jsonb
        WHEN hosted_images ~ '^\[' THEN hosted_images::jsonb
        ELSE '[]'::jsonb
    END;

ALTER TABLE sb_product ALTER COLUMN detail_html TYPE text;

-- 신규 컬럼
ALTER TABLE sb_product ADD COLUMN IF NOT EXISTS delivery_fee DECIMAL(15,2);
ALTER TABLE sb_product ADD COLUMN IF NOT EXISTS stock_status VARCHAR(30);
ALTER TABLE sb_product ADD COLUMN IF NOT EXISTS restock_date DATE;

-- 신규 테이블: sb_currency
CREATE TABLE IF NOT EXISTS sb_currency (
    currency_code VARCHAR(3) NOT NULL PRIMARY KEY,
    exchange_rate DECIMAL NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 신규 테이블: sb_supplier
CREATE TABLE IF NOT EXISTS sb_supplier (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    supplier_code VARCHAR(10) NOT NULL UNIQUE,
    supplier_name VARCHAR(100) NOT NULL,
    currency_code VARCHAR(3),
    CONSTRAINT fk_supplier_currency FOREIGN KEY (currency_code) REFERENCES sb_currency(currency_code)
);

-- 신규 테이블: sb_process_status
CREATE TABLE IF NOT EXISTS sb_process_status (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    batch_id VARCHAR(50) NOT NULL,
    product_code VARCHAR(50) NOT NULL,
    job_type VARCHAR(50),
    step VARCHAR(50),
    process_status VARCHAR(20),
    message VARCHAR(1000),
    details TEXT,
    started_at TIMESTAMP,
    updated_at_extra TIMESTAMP,
    CONSTRAINT uk_process_batch_product UNIQUE (batch_id, product_code)
);
