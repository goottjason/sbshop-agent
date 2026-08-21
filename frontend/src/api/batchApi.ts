import { apiClient } from './axios';

export const batchApi = {
  crawlAndUpdate: (productIds: number[], marginRate: number, couponRate: number, minMarginPrice: number) =>
    apiClient.post('/api/v1/products/batch/crawl-and-update', {
      productIds, marginRate, couponRate, minMarginPrice,
    }),

  manualUpdate: (items: { productId: number; price?: number | null; stock?: number | null }[]) =>
    apiClient.post('/api/v1/products/batch/manual-update-price-stock', {
      items,
    }),

  updateBySupplier: (supplierCode: string, marginRate: number, couponRate: number, minMarginPrice: number) =>
    apiClient.post('/api/v1/products/batch/by-supplier', {
      supplierCode, marginRate, couponRate, minMarginPrice,
    }),

  getBatchStatus: (batchId: string) =>
    apiClient.get(`/api/v1/products/batch/status/${batchId}`),

  getBatchSummary: (batchId: string) =>
    apiClient.get(`/api/v1/products/batch/status/${batchId}/summary`),
};
