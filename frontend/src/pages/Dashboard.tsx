import { useEffect, useState, useCallback } from 'react';
import { Package, TrendingUp, AlertCircle, ShoppingCart, Truck, CheckCircle, RefreshCw } from 'lucide-react';
import { fetchOrderCount } from '../api/orderApi';

// 통관 오류/대기 = 대기(PENDING) + 각종 불일치(INVALID_*)
const CUSTOMS_ISSUE_STATUSES = ['PENDING', 'INVALID_PCCC', 'INVALID_PHONE', 'INVALID_ZIPCODE'];

interface Metric {
  key: string;
  title: string;
  icon: React.ReactNode;
  color?: string;
  load: () => Promise<number>;
}

const METRICS: Metric[] = [
  { key: 'total', title: '전체 주문 수', icon: <Package size={20} />,
    load: () => fetchOrderCount() },
  { key: 'new', title: '미발주 (결제완료)', icon: <ShoppingCart size={20} />, color: '#2563eb',
    load: () => fetchOrderCount({ shippingStatuses: ['NEW'] }) },
  { key: 'preparing', title: '구매준비', icon: <RefreshCw size={20} />, color: 'var(--warning)',
    load: () => fetchOrderCount({ shippingStatuses: ['PREPARING'] }) },
  { key: 'shipping', title: '배송 진행 중', icon: <TrendingUp size={20} />, color: 'var(--success)',
    load: () => fetchOrderCount({ shippingStatuses: ['DISPATCHED', 'SHIPPED'] }) },
  { key: 'delivered', title: '배송완료', icon: <CheckCircle size={20} />, color: '#0f766e',
    load: () => fetchOrderCount({ shippingStatuses: ['DELIVERED'] }) },
  { key: 'customs', title: '통관 오류/대기', icon: <AlertCircle size={20} />, color: 'var(--error)',
    load: () => fetchOrderCount({ customsStatuses: CUSTOMS_ISSUE_STATUSES }) },
  { key: 'ship-ready', title: '배송지시 완료 대기', icon: <Truck size={20} />, color: '#7c3aed',
    load: () => fetchOrderCount({ shippingStatuses: ['DISPATCHED'] }) },
];

const Dashboard = () => {
  const [counts, setCounts] = useState<Record<string, number | null>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError(false);
    try {
      const results = await Promise.all(
        METRICS.map(async (m) => {
          try {
            return [m.key, await m.load()] as const;
          } catch {
            return [m.key, null] as const;
          }
        }),
      );
      const next: Record<string, number | null> = {};
      results.forEach(([k, v]) => { next[k] = v; });
      setCounts(next);
      if (results.some(([, v]) => v === null)) setError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadAll(); }, [loadAll]);

  return (
    <div style={{ padding: '16px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <h1 style={{ margin: 0 }}>대시보드</h1>
        <button className="btn-primary" onClick={loadAll} disabled={loading}
          style={{ display: 'flex', alignItems: 'center', gap: 6, opacity: loading ? 0.6 : 1 }}>
          <RefreshCw size={16} /> 새로고침
        </button>
      </div>
      <p style={{ color: '#666', marginBottom: 24 }}>
        SB Shop 에이전트의 현재 비즈니스 현황입니다.
        {error && <span style={{ color: 'var(--error)', marginLeft: 8 }}>일부 지표를 불러오지 못했습니다.</span>}
      </p>

      <div className="dashboard-grid">
        {METRICS.map((m) => {
          const v = counts[m.key];
          return (
            <div className="card" key={m.key}>
              <div className="card-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {m.icon} {m.title}
              </div>
              <div className="card-value" style={{ color: m.color }}>
                {loading && v === undefined ? '…' : v === null ? '-' : (v ?? 0).toLocaleString()}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default Dashboard;
