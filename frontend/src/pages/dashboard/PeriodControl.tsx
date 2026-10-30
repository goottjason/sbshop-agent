import type { Unit } from './dashboardApi';
import { canShiftForward, clampToToday, includesToday, periodLabel, shiftPeriod, type PeriodValue } from './period';
import { kstDateString } from '../../utils/datetime';

const UNITS: Unit[] = ['DAY', 'WEEK', 'MONTH'];
const UNIT_LABEL: Record<Unit, string> = { DAY: '일별', WEEK: '주별', MONTH: '월별' };
const CURRENT_LABEL: Record<Unit, string> = { DAY: '오늘', WEEK: '이번 주', MONTH: '이번 달' };

export function PeriodControl({ value, onChange, today = kstDateString() }: {
  value: PeriodValue; onChange: (v: PeriodValue) => void; today?: string;
}) {
  const canNext = canShiftForward(value, today);
  return (
    <div className="dash-period">
      <div className="dash-unit-toggle">
        {UNITS.map((u) => (
          <button key={u} type="button" className={value.unit === u ? 'active' : undefined}
            onClick={() => onChange(clampToToday({ ...value, unit: u }, today))}>{UNIT_LABEL[u]}</button>
        ))}
      </div>
      <div className="dash-period-nav">
        <button type="button" aria-label="이전 기간" onClick={() => onChange(shiftPeriod(value, -1))}>‹</button>
        <span className="dash-period-label">{periodLabel(value)}</span>
        <button type="button" aria-label="다음 기간" disabled={!canNext} onClick={() => onChange(clampToToday(shiftPeriod(value, 1), today))}>›</button>
      </div>
      <button type="button" className="dash-period-today" disabled={includesToday(value, today)}
        onClick={() => onChange({ ...value, anchor: today })}>{CURRENT_LABEL[value.unit]}</button>
    </div>
  );
}
