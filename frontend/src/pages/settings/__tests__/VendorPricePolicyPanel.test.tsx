import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import VendorPricePolicyPanel from '../VendorPricePolicyPanel';
import {
  fetchVendorPricePolicies,
  saveVendorPricePolicy,
  type VendorPricePolicy,
} from '../../../api/vendorPricePolicyApi';

vi.mock('../../../api/vendorPricePolicyApi', () => ({
  fetchVendorPricePolicies: vi.fn(),
  saveVendorPricePolicy: vi.fn(),
}));

const IHB: VendorPricePolicy = {
  vendor: 'IHB', marginRate: 4, couponRate: 0, minMarginPrice: 0, shipCurrency: 'KRW',
  shipBaseAmount: 0, shipBaseWeightG: 0, shipStepAmount: 0, shipStepWeightG: 0,
  domesticFee: 6000, domesticFreeOver: 40000,
  forwarderBaseAmount: null, forwarderBaseWeightG: null, forwarderStepAmount: null,
  forwarderStepWeightG: null, forwarderPackagingG: null,
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchVendorPricePolicies).mockResolvedValue([IHB]);
  vi.mocked(saveVendorPricePolicy).mockResolvedValue(IHB);
});

const renderPanel = () => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
    <VendorPricePolicyPanel />
  </QueryClientProvider>,
);

const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

it('배대지 요금표 다섯 칸을 숫자로 저장하고, 국내 배송비 0은 빈 값(null)과 구분해 0으로 보낸다', async () => {
  renderPanel();
  await screen.findByLabelText('기본 금액 (원)');

  type('기본 금액 (원)', '10500');
  type('기본 무게 (g)', '500');
  type('단계 금액 (원)', '2600');
  type('단계 무게 (g)', '500');
  type('포장 가산 (g)', '200');
  type('국내 배송비 (원)', '0');
  type('무료 기준액 (원)', '0');
  fireEvent.click(screen.getByRole('button', { name: 'IHB 정책 저장' }));

  await waitFor(() => expect(saveVendorPricePolicy).toHaveBeenCalledTimes(1));
  expect(saveVendorPricePolicy).toHaveBeenCalledWith('IHB', expect.objectContaining({
    forwarderBaseAmount: 10500, forwarderBaseWeightG: 500, forwarderStepAmount: 2600,
    forwarderStepWeightG: 500, forwarderPackagingG: 200, domesticFee: 0, domesticFreeOver: 0,
  }));
});

it('비운 칸은 null로 보낸다 — 배대지 기본 금액을 비우면 배대지 배송비를 쓰지 않는다', async () => {
  vi.mocked(fetchVendorPricePolicies).mockResolvedValue([{ ...IHB, forwarderBaseAmount: 10500,
    forwarderBaseWeightG: 500, forwarderStepAmount: 2600, forwarderStepWeightG: 500, forwarderPackagingG: 200 }]);
  renderPanel();
  expect(await screen.findByLabelText('기본 금액 (원)')).toHaveValue(10500);

  type('기본 금액 (원)', '');
  type('국내 배송비 (원)', '');
  fireEvent.click(screen.getByRole('button', { name: 'IHB 정책 저장' }));

  await waitFor(() => expect(saveVendorPricePolicy).toHaveBeenCalledTimes(1));
  expect(saveVendorPricePolicy).toHaveBeenCalledWith('IHB', expect.objectContaining({
    forwarderBaseAmount: null, forwarderBaseWeightG: 500, domesticFee: null, domesticFreeOver: 40000,
  }));
});

it('저장이 거부되면 서버 사유를 보여 준다', async () => {
  vi.mocked(saveVendorPricePolicy).mockRejectedValue({ response: { data: {
    message: '배대지 기본 금액을 넣으면 기본 무게·단계 금액·단계 무게도 넣어야 합니다(무게는 0보다 커야 합니다).' } } });
  renderPanel();
  await screen.findByLabelText('기본 금액 (원)');

  type('기본 금액 (원)', '10500');
  fireEvent.click(screen.getByRole('button', { name: 'IHB 정책 저장' }));

  expect(await screen.findByText(/저장하지 못했습니다: 배대지 기본 금액을 넣으면/)).toBeInTheDocument();
});
