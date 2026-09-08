import { apiClient } from './axios';
export interface StockSyncItem {
  id: number | null; productId: number; sbCode: string | null; market: string; listingId: string | null; revision: number;
  expectedQuantity: number | null; observedQuantity: number | null; state: string; detail: string; writes: number; reads: number;
  nextRunAt: string | null; checkedAt: string | null;
  observedSaleState?: string | null; observedStockState?: string | null;
}
export interface StockSyncReview {
  id: string; actor: string; createdAt: string; expiresAt: string; committed: boolean; total?: number; items: StockSyncItem[];
}
const root = '/api/v1/market-stock-sync';
export const marketStockSyncApi = {
  preview: (productIds: number[], markets: string[]) => apiClient.post<StockSyncReview>(`${root}/reviews`, { productIds, markets }),
  commit: (id: string) => apiClient.post<StockSyncReview>(`${root}/reviews/${id}/commit`),
  get: (id: string, signal?: AbortSignal) => apiClient.get<StockSyncReview>(`${root}/reviews/${id}`, { signal }),
  recent: (signal?: AbortSignal) => apiClient.get<StockSyncReview[]>(`${root}/reviews`, { signal }),
  history: (id: number, signal?: AbortSignal) => apiClient.get<{ id: number; phase: string; detail: string; recordedAt: string }[]>(`${root}/tasks/${id}/history`, { signal }),
};
