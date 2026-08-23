const HAS_ZONE = /([zZ])|([+-]\d{2}:?\d{2})$/;

export function toKstDate(value?: string | null): Date | null {
  if (!value) return null;
  const normalized = HAS_ZONE.test(value) ? value : `${value}Z`;
  const d = new Date(normalized);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function formatKst(value?: string | null): string {
  const d = toKstDate(value);
  if (!d) return '-';
  return d.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
}

const KST_YMD = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

function kstYmd(base: Date): { y: number; m: number; d: number } {
  const parts = KST_YMD.formatToParts(base);
  const pick = (type: string) => Number(parts.find(p => p.type === type)?.value ?? '0');
  return { y: pick('year'), m: pick('month'), d: pick('day') };
}

export function kstDateString(base: Date = new Date()): string {
  const { y, m, d } = kstYmd(base);
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

function ymdString(y: number, m: number, d: number): string {
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

function daysInMonth(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

function shiftMonth(y: number, m: number, months: number): { y: number; m: number } {
  const total = y * 12 + (m - 1) + months;
  return { y: Math.floor(total / 12), m: ((total % 12) + 12) % 12 + 1 };
}

export function kstDateStringOffset(
  offset: { days?: number; months?: number },
  base: Date = new Date(),
): string {
  const { y, m, d } = kstYmd(base);
  let ty = y;
  let tm = m;
  let td = d;
  if (offset.months) {
    const shifted = shiftMonth(y, m, offset.months);
    ty = shifted.y;
    tm = shifted.m;
    td = Math.min(d, daysInMonth(ty, tm));
  }
  if (offset.days) {
    const shifted = new Date(Date.UTC(ty, tm - 1, td));
    shifted.setUTCDate(shifted.getUTCDate() + offset.days);
    return ymdString(shifted.getUTCFullYear(), shifted.getUTCMonth() + 1, shifted.getUTCDate());
  }
  return ymdString(ty, tm, td);
}

export type KstMonthRange = { year: number; month: number; start: string; end: string };

export function kstMonthRange(monthsAgo: number, base: Date = new Date()): KstMonthRange {
  const { y, m } = kstYmd(base);
  const { y: ty, m: tm } = shiftMonth(y, m, monthsAgo);
  return {
    year: ty,
    month: tm,
    start: ymdString(ty, tm, 1),
    end: ymdString(ty, tm, daysInMonth(ty, tm)),
  };
}

export function kstWeekStartString(base: Date = new Date()): string {
  const { y, m, d } = kstYmd(base);
  const cursor = new Date(Date.UTC(y, m - 1, d));
  const mondayOffset = (cursor.getUTCDay() + 6) % 7;
  cursor.setUTCDate(cursor.getUTCDate() - mondayOffset);
  return ymdString(cursor.getUTCFullYear(), cursor.getUTCMonth() + 1, cursor.getUTCDate());
}

export type KstPeriodRange = { id: string; start: string; end: string; month?: number };

export function kstPeriodRanges(today: string = kstDateString()): KstPeriodRange[] {
  const base = new Date(`${today}T00:00:00Z`);
  const currentMonth = kstMonthRange(0, base);
  const prevMonth = kstMonthRange(-1, base);
  const prevPrevMonth = kstMonthRange(-2, base);
  return [
    { id: 'TODAY', start: today, end: today },
    { id: 'THIS_WEEK', start: kstWeekStartString(base), end: today },
    { id: 'MONTH_0', start: currentMonth.start, end: today, month: currentMonth.month },
    { id: 'MONTH_1', start: prevMonth.start, end: prevMonth.end, month: prevMonth.month },
    { id: 'MONTH_2', start: prevPrevMonth.start, end: prevPrevMonth.end, month: prevPrevMonth.month },
  ];
}
