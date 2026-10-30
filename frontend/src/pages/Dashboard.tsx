import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PeriodControl } from './dashboard/PeriodControl';
import { chartRange, defaultPeriod, periodRange, selectedBucket, shiftPeriod, type PeriodValue } from './dashboard/period';
import { KpiCards } from './dashboard/KpiCards';
import { TrendChart } from './dashboard/TrendChart';
import { BreakdownPanels } from './dashboard/BreakdownPanels';
import { AttentionPanel } from './dashboard/AttentionPanel';
import { fetchSummary, fetchTimeseries, fetchAttention } from './dashboard/dashboardApi';
import './dashboard/dashboard.css';

export default function Dashboard() {
  const [period, setPeriod] = useState<PeriodValue>(() => defaultPeriod());
  const range = useMemo(() => periodRange(period), [period]);
  const prevRange = useMemo(() => periodRange(shiftPeriod(period, -1)), [period]);
  const chart = useMemo(() => chartRange(period), [period]);

  const summary = useQuery({ queryKey: ['summary', range.start, range.end], queryFn: () => fetchSummary(range.start, range.end) });
  const prevSummary = useQuery({ queryKey: ['summary', prevRange.start, prevRange.end], queryFn: () => fetchSummary(prevRange.start, prevRange.end) });
  const timeseries = useQuery({ queryKey: ['timeseries', chart.start, chart.end, period.unit], queryFn: () => fetchTimeseries(chart.start, chart.end, period.unit) });
  const attention = useQuery({ queryKey: ['attention'], queryFn: fetchAttention, refetchInterval: 60000 });

  return (
    <div className="dash">
      <header className="dash-header">
        <h1>대시보드</h1>
        <PeriodControl value={period} onChange={setPeriod} />
      </header>
      <KpiCards data={summary.data} prev={prevSummary.data} range={range} unit={period.unit} />
      <div className="dash-middle">
        <TrendChart data={timeseries.data} unit={period.unit} selected={selectedBucket(period)}
          onSelect={(bucketStart) => setPeriod({ unit: period.unit, anchor: bucketStart })} />
        <div className="dash-attention"><AttentionPanel data={attention.data} /></div>
      </div>
      <BreakdownPanels range={range} />
    </div>
  );
}
