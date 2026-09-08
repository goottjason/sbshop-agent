import { apiClient } from './axios';

export type SupplierBatchMode = 'PRICE_STOCK' | 'PRICE' | 'STOCK';
export type SupplierBatchMarket = 'COUPANG' | 'ELEVEN_STREET' | 'SMART_STORE' | 'CAFE24';
export type SupplierBatchStageKind = 'CRAWL' | 'DB' | 'MARKET';
export type SupplierBatchField = 'PRICE' | 'STOCK';
export type SupplierBatchItemFilter = 'ALL' | 'FAILED' | 'BLOCKED' | 'PENDING' | 'SUCCEEDED';
export interface SupplierBatchPolicy { marginRate: number; couponRate: number; minMarginPrice: number }
export interface SupplierBatchOptions {
  vendors: { vendor: string; label: string; productCount: number; defaults: { marginRate: number | null; couponRate: number | null; minMarginPrice: number | null } }[];
  markets: { market: SupplierBatchMarket; label: string }[];
  supportedVendors: string[];
}
export interface SupplierBatchCreate extends SupplierBatchPolicy {
  requestId: string; vendor: string; mode: SupplierBatchMode; markets: SupplierBatchMarket[];
}
export interface SupplierBatchRun {
  id: string; vendor: string; mode: SupplierBatchMode; actor: string;
  state: 'RUNNING' | 'PAUSING' | 'PAUSED' | 'COMPLETED';
  createdAt: string; updatedAt: string; finishedAt: string | null;
  policy: SupplierBatchPolicy; markets: SupplierBatchMarket[];
  total: number; processed: number; succeeded: number; failed: number; blocked: number; pending: number;
  inFlight: number; nextRunAt: string | null;
}
export interface SupplierBatchStage {
  id: number; stage: SupplierBatchStageKind; market?: SupplierBatchMarket | null; field?: SupplierBatchField | null;
  state: 'WAITING' | 'RUNNING' | 'SUCCEEDED' | 'UNCHANGED' | 'FAILED' | 'BLOCKED' | 'SKIPPED';
  detail: string | null; retryable: boolean; attempts: number; referenceId?: string | null;
  expected?: string | null; observed?: string | null;
  startedAt?: string | null; finishedAt?: string | null; nextRunAt?: string | null;
}
export interface SupplierBatchItem {
  id: number; productId: number; sbCode: string; productName: string; thumbnailUrl: string | null;
  state: string; detail: string | null; attempts: number; sourceSnapshotId: string | null;
  editReviewId: string | null; stages: SupplierBatchStage[];
}
export interface SupplierBatchPage<T> { content: T[]; number: number; size: number; totalElements: number; totalPages: number }
export interface SupplierBatchRetry {
  requestId: string; itemId?: number; stage?: SupplierBatchStageKind; market?: SupplierBatchMarket; field?: SupplierBatchField;
}
export interface SupplierBatchRetryOptions { retryableStageCounts: Record<SupplierBatchStageKind, number>; retryableProducts: number; blockedStageCount: number }
export interface SupplierBatchItemDetail {
  item: SupplierBatchItem;
  history: { id: number; stage: SupplierBatchStageKind; market?: SupplierBatchMarket | null; field?: SupplierBatchField | null;
    state: string; detail: string | null; recordedAt: string }[];
  priceCalculation?: {
    costPrice: number | string | null; exchangeRate: number | string | null; policy: SupplierBatchPolicy;
    pricingEvidence?: { sourcePrice: number | string | null; currency: string | null; observedExchangeRate: number | string | null;
      normalizedExchangeRate: number | string | null; goodsPriceKrw: number | string | null } | null;
    prices: { market: SupplierBatchMarket; minimumPrice: number | string | null; salePrice: number | string | null }[];
    notices: string[];
  } | null;
}

const base = '/api/v1/supplier-batches';
export const supplierBatchApi = {
  options: (signal?: AbortSignal) => apiClient.get<SupplierBatchOptions>(`${base}/options`, { signal }),
  create: (body: SupplierBatchCreate) => apiClient.post<SupplierBatchRun>(base, body),
  runs: (page: number, size: number, signal?: AbortSignal) => apiClient.get<SupplierBatchPage<SupplierBatchRun>>(base, { params: { page, size }, signal }),
  run: (id: string, signal?: AbortSignal) => apiClient.get<SupplierBatchRun>(`${base}/${encodeURIComponent(id)}`, { signal }),
  items: (id: string, page: number, size: number, keyword: string, filter: SupplierBatchItemFilter, signal?: AbortSignal) =>
    apiClient.get<SupplierBatchPage<SupplierBatchItem>>(`${base}/${encodeURIComponent(id)}/items`, { params: { page, size, keyword, filter }, signal }),
  detail: (id: string, itemId: number, signal?: AbortSignal) =>
    apiClient.get<SupplierBatchItemDetail>(`${base}/${encodeURIComponent(id)}/items/${itemId}`, { signal }),
  pause: (id: string) => apiClient.post<SupplierBatchRun>(`${base}/${encodeURIComponent(id)}/pause`),
  resume: (id: string) => apiClient.post<SupplierBatchRun>(`${base}/${encodeURIComponent(id)}/resume`),
  retry: (id: string, body: SupplierBatchRetry) => apiClient.post<SupplierBatchRun>(`${base}/${encodeURIComponent(id)}/retry`, body),
  retryOptions: (id: string, signal?: AbortSignal) => apiClient.get<SupplierBatchRetryOptions>(`${base}/${encodeURIComponent(id)}/retry-options`, { signal }),
};
