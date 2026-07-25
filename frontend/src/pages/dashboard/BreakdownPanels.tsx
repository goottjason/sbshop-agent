import { PieChart, Pie, Cell, BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { PieSectorDataItem, MouseHandlerDataParam } from 'recharts';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { fetchBreakdown, type Dimension } from './dashboardApi';
import { buildOrderGridUrl, type DrilldownFilters } from './drilldown';

const MARKET_COLOR: Record<string, string> = {
  COUPANG: '#c2185b', SMART_STORE: '#689f38', ELEVEN_STREET: '#1565c0',
  GMARKET: '#1b5e20', AUCTION: '#e65100', CAFE24: '#fbc02d',
};
const STATUS_LABEL: Record<string, string> = {
  NEW: '결제완료', PREPARING: '구매준비', DISPATCHED: '배송지시', SHIPPED: '배송중',
  DELIVERED: '배송완료', CANCELED: '취소', RETURNED: '반품', EXCHANGED: '교환', UNKNOWN: '알수없음',
};

export function BreakdownPanels({ range }: { range: { start: string; end: string } }) {
  const nav = useNavigate();
  const d0 = range.start.slice(0, 10), d1 = range.end.slice(0, 10);
  const useBreakdownQuery = (dim: Dimension, limit = 10) => useQuery({
    queryKey: ['breakdown', dim, range.start, range.end],
    queryFn: () => fetchBreakdown(range.start, range.end, dim, limit),
  });
  // 훅 규칙: 고정 4회 명시 호출 (조건/반복 금지)
  const market = useBreakdownQuery('MARKET');
  const status = useBreakdownQuery('STATUS');
  const product = useBreakdownQuery('PRODUCT', 10);
  const vendor = useBreakdownQuery('VENDOR');
  const go = (f: DrilldownFilters) => nav(buildOrderGridUrl({ ...f, startDate: d0, endDate: d1 }));

  const statusRows = (status.data ?? []).map((s) => ({ ...s, label: STATUS_LABEL[s.key] ?? s.key }));

  // recharts v3: Pie onClick receives (data: PieSectorDataItem, index, event) — the original datum
  // (with our `key`/`label` fields) is spread into `data.payload`.
  const onMarketClick = (data: PieSectorDataItem) => {
    const key = (data.payload as { key?: string } | undefined)?.key;
    if (key) go({ markets: [key] });
  };

  // recharts v3: BarChart chart-level onClick receives MouseHandlerDataParam (has activeLabel), not
  // a bare { activeLabel } object like v2 — same adaptation as TrendChart.tsx.
  const onStatusClick = (state: MouseHandlerDataParam) => {
    const label = state?.activeLabel;
    const it = statusRows.find((x) => x.label === label);
    if (it) go({ statuses: [it.key] });
  };
  const onProductClick = (state: MouseHandlerDataParam) => {
    const label = state?.activeLabel;
    const it = (product.data ?? []).find((x) => x.label === label);
    if (it) go({ keyword: it.key });
  };
  const onVendorClick = (state: MouseHandlerDataParam) => {
    const label = state?.activeLabel;
    const it = (vendor.data ?? []).find((x) => x.key === label);
    if (it) go({ vendors: [it.key] });
  };

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
      <div className="card">
        <div className="card-title">마켓별</div>
        <ResponsiveContainer width="100%" height={220}>
          <PieChart>
            <Pie data={market.data ?? []} dataKey="orderCount" nameKey="label" innerRadius={50} outerRadius={80}
              onClick={onMarketClick}>
              {(market.data ?? []).map((it) => <Cell key={it.key} fill={MARKET_COLOR[it.key] ?? '#9ca3af'} />)}
            </Pie>
            <Tooltip formatter={(v) => `${Number(v)}건`} />
          </PieChart>
        </ResponsiveContainer>
      </div>

      <div className="card">
        <div className="card-title">주문상태</div>
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={statusRows} onClick={onStatusClick}>
            <XAxis dataKey="label" fontSize={11} /><YAxis fontSize={11} />
            <Tooltip formatter={(v) => `${Number(v)}건`} />
            <Bar dataKey="orderCount" fill="#93c5fd" radius={[3, 3, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="card">
        <div className="card-title">상품 Top 10</div>
        <ResponsiveContainer width="100%" height={260}>
          <BarChart layout="vertical" data={product.data ?? []} onClick={onProductClick}>
            <XAxis type="number" fontSize={11} /><YAxis type="category" dataKey="label" width={120} fontSize={10} />
            <Tooltip formatter={(v) => `${Number(v)}건`} />
            <Bar dataKey="orderCount" fill="#a7f3d0" radius={[0, 3, 3, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="card">
        <div className="card-title">소싱처별</div>
        <ResponsiveContainer width="100%" height={260}>
          <BarChart layout="vertical" data={vendor.data ?? []} onClick={onVendorClick}>
            <XAxis type="number" fontSize={11} /><YAxis type="category" dataKey="key" width={80} fontSize={11} />
            <Tooltip formatter={(v) => `${Number(v)}건`} />
            <Bar dataKey="orderCount" fill="#fde68a" radius={[0, 3, 3, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
