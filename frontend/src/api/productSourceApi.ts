import { apiClient } from './axios';
import type { EditCommit, EditReview } from './productChangeApi';

export type ProductSourceField = 'PRICE' | 'STOCK';
export interface ProductSourceValues {
  costPrice: number | null;
  exchangeRate: number | null;
  stockStatus: 'IN_STOCK' | 'OUT_OF_STOCK' | null;
  stock: number | null;
}

export interface ProductSourceSnapshot {
  id: string;
  productId: number;
  sbCode: string | null;
  revision: number;
  sourceUrl: string | null;
  vendor: string | null;
  state: 'QUEUED' | 'COLLECTING' | 'READY' | 'PARTIAL' | 'FAILED' | 'UNSUPPORTED';
  reason: string | null;
  requestedAt: string;
  collectedAt: string | null;
  expiresAt: string | null;
  appliedAt: string | null;
  current: ProductSourceValues;
  proposed: ProductSourceValues | null;
  fields: {
    field: ProductSourceField;
    available: boolean;
    editable: boolean;
    reason: string;
    collectedAt: string | null;
    appliedAt: string | null;
  }[];
  notices: string[];
}
export interface ProductSourceCollection {
  id: string;
  createdAt: string;
  items: ProductSourceSnapshot[];
}
export interface ProductSourceCollectionRequest { requestId: string; productIds: number[] }
export interface ProductSourceSelection { snapshotId: string; fields: ProductSourceField[] }

export const productSourceApi = {
  collect: (request: ProductSourceCollectionRequest) =>
    apiClient.post<ProductSourceCollection>('/api/v1/products/source-refresh/collections', request),
  collection: (id: string, signal?: AbortSignal) =>
    apiClient.get<ProductSourceCollection>(`/api/v1/products/source-refresh/collections/${encodeURIComponent(id)}`, { signal }),
  history: (id: number, signal?: AbortSignal) =>
    apiClient.get<ProductSourceSnapshot[]>(`/api/v1/products/source-refresh/${id}/history`, { signal }),
  review: (items: ProductSourceSelection[]) =>
    apiClient.post<EditReview>('/api/v1/products/source-refresh/reviews', { items }),
  commit: (reviewId: string) =>
    apiClient.post<EditCommit>('/api/v1/products/source-refresh/commit', { reviewId }),
};
