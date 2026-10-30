import type { Unit } from './dashboardApi';

export interface PeriodValue { year: number; month: number; unit: Unit; }
export interface DateRange { start: string; end: string; }

const pad = (n: number) => String(n).padStart(2, '0');
const ymd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const fmtStart = (d: Date) => `${ymd(d)}T00:00:00`;
const fmtEnd = (d: Date) => `${ymd(d)}T23:59:59`;
const mondayOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate() - ((d.getDay() + 6) % 7));
const sundayOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + ((7 - d.getDay()) % 7));

export function computeRange(v: PeriodValue): { kpi: DateRange; chart: DateRange } {
  const first = new Date(v.year, v.month - 1, 1);
  const last = new Date(v.year, v.month, 0);
  const kpi = { start: fmtStart(first), end: fmtEnd(last) };
  if (v.unit === 'WEEK') return { kpi, chart: { start: fmtStart(mondayOf(first)), end: fmtEnd(sundayOf(last)) } };
  if (v.unit === 'MONTH') return { kpi, chart: { start: fmtStart(new Date(v.year, v.month - 12, 1)), end: kpi.end } };
  return { kpi, chart: kpi };
}

export function formatBucketLabel(bucketStart: string, unit: Unit): string {
  const [y, m, d] = bucketStart.split('-').map(Number);
  if (unit === 'MONTH') return `${y}.${pad(m)}`;
  if (unit === 'WEEK') {
    const end = new Date(y, m - 1, d + 6);
    return `${m}/${d}~${end.getMonth() + 1}/${end.getDate()}`;
  }
  return `${m}/${d}`;
}
