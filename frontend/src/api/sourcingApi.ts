import { apiClient } from './axios';

export interface SourcingResult {
  sourceUrl: string;
  baseName: string;
  originalName: string;
  brand: string;
  costPrice: number;
  sourceImages: string[];
  isAvailable: boolean;
  capacity: number;
  unit: string;
}

// F-PSRC-2: iHerb 소싱 응답 — 성공 상품 + 실패 URL(사유) 집계.
export interface IherbSourcingResponse {
  succeeded: SourcingResult[];
  failed: { url: string; reason: string }[];
}

// F-PSRC-6: 대량 등록 응답 — 성공/실패 항목 집계.
export interface BulkProductCreateResponse {
  succeeded: { index: number; productId: number; sbCode: string }[];
  failed: { index: number; baseName: string; reason: string }[];
}

// F-PSRC-9: 마켓 등록 응답 — 등록 상태·상품 URL·마켓별 식별자.
export interface MarketPublishResponse {
  market: string;
  status: 'SYNCED' | 'PENDING';
  url: string | null;
  identifiers: Record<string, string>;
}

export const sourcingApi = {
  sourceFromIherb: (urls: string[]) =>
    apiClient.post('/api/v1/sourcing/iherb', urls),

  // POST /api/v1/products/bulk → 생성된 productId 목록(number[])
  saveProductsBulk: (products: Record<string, unknown>[]) =>
    apiClient.post('/api/v1/products/bulk', products),

  publishToMarket: (productId: number, marketType: string) =>
    apiClient.post<MarketPublishResponse>(`/api/v1/products/${productId}/markets/${marketType}`),
};
