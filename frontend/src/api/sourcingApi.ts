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

export const sourcingApi = {
  sourceFromIherb: (urls: string[]) =>
    apiClient.post('/api/v1/sourcing/iherb', urls),

  // POST /api/v1/products/bulk → 생성된 productId 목록(number[])
  saveProductsBulk: (products: Record<string, unknown>[]) =>
    apiClient.post('/api/v1/products/bulk', products),

  publishToMarket: (productId: number, marketType: string) =>
    apiClient.post(`/api/v1/products/${productId}/markets/${marketType}`),
};
