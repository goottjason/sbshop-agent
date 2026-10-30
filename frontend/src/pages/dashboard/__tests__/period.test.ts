import { describe, expect, it } from 'vitest';
import {
  canShiftForward, chartRange, compareLabel, defaultPeriod, formatBucketLabel,
  periodLabel, periodRange, selectedBucket, shiftPeriod,
} from '../period';

describe('defaultPeriod', () => {
  it('KST 오늘을 기준일로 일별에서 시작한다', () => {
    expect(defaultPeriod('2026-10-30')).toEqual({ unit: 'DAY', anchor: '2026-10-30' });
  });

  it('기준일이 같으면 단위만 바꿔도 오늘·이번 주·이번 달이 된다', () => {
    const v = defaultPeriod('2026-10-30');
    expect(periodRange(v)).toEqual({ start: '2026-10-30T00:00:00', end: '2026-10-30T23:59:59' });
    expect(periodRange({ ...v, unit: 'WEEK' })).toEqual({ start: '2026-10-26T00:00:00', end: '2026-11-01T23:59:59' });
    expect(periodRange({ ...v, unit: 'MONTH' })).toEqual({ start: '2026-10-01T00:00:00', end: '2026-10-31T23:59:59' });
  });
});

describe('periodRange', () => {
  it('주별: 일요일은 그 전 월요일에 시작하는 주에 속한다', () => {
    expect(periodRange({ unit: 'WEEK', anchor: '2026-11-01' }))
      .toEqual({ start: '2026-10-26T00:00:00', end: '2026-11-01T23:59:59' });
  });

  it('주별: 월요일은 그 주의 시작이다', () => {
    expect(periodRange({ unit: 'WEEK', anchor: '2026-10-26' }).start).toBe('2026-10-26T00:00:00');
  });

  it('주별: 연도 경계를 넘는 주', () => {
    expect(periodRange({ unit: 'WEEK', anchor: '2027-01-01' }))
      .toEqual({ start: '2026-12-28T00:00:00', end: '2027-01-03T23:59:59' });
  });

  it('월별: 윤년 2월 말일', () => {
    expect(periodRange({ unit: 'MONTH', anchor: '2028-02-10' }).end).toBe('2028-02-29T23:59:59');
  });
});

describe('shiftPeriod', () => {
  it('일별은 하루씩, 월 경계를 넘는다', () => {
    expect(shiftPeriod({ unit: 'DAY', anchor: '2026-10-31' }, 1).anchor).toBe('2026-11-01');
    expect(shiftPeriod({ unit: 'DAY', anchor: '2026-01-01' }, -1).anchor).toBe('2025-12-31');
  });

  it('주별은 한 주씩', () => {
    const prev = shiftPeriod({ unit: 'WEEK', anchor: '2026-10-30' }, -1);
    expect(periodRange(prev)).toEqual({ start: '2026-10-19T00:00:00', end: '2026-10-25T23:59:59' });
  });

  it('월별은 한 달씩, 말일은 짧은 달에서 말일로 맞춘다', () => {
    const prev = shiftPeriod({ unit: 'MONTH', anchor: '2026-10-31' }, -1);
    expect(prev.anchor).toBe('2026-09-30');
    expect(periodRange(prev)).toEqual({ start: '2026-09-01T00:00:00', end: '2026-09-30T23:59:59' });
  });

  it('월별 연도 경계', () => {
    expect(periodRange(shiftPeriod({ unit: 'MONTH', anchor: '2026-01-15' }, -1)).start).toBe('2025-12-01T00:00:00');
    expect(periodRange(shiftPeriod({ unit: 'MONTH', anchor: '2026-12-15' }, 1)).start).toBe('2027-01-01T00:00:00');
  });

  it('직전 기간: 일별 전일, 주별 전주, 월별 전월', () => {
    expect(periodRange(shiftPeriod({ unit: 'DAY', anchor: '2026-10-30' }, -1)).start).toBe('2026-10-29T00:00:00');
    expect(periodRange(shiftPeriod({ unit: 'WEEK', anchor: '2026-10-30' }, -1)).start).toBe('2026-10-19T00:00:00');
    expect(periodRange(shiftPeriod({ unit: 'MONTH', anchor: '2026-10-30' }, -1)).start).toBe('2026-09-01T00:00:00');
  });
});

describe('canShiftForward', () => {
  it('다음 기간이 오늘 이후에 시작하면 이동할 수 없다', () => {
    expect(canShiftForward({ unit: 'DAY', anchor: '2026-10-30' }, '2026-10-30')).toBe(false);
    expect(canShiftForward({ unit: 'DAY', anchor: '2026-10-29' }, '2026-10-30')).toBe(true);
    expect(canShiftForward({ unit: 'WEEK', anchor: '2026-10-30' }, '2026-10-30')).toBe(false);
    expect(canShiftForward({ unit: 'MONTH', anchor: '2026-09-15' }, '2026-10-30')).toBe(true);
  });
});

describe('periodLabel', () => {
  it('일별은 날짜와 요일', () => expect(periodLabel({ unit: 'DAY', anchor: '2026-10-30' })).toBe('2026-10-30(금)'));
  it('주별은 월요일~일요일', () => expect(periodLabel({ unit: 'WEEK', anchor: '2026-10-30' })).toBe('2026-10-26~11-01'));
  it('주별 연도 경계는 끝에도 연도를 붙인다', () => expect(periodLabel({ unit: 'WEEK', anchor: '2027-01-01' })).toBe('2026-12-28~2027-01-03'));
  it('월별은 연월', () => expect(periodLabel({ unit: 'MONTH', anchor: '2026-10-30' })).toBe('2026년 10월'));
});

describe('compareLabel', () => {
  it('단위별 직전 기간 이름', () => {
    expect(compareLabel('DAY')).toBe('전일 대비');
    expect(compareLabel('WEEK')).toBe('전주 대비');
    expect(compareLabel('MONTH')).toBe('전월 대비');
  });
});

describe('chartRange', () => {
  it('일별: 선택일까지 최근 30일', () => {
    expect(chartRange({ unit: 'DAY', anchor: '2026-10-30' }))
      .toEqual({ start: '2026-10-01T00:00:00', end: '2026-10-30T23:59:59' });
  });

  it('주별: 선택 주까지 12주, 시작은 월요일', () => {
    expect(chartRange({ unit: 'WEEK', anchor: '2026-10-30' }))
      .toEqual({ start: '2026-08-10T00:00:00', end: '2026-11-01T23:59:59' });
  });

  it('월별: 선택 달까지 12개월, 연도 경계', () => {
    expect(chartRange({ unit: 'MONTH', anchor: '2026-10-30' }))
      .toEqual({ start: '2025-11-01T00:00:00', end: '2026-10-31T23:59:59' });
    expect(chartRange({ unit: 'MONTH', anchor: '2026-01-31' }).start).toBe('2025-02-01T00:00:00');
  });
});

describe('selectedBucket', () => {
  it('차트 강조 칸은 백엔드 버킷 시작일과 같다', () => {
    expect(selectedBucket({ unit: 'DAY', anchor: '2026-10-30' })).toBe('2026-10-30');
    expect(selectedBucket({ unit: 'WEEK', anchor: '2026-10-30' })).toBe('2026-10-26');
    expect(selectedBucket({ unit: 'MONTH', anchor: '2026-10-30' })).toBe('2026-10-01');
  });
});

describe('formatBucketLabel', () => {
  it('일별은 월/일', () => expect(formatBucketLabel('2026-10-05', 'DAY')).toBe('10/5'));
  it('주별은 월~일 범위', () => expect(formatBucketLabel('2026-09-28', 'WEEK')).toBe('9/28~10/4'));
  it('월별은 연.월', () => expect(formatBucketLabel('2026-10-01', 'MONTH')).toBe('2026.10'));
});
