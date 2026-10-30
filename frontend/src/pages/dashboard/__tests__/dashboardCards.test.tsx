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
  const attention: Attention = { customsIssue: 0, outOfStock: 0, delayed: 1, returnCancel: 3 };

  it('클레임 행은 진행 중 클레임 수를 보여주고 클레임 주문 그리드로 이동한다', () => {
    renderAt(<AttentionPanel data={attention} />);
    fireEvent.click(screen.getByText('진행 중 클레임(취소·반품·교환)'));
    expect(screen.getByTestId('loc').textContent)
      .toBe('/orders?claimTypes=CANCEL&claimTypes=RETURN&claimTypes=EXCHANGE');
  });
});
