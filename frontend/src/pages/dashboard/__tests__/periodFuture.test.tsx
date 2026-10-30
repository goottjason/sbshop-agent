import { describe, it, expect } from 'vitest';
import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { PeriodControl } from '../PeriodControl';
import { clampToToday, includesToday, type PeriodValue } from '../period';

describe('clampToToday', () => {
  it('오늘 이후 anchor는 오늘로 자른다', () => {
    expect(clampToToday({ unit: 'MONTH', anchor: '2026-10-20' }, '2026-10-05')).toEqual({ unit: 'MONTH', anchor: '2026-10-05' });
  });
  it('오늘 이전 anchor는 그대로', () => {
    expect(clampToToday({ unit: 'DAY', anchor: '2026-09-20' }, '2026-10-05')).toEqual({ unit: 'DAY', anchor: '2026-09-20' });
  });
});

describe('includesToday', () => {
  it('선택 기간이 오늘을 포함하는지', () => {
    expect(includesToday({ unit: 'DAY', anchor: '2026-10-05' }, '2026-10-05')).toBe(true);
    expect(includesToday({ unit: 'DAY', anchor: '2026-10-20' }, '2026-10-05')).toBe(false);
    expect(includesToday({ unit: 'WEEK', anchor: '2026-10-07' }, '2026-10-05')).toBe(true);
    expect(includesToday({ unit: 'MONTH', anchor: '2026-09-20' }, '2026-10-05')).toBe(false);
  });
});

function Harness({ initial, today }: { initial: PeriodValue; today: string }) {
  const [v, setV] = useState(initial);
  return (
    <>
      <PeriodControl value={v} onChange={setV} today={today} />
      <div data-testid="anchor">{v.unit}:{v.anchor}</div>
    </>
  );
}

describe('PeriodControl 미래 날짜 방지', () => {
  const today = '2026-10-05';

  it('월별에서 다음 달로 이동하면 anchor가 오늘을 넘지 않고, 일별로 바꿔도 오늘이다', () => {
    render(<Harness initial={{ unit: 'DAY', anchor: '2026-09-20' }} today={today} />);
    fireEvent.click(screen.getByText('월별'));
    fireEvent.click(screen.getByLabelText('다음 기간'));
    expect(screen.getByTestId('anchor').textContent).toBe('MONTH:2026-10-05');
    fireEvent.click(screen.getByText('일별'));
    expect(screen.getByTestId('anchor').textContent).toBe('DAY:2026-10-05');
    expect(screen.getByText('2026-10-05(월)')).toBeInTheDocument();
  });

  it('단위 전환도 미래 anchor를 오늘로 자른다', () => {
    render(<Harness initial={{ unit: 'MONTH', anchor: '2026-10-20' }} today={today} />);
    fireEvent.click(screen.getByText('주별'));
    expect(screen.getByTestId('anchor').textContent).toBe('WEEK:2026-10-05');
  });

  it('선택 기간이 오늘을 포함하지 않으면 오늘 버튼은 활성이고 누르면 오늘로 돌아온다', () => {
    render(<Harness initial={{ unit: 'DAY', anchor: '2026-10-20' }} today={today} />);
    const btn = screen.getByText('오늘');
    expect(btn).toBeEnabled();
    fireEvent.click(btn);
    expect(screen.getByTestId('anchor').textContent).toBe('DAY:2026-10-05');
    expect(screen.getByText('오늘')).toBeDisabled();
  });

  it('과거 기간에서는 오늘 버튼이 활성이다', () => {
    render(<Harness initial={{ unit: 'WEEK', anchor: '2026-09-20' }} today={today} />);
    expect(screen.getByText('이번 주')).toBeEnabled();
  });
});
