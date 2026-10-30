import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { KpiCards } from '../KpiCards';
import { AttentionPanel } from '../AttentionPanel';
import type { Summary, Attention } from '../dashboardApi';

function Probe() {
  const loc = useLocation();
  return <div data-testid="loc">{loc.pathname + loc.search}</div>;
}

const renderAt = (ui: React.ReactNode) => render(
  <MemoryRouter initialEntries={['/']}>
    <Routes>
      <Route path="/" element={ui} />
      <Route path="/orders" element={<Probe />} />
    </Routes>
  </MemoryRouter>,
);

const summary: Summary = {
  period: {
    orderCount: 90, settlementSum: 1000000, profitSum: 200000,
    returnCount: 2, returnProfit: -59814, unsourcedCount: 21, unsourcedSettlement: 686741,
  },
  current: { newCount: 1, shippingCount: 26, customsIssueCount: 0 },
};
const range = { start: '2026-10-01T00:00:00', end: '2026-10-31T23:59:59' };

describe('KpiCards', () => {
  it('주문 수 카드 부제에 반품 건수와 손실을 별도로 보여준다', () => {
    renderAt(<KpiCards data={summary} range={range} />);
    expect(screen.getByText('반품 2건 별도(손익 -59,814원)')).toBeInTheDocument();
  });

  it('반품 손익이 0이면 건수만 보여준다', () => {
    renderAt(<KpiCards data={{ ...summary, period: { ...summary.period, returnProfit: 0 } }} range={range} />);
    expect(screen.getByText('반품 2건 별도')).toBeInTheDocument();
  });

  it('순수익 카드 부제에 원가 미입력 제외분을 보여준다', () => {
    renderAt(<KpiCards data={summary} range={range} />);
    expect(screen.getByText('원가 미입력 21건(정산 686,741원) 제외')).toBeInTheDocument();
  });

  it('미발주 드릴다운은 클레임 없는 주문만 연다', () => {
    renderAt(<KpiCards data={summary} range={range} />);
    fireEvent.click(screen.getByText('미발주'));
    expect(screen.getByTestId('loc').textContent).toBe('/orders?statuses=NEW&claimTypes=NONE');
  });
});

describe('AttentionPanel', () => {
  const attention: Attention = {
    customsIssue: 0, outOfStock: 0,
    newDelayed: 2, newDelayedThrough: '2026-10-29',
    preparingDelayed: 8, preparingDelayedThrough: '2026-10-27',
    returnCancel: 3,
  };

  it('지연을 결제완료·구매준비 두 줄로 나눠 각 건수를 보여준다', () => {
    renderAt(<AttentionPanel data={attention} />);
    expect(screen.getByText('결제완료 1일+ 미처리').nextSibling?.textContent).toBe('2건');
    expect(screen.getByText('구매준비 3일+ 미발송').nextSibling?.textContent).toBe('8건');
  });

  it('결제완료 지연 행은 서버가 센 날짜 경계까지의 결제완료 주문을 연다', () => {
    renderAt(<AttentionPanel data={attention} />);
    fireEvent.click(screen.getByText('결제완료 1일+ 미처리'));
    expect(screen.getByTestId('loc').textContent).toBe('/orders?statuses=NEW&claimTypes=NONE&endDate=2026-10-29');
  });

  it('구매준비 지연 행은 서버가 센 날짜 경계까지의 구매준비 주문을 연다', () => {
    renderAt(<AttentionPanel data={attention} />);
    fireEvent.click(screen.getByText('구매준비 3일+ 미발송'));
    expect(screen.getByTestId('loc').textContent).toBe('/orders?statuses=PREPARING&claimTypes=NONE&endDate=2026-10-27');
  });

  it('클레임 행은 요청·처리중 단계의 클레임 주문만 연다', () => {
    renderAt(<AttentionPanel data={attention} />);
    fireEvent.click(screen.getByText('진행 중 클레임(취소·반품·교환)'));
    const all = ['UNKNOWN', 'NEW', 'PREPARING', 'DISPATCHED', 'SHIPPED', 'DELIVERED', 'CONFIRMED', 'CANCELED', 'RETURNED', 'EXCHANGED'];
    expect(screen.getByTestId('loc').textContent).toBe('/orders?' + [
      ...all.map((s) => `statuses=${s}`),
      'claimTypes=CANCEL', 'claimTypes=RETURN', 'claimTypes=EXCHANGE',
      'claimStages=REQUESTED', 'claimStages=IN_PROGRESS',
    ].join('&'));
  });
});
