import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { AxiosResponse } from 'axios';
import { ProductPriceSync } from '../ProductPriceSync';
import { marketPriceSyncApi, type PriceSyncReview } from '../../../api/marketPriceSyncApi';

vi.mock('../../../api/marketPriceSyncApi', () => ({ marketPriceSyncApi: { recent: vi.fn(), preview: vi.fn(), commit: vi.fn() } }));

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn() })));
  vi.mocked(marketPriceSyncApi.recent).mockResolvedValue({ data: [] } as AxiosResponse);
  vi.mocked(marketPriceSyncApi.preview).mockResolvedValue({ data: { id: 'review-1', actor: 'tester', committed: false,
    expiresAt: '2099-01-01T00:00:00Z', items: [{ productId: 12, market: 'COUPANG', state: 'READY' }],
  } as PriceSyncReview } as AxiosResponse);
});
afterEach(() => vi.unstubAllGlobals());

it('가격 검토 후 마켓 선택이 바뀌면 이전 마켓의 검토를 접수할 수 없다', async () => {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
    <ProductPriceSync productIds={[12]} onClose={vi.fn()} />
  </QueryClientProvider>);
  fireEvent.click(screen.getByRole('button', { name: '선택 상품 가격 검토' }));
  expect(await screen.findByRole('button', { name: '검토한 가격 반영 접수' })).toBeEnabled();
  fireEvent.click(screen.getByRole('checkbox', { name: '쿠팡' }));
  expect(screen.queryByRole('button', { name: '검토한 가격 반영 접수' })).not.toBeInTheDocument();
  expect(marketPriceSyncApi.commit).not.toHaveBeenCalled();
});
