import { describe, it, expect } from 'vitest';
import { orderRowId, selectedGridRows, selectedOrderIds } from '../helpers';
import type { RowData } from '../types';

const row = (orderId: number, lineItemId: number, rowType: string): RowData => ({
  order: { id: orderId },
  lineItem: { id: lineItemId },
  rowType,
});

const grid = (): RowData[] => [
  row(594, 801, 'order'), row(594, 801, 'product'), row(594, 801, 'fulfillment'),
  row(593, 802, 'order'), row(593, 802, 'product'), row(593, 802, 'fulfillment'),
];

describe('orderRowId', () => {
  it('같은 주문의 세 행을 서로 다른 값으로 구분한다', () => {
    const ids = grid().map(orderRowId);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('배열 위치가 바뀌어도 같은 주문·라인아이템이면 같은 값을 낸다', () => {
    const before = orderRowId(row(594, 801, 'product'), 1);
    const after = orderRowId(row(594, 801, 'product'), 4);
    expect(after).toBe(before);
  });

  it('식별자가 없는 행은 위치로 대체해 충돌하지 않는다', () => {
    const anonymous: RowData = { rowType: 'order' };
    expect(orderRowId(anonymous, 0)).not.toBe(orderRowId(anonymous, 1));
  });
});

describe('selectedGridRows', () => {
  it('필터로 부분집합만 보이는 상태에서도 선택한 주문을 그대로 돌려준다', () => {
    const all = grid();
    const visible = all.slice(3);
    const selection = { [orderRowId(visible[0], 0)]: true };
    expect(selectedOrderIds(selectedGridRows(visible, selection))).toEqual([593]);
  });

  it('보이지 않는 행의 선택은 대상에서 제외한다', () => {
    const visible = grid().slice(3);
    const selection = { '594:801:order': true };
    expect(selectedGridRows(visible, selection)).toEqual([]);
  });
});

describe('selectedOrderIds', () => {
  it('한 주문의 여러 행을 하나의 주문 id로 접는다', () => {
    const selection = Object.fromEntries(grid().slice(0, 3).map((r, i) => [orderRowId(r, i), true]));
    expect(selectedOrderIds(selectedGridRows(grid(), selection))).toEqual([594]);
  });
});
