import { apiClient } from './axios';

export interface MarketPlusFieldStage {
  code: string; detail: string; at: string | null; expectedValue: string | null; observedValue: string | null;
}
export interface MarketPlusFieldProgress {
  productId: number; sbCode: string; currentRevision: number; generatedAt: string;
  fields: { historyId: number; targetId: number; revision: number; currentRevision: boolean; savedAt: string;
    market: string; field: string; beforeValue: string | null; savedValue: string | null; shortened: boolean;
    cafe24: MarketPlusFieldStage; transmission: MarketPlusFieldStage; finalMarket: MarketPlusFieldStage }[];
  preparations: { market: string; sellerAccount: string | null; cafe24ProductNo: string | null;
    cafe24ProductCode: string | null; externalId: string; connectionState: string; automaticRetryAllowed: boolean;
    blockers: string[]; historyUrl: string; publicProductUrl: string | null }[];
  publicValues: { id: number; registrationId: number; productRevision: number; market: string; externalId: string; sellerAccount: string;
    capturedAt: string; recordedAt: string; values: Record<string, string>; quantityBasis: string; currentConnection: boolean; currentRevision: boolean }[];
  notices: string[]; truncated: boolean;
}
export const marketPlusFieldProgressApi = {
  get: (productId: number, signal?: AbortSignal) => apiClient.get<MarketPlusFieldProgress>(`/api/v1/products/${productId}/marketplus-field-progress`, { signal }),
};
