import { describe, expect, it } from 'vitest';
import { computeRange, formatBucketLabel } from '../period';

describe('computeRange', () => {
  it('일별: KPI와 차트 모두 선택한 달 한 달', () => {
    const r = computeRange({ year: 2026, month: 10, unit: 'DAY' });
    expect(r.kpi).toEqual({ start: '2026-10-01T00:00:00', end: '2026-10-31T23:59:59' });
    expect(r.chart).toEqual(r.kpi);
  });

  it('주별: KPI는 한 달, 차트는 그 달과 겹치는 온전한 주(월~일)', () => {
    const r = computeRange({ year: 2026, month: 10, unit: 'WEEK' });
    expect(r.kpi).toEqual({ start: '2026-10-01T00:00:00', end: '2026-10-31T23:59:59' });
    expect(r.chart).toEqual({ start: '2026-09-28T00:00:00', end: '2026-11-01T23:59:59' });
  });

  it('주별: 1일이 월요일이고 말일이 일요일이면 차트 범위가 달과 같다', () => {
    const r = computeRange({ year: 2027, month: 2, unit: 'WEEK' });
    expect(r.chart).toEqual({ start: '2027-02-01T00:00:00', end: '2027-02-28T23:59:59' });
  });

  it('월별: KPI는 선택한 달 한 달, 차트만 12개월', () => {
    const r = computeRange({ year: 2026, month: 10, unit: 'MONTH' });
    expect(r.kpi).toEqual({ start: '2026-10-01T00:00:00', end: '2026-10-31T23:59:59' });
    expect(r.chart).toEqual({ start: '2025-11-01T00:00:00', end: '2026-10-31T23:59:59' });
  });

  it('월별: 연도 경계', () => {
    const r = computeRange({ year: 2026, month: 1, unit: 'MONTH' });
    expect(r.chart.start).toBe('2025-02-01T00:00:00');
  });
});

describe('formatBucketLabel', () => {
  it('일별은 월/일', () => expect(formatBucketLabel('2026-10-05', 'DAY')).toBe('10/5'));
  it('주별은 월~일 범위', () => expect(formatBucketLabel('2026-09-28', 'WEEK')).toBe('9/28~10/4'));
  it('월별은 연.월', () => expect(formatBucketLabel('2026-10-01', 'MONTH')).toBe('2026.10'));
});
