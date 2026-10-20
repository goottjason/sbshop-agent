import { apiClient } from './axios';

export interface LedgerExpense {
  id: number;
  spentDate: string; // YYYY-MM-DD
  amount: number;
  category: string;
  paymentSource: string; // e.g. 농협은행, 국민카드, 현금 등
  memo: string | null;
  adminUser: string; // JW, YH
  createdAt: string;
}

export interface ExpenseCreatePayload {
  spentDate: string;
  amount: number;
  category: string;
  paymentSource: string;
  memo?: string;
  adminUser: string;
}

export interface PeriodSummary {
  periodLabel: string;
  adminUser: string;
  totalAmount: number;
  budgetAmount: number;
  byCategory: Record<string, number>;
  byPaymentSource: Record<string, number>;
  byAdminUser: Record<string, number>;
  count: number;
}

export interface BudgetPayload {
  yearMonth: string;
  adminUser: string;
  targetAmount: number;
}

export interface BudgetResponse {
  yearMonth: string;
  adminUser: string;
  targetAmount: number;
}

// ─────────────────────────────────────────────────────────
// 로컬 스토리지 Fallback 레이어 (로컬 개발 환경 및 백엔드 미기동 시에도 UI 완전 체험 가능)
// ─────────────────────────────────────────────────────────
const STORAGE_KEY_EXPENSES = 'sbshop.ledger.expenses.v3';
const STORAGE_KEY_BUDGETS = 'sbshop.ledger.budgets.v3';
const STORAGE_KEY_SOURCES = 'sbshop.ledger.payment_sources.v3';
const STORAGE_KEY_CATEGORIES = 'sbshop.ledger.categories.v3';

const DEFAULT_SOURCES = [
  'JW_롯데(LOCA)',
  'YH_신한(Y)',
  'JW_토스페이',
  '국민카드',
  '농협은행',
  '신한카드',
  '카카오뱅크',
  '토스뱅크',
  '현금',
];
const DEFAULT_CATS = ['식비', '외식/배달', '카페/간식', '교통/유류비', '비품/소모품', '쇼핑/생활', '통신/공과금', '기타'];

const loadLocalPaymentSources = (): string[] => {
  try {
    const raw =
      localStorage.getItem(STORAGE_KEY_SOURCES) ||
      localStorage.getItem('sbshop.ledger.payment_sources.v2') ||
      localStorage.getItem('sbshop.ledger.payment_sources.v1');
    if (!raw) return DEFAULT_SOURCES;
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length > 0 ? parsed : DEFAULT_SOURCES;
  } catch {
    return DEFAULT_SOURCES;
  }
};

const loadLocalCategories = (): string[] => {
  try {
    const raw =
      localStorage.getItem(STORAGE_KEY_CATEGORIES) ||
      localStorage.getItem('sbshop.ledger.categories.v2') ||
      localStorage.getItem('sbshop.ledger.categories.v1');
    if (!raw) return DEFAULT_CATS;
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length > 0 ? parsed : DEFAULT_CATS;
  } catch {
    return DEFAULT_CATS;
  }
};

const getInitialMockExpenses = (): LedgerExpense[] => [
  {
    id: 1,
    spentDate: '2026-10-12',
    amount: 15000,
    category: '식비',
    paymentSource: '국민카드',
    memo: '스타벅스 커피 미팅',
    adminUser: 'JW',
    createdAt: '2026-10-12T10:00:00',
  },
  {
    id: 2,
    spentDate: '2026-10-12',
    amount: 45000,
    category: '비품/소모품',
    paymentSource: '농협은행',
    memo: '택배 포장용 박스 및 라벨지',
    adminUser: 'YH',
    createdAt: '2026-10-12T14:30:00',
  },
  {
    id: 3,
    spentDate: '2026-10-15',
    amount: 12500,
    category: '교통/유류비',
    paymentSource: '카카오뱅크',
    memo: '세무서 방문 택시비',
    adminUser: 'JW',
    createdAt: '2026-10-15T11:20:00',
  },
  {
    id: 4,
    spentDate: '2026-10-17',
    amount: 88000,
    category: '식비',
    paymentSource: '국민카드',
    memo: '구매대행 운영 정기 점심 회식',
    adminUser: 'YH',
    createdAt: '2026-10-17T12:40:00',
  },
  {
    id: 5,
    spentDate: '2026-10-19',
    amount: 6500,
    category: '카페/간식',
    paymentSource: '신한카드',
    memo: '오후 업무용 커피',
    adminUser: 'JW',
    createdAt: '2026-10-19T09:15:00',
  },
  {
    id: 6,
    spentDate: '2026-10-19',
    amount: 32000,
    category: '비품/소모품',
    paymentSource: '농협은행',
    memo: '사무실 복사용지 및 프린터 토너',
    adminUser: 'YH',
    createdAt: '2026-10-19T11:00:00',
  },
  {
    id: 7,
    spentDate: '2026-10-19',
    amount: 67800,
    category: '식비',
    paymentSource: '농협은행',
    memo: '이마트 공동 장보기 (생필품/식재료)',
    adminUser: 'CM',
    createdAt: '2026-10-19T18:30:00',
  },
  {
    id: 8,
    spentDate: '2026-10-19',
    amount: 5000,
    category: '식비',
    paymentSource: '현금',
    memo: '우체국 방문 후 간식',
    adminUser: 'JW',
    createdAt: '2026-10-19T14:10:00',
  },
];

const loadLocalExpenses = (): LedgerExpense[] => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_EXPENSES);
    if (!raw) {
      const init = getInitialMockExpenses();
      localStorage.setItem(STORAGE_KEY_EXPENSES, JSON.stringify(init));
      return init;
    }
    return JSON.parse(raw);
  } catch {
    return getInitialMockExpenses();
  }
};

const saveLocalExpenses = (items: LedgerExpense[]) => {
  localStorage.setItem(STORAGE_KEY_EXPENSES, JSON.stringify(items));
};

const loadLocalBudgets = (): Record<string, number> => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_BUDGETS);
    if (!raw) {
      const init = { '2026-10_ALL': 3500000, '2026-10_JW': 1000000, '2026-10_YH': 1000000, '2026-10_CM': 1500000 };
      localStorage.setItem(STORAGE_KEY_BUDGETS, JSON.stringify(init));
      return init;
    }
    return JSON.parse(raw);
  } catch {
    return { '2026-10_ALL': 2500000 };
  }
};

const saveLocalBudget = (yearMonth: string, adminUser: string, amount: number) => {
  const budgets = loadLocalBudgets();
  budgets[`${yearMonth}_${adminUser}`] = amount;
  localStorage.setItem(STORAGE_KEY_BUDGETS, JSON.stringify(budgets));
};

export const ledgerApi = {
  getExpenses: async (params: { startDate?: string; endDate?: string; adminUser?: string }) => {
    try {
      const { data } = await apiClient.get<LedgerExpense[]>('/api/v1/ledger/expenses', { params });
      return data;
    } catch {
      let list = loadLocalExpenses();
      if (params.startDate) {
        list = list.filter((e) => e.spentDate >= params.startDate!);
      }
      if (params.endDate) {
        list = list.filter((e) => e.spentDate <= params.endDate!);
      }
      if (params.adminUser && params.adminUser !== 'ALL') {
        list = list.filter((e) => e.adminUser === params.adminUser);
      }
      return list.sort((a, b) => b.spentDate.localeCompare(a.spentDate) || b.id - a.id);
    }
  },

  createExpense: async (payload: ExpenseCreatePayload) => {
    try {
      const { data } = await apiClient.post<LedgerExpense>('/api/v1/ledger/expenses', payload);
      return data;
    } catch {
      const list = loadLocalExpenses();
      const newId = list.length > 0 ? Math.max(...list.map((e) => e.id)) + 1 : 1;
      const newExpense: LedgerExpense = {
        id: newId,
        spentDate: payload.spentDate,
        amount: payload.amount,
        category: payload.category,
        paymentSource: payload.paymentSource,
        memo: payload.memo || null,
        adminUser: payload.adminUser,
        createdAt: new Date().toISOString(),
      };
      list.push(newExpense);
      saveLocalExpenses(list);
      return newExpense;
    }
  },

  updateExpense: async (id: number, payload: ExpenseCreatePayload) => {
    try {
      const { data } = await apiClient.put<LedgerExpense>(`/api/v1/ledger/expenses/${id}`, payload);
      return data;
    } catch {
      const list = loadLocalExpenses();
      const idx = list.findIndex((e) => e.id === id);
      if (idx !== -1) {
        list[idx] = {
          ...list[idx],
          spentDate: payload.spentDate,
          amount: payload.amount,
          category: payload.category,
          paymentSource: payload.paymentSource,
          memo: payload.memo || null,
          adminUser: payload.adminUser,
        };
        saveLocalExpenses(list);
        return list[idx];
      }
      throw new Error('Not found');
    }
  },

  deleteExpense: async (id: number) => {
    try {
      await apiClient.delete(`/api/v1/ledger/expenses/${id}`);
    } catch {
      const list = loadLocalExpenses().filter((e) => e.id !== id);
      saveLocalExpenses(list);
    }
  },

  getMonthlySummary: async (yearMonth: string, adminUser?: string) => {
    try {
      const { data } = await apiClient.get<PeriodSummary>('/api/v1/ledger/summary', {
        params: { yearMonth, adminUser },
      });
      return data;
    } catch {
      const list = loadLocalExpenses().filter((e) => e.spentDate.startsWith(yearMonth));
      const targetUser = adminUser && adminUser !== 'ALL' ? adminUser : 'ALL';
      const filtered = targetUser === 'ALL' ? list : list.filter((e) => e.adminUser === targetUser);

      let totalAmount = 0;
      const byCategory: Record<string, number> = {};
      const byPaymentSource: Record<string, number> = {};
      const byAdminUser: Record<string, number> = {};

      filtered.forEach((e) => {
        const amt = Number(e.amount) || 0;
        totalAmount += amt;
        byCategory[e.category] = (byCategory[e.category] || 0) + amt;
        byPaymentSource[e.paymentSource] = (byPaymentSource[e.paymentSource] || 0) + amt;
        byAdminUser[e.adminUser] = (byAdminUser[e.adminUser] || 0) + amt;
      });

      const budgets = loadLocalBudgets();
      const budgetAmount = budgets[`${yearMonth}_${targetUser}`] || (targetUser === 'ALL' ? 2500000 : 1250000);

      return {
        periodLabel: yearMonth,
        adminUser: targetUser,
        totalAmount,
        budgetAmount,
        byCategory,
        byPaymentSource,
        byAdminUser,
        count: filtered.length,
      };
    }
  },

  getBudget: async (yearMonth: string, adminUser: string = 'ALL') => {
    try {
      const { data } = await apiClient.get<BudgetResponse>('/api/v1/ledger/budget', {
        params: { yearMonth, adminUser },
      });
      return data;
    } catch {
      const budgets = loadLocalBudgets();
      const amt = budgets[`${yearMonth}_${adminUser}`] || (adminUser === 'ALL' ? 2500000 : 1250000);
      return { yearMonth, adminUser, targetAmount: amt };
    }
  },

  setBudget: async (payload: BudgetPayload) => {
    try {
      const { data } = await apiClient.post<BudgetResponse>('/api/v1/ledger/budget', payload);
      return data;
    } catch {
      saveLocalBudget(payload.yearMonth, payload.adminUser, payload.targetAmount);
      return { yearMonth: payload.yearMonth, adminUser: payload.adminUser, targetAmount: payload.targetAmount };
    }
  },

  // ─────────────────────────────────────────────────────────
  // 지출처(출금처/카드/계좌) 목록 관리 (가져오기 / 저장 / 수정 / 삭제 - DB 영구 저장)
  // ─────────────────────────────────────────────────────────
  getPaymentSources: async (): Promise<string[]> => {
    const local = loadLocalPaymentSources();
    try {
      const { data } = await apiClient.get<string[]>('/api/v1/ledger/payment-sources');
      if (Array.isArray(data) && data.length > 0) {
        // 만약 로컬에 서버에 없는 커스텀 항목이 있다면 병합하여 서버에 자동 동기화
        const missing = local.filter((item) => !data.includes(item));
        if (missing.length > 0) {
          const merged = [...data, ...missing];
          await apiClient.put<string[]>('/api/v1/ledger/payment-sources', merged).catch(() => {});
          localStorage.setItem(STORAGE_KEY_SOURCES, JSON.stringify(merged));
          return merged;
        }
        localStorage.setItem(STORAGE_KEY_SOURCES, JSON.stringify(data));
        return data;
      }
      return local;
    } catch {
      return local;
    }
  },

  savePaymentSources: async (sources: string[]): Promise<string[]> => {
    localStorage.setItem(STORAGE_KEY_SOURCES, JSON.stringify(sources));
    try {
      const { data } = await apiClient.put<string[]>('/api/v1/ledger/payment-sources', sources);
      return data;
    } catch {
      return sources;
    }
  },

  // ─────────────────────────────────────────────────────────
  // 분류(카테고리) 목록 관리 (가져오기 / 저장 / 수정 / 삭제 - DB 영구 저장)
  // ─────────────────────────────────────────────────────────
  getCategories: async (): Promise<string[]> => {
    const local = loadLocalCategories();
    try {
      const { data } = await apiClient.get<string[]>('/api/v1/ledger/categories');
      if (Array.isArray(data) && data.length > 0) {
        // 만약 로컬에 서버에 없는 커스텀 항목이 있다면 병합하여 서버에 자동 동기화
        const missing = local.filter((item) => !data.includes(item));
        if (missing.length > 0) {
          const merged = [...data, ...missing];
          await apiClient.put<string[]>('/api/v1/ledger/categories', merged).catch(() => {});
          localStorage.setItem(STORAGE_KEY_CATEGORIES, JSON.stringify(merged));
          return merged;
        }
        localStorage.setItem(STORAGE_KEY_CATEGORIES, JSON.stringify(data));
        return data;
      }
      return local;
    } catch {
      return local;
    }
  },

  saveCategories: async (categories: string[]): Promise<string[]> => {
    localStorage.setItem(STORAGE_KEY_CATEGORIES, JSON.stringify(categories));
    try {
      const { data } = await apiClient.put<string[]>('/api/v1/ledger/categories', categories);
      return data;
    } catch {
      return categories;
    }
  },
};
