import { apiClient } from './axios';
export interface PriceSyncItem {
  id: number | null; productId: number; sbCode: string | null; market: string; listingId: string | null; revision: number;
  expectedPrice: number | null; observedPrice: number | null; state: string; detail: string; writes: number; reads: number;
  nextRunAt: string | null; checkedAt: string | null;
}
export interface PriceSyncReview {
  id: string; actor: string; createdAt: string; expiresAt: string; committed: boolean; total?: number; items: PriceSyncItem[];
}
const root = '/api/v1/products/price-sync';
export const marketPriceSyncApi = {
  preview: (productIds: number[], markets: string[]) => apiClient.post<PriceSyncReview>(`${root}/reviews`, { productIds, markets }),
  commit: (id: string) => apiClient.post<PriceSyncReview>(`${root}/reviews/${id}/commit`),
  get: (id: string, signal?: AbortSignal) => apiClient.get<PriceSyncReview>(`${root}/reviews/${id}`, { signal }),
  recent: (signal?: AbortSignal) => apiClient.get<PriceSyncReview[]>(`${root}/reviews`, { signal }),
  history: (id: number, signal?: AbortSignal) => apiClient.get<{ id: number; phase: string; detail: string; recordedAt: string }[]>(`${root}/tasks/${id}/history`, { signal }),
};
