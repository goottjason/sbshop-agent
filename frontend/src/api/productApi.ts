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
  // D-047: 마켓별 등록상태 맵. 키는 백엔드 MarketType.name()
  // (COUPANG / SMART_STORE / ELEVEN_STREET / GMARKET / AUCTION / CAFE24).
  marketRegistrations?: Record<string, MarketBadgeState>;
  // 상품 카테고리(ProductCategory enum). 목록 API가 아직 미포함 → 값 없으면 '-'. 다음 세션 백엔드 확장.
  category?: string;
  // SP-B: 백엔드 StockStatus 열거값. 가격/재고 편집 시 현재 판매상태 시드에 사용.
  stockStatus?: 'IN_STOCK' | 'OUT_OF_STOCK';
}

// 마켓 배지 1칸의 서버 상태. 키가 없으면 그 마켓은 미등록(클릭하면 등록).
// status: 'SYNCED' 등록 완료 · 'PENDING' 등록행은 있으나 동기화 미완료.
// url: 마켓 상품페이지. 링크 식별자 미확보면 null.
export interface MarketBadgeState {
  status: 'SYNCED' | 'PENDING';
  url: string | null;
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

// 상세 모달 편집 대상 필드(평탄화). 백엔드 PATCH 미구현 → productMockApi로 모킹(다음 세션 구현).
export interface ProductEditFields {
  brand: string;
  productName: string;
  baseName: string;
  originalName: string;
  category: string;
  costPrice: number;
  salePrice: number;
  marginRate: number;
  stock: number;
  weight: number;
  bundleQuantity: number;
  barcode: string;
  capacity: number;
  measureUnit: string;
  vendor: string;
  manufacturer: string;
  origin: string;
  hsCode: string;
  sourceUrl: string;
  memo: string;
  detailHtml: string;
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
// F-PROD-12·F-PROD-16: 개별 이미지 처리(리사이즈/다운로드) 부분 실패. ref=원본 URL/파일명, reason=사유.
export interface ImageProcessFailure {
  ref: string;
  reason: string;
}
export interface ImageUploadResult {
  storageUpdated: boolean;
  synced: MarketOutcome[];
  skipped: MarketOutcome[];
  failed: MarketFailure[];
  // F-PROD-12·F-PROD-16: 저장에 성공한 이미지 장수와, 조용히 드롭되지 않은 개별 실패 목록.
  imagesSucceeded: number;
  imagesFailed: ImageProcessFailure[];
}

// D-060: 가격/재고 저장 시 마켓 동기화 결과(MarketRepublishResult 레코드).
// synced/skipped = MarketType.name() 배열, failed = {MARKET: 오류메시지}.
export interface PriceStockSyncResult {
  synced: string[];
  skipped: string[];
  failed: Record<string, string>;
}

export const productApi = {
  fetchProducts: (page: number, size: number, keyword?: string) =>
    apiClient.get('/api/v1/products', { params: { page, size, keyword } }),

  fetchProductDetail: (id: number) =>
    apiClient.get(`/api/v1/products/${id}`),

  // soldOut=null이면 재고상태는 변경하지 않고 가격만 반영한다(백엔드 F-PROD-7).
  updatePriceStock: (id: number, price: number, soldOut: boolean | null) =>
    apiClient.put(`/api/v1/products/${id}/price-stock`, { price, soldOut }),

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

  crawlAndUpload: (id: number) =>
    apiClient.post(`/api/v1/products/${id}/images/crawl-and-upload`),

  getMarketRegistrations: (id: number) =>
    apiClient.get(`/api/v1/products/${id}/markets`),

  getLocalMarketData: (id: number, marketType: string) =>
    apiClient.get(`/api/v1/products/${id}/markets/${marketType}/local`),

  syncMarketLive: (id: number, marketType: string) =>
    apiClient.post(`/api/v1/products/${id}/markets/${marketType}/sync`),
};
