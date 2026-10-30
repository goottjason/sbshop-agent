import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { KpiCards } from '../KpiCards';
import { formatDelta } from '../period';
import type { Summary } from '../dashboardApi';

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

const base: Summary = {
  period: {
    orderCount: 12, settlementSum: 500000, profitSum: 80000,
    returnCount: 0, returnProfit: 0, unsourcedCount: 0, unsourcedSettlement: 0,
  },
  current: { newCount: 3, shippingCount: 7, customsIssueCount: 1 },
};
const prev: Summary = { ...base, period: { ...base.period, orderCount: 9, settlementSum: 520000, profitSum: 80000 } };
const range = { start: '2026-10-26T00:00:00', end: '2026-11-01T23:59:59' };

describe('formatDelta', () => {
  it('증가는 +, 감소는 -, 같으면 ±0', () => {
    expect(formatDelta(12, 9, false)).toBe('+3');
    expect(formatDelta(500000, 520000, true)).toBe('-20,000원');
    expect(formatDelta(80000, 80000, true)).toBe('±0원');
  });

  it('어느 한쪽이 없으면 표시하지 않는다', () => {
    expect(formatDelta(12, undefined, false)).toBeUndefined();
    expect(formatDelta(undefined, 9, false)).toBeUndefined();
  });
});

describe('KpiCards 기간 비교', () => {
  it('기간 카드마다 직전 기간 대비 증감을 보여준다', () => {
    renderAt(<KpiCards data={base} prev={prev} range={range} unit="WEEK" />);
    expect(screen.getByText('전주 대비 +3')).toBeInTheDocument();
    expect(screen.getByText('전주 대비 -20,000원')).toBeInTheDocument();
    expect(screen.getByText('전주 대비 ±0원')).toBeInTheDocument();
  });

  it('현재값 카드는 별도 묶음이고 증감이 없다', () => {
    renderAt(<KpiCards data={base} prev={prev} range={range} unit="DAY" />);
    const current = screen.getByTestId('kpi-current');
    expect(current.textContent).toContain('미발주');
    expect(current.textContent).toContain('배송중');
    expect(current.textContent).toContain('통관오류');
    expect(current.textContent).not.toContain('대비');
    expect(screen.getByTestId('kpi-period').textContent).toContain('전일 대비');
  });

  it('기간 카드 클릭은 선택 기간 날짜로 주문 그리드를 연다', () => {
    renderAt(<KpiCards data={base} prev={prev} range={range} unit="WEEK" />);
    fireEvent.click(screen.getByText('정산금액'));
    expect(screen.getByTestId('loc').textContent).toBe('/orders?startDate=2026-10-26&endDate=2026-11-01');
  });
});
