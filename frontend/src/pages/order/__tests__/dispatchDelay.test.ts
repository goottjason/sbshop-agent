import { describe, it, expect } from 'vitest';
import { dispatchDelay } from '../helpers';
import type { OrderLineItemDto, ShipmentDto } from '../../../api/orderApi';

const now = new Date('2026-10-24T10:00:00');
const line = (shippingStatus: string, claimActive = false): OrderLineItemDto => ({
  shippingData: { shippingStatus },
  claimData: { active: claimActive },
});
const shipment = (dispatchDueAt: string | null, dispatchDelayReason: string | null = null): ShipmentDto => ({
  dispatchDueAt, dispatchDelayReason,
});

describe('dispatchDelay', () => {
  it('발송 전 주문이 발송기한을 넘기면 발송지연으로 표시한다', () => {
    const result = dispatchDelay(line('NEW'), shipment('2026-10-21T23:59:59'), now);
    expect(result?.label).toBe('발송지연');
    expect(result?.overdue).toBe(true);
    expect(result?.title).toContain('10-21');
  });

  it('기한 전이면 표시하지 않는다', () => {
    expect(dispatchDelay(line('PREPARING'), shipment('2026-10-25T23:59:59'), now)).toBeNull();
  });

  it('이미 발송했거나 클레임 중이면 표시하지 않는다', () => {
    expect(dispatchDelay(line('SHIPPED'), shipment('2026-10-21T23:59:59'), now)).toBeNull();
    expect(dispatchDelay(line('NEW', true), shipment('2026-10-21T23:59:59'), now)).toBeNull();
  });

  it('판매자가 발송지연 처리해 기한이 남았으면 처리 사실과 새 기한을 보여준다', () => {
    const result = dispatchDelay(line('NEW'), shipment('2026-10-28T23:59:59', 'PRODUCT_PREPARE: 입고 지연'), now);
    expect(result?.label).toBe('지연안내');
    expect(result?.overdue).toBe(false);
    expect(result?.title).toContain('입고 지연');
  });

  it('기한 정보가 없으면 표시하지 않는다', () => {
    expect(dispatchDelay(line('NEW'), null, now)).toBeNull();
    expect(dispatchDelay(line('NEW'), shipment(null), now)).toBeNull();
  });
});
