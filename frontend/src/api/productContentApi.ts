import { apiClient } from './axios';
import type { EditCommit, EditReview } from './productChangeApi';

export type ProductContentField = 'IMAGES' | 'DETAIL_HTML';
export interface ProductContentValues {
  sourceImages: string[];
  hostedImages: string[];
  detailHtml: string | null;
}
export interface ProductContentSnapshot {
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
  current: ProductContentValues;
  proposed: ProductContentValues | null;
  fields: {
    field: ProductContentField;
    available: boolean;
    editable: boolean;
    reason: string;
    collectedAt: string | null;
    appliedAt: string | null;
  }[];
  notices: string[];
}
export interface ProductContentCollection {
  id: string;
  createdAt: string;
  items: ProductContentSnapshot[];
}
export interface ProductContentCollectionRequest { requestId: string; productIds: number[] }
export interface ProductContentSelection { snapshotId: string; fields: ProductContentField[] }

export const productContentApi = {
  collect: (request: ProductContentCollectionRequest) =>
    apiClient.post<ProductContentCollection>('/api/v1/products/content/collections', request),
  collection: (id: string, signal?: AbortSignal) =>
    apiClient.get<ProductContentCollection>(`/api/v1/products/content/collections/${encodeURIComponent(id)}`, { signal }),
  history: (id: number, signal?: AbortSignal) =>
    apiClient.get<ProductContentSnapshot[]>(`/api/v1/products/content/${id}/history`, { signal }),
  review: (items: ProductContentSelection[]) =>
    apiClient.post<EditReview>('/api/v1/products/content/reviews', { items }),
  commit: (reviewId: string) =>
    apiClient.post<EditCommit>('/api/v1/products/content/commit', { reviewId }),
};
