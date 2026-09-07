import { apiClient } from './axios';
export interface RegistrationCandidate { productId: number; sbCode: string | null; market: string; oldListingId: string | null; connectionState: string; reason: string; selectable: boolean }
export interface RegistrationTask {
  id: string; productId: number; sbCode: string; market: string; actor: string; state: string; detail: string;
  name: string; categoryId: string; categoryPath: string | null; price: number; quantity: number; image: string;
  listingId: string | null; expiresAt: string; createdAt: string; checkedAt: string | null; committed: boolean;
}
const root = '/api/v1/products/registrations';
export const marketPublicationApi = {
  candidates: (productIds: number[], markets: string[]) => apiClient.post<RegistrationCandidate[]>(`${root}/candidates`, { productIds, markets }),
  prepare: (selected: { productId: number; market: string }[]) => apiClient.post<{ prepared: RegistrationTask[]; excluded: RegistrationCandidate[] }>(`${root}/reviews`, { selected }),
  commit: (id: string) => apiClient.post<RegistrationTask>(`${root}/reviews/${id}/commit`),
  recent: (signal?: AbortSignal) => apiClient.get<RegistrationTask[]>(root, { signal }),
  recheck: (id: string, listingId: string) => apiClient.post<RegistrationTask>(`${root}/${id}/recheck`, { listingId }),
};
