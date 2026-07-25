import { useNavigate } from 'react-router-dom';
import type { Attention } from './dashboardApi';
import { buildOrderGridUrl } from './drilldown';

const CUSTOMS = ['PENDING', 'INVALID_PCCC', 'INVALID_PHONE', 'INVALID_ZIPCODE'];
const todayMinus = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10); };

export function AttentionPanel({ data }: { data?: Attention }) {
  const nav = useNavigate();
  const rows = [
    { label: '통관 오류/대기', v: data?.customsIssue, to: buildOrderGridUrl({ customsStatuses: CUSTOMS }) },
    { label: '재고부족(품절) 주문', v: data?.outOfStock, to: buildOrderGridUrl({ stockStatuses: ['OUT_OF_STOCK'], statuses: ['NEW', 'PREPARING', 'DISPATCHED'] }) },
    { label: '배송/처리 지연(미발주 1일+)', v: data?.delayed, to: buildOrderGridUrl({ statuses: ['NEW'], endDate: todayMinus(1) }) },
    { label: '반품/취소', v: data?.returnCancel, to: buildOrderGridUrl({ statuses: ['CANCELED', 'RETURNED'] }) },
  ];
  return (
    <div className="card">
      <div className="card-title">문제 / 이상</div>
      {rows.map((r) => (
        <div key={r.label} onClick={() => nav(r.to)}
          style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid #f1f5f9', cursor: 'pointer' }}>
          <span>{r.label}</span>
          <span style={{ fontWeight: 700, color: (r.v ?? 0) > 0 ? '#c62828' : '#9ca3af' }}>{r.v ?? '-'}건</span>
        </div>
      ))}
    </div>
  );
}
