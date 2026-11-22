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
  /** 배대지 요금표(원화, D-360). 기본 금액이 비어 있거나 0이면 배대지 배송비를 더하지 않는다. */
  forwarderBaseAmount: number | null;
  forwarderBaseWeightG: number | null;
  forwarderStepAmount: number | null;
  forwarderStepWeightG: number | null;
  forwarderPackagingG: number | null;
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
