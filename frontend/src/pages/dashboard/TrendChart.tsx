import { ComposedChart, Bar, Cell, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import type { MouseHandlerDataParam } from 'recharts';
import type { TimeseriesBucket, Unit } from './dashboardApi';
import { formatBucketLabel } from './period';

const TITLE: Record<Unit, string> = { DAY: '최근 30일 추이', WEEK: '최근 12주 추이', MONTH: '최근 12개월 추이' };

export function TrendChart({ data, unit, selected, onSelect }: {
  data?: TimeseriesBucket[]; unit: Unit; selected: string; onSelect: (bucketStart: string) => void;
}) {
  const rows = data ?? [];
  const onClick = (state: MouseHandlerDataParam) => {
    const label = state?.activeLabel;
    if (label != null) onSelect(String(label));
  };
  return (
    <div className="card dash-panel">
      <div className="card-title">{TITLE[unit]}<span className="dash-hint">막대를 누르면 그 기간을 봅니다</span></div>
      <div className="dash-panel-body">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={rows} onClick={onClick} margin={{ top: 4, right: 4, bottom: 0, left: -8 }} style={{ cursor: 'pointer' }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#eef2f7" />
            <XAxis dataKey="bucketStart" fontSize={11} tickFormatter={(v) => formatBucketLabel(String(v), unit)} />
            <YAxis yAxisId="left" fontSize={11} allowDecimals={false} />
            <YAxis yAxisId="right" orientation="right" fontSize={11} tickFormatter={(v) => `${(v / 10000).toLocaleString()}만`} />
            <Tooltip
              labelFormatter={(v) => formatBucketLabel(String(v), unit)}
              formatter={(v, n) => {
                const num = Number(v);
                return n === '주문수' ? `${num}건` : `${num.toLocaleString()}원`;
              }}
            />
            <Legend iconSize={10} wrapperStyle={{ fontSize: 12 }} />
            <Bar yAxisId="left" dataKey="orderCount" name="주문수" fill="#c7d2fe" radius={[3, 3, 0, 0]}>
              {rows.map((b) => <Cell key={b.bucketStart} fill={b.bucketStart === selected ? '#6366f1' : '#c7d2fe'} />)}
            </Bar>
            <Line yAxisId="right" dataKey="settlementSum" name="정산금액" stroke="#1565c0" dot={false} strokeWidth={2} />
            <Line yAxisId="right" dataKey="profitSum" name="순수익" stroke="#2e7d32" dot={false} strokeWidth={2} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
