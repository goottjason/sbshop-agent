import { ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import type { MouseHandlerDataParam } from 'recharts';
import { useNavigate } from 'react-router-dom';
import type { TimeseriesBucket, Unit } from './dashboardApi';
import { buildOrderGridUrl } from './drilldown';

const pad = (n: number) => String(n).padStart(2, '0');

// 'YYYY-MM-DD' 버킷 시작일 + 단위 → 버킷의 마지막 날짜('YYYY-MM-DD'). DAY는 당일, WEEK는 월요일 시작 주의 일요일, MONTH는 그 달 말일.
function bucketEndDate(bucketStart: string, unit: Unit): string {
  const [y, m, d] = bucketStart.split('-').map(Number);
  if (unit === 'DAY') return bucketStart;
  if (unit === 'WEEK') {
    const end = new Date(y, m - 1, d + 6);
    return `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}`;
  }
  // MONTH: 다음 달 0일 = 이번 달 말일
  const end = new Date(y, m, 0);
  return `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}`;
}

export function TrendChart({ data, unit }: { data?: TimeseriesBucket[]; unit: Unit }) {
  const nav = useNavigate();
  // recharts v3: chart-level onClick receives (MouseHandlerDataParam, MouseEvent), not a bare { activeLabel } object like v2.
  const onClick = (state: MouseHandlerDataParam) => {
    const label = state?.activeLabel;
    if (label != null) {
      const bucketStart = String(label);
      const endDate = bucketEndDate(bucketStart, unit);
      nav(buildOrderGridUrl({ startDate: bucketStart, endDate }));
    }
  };
  return (
    <div className="card" style={{ height: 340 }}>
      <div className="card-title">추이</div>
      <ResponsiveContainer width="100%" height="90%">
        <ComposedChart data={data ?? []} onClick={onClick}>
          <CartesianGrid strokeDasharray="3 3" stroke="#eef2f7" />
          <XAxis dataKey="bucketStart" fontSize={11} />
          <YAxis yAxisId="left" fontSize={11} />
          <YAxis yAxisId="right" orientation="right" fontSize={11} tickFormatter={(v) => `${(v / 10000).toLocaleString()}만`} />
          <Tooltip
            formatter={(v, n) => {
              // recharts v3 widens the tooltip value to ValueType (number | string | array | undefined); our data is always numeric.
              const num = Number(v);
              return n === '주문수' ? `${num}건` : `${num.toLocaleString()}원`;
            }}
          />
          <Legend />
          <Bar yAxisId="left" dataKey="orderCount" name="주문수" fill="#c7d2fe" radius={[3, 3, 0, 0]} />
          <Line yAxisId="right" dataKey="settlementSum" name="정산금액" stroke="#1565c0" dot={false} strokeWidth={2} />
          <Line yAxisId="right" dataKey="profitSum" name="순수익" stroke="#2e7d32" dot={false} strokeWidth={2} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}
