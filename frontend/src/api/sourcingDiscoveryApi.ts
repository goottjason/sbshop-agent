import { apiClient } from './axios';

/** 통관 게이트 판정. REVIEW는 사용자가 성분을 확인하고 승인해야 등록할 수 있다. */
export type CustomsVerdict = 'PASS' | 'REVIEW' | 'BLOCKED' | 'UNKNOWN';

export interface Candidate {
  id: number;
  vendor: string;
  externalId: string;
  sourceUrl: string;
  brand: string | null;
  nameKo: string | null;
  categorySlug: string | null;
  imageUrl: string | null;
  listPrice: number | null;
  discountPrice: number | null;
  discountPct: number | null;
  rating: number | null;
  reviewCount: number | null;
  sales30d: number | null;
  rankPosition: number | null;
  monthlySearchVolume: number | null;
  competitorCount: number | null;
  domesticLowPrice: number | null;
  demandKeyword: string | null;
  customsVerdict: CustomsVerdict | null;
  customsReason: string | null;
  ingredientsRaw: string | null;
  totalScore: number | null;
  /** 서브스코어 근거 JSON 원문 — ScoreBreakdown 컴포넌트가 파싱한다. */
  scoreBreakdown: string | null;
  estimatedSalePrice: number | null;
  estimatedMarginRate: number | null;
  candidateStatus: string | null;
  excludeReason: string | null;
  discoveredAt: string | null;
  lastSeenAt: string | null;
}

/** scoreBreakdown JSON의 구조. */
export interface ScoreBreakdown {
  total: number;
  usableWeight: number;
  estimatedSalePrice: number;
  estimatedMargin: number;
  parts: Record<string, { score: number; weight: number; contribution: number }>;
  /** 신호가 없어 채점에서 빠진 항목 — 점수가 낮은 이유를 설명한다. */
  missing: string[];
}

export interface MarketDraft {
  id: number;
  marketType: string;
  productName: string | null;
  categoryId: string | null;
  categoryPath: string | null;
  salePrice: number | null;
  channelFeeRate: number | null;
  keywords: string | null;
  noticeFields: string | null;
  extraFields: string | null;
  /** 미충족 필수필드 JSON 배열. 비어 있어야 등록 가능. */
  missingFields: string | null;
  valid: boolean;
  enabled: boolean;
  publishError: string | null;
  marketIdentifiers: string | null;
}

export interface Draft {
  id: number;
  candidateId: number | null;
  baseNameKo: string | null;
  originalName: string | null;
  brand: string | null;
  bundleQty: number | null;
  marginRate: number | null;
  costPrice: number | null;
  sourceUrl: string | null;
  origin: string | null;
  hsCode: string | null;
  barcode: string | null;
  weightG: number | null;
  capacity: number | null;
  measureUnit: string | null;
  category: string | null;
  detailHtml: string | null;
  sourceImages: string | null;
  hostedImages: string | null;
  ingredientsKo: string | null;
  usageKo: string | null;
  cautionKo: string | null;
  customsAck: boolean;
  draftStatus: string | null;
  /** 인리치먼트 중 무엇이 실패했는지 — 화면에 그대로 띄운다. */
  enrichNote: string | null;
  productId: number | null;
  marketDrafts: MarketDraft[];
}

export interface DiscoveryStatus {
  running: boolean;
  lastRun:
    | {
        startedAt: string;
        finishedAt: string;
        crawled: number;
        created: number;
        updated: number;
        excluded: number;
        scored: number;
        customsBlocked: number;
        customsReview: number;
        cooldownReleased: number;
        warnings: string[];
      }
    | Record<string, never>;
}

export interface SourcingConfig {
  recommendCount: number;
  categories: string;
  pagesPerCategory: number;
  scoreWeights: string;
  profitGuardEnabled: boolean;
  targetMarginRate: number;
  minMarginPrice: number;
  maxPriceRatio: number;
  couponRate: number;
  rejectCooldownDays: number;
  excludeSponsored: boolean;
  minReviewCount: number;
  minRating: number;
  scheduleEnabled: boolean;
  scheduleCron: string;
}

export interface PublishOutcome {
  marketType: string;
  ok: boolean;
  identifiers: string | null;
  error: string | null;
}

export interface PublishResult {
  draftId: number;
  productId: number;
  sbCode: string;
  successCount: number;
  totalCount: number;
  outcomes: PublishOutcome[];
}

export interface MarketDraftPatch {
  marketType: string;
  productName?: string | null;
  categoryId?: string | null;
  categoryPath?: string | null;
  salePrice?: number | null;
  keywords?: string[] | null;
  enabled?: boolean | null;
}

export interface DraftPatch {
  baseNameKo?: string | null;
  bundleQty?: number | null;
  marginRate?: number | null;
  costPrice?: number | null;
  origin?: string | null;
  hsCode?: string | null;
  barcode?: string | null;
  weightG?: number | null;
  capacity?: number | null;
  measureUnit?: string | null;
  detailHtml?: string | null;
  customsAck?: boolean | null;
  marketDrafts?: MarketDraftPatch[];
}

const BASE = '/api/v1/sourcing';

export const sourcingDiscoveryApi = {
  runDiscovery: () => apiClient.post<{ message: string }>(`${BASE}/discovery/run`),
  discoveryStatus: () => apiClient.get<DiscoveryStatus>(`${BASE}/discovery/status`),

  candidates: (limit?: number, includeReview = true) =>
    apiClient.get<Candidate[]>(`${BASE}/candidates`, { params: { limit, includeReview } }),
  candidate: (id: number) => apiClient.get<Candidate>(`${BASE}/candidates/${id}`),
  customsBlocked: () => apiClient.get<Candidate[]>(`${BASE}/candidates/customs-blocked`),
  reject: (id: number) => apiClient.post<Candidate>(`${BASE}/candidates/${id}/reject`),

  createDrafts: (candidateIds: number[]) =>
    apiClient.post<{
      drafts: Draft[];
      failures: { candidateId: number; name: string; reason: string }[];
    }>(`${BASE}/drafts`, { candidateIds }),
  drafts: (status?: string[]) =>
    apiClient.get<Draft[]>(`${BASE}/drafts`, { params: { status } }),
  draft: (id: number) => apiClient.get<Draft>(`${BASE}/drafts/${id}`),
  updateDraft: (id: number, patch: DraftPatch) =>
    apiClient.patch<Draft>(`${BASE}/drafts/${id}`, patch),
  publishDraft: (id: number) => apiClient.post<PublishResult>(`${BASE}/drafts/${id}/publish`),

  config: () => apiClient.get<SourcingConfig>(`${BASE}/config`),
  updateConfig: (config: Partial<SourcingConfig>) =>
    apiClient.put<SourcingConfig>(`${BASE}/config`, config),

  syncBannedIngredients: () =>
    apiClient.post<{
      ok: boolean;
      created: number;
      updated: number;
      activeCount: number;
      syncedAt: string;
      error: string | null;
    }>(`${BASE}/customs/sync-banned`),
};

/** JSON 문자열 컬럼을 안전하게 파싱한다. 서버가 "[]"·null·깨진 값을 줄 수 있다. */
export function parseJsonField<T>(raw: string | null | undefined, fallback: T): T {
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}
