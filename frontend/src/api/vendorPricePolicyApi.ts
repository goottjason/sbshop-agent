import { apiClient } from './axios';

export interface VendorPricePolicy {
  vendor: string;
  marginRate: number | null;
  couponRate: number | null;
  minMarginPrice: number | null;
  shipCurrency: string | null;
  shipBaseAmount: number | null;
  shipBaseWeightG: number | null;
  shipStepAmount: number | null;
  shipStepWeightG: number | null;
  domesticFee: number | null;
  domesticFreeOver: number | null;
}

export const fetchVendorPricePolicies = async (): Promise<VendorPricePolicy[]> => {
  const { data } = await apiClient.get('/api/v1/vendor-price-policy');
  return data;
};

export const saveVendorPricePolicy = async (
  vendor: string,
  policy: Omit<VendorPricePolicy, 'vendor'>
): Promise<VendorPricePolicy> => {
  const { data } = await apiClient.put(`/api/v1/vendor-price-policy/${vendor}`, policy);
  return data;
};
