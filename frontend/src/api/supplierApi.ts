import { apiClient } from './axios';

export const supplierApi = {
  getSuppliers: () => apiClient.get('/api/v1/suppliers'),
  createSupplier: (supplierCode: string, supplierName: string, currencyCode: string) =>
    apiClient.post('/api/v1/suppliers', { supplierCode, supplierName, currencyCode }),
  getCurrencies: () => apiClient.get('/api/v1/currencies'),
  createCurrency: (currencyCode: string, exchangeRate: number) =>
    apiClient.post('/api/v1/currencies', { currencyCode, exchangeRate }),
};
