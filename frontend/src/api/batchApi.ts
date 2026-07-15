import { apiClient } from './axios';

export const batchApi = {
  crawlAndUpdate: (productIds: number[], marginRate: number, couponRate: number, minMarginPrice: number) =>
    apiClient.post('/api/v1/products/batch/crawl-and-update', {
      productIds, marginRate, couponRate, minMarginPrice,
    }),

  // F-BATCH-M1: productId·price·stock을 쌍(items)으로 전송해 값이 엉뚱한 상품에 적용되는 오염을 차단한다.
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
