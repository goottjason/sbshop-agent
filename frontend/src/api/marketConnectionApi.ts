import { apiClient } from './axios';

export interface ProductConnection {
  registrationId: number; revision: number; market: string; externalId: string | null;
  state: 'LINKED' | 'DETACHED_DELETED' | 'DETACHED_PROHIBITED'; inspectionSupported: boolean; writeBlock: string | null;
}
export interface ConnectionEvent {
  id: number; market: string; externalId: string; actor: string; source: string; result: string;
  observedState: string; observedAt: string; recordedAt: string; evidence: string;
}
export interface ConnectionResult { eventId: number; result: string; state: string; detail: string }
const root = (id: number) => `/api/v1/products/${id}/connections`;
export const marketConnectionApi = {
  list: (id: number, signal?: AbortSignal) => apiClient.get<ProductConnection[]>(root(id), { signal }),
  history: (id: number, signal?: AbortSignal) => apiClient.get<ConnectionEvent[]>(`${root(id)}/history`, { signal }),
  inspect: (id: number, row: ProductConnection) => apiClient.post<ConnectionResult>(`${root(id)}/${row.registrationId}/${row.market}/inspect`),
  prohibit: (id: number, row: ProductConnection, sellerAccount: string, reason: string) => apiClient.post<ConnectionResult>(`${root(id)}/${row.registrationId}/${row.market}/prohibition`, {
    expectedRevision: row.revision, externalId: row.externalId, sellerAccount, reason, confirmedPermanent: true,
  }),
};
