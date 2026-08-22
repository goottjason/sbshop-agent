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

export function kstDateStringOffset(
  offset: { days?: number; months?: number },
  base: Date = new Date(),
): string {
  const { y, m, d } = kstYmd(base);
  const shifted = new Date(Date.UTC(y, m - 1, d));
  if (offset.months) shifted.setUTCMonth(shifted.getUTCMonth() + offset.months);
  if (offset.days) shifted.setUTCDate(shifted.getUTCDate() + offset.days);
  return shifted.toISOString().slice(0, 10);
}
