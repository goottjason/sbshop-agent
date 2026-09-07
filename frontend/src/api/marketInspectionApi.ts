import { apiClient } from './axios';

export interface InspectionItem {
  id: number; productId: number; sbCode: string | null; externalId: string | null; state: string; attempts: number;
  nextRunAt: string; code: string | null; detail: string | null; observedState: string | null; eventId: number | null;
}
export interface InspectionBatch {
  id: string; actor: string; source: string; market?: string; createdAt: string; total: number; pending: number;
  confirmed: number; detached: number; needsAttention: number; skipped: number; items: InspectionItem[];
}
export interface DailyInspectionStatus {
  enabled: boolean; accountVerified: boolean; schedule: string; nextDueAt: string | null; detail: string;
  latest: {
    id: string; date: string; state: string; enrolled: number; batchCount: number;
    startedAt: string; finishedAt: string | null;
    totals: { pending: number; confirmed: number; detached: number; needsAttention: number; skipped: number };
  } | null;
}
const root = '/api/v1/products/connection-inspections';
export const marketInspectionApi = {
  daily: (signal?: AbortSignal) => apiClient.get<DailyInspectionStatus>(`${root}/daily`, { signal }),
  dailyMarkets: (signal?: AbortSignal) => apiClient.get<{ market: string; status: DailyInspectionStatus }[]>(`${root}/daily/markets`, { signal }),
  dailyBatches: (id: string, signal?: AbortSignal) => apiClient.get<InspectionBatch[]>(`${root}/daily/${id}/batches`, { signal }),
  availability: (signal?: AbortSignal, market?: string) => apiClient.get<{ supported: boolean; accountVerified: boolean; detail: string }>(`${root}/availability`, { signal, params: market ? { market } : undefined }),
  recent: (signal?: AbortSignal) => apiClient.get<InspectionBatch[]>(root, { signal }),
  get: (id: string, signal?: AbortSignal) => apiClient.get<InspectionBatch>(`${root}/${id}`, { signal }),
  create: (productIds: number[], requestId: string, market?: string) => apiClient.post<InspectionBatch>(root, { productIds, requestId, market }),
  retry: (id: string, requestId: string) => apiClient.post<InspectionBatch>(`${root}/${id}/retry`, { requestId }),
};

export { createRequestId as inspectionRequestId } from '../utils/requestId';
