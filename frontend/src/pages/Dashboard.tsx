import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PeriodControl, computeRange, type PeriodValue } from './dashboard/PeriodControl';
import { KpiCards } from './dashboard/KpiCards';
import { TrendChart } from './dashboard/TrendChart';
import { BreakdownPanels } from './dashboard/BreakdownPanels';
import { AttentionPanel } from './dashboard/AttentionPanel';
import { fetchSummary, fetchTimeseries, fetchAttention } from './dashboard/dashboardApi';

export default function Dashboard() {
  const now = new Date();
  const [period, setPeriod] = useState<PeriodValue>({ year: now.getFullYear(), month: now.getMonth() + 1, unit: 'DAY' });
  const range = useMemo(() => computeRange(period), [period]);

  const summary = useQuery({ queryKey: ['summary', range.start, range.end], queryFn: () => fetchSummary(range.start, range.end) });
  const timeseries = useQuery({ queryKey: ['timeseries', range.start, range.end, period.unit], queryFn: () => fetchTimeseries(range.start, range.end, period.unit) });
  const attention = useQuery({ queryKey: ['attention'], queryFn: fetchAttention, refetchInterval: 60000 });

  return (
    <div style={{ padding: '16px 24px' }}>
      <h1 style={{ marginBottom: 16 }}>대시보드</h1>
      <PeriodControl value={period} onChange={setPeriod} />
      <KpiCards data={summary.data} range={range} />
      <div style={{ marginTop: 16 }}><TrendChart data={timeseries.data} /></div>
      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16, marginTop: 16 }}>
        <BreakdownPanels range={range} />
        <AttentionPanel data={attention.data} />
      </div>
    </div>
  );
}
