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
}

export type UnsyncReason = 'DELETED_ON_MARKET' | 'VALIDATION_FAILED' | 'TRANSIENT_ERROR' | 'NEVER_SYNCED';

export interface MarketBadgeState {
  status: 'SYNCED' | 'PENDING' | 'DELETED' | 'FAILED';
  url: string | null;
  reason: UnsyncReason | null;
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
};
