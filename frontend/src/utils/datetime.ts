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
