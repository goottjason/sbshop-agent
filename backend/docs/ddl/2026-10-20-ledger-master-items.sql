-- 가계부 분류(카테고리) 마스터 테이블
CREATE TABLE IF NOT EXISTS ledger_category (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uk_ledger_category_name UNIQUE(name)
);

CREATE INDEX IF NOT EXISTS idx_ledger_category_status_order ON ledger_category(status, display_order);

-- 가계부 지출처(출금계좌/카드) 마스터 테이블
CREATE TABLE IF NOT EXISTS ledger_payment_source (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uk_ledger_payment_source_name UNIQUE(name)
);

CREATE INDEX IF NOT EXISTS idx_ledger_payment_source_status_order ON ledger_payment_source(status, display_order);

-- 초기 시드 데이터 삽입 (기존 지출 내역에서 이미 등록된 항목 및 기본 프리셋 병합)
INSERT INTO ledger_category (name, display_order, status)
VALUES
    ('식비', 1, 'ACTIVE'),
    ('외식/배달', 2, 'ACTIVE'),
    ('카페/간식', 3, 'ACTIVE'),
    ('교통/유류비', 4, 'ACTIVE'),
    ('비품/소모품', 5, 'ACTIVE'),
    ('쇼핑/생활', 6, 'ACTIVE'),
    ('통신/공과금', 7, 'ACTIVE'),
    ('기타', 8, 'ACTIVE')
ON CONFLICT (name) DO NOTHING;

INSERT INTO ledger_payment_source (name, display_order, status)
VALUES
    ('JW_롯데(LOCA)', 1, 'ACTIVE'),
    ('YH_신한(Y)', 2, 'ACTIVE'),
    ('JW_토스페이', 3, 'ACTIVE'),
    ('국민카드', 4, 'ACTIVE'),
    ('농협은행', 5, 'ACTIVE'),
    ('신한카드', 6, 'ACTIVE'),
    ('카카오뱅크', 7, 'ACTIVE'),
    ('토스뱅크', 8, 'ACTIVE'),
    ('현금', 9, 'ACTIVE')
ON CONFLICT (name) DO NOTHING;
