import { apiClient } from './axios';

export interface MarketPlusObservation {
  id: number; market: string; externalId: string; sellerAccount: string; transferType: string;
  outcome: 'SUCCESS' | 'FAILURE'; reasonCode: string; detail: string;
  requestedAt: string; completedAt: string; capturedAt: string; actor: string;
}
export interface MarketPlusHistoryItem { observation: MarketPlusObservation; currentConnection: boolean }
export interface MarketPlusReadiness { ready: boolean; reasons: string[] }
export interface MarketPlusObserverStatus {
  state: 'NOT_CONFIGURED' | 'UNAVAILABLE' | 'PAUSED' | 'RUNNING' | 'ATTENTION' | 'OBSERVED_PARTIAL' | 'STALE';
  message: string; collectEnabled: boolean; uploadEnabled: boolean; heartbeatAt: string | null;
  lastCollectedAt: string | null; lastUploadedAt: string | null; nextAttemptAt: string | null;
  pendingFiles: number; rejectedFiles: number; blockedFiles: number;
}
export type MarketPlusIssueFilter = 'ALL' | 'ANY_ISSUE' | 'FAILURE' | 'CONFLICT';
export interface MarketPlusImportResult {
  saved: number; duplicate: number; rejected: number;
  items: { row: number; page?: number; externalId: string; productId: number | null; result: string; detail: string; retryable?: boolean }[];
}
export const marketPlusTransmissionApi = {
  observerStatus: () => apiClient.get<MarketPlusObserverStatus>('/api/v1/marketplus/observer/status'),
  readiness: () => apiClient.get<MarketPlusReadiness>('/api/v1/marketplus/transmissions/readiness'),
  history: (productId: number, signal?: AbortSignal) => apiClient.get<MarketPlusHistoryItem[]>(`/api/v1/products/${productId}/marketplus-transmissions`, { signal }),
  import: (payload: unknown) => apiClient.post<MarketPlusImportResult>('/api/v1/marketplus/transmissions/import', payload),
};
