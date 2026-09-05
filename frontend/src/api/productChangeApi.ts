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
