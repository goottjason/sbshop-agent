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

export interface IherbSourcingResponse {
  succeeded: SourcingResult[];
  failed: { url: string; reason: string }[];
}

export interface BulkProductCreateResponse {
  succeeded: { index: number; productId: number; sbCode: string }[];
  failed: { index: number; baseName: string; reason: string }[];
}

interface MarketPublishResponse {
  market: string;
  status: 'SYNCED' | 'PENDING';
  url: string | null;
  identifiers: Record<string, string>;
}

interface MarketPublishPriceParams {
  marginRate?: number;
  couponRate?: number;
  minMarginPrice?: number;
}

export const sourcingApi = {
  sourceFromIherb: (urls: string[]) =>
    apiClient.post('/api/v1/sourcing/iherb', urls),

  saveProductsBulk: (products: Record<string, unknown>[]) =>
    apiClient.post('/api/v1/products/bulk', products),

  publishToMarket: (productId: number, marketType: string, priceParams?: MarketPublishPriceParams) =>
    apiClient.post<MarketPublishResponse>(`/api/v1/products/${productId}/markets/${marketType}`, priceParams),
};
