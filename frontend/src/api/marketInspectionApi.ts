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
  dailyBatches: (id: string, signal?: AbortSignal) => apiClient.get<InspectionBatch[]>(`${root}/daily/${id}/batches`, { signal }),
  availability: (signal?: AbortSignal, market?: string) => apiClient.get<{ supported: boolean; accountVerified: boolean; detail: string }>(`${root}/availability`, { signal, params: market ? { market } : undefined }),
  recent: (signal?: AbortSignal) => apiClient.get<InspectionBatch[]>(root, { signal }),
  get: (id: string, signal?: AbortSignal) => apiClient.get<InspectionBatch>(`${root}/${id}`, { signal }),
  create: (productIds: number[], requestId: string, market?: string) => apiClient.post<InspectionBatch>(root, { productIds, requestId, market }),
  retry: (id: string, requestId: string) => apiClient.post<InspectionBatch>(`${root}/${id}/retry`, { requestId }),
};

// getRandomValues also works on the HTTP admin site, where randomUUID may be unavailable.
export function inspectionRequestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
  const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
