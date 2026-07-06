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
};
