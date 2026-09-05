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
  marketRegistrations?: Record<string, MarketBadgeState>;
  category?: string;
  stockStatus?: 'IN_STOCK' | 'OUT_OF_STOCK';
  /** 원본 소멸 사유. LINK_DEAD(링크 죽음) / DISCONTINUED(단종). null 이면 정상 */
  sourceGoneReason?: string | null;
  /** 원본 소멸을 처음 감지한 시각 — 언제부터 사라졌는지가 폐기 판단 근거다 */
  sourceGoneAt?: string | null;
  /** 마지막 크롤이 실패한 사유(봇차단·일시오류 등). null 이면 마지막 크롤 성공 */
  lastCrawlError?: string | null;
  /** 마지막 크롤 시도 시각 */
  lastCrawlAt?: string | null;
}

export type BadgeReason =
  | 'DELETED_ON_MARKET' | 'NEVER_SYNCED'
  | 'VALIDATION_FAILED' | 'TRANSIENT_ERROR' | 'BLOCKED_BY_MARKET';

export interface MarketBadgeState {
  status: 'SYNCED' | 'PENDING' | 'DELETED' | 'FAILED';
  url: string | null;
  reason: BadgeReason | null;
  errorAt: string | null;
}

export interface ProductPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface ProductQuery {
  page: number;
  size: number;
  keyword?: string;
  categories?: string[];
  includeUncategorized?: boolean;
  vendors?: string[];
  stockStatuses?: string[];
  markets?: string[];
  inStockOnly?: boolean;
  sourceGone?: 'GONE_ONLY' | 'ALIVE_ONLY';
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

interface MarketOutcome {
  market: string;
  label: string;
}
interface MarketFailure extends MarketOutcome {
  error: string;
}
interface ImageProcessFailure {
  ref: string;
  reason: string;
}
export interface ImageUploadResult {
  storageUpdated: boolean;
  synced: MarketOutcome[];
  skipped: MarketOutcome[];
  failed: MarketFailure[];
  imagesSucceeded: number;
  imagesFailed: ImageProcessFailure[];
}

export interface MarketItemInfo {
  isMasterData: boolean;
  mappingKey: string | null;
  marketIdentifiers: Record<string, string> | null;
  name: string | null;
  originalName: string | null;
  salePrice: number | null;
  stock: number | null;
  detailHtml: string | null;
  images: string[] | null;
  brand: string | null;
  manufacturer: string | null;
  barcode: string | null;
  generalProductName: string | null;
  rawData: Record<string, unknown> | null;
}

export interface MarketRegistrationRecord {
  id: number;
  status: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  productId: number;
  sbProductId: number | null;
  marketType: string;
  marketProductName: string | null;
  marketIdentifiers: Record<string, unknown>;
  marketDetailedInfo: Record<string, unknown>;
  isSynced: boolean | null;
  lastSyncedAt: string | null;
}

interface MarketPlusHandoff {
  market: string;
  cafe24ProductCode: string;
  marketplusUrl: string;
  guide: string;
}

export interface FieldSyncResponse {
  success: boolean;
  batchId: string;
  synced: string[];
  skipped: string[];
  failed: Record<string, string>;
}

export const productApi = {
  fetchProducts: (query: ProductQuery) =>
    apiClient.get<ProductPage<ProductList>>('/api/v1/products', { params: query }),

  fetchCategories: () =>
    apiClient.get<string[]>('/api/v1/products/categories'),

  fetchProductDetail: (id: number) =>
    apiClient.get(`/api/v1/products/${id}`),

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

  crawlAndUpload: (id: number) =>
    apiClient.post(`/api/v1/products/${id}/images/crawl-and-upload`),

  getMarketPlusHandoff: (id: number, marketType: string) =>
    apiClient.get<MarketPlusHandoff>(`/api/v1/products/${id}/markets/${marketType}/handoff`),

  getMarketRegistrations: (id: number) =>
    apiClient.get<MarketRegistrationRecord[]>(`/api/v1/products/${id}/markets`),

  getLocalMarketData: (id: number, marketType: string) =>
    apiClient.get<MarketRegistrationRecord>(`/api/v1/products/${id}/markets/${marketType}/local`),

  syncMarketLive: (id: number, marketType: string) =>
    apiClient.post<MarketItemInfo>(`/api/v1/products/${id}/markets/${marketType}/sync`),

  fieldSync: (id: number, fields: string[], markets: string[]) =>
    apiClient.post<FieldSyncResponse>(`/api/v1/products/${id}/field-sync`, { fields, markets }),
};
