import type { Unit } from './dashboardApi';

export interface PeriodValue { year: number; month: number; unit: Unit; } // month: 1-12

// 선택된 (연,월,단위) → 조회 구간. 일/주는 그 달, 월은 최근 12개월.
export function computeRange(v: PeriodValue): { start: string; end: string } {
  if (v.unit === 'MONTH') {
    const endD = new Date(v.year, v.month, 0); // 그 달 말일
    const startD = new Date(v.year, v.month - 1 - 11, 1); // 12개월 전 1일
    return { start: fmtStart(startD), end: fmtEnd(endD) };
  }
  const startD = new Date(v.year, v.month - 1, 1);
  const endD = new Date(v.year, v.month, 0);
  return { start: fmtStart(startD), end: fmtEnd(endD) };
}
const pad = (n: number) => String(n).padStart(2, '0');
const fmtStart = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T00:00:00`;
const fmtEnd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T23:59:59`;

export function PeriodControl({ value, onChange }: { value: PeriodValue; onChange: (v: PeriodValue) => void }) {
  const move = (delta: number) => {
    const d = new Date(value.year, value.month - 1 + delta, 1);
    onChange({ ...value, year: d.getFullYear(), month: d.getMonth() + 1 });
  };
  const units: Unit[] = ['DAY', 'WEEK', 'MONTH'];
  const label: Record<Unit, string> = { DAY: '일별', WEEK: '주별', MONTH: '월별' };
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button onClick={() => move(-1)} style={btn}>‹</button>
        <span style={{ fontWeight: 600, minWidth: 110, textAlign: 'center' }}>{value.year}년 {value.month}월</span>
        <button onClick={() => move(1)} style={btn}>›</button>
      </div>
      <div style={{ display: 'flex', border: '1px solid #d1d5db', borderRadius: 6, overflow: 'hidden' }}>
        {units.map((u) => (
          <button key={u} onClick={() => onChange({ ...value, unit: u })}
            style={{ padding: '6px 14px', border: 'none', cursor: 'pointer',
              background: value.unit === u ? 'var(--primary-color)' : '#fff',
              color: value.unit === u ? '#fff' : '#333' }}>{label[u]}</button>
        ))}
      </div>
    </div>
  );
}
const btn: React.CSSProperties = { padding: '4px 10px', border: '1px solid #d1d5db', borderRadius: 6, background: '#fff', cursor: 'pointer', fontSize: 16 };
