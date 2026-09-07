import { apiClient } from './axios';
export interface FieldSyncItem {
  id: number | null; productId: number; sbCode: string | null; market: string; listingId: string | null; revision: number;
  fields: string[]; expectedValues: Record<string, string>; observedValues: Record<string, string>; state: string; detail: string;
  requiresApproval: boolean; writes: number; reads: number; nextRunAt: string | null; checkedAt: string | null;
}
export interface FieldSyncReview {
  id: string; actor: string; createdAt: string; expiresAt: string; committed: boolean; preparing: boolean;
  items: FieldSyncItem[]; total: number;
}
export interface FieldSyncAttempt { id: number; taskId: number; phase: string; detail: string; recordedAt: string; observedValues: string | null }
const root = '/api/v1/market-field-sync';
export const marketFieldSyncApi = {
  preview: (productIds: number[], markets: string[], fields: string[]) => apiClient.post<FieldSyncReview>(`${root}/reviews`, { productIds, markets, fields }),
  commit: (id: string, acceptApproval: boolean) => apiClient.post<FieldSyncReview>(`${root}/reviews/${id}/commit`, { acceptApproval }),
  get: (id: string, signal?: AbortSignal) => apiClient.get<FieldSyncReview>(`${root}/reviews/${id}`, { signal }),
  recent: (signal?: AbortSignal) => apiClient.get<FieldSyncReview[]>(`${root}/reviews`, { signal }),
  history: (id: number, signal?: AbortSignal) => apiClient.get<FieldSyncAttempt[]>(`${root}/tasks/${id}/history`, { signal }),
};
