-- 가계부 지출 내역 테이블
CREATE TABLE IF NOT EXISTS ledger_expense (
    id BIGSERIAL PRIMARY KEY,
    spent_date DATE NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    category VARCHAR(50) NOT NULL,
    payment_source VARCHAR(100) NOT NULL,
    memo TEXT,
    admin_user VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ledger_expense_date ON ledger_expense(spent_date);
CREATE INDEX IF NOT EXISTS idx_ledger_expense_admin ON ledger_expense(admin_user);
CREATE INDEX IF NOT EXISTS idx_ledger_expense_status ON ledger_expense(status);

-- 가계부 월별 예산 테이블
CREATE TABLE IF NOT EXISTS ledger_budget (
    id BIGSERIAL PRIMARY KEY,
    year_month VARCHAR(7) NOT NULL,
    admin_user VARCHAR(50) NOT NULL DEFAULT 'ALL',
    target_amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uk_ledger_budget UNIQUE(year_month, admin_user)
);

CREATE INDEX IF NOT EXISTS idx_ledger_budget_ym ON ledger_budget(year_month);
