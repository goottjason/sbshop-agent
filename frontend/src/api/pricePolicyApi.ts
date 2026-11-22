import { apiClient } from './axios';

export interface PricePolicy {
  marginRate: number | null;
  couponRate: number | null;
  minMarginPrice: number | null;
  /** 마진 하한(D-360): 수수료를 뺀 순이익 ≥ min(판매가 × 하한율, 하한액). 비우면 쓰지 않는다. */
  marginFloorRate: number | null;
  marginFloorAmount: number | null;
}

export type PricePolicyForm = Record<keyof PricePolicy, string>;

/** 빈 칸은 null(정책 없음), 0은 0으로 보낸다 — 둘은 계산 결과가 다르다. */
export const toPolicyNumber = (value: string): number | null => {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
};

export const toPricePolicyPayload = (form: PricePolicyForm): PricePolicy => ({
  marginRate: toPolicyNumber(form.marginRate),
  couponRate: toPolicyNumber(form.couponRate),
  minMarginPrice: toPolicyNumber(form.minMarginPrice),
  marginFloorRate: toPolicyNumber(form.marginFloorRate),
  marginFloorAmount: toPolicyNumber(form.marginFloorAmount),
});

export const fetchPricePolicy = async (): Promise<PricePolicy> => {
  const { data } = await apiClient.get('/api/v1/price-policy');
  return data;
};

export const savePricePolicy = async (policy: PricePolicy): Promise<PricePolicy> => {
  const { data } = await apiClient.put('/api/v1/price-policy', policy);
  return data;
};
