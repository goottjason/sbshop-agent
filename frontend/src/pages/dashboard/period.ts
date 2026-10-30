import type { Unit } from './dashboardApi';
import { kstDateString } from '../../utils/datetime';

export interface PeriodValue { unit: Unit; anchor: string; }
export interface DateRange { start: string; end: string; }

const pad = (n: number) => String(n).padStart(2, '0');
const parse = (s: string) => {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
};
const fmt = (d: Date) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
const addDays = (s: string, n: number) => {
  const d = parse(s);
  d.setUTCDate(d.getUTCDate() + n);
  return fmt(d);
};
const addMonths = (s: string, n: number) => {
  const [y, m, d] = s.split('-').map(Number);
  const last = new Date(Date.UTC(y, m - 1 + n + 1, 0));
  return fmt(new Date(Date.UTC(last.getUTCFullYear(), last.getUTCMonth(), Math.min(d, last.getUTCDate()))));
};
const mondayOf = (s: string) => addDays(s, -((parse(s).getUTCDay() + 6) % 7));
const firstOfMonth = (s: string) => `${s.slice(0, 7)}-01`;
const lastOfMonth = (s: string) => addDays(addMonths(firstOfMonth(s), 1), -1);
const toRange = (from: string, to: string): DateRange => ({ start: `${from}T00:00:00`, end: `${to}T23:59:59` });

export function defaultPeriod(today: string = kstDateString()): PeriodValue {
  return { unit: 'DAY', anchor: today };
}

function bounds(v: PeriodValue): { from: string; to: string } {
  if (v.unit === 'WEEK') {
    const from = mondayOf(v.anchor);
    return { from, to: addDays(from, 6) };
  }
  if (v.unit === 'MONTH') return { from: firstOfMonth(v.anchor), to: lastOfMonth(v.anchor) };
  return { from: v.anchor, to: v.anchor };
}

export function periodRange(v: PeriodValue): DateRange {
  const b = bounds(v);
  return toRange(b.from, b.to);
}

export function shiftPeriod(v: PeriodValue, delta: number): PeriodValue {
  if (v.unit === 'MONTH') return { ...v, anchor: addMonths(v.anchor, delta) };
  return { ...v, anchor: addDays(v.anchor, v.unit === 'WEEK' ? delta * 7 : delta) };
}

export function canShiftForward(v: PeriodValue, today: string = kstDateString()): boolean {
  return bounds(shiftPeriod(v, 1)).from <= today;
}

export function clampToToday(v: PeriodValue, today: string = kstDateString()): PeriodValue {
  return v.anchor > today ? { ...v, anchor: today } : v;
}

export function includesToday(v: PeriodValue, today: string = kstDateString()): boolean {
  const b = bounds(v);
  return b.from <= today && today <= b.to;
}

export function selectedBucket(v: PeriodValue): string {
  return bounds(v).from;
}

export function chartRange(v: PeriodValue): DateRange {
  const b = bounds(v);
  if (v.unit === 'WEEK') return toRange(addDays(b.from, -11 * 7), b.to);
  if (v.unit === 'MONTH') return toRange(addMonths(b.from, -11), b.to);
  return toRange(addDays(b.from, -29), b.to);
}

const DOW = ['일', '월', '화', '수', '목', '금', '토'];

export function periodLabel(v: PeriodValue): string {
  const b = bounds(v);
  if (v.unit === 'WEEK') return `${b.from}~${b.to.slice(0, 4) === b.from.slice(0, 4) ? b.to.slice(5) : b.to}`;
  if (v.unit === 'MONTH') return `${b.from.slice(0, 4)}년 ${Number(b.from.slice(5, 7))}월`;
  return `${b.from}(${DOW[parse(b.from).getUTCDay()]})`;
}

const COMPARE: Record<Unit, string> = { DAY: '전일 대비', WEEK: '전주 대비', MONTH: '전월 대비' };

export function compareLabel(unit: Unit): string {
  return COMPARE[unit];
}

export function formatDelta(cur: number | undefined, prev: number | undefined, won: boolean): string | undefined {
  if (cur == null || prev == null) return undefined;
  const diff = cur - prev;
  const sign = diff > 0 ? '+' : diff < 0 ? '-' : '±';
  return `${sign}${Math.abs(diff).toLocaleString()}${won ? '원' : ''}`;
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
