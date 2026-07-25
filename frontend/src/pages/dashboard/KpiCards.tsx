import { useNavigate } from 'react-router-dom';
import type { Summary } from './dashboardApi';
import { buildOrderGridUrl } from './drilldown';

export function KpiCards({ data, range }: { data?: Summary; range: { start: string; end: string } }) {
  const nav = useNavigate();
  const d0 = range.start.slice(0, 10), d1 = range.end.slice(0, 10);
  const won = (n?: number) => (n == null ? '-' : `${n.toLocaleString()}원`);
  const num = (n?: number) => (n == null ? '-' : n.toLocaleString());
  const cards = [
    { title: '주문 수', value: num(data?.period.orderCount), onClick: () => nav(buildOrderGridUrl({ startDate: d0, endDate: d1 })) },
    { title: '정산금액', value: won(data?.period.settlementSum), onClick: () => nav(buildOrderGridUrl({ startDate: d0, endDate: d1 })) },
    { title: '순수익', value: won(data?.period.profitSum), sub: '실구매가 입력 기준', onClick: () => nav(buildOrderGridUrl({ startDate: d0, endDate: d1 })) },
    { title: '미발주', value: num(data?.current.newCount), onClick: () => nav(buildOrderGridUrl({ statuses: ['NEW'] })) },
    { title: '배송중', value: num(data?.current.shippingCount), onClick: () => nav(buildOrderGridUrl({ statuses: ['DISPATCHED', 'SHIPPED'] })) },
    { title: '통관오류', value: num(data?.current.customsIssueCount), onClick: () => nav(buildOrderGridUrl({ customsStatuses: ['PENDING', 'INVALID_PCCC', 'INVALID_PHONE', 'INVALID_ZIPCODE'] })) },
  ];
  return (
    <div className="dashboard-grid">
      {cards.map((c) => (
        <div className="card" key={c.title} onClick={c.onClick} style={{ cursor: 'pointer' }}>
          <div className="card-title">{c.title}</div>
          <div className="card-value">{c.value}</div>
          {c.sub && <div style={{ fontSize: 11, color: '#9ca3af' }}>{c.sub}</div>}
        </div>
      ))}
    </div>
  );
}
