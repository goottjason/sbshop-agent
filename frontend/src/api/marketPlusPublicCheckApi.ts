import { apiClient } from './axios';
export interface PublicCheckRequest { requestId: string; productIds: number[]; markets: string[] }
export interface PublicCheckItem {
  id: number; productId: number; sbCode: string | null; market: string; state: string; reason: string; attempts: number;
  nextRunAt: string | null; checkedAt: string | null; observationId: number | null; values: Record<string, string>; events: string[];
}
export interface PublicCheckCollection { id: string; requestId: string; createdAt: string; items: PublicCheckItem[] }
const root = '/api/v1/products/marketplus-public-checks/collections';
export const marketPlusPublicCheckApi = {
  create: (request: PublicCheckRequest) => apiClient.post<PublicCheckCollection>(root, request),
  get: (id: string, signal?: AbortSignal) => apiClient.get<PublicCheckCollection>(`${root}/${id}`, { signal }),
  recent: (signal?: AbortSignal) => apiClient.get<PublicCheckCollection[]>(root, { signal }),
};
