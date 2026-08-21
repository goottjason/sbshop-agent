import { apiClient } from './axios';

export interface PricePolicy {
  marginRate: number | null;
  couponRate: number | null;
  minMarginPrice: number | null;
}

export const fetchPricePolicy = async (): Promise<PricePolicy> => {
  const { data } = await apiClient.get('/api/v1/price-policy');
  return data;
};

export const savePricePolicy = async (policy: PricePolicy): Promise<PricePolicy> => {
  const { data } = await apiClient.put('/api/v1/price-policy', policy);
  return data;
};
