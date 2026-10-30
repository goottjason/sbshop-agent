import { useNavigate } from 'react-router-dom';
import type { Summary, Unit } from './dashboardApi';
import { buildOrderGridUrl } from './drilldown';
import { compareLabel, formatDelta } from './period';

interface Card { title: string; value: string; sub?: string; delta?: string; onClick: () => void; }

function CardView({ c, unit }: { c: Card; unit: Unit }) {
  const tone = c.delta?.startsWith('+') ? 'up' : c.delta?.startsWith('-') ? 'down' : 'flat';
  return (
    <div className="card dash-kpi" onClick={c.onClick}>
      <div className="dash-kpi-title">{c.title}</div>
      <div className="dash-kpi-value">{c.value}</div>
      {c.delta && <div className={`dash-kpi-delta ${tone}`}>{`${compareLabel(unit)} ${c.delta}`}</div>}
      {c.sub && <div className="dash-kpi-sub" title={c.sub}>{c.sub}</div>}
    </div>
  );
}

export function KpiCards({ data, prev, range, unit = 'DAY' }: {
  data?: Summary; prev?: Summary; range: { start: string; end: string }; unit?: Unit;
}) {
  const nav = useNavigate();
  const d0 = range.start.slice(0, 10), d1 = range.end.slice(0, 10);
  const won = (n?: number) => (n == null ? '-' : `${n.toLocaleString()}원`);
  const num = (n?: number) => (n == null ? '-' : n.toLocaleString());
  const toPeriod = () => nav(buildOrderGridUrl({ startDate: d0, endDate: d1 }));
  const p = data?.period, q = prev?.period;
  const periodCards: Card[] = [
    { title: '주문 수', value: num(p?.orderCount), delta: formatDelta(p?.orderCount, q?.orderCount, false), sub: p?.returnCount ? `반품 ${num(p.returnCount)}건 별도${p.returnProfit ? `(손익 ${won(p.returnProfit)})` : ''}` : undefined, onClick: toPeriod },
    { title: '정산금액', value: won(p?.settlementSum), delta: formatDelta(p?.settlementSum, q?.settlementSum, true), onClick: toPeriod },
    { title: '순수익', value: won(p?.profitSum), delta: formatDelta(p?.profitSum, q?.profitSum, true), sub: p?.unsourcedCount ? `원가 미입력 ${num(p.unsourcedCount)}건(정산 ${won(p.unsourcedSettlement)}) 제외` : '실구매가 입력 기준', onClick: toPeriod },
  ];
  const currentCards: Card[] = [
    { title: '미발주', value: num(data?.current.newCount), onClick: () => nav(buildOrderGridUrl({ statuses: ['NEW'], claimTypes: ['NONE'] })) },
    { title: '배송중', value: num(data?.current.shippingCount), onClick: () => nav(buildOrderGridUrl({ statuses: ['DISPATCHED', 'SHIPPED'], claimTypes: ['NONE'] })) },
    { title: '통관오류', value: num(data?.current.customsIssueCount), onClick: () => nav(buildOrderGridUrl({ customsStatuses: ['PENDING', 'INVALID_PCCC', 'INVALID_PHONE', 'INVALID_ZIPCODE'] })) },
  ];
  return (
    <div className="dash-kpis">
      <section className="dash-kpi-group" data-testid="kpi-period">
        <div className="dash-kpi-group-label">선택 기간</div>
        <div className="dash-kpi-row">{periodCards.map((c) => <CardView key={c.title} c={c} unit={unit} />)}</div>
      </section>
      <section className="dash-kpi-group current" data-testid="kpi-current">
        <div className="dash-kpi-group-label">현재 (기간 무관)</div>
        <div className="dash-kpi-row">{currentCards.map((c) => <CardView key={c.title} c={c} unit={unit} />)}</div>
      </section>
    </div>
  );
}
