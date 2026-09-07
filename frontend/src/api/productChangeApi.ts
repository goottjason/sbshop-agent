import { apiClient } from './axios';

export type NumericField = 'SALE_PRICE' | 'COST_PRICE' | 'EXCHANGE_RATE' | 'DELIVERY_FEE' | 'MIN_MARGIN_PRICE'
  | 'MARGIN_RATE' | 'COUPON_RATE' | 'STOCK' | 'WEIGHT' | 'BUNDLE_QUANTITY' | 'CAPACITY';
export type ChangeOperation = 'SET' | 'ADD' | 'PERCENT';
export interface NumericFieldOption {
  field: NumericField;
  label: string;
  unit: string;
  scale: number;
  operations: ChangeOperation[];
}
export interface NumericPreviewRequest {
  productIds: number[];
  changes: { field: NumericField; operation: ChangeOperation; value: string }[];
  fractionPolicy: 'REJECT' | 'APPLY_FIELD_RULES';
}
export interface NumericPreviewResult {
  mode: 'READ_ONLY';
  generatedAt: string;
  total: number;
  valid: number;
  unchanged: number;
  invalid: number;
  notFound: number;
  items: {
    productId: number;
    sbCode: string | null;
    status: 'VALID' | 'UNCHANGED' | 'INVALID' | 'NOT_FOUND';
    marketCheck: 'NOT_REQUIRED' | 'REQUIRED';
    markets: string[];
    notes: string[];
    fields: {
      field: NumericField;
      before: string | null;
      calculated: string | null;
      after: string | null;
      rounded: boolean;
      status: 'VALID' | 'UNCHANGED' | 'INVALID';
      reason: string | null;
    }[];
  }[];
}

export interface ProductPricePreviewResult {
  mode: 'READ_ONLY';
  productId: number;
  sbCode: string;
  generatedAt: string;
  items: {
    market: string;
    status: 'CALCULATED' | 'FALLBACK' | 'FAILED';
    roundedPrice: string | null;
    minimumPrice: string | null;
    salePrice: string | null;
    minimumAdjusted: boolean;
    reason: string;
  }[];
}

export const productChangeApi = {
  pricePreview: (productId: number, signal?: AbortSignal) =>
    apiClient.get<ProductPricePreviewResult>(`/api/v1/products/${productId}/price-preview`, { signal }),
  fields: (signal?: AbortSignal) => apiClient.get<NumericFieldOption[]>('/api/v1/products/changes/numeric-preview/fields', { signal }),
  preview: (request: NumericPreviewRequest, signal?: AbortSignal) =>
    apiClient.post<NumericPreviewResult>('/api/v1/products/changes/numeric-preview', request, { signal }),
};

export interface EditConnection {
  registrationId: number;
  market: string;
  externalId: string | null;
  state: 'RECORDED' | 'REVIEW_REQUIRED' | 'REGISTRATION_UNCONFIRMED';
  reason: string;
}
export interface EditWorkspace {
  productId: number;
  revision: number;
  fields: { field: string; permission: 'EDITABLE' | 'INTERNAL' | 'LOCKED' | 'VERIFICATION_REQUIRED'; reason: string }[];
  connections: EditConnection[];
}
export interface EditChange { field: string; before: string | null; after: string | null; derived: boolean }
export interface EditReview {
  reviewId: string;
  expiresAt: string;
  items: {
    productId: number; sbCode: string | null; revision: number;
    state: 'READY' | 'UNCHANGED' | 'EXCLUDED' | 'NOT_FOUND';
    changes: EditChange[]; connections: EditConnection[];
    prices: { market: string; minimumPrice: string; salePrice: string }[];
    reasons: string[]; notices?: string[];
  }[];
}
export interface EditCommit {
  reviewId: string;
  items: { productId: number; sbCode: string | null; state: string; historyId: number | null; reason: string }[];
}
export interface EditHistory {
  id: number; beforeRevision: number; afterRevision: number; actor: string; createdAt: string;
  changes: EditChange[]; targets: { id: number; market: string; state: string }[];
}
export const productEditApi = {
  workspace: (id: number, signal?: AbortSignal) => apiClient.get<EditWorkspace>(`/api/v1/products/${id}/edit-workspace`, { signal }),
  history: (id: number, signal?: AbortSignal) => apiClient.get<EditHistory[]>(`/api/v1/products/${id}/change-history`, { signal }),
  preview: (request: NumericPreviewRequest, signal?: AbortSignal) => apiClient.post<EditReview>('/api/v1/products/changes/preview', request, { signal }),
  previewSingle: (id: number, expectedRevision: number, values: Record<string, unknown>) => apiClient.post<EditReview>(`/api/v1/products/${id}/changes/preview`, { expectedRevision, values }),
  commit: (reviewId: string) => apiClient.post<EditCommit>('/api/v1/products/changes/commit', { reviewId }),
};
