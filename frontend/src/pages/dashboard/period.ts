import type { Unit } from './dashboardApi';

export interface PeriodValue { year: number; month: number; unit: Unit; }

const pad = (n: number) => String(n).padStart(2, '0');
const fmtStart = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T00:00:00`;
const fmtEnd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T23:59:59`;

export function computeRange(v: PeriodValue): { start: string; end: string } {
  if (v.unit === 'MONTH') {
    const endD = new Date(v.year, v.month, 0);
    const startD = new Date(v.year, v.month - 1 - 11, 1);
    return { start: fmtStart(startD), end: fmtEnd(endD) };
  }
  const startD = new Date(v.year, v.month - 1, 1);
  const endD = new Date(v.year, v.month, 0);
  return { start: fmtStart(startD), end: fmtEnd(endD) };
}
