import { PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import type { PieSectorDataItem } from 'recharts';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { fetchBreakdown, type Dimension } from './dashboardApi';
import { buildOrderGridUrl, EVERY_SHIPPING_STATUS, type DrilldownFilters } from './drilldown';

const MARKET_COLOR: Record<string, string> = {
  COUPANG: '#c2185b', SMART_STORE: '#689f38', ELEVEN_STREET: '#1565c0',
  GMARKET: '#1b5e20', AUCTION: '#e65100', CAFE24: '#fbc02d',
};
const STATUS_LABEL: Record<string, string> = {
  NEW: '결제완료', PREPARING: '구매준비', DISPATCHED: '배송지시', SHIPPED: '배송중',
  DELIVERED: '배송완료', CONFIRMED: '구매확정', CANCELED: '취소', RETURNED: '반품', EXCHANGED: '교환', UNKNOWN: '알수없음',
  CLAIM: '취소·반품·교환', UNASSIGNED: '미지정',
};

interface RankItem { key: string; label: string; orderCount: number; }

function RankList({ items, color, onSelect }: { items: RankItem[]; color: string; onSelect: (it: RankItem) => void }) {
  const max = Math.max(1, ...items.map((it) => it.orderCount));
  if (items.length === 0) return <div className="dash-empty">데이터 없음</div>;
  return (
    <ol className="dash-rank">
      {items.map((it, i) => {
        const clickable = it.key !== 'UNASSIGNED';
        return (
          <li key={it.key} data-rank-row className={clickable ? 'clickable' : undefined}
            onClick={clickable ? () => onSelect(it) : undefined}>
            <span className="dash-rank-no">{i + 1}</span>
            <span className="dash-rank-name" title={it.label}>{it.label}</span>
            <span className="dash-rank-bar"><span style={{ width: `${(it.orderCount / max) * 100}%`, background: color }} /></span>
            <span className="dash-rank-count">{it.orderCount.toLocaleString()}</span>
          </li>
        );
      })}
    </ol>
  );
}

export function BreakdownPanels({ range }: { range: { start: string; end: string } }) {
  const nav = useNavigate();
  const d0 = range.start.slice(0, 10), d1 = range.end.slice(0, 10);
  const useBreakdownQuery = (dim: Dimension, limit = 10) => useQuery({
    queryKey: ['breakdown', dim, range.start, range.end],
    queryFn: () => fetchBreakdown(range.start, range.end, dim, limit),
  });
  const market = useBreakdownQuery('MARKET');
  const status = useBreakdownQuery('STATUS');
  const product = useBreakdownQuery('PRODUCT', 10);
  const vendor = useBreakdownQuery('VENDOR');
  const go = (f: DrilldownFilters) => nav(buildOrderGridUrl({ ...f, startDate: d0, endDate: d1 }));

  const statusRows = (status.data ?? []).map((s) => ({ ...s, label: STATUS_LABEL[s.key] ?? s.key }));

  const onMarketClick = (data: PieSectorDataItem) => {
    const key = (data.payload as { key?: string } | undefined)?.key;
    if (key) go({ markets: [key] });
  };

  return (
    <div className="dash-breakdown">
      <div className="card dash-panel">
        <div className="card-title">마켓별</div>
        <div className="dash-panel-body">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={market.data ?? []} dataKey="orderCount" nameKey="label" innerRadius="45%" outerRadius="80%"
                onClick={onMarketClick}>
                {(market.data ?? []).map((it) => <Cell key={it.key} fill={MARKET_COLOR[it.key] ?? '#9ca3af'} cursor="pointer" />)}
              </Pie>
              <Tooltip formatter={(v) => `${Number(v)}건`} />
              <Legend layout="vertical" align="right" verticalAlign="middle" iconSize={10} wrapperStyle={{ fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="card dash-panel">
        <div className="card-title">주문상태</div>
        <div className="dash-panel-body">
          <RankList items={statusRows} color="#93c5fd"
            onSelect={(it) => go(it.key === 'CLAIM' ? { statuses: EVERY_SHIPPING_STATUS, claimTypes: ['CANCEL', 'RETURN', 'EXCHANGE'], claimStages: ['REQUESTED', 'IN_PROGRESS', 'DONE'] } : { statuses: [it.key], claimTypes: ['NONE'] })} />
        </div>
      </div>

      <div className="card dash-panel">
        <div className="card-title">상품 Top 10</div>
        <div className="dash-panel-body">
          <RankList items={product.data ?? []} color="#6ee7b7" onSelect={(it) => go({ keyword: it.key })} />
        </div>
      </div>

      <div className="card dash-panel">
        <div className="card-title">소싱처별</div>
        <div className="dash-panel-body">
          <RankList items={vendor.data ?? []} color="#fcd34d" onSelect={(it) => go({ vendors: [it.key] })} />
        </div>
      </div>
    </div>
  );
}
