import { apiClient } from './axios';

export interface ProductList {
  id: number;
  sbCode: string;
  brand: string;
  productName: string;
  baseName: string;
  originalName: string;
  vendor: string;
  salePrice: number;
  stock: number;
  repImageUrl: string;
  hostedImages: string[];
  sourcingUrl: string;
  memo: string;
  // D-047: 마켓별 연동코드 맵. 키는 백엔드 MarketType.name()
  // (COUPANG / SMART_STORE / ELEVEN_STREET / GMARKET / AUCTION / CAFE24).
  // 값은 마켓 상품코드(vendorItemId 우선), 없으면 내부 productId 폴백(= row.id).
  marketRegistrations?: Record<string, string>;
}

export interface ProductDetail {
  id: number;
  sbCode: string;
  brand: string;
  productName: string;
  baseName: string;
  originalName: string;
  category: string;
  vendor: string;
  priceInfo: { costPrice: number; salePrice: number; marginRate: number };
  logisticsInfo: { stock: number; weight: number; bundleQuantity: number };
  productSpec: { barcode: string; capacity: number; measureUnit: string };
  sourcingInfo: { vendor: string; sourceUrl: string; manufacturer: string; origin: string; hsCode: string };
  sourceImages: string[];
  hostedImages: string[];
  detailHtml: string;
  memo: string;
}

// D-049(반려 재수정): 이미지 업로드 응답. 자사 저장 성공 여부(storageUpdated)와
// 마켓별 재게시 결과(성공/스킵/실패)를 분리해 담는다. 부분 실패를 사용자에게 표면화하기 위함.
export interface MarketOutcome {
  market: string; // MarketType.name() (COUPANG / SMART_STORE / ...)
  label: string; // 한글 표기 (쿠팡 / 스마트스토어 / ...)
}
export interface MarketFailure extends MarketOutcome {
  error: string;
}
export interface ImageUploadResult {
  storageUpdated: boolean;
  synced: MarketOutcome[];
  skipped: MarketOutcome[];
  failed: MarketFailure[];
}

export const productApi = {
  fetchProducts: (page: number, size: number, keyword?: string) =>
    apiClient.get('/api/v1/products', { params: { page, size, keyword } }),

  fetchProductDetail: (id: number) =>
    apiClient.get(`/api/v1/products/${id}`),

  updatePriceStock: (id: number, price: number, stock: number) =>
    apiClient.put(`/api/v1/products/${id}/price-stock`, { price, stock }),

  uploadImages: (id: number, formData: FormData) =>
    apiClient.put(`/api/v1/products/${id}/images`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }),

  uploadImagesByUrl: (id: number, urls: string[]) =>
    apiClient.put(`/api/v1/products/${id}/images/by-url`, urls),

  updateProduct: (id: number, data: Record<string, unknown>) =>
    apiClient.put(`/api/v1/products/${id}`, data),

  deleteProduct: (id: number) =>
    apiClient.delete(`/api/v1/products/${id}`),

  crawlSourceImages: (id: number) =>
    apiClient.get(`/api/v1/products/${id}/images/crawl`),

  getMarketRegistrations: (id: number) =>
    apiClient.get(`/api/v1/products/${id}/markets`),

  getLocalMarketData: (id: number, marketType: string) =>
    apiClient.get(`/api/v1/products/${id}/markets/${marketType}/local`),

  syncMarketLive: (id: number, marketType: string) =>
    apiClient.post(`/api/v1/products/${id}/markets/${marketType}/sync`),
};
