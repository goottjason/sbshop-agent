import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BreakdownPanels } from '../BreakdownPanels';
import { fetchBreakdown } from '../dashboardApi';

vi.mock('../dashboardApi', async (orig) => ({
  ...(await orig<typeof import('../dashboardApi')>()),
  fetchBreakdown: vi.fn(),
}));

function Probe() {
  const loc = useLocation();
  return <div data-testid="loc">{loc.pathname + loc.search}</div>;
}

const LONG = '나우푸드 오메가3 피쉬오일 1000mg 소프트젤 200정 대용량 두 병 세트';
const rows: Record<string, { key: string; label: string; orderCount: number; settlementSum: number; profitSum: number }[]> = {
  MARKET: [{ key: 'COUPANG', label: '쿠팡', orderCount: 4, settlementSum: 0, profitSum: 0 }],
  STATUS: [
    { key: 'SHIPPED', label: 'SHIPPED', orderCount: 4, settlementSum: 0, profitSum: 0 },
    { key: 'CLAIM', label: 'CLAIM', orderCount: 9, settlementSum: 0, profitSum: 0 },
  ],
  PRODUCT: [
    { key: 'P-1', label: LONG, orderCount: 8, settlementSum: 0, profitSum: 0 },
    { key: 'P-2', label: '비타민C', orderCount: 2, settlementSum: 0, profitSum: 0 },
  ],
  VENDOR: [{ key: 'IHERB', label: '아이허브', orderCount: 6, settlementSum: 0, profitSum: 0 }],
};

const range = { start: '2026-10-30T00:00:00', end: '2026-10-30T23:59:59' };

const renderPanels = () => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<BreakdownPanels range={range} />} />
        <Route path="/orders" element={<Probe />} />
      </Routes>
    </MemoryRouter>
  </QueryClientProvider>,
);

describe('BreakdownPanels 순위 리스트', () => {
  beforeEach(() => {
    vi.mocked(fetchBreakdown).mockReset();
    vi.mocked(fetchBreakdown).mockImplementation(async (_s, _e, dim) => rows[dim]);
  });

  it('선택 기간으로 4개 분해를 조회한다', async () => {
    renderPanels();
    await waitFor(() => expect(fetchBreakdown).toHaveBeenCalledTimes(4));
    for (const c of vi.mocked(fetchBreakdown).mock.calls) {
      expect(c[0]).toBe(range.start);
      expect(c[1]).toBe(range.end);
    }
  });

  it('상품명은 한 줄 말줄임 행으로 그리고 전체 이름을 title로 준다', async () => {
    renderPanels();
    const name = await screen.findByText(LONG);
    expect(name.getAttribute('title')).toBe(LONG);
    expect(name.className).toContain('dash-rank-name');
    const row = name.closest('[data-rank-row]') as HTMLElement;
    expect(row.textContent).toContain('1');
    expect(row.textContent).toContain('8');
  });

  it('상품 행 클릭은 기간과 상품 키로 주문 그리드를 연다', async () => {
    renderPanels();
    fireEvent.click(await screen.findByText(LONG));
    expect(screen.getByTestId('loc').textContent)
      .toBe('/orders?keyword=P-1&startDate=2026-10-30&endDate=2026-10-30');
  });

  it('소싱처도 같은 순위 리스트이고 클릭하면 소싱처 필터로 연다', async () => {
    renderPanels();
    const v = await screen.findByText('아이허브');
    expect(v.className).toContain('dash-rank-name');
    fireEvent.click(v);
    expect(screen.getByTestId('loc').textContent)
      .toBe('/orders?vendors=IHERB&startDate=2026-10-30&endDate=2026-10-30');
  });

  it('주문상태도 순위 리스트로 한국어 라벨을 쓴다', async () => {
    renderPanels();
    fireEvent.click(await screen.findByText('배송중'));
    expect(screen.getByTestId('loc').textContent)
      .toBe('/orders?statuses=SHIPPED&claimTypes=NONE&startDate=2026-10-30&endDate=2026-10-30');
  });

  it('주문상태 CLAIM은 종결 배송상태 포함 전 상태·클레임 단계 전부·선택 기간으로 연다', async () => {
    renderPanels();
    fireEvent.click(await screen.findByText('취소·반품·교환'));
    expect(screen.getByTestId('loc').textContent).toBe(
      '/orders?statuses=UNKNOWN&statuses=NEW&statuses=PREPARING&statuses=DISPATCHED&statuses=SHIPPED'
      + '&statuses=DELIVERED&statuses=CONFIRMED&statuses=CANCELED&statuses=RETURNED&statuses=EXCHANGED'
      + '&claimTypes=CANCEL&claimTypes=RETURN&claimTypes=EXCHANGE'
      + '&claimStages=REQUESTED&claimStages=IN_PROGRESS&claimStages=DONE'
      + '&startDate=2026-10-30&endDate=2026-10-30');
  });
});
