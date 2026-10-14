import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { isPlaceholderTracking, marketSyncState } from '../helpers';
import { TrackingCompareCell } from '../cells';
import type { OrderLineItemDto, ShipmentDto } from '../../../api/orderApi';

const lineItem = (trackingNo: string): OrderLineItemDto => ({
  shippingData: { trackingNo, shippingStatus: 'SHIPPED', trackingSentToMarket: true },
} as unknown as OrderLineItemDto);

const shipment = (marketTrackingNo: string): ShipmentDto => ({
  marketTrackingNo, manualFixRequired: false,
} as unknown as ShipmentDto);

describe('isPlaceholderTracking', () => {
  it('0으로만 된 값은 송장이 아니다', () => {
    expect(isPlaceholderTracking('00000000')).toBe(true);
    expect(isPlaceholderTracking(' 0000 ')).toBe(true);
    expect(isPlaceholderTracking('0000-0000')).toBe(true);
  });

  it('실제 송장과 빈 값은 자리표시가 아니다', () => {
    expect(isPlaceholderTracking('424446007042')).toBe(false);
    expect(isPlaceholderTracking('')).toBe(false);
    expect(isPlaceholderTracking(undefined)).toBe(false);
  });
});

describe('marketSyncState', () => {
  it('마켓 송장이 00000000이면 API로 고칠 수 없으므로 수동수정 필요다', () => {
    expect(marketSyncState(lineItem('424446007042'), shipment('00000000'))).toBe('manual');
  });
});

describe('TrackingCompareCell', () => {
  it('마켓 00000000 대신 우리 송장을 대표 번호로 보여 주고 ESM 수정 안내를 띄운다', () => {
    render(<TrackingCompareCell carrier="CJ" trackingNo="424446007042" marketTrackingNo="00000000" />);
    expect(screen.getByTestId('tracking-main').textContent).toBe('424446007042');
    expect(screen.getByText(/송장 없이 발송/).textContent).toContain('ESM');
  });

  it('우리 송장이 없어도 00000000을 송장처럼 보이지 않는다', () => {
    render(<TrackingCompareCell carrier="CJ" trackingNo="" marketTrackingNo="00000000" />);
    expect(screen.queryByTestId('tracking-main')).toBeNull();
    expect(screen.getByText(/송장 없이 발송/)).toBeTruthy();
  });
});
