import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, act, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { OrderDetailResponseDto, PageResponse } from '../../../api/orderApi';
import type { RowData } from '../types';

vi.mock('react-toastify', () => ({
  toast: Object.assign(vi.fn(), {
    success: vi.fn(), error: vi.fn(), warn: vi.fn(), warning: vi.fn(), info: vi.fn(),
  }),
}));

vi.mock('../../../api/orderApi', () => ({
  fetchOrders: vi.fn(),
  fetchCommonCodes: vi.fn(),
  fetchSyncStatus: vi.fn(),
  updateOrder: vi.fn(),
  updateOrderLineItem: vi.fn(),
  updateSourcingInfo: vi.fn(),
  updateShippingInfo: vi.fn(),
  updatePurchaseStatus: vi.fn(),
  shipOrders: vi.fn(),
  confirmOrdersBatch: vi.fn(),
  cancelOrder: vi.fn(),
  syncCoupangOrders: vi.fn(),
  syncSmartStoreOrders: vi.fn(),
  syncElevenStreetOrders: vi.fn(),
  syncEsmplusOrders: vi.fn(),
  syncCustomsStatus: vi.fn(),
  syncProductStock: vi.fn(),
}));

import {
  fetchOrders, fetchCommonCodes, fetchSyncStatus,
  updateOrder, confirmOrdersBatch,
} from '../../../api/orderApi';
import OrderGrid from '../OrderGrid';
import { buildOrderColumns } from '../orderColumns';

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  static CLOSED = 2;
  url: string;
  readyState = 1;
  onerror: (() => void) | null = null;
  handlers: Record<string, ((e: { data: string }) => void)[]> = {};
  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }
  addEventListener(type: string, cb: (e: { data: string }) => void) {
    (this.handlers[type] ||= []).push(cb);
  }
  removeEventListener() {}
  close() { this.readyState = 2; }
  emit(type: string, data = '') {
    (this.handlers[type] || []).forEach(cb => cb({ data }));
  }
}

class FakeResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const ADDRESS_A = '부산광역시 해운대구 센텀로 11';
const ADDRESS_B = '서울특별시 강남구 테헤란로 22';

function detail(
  orderId: number,
  lineItemId: number,
  recipientName: string,
  address: string,
  shipping: { shippingStatus: string; trackingNo?: string },
  message = '',
): OrderDetailResponseDto {
  return {
    order: {
      id: orderId,
      marketType: 'COUPANG',
      marketOrderNo: `MK-${orderId}`,
      orderDate: '2026-09-14T10:00:00',
      recipientName,
      recipientPhone: '01011112222',
      zipcode: '48058',
      address,
      message,
      ordererName: recipientName,
      customsData: {},
    },
    lineItems: [{
      lineItem: {
        id: lineItemId,
        quantity: 1,
        purchaseStatus: 'NOT_PURCHASED',
        shippingData: { shippingStatus: shipping.shippingStatus, trackingNo: shipping.trackingNo ?? '' },
        sourcingData: {},
        settlementData: { settlementAmount: 0 },
      },
      product: { id: lineItemId * 10, sbCode: `SB${lineItemId}`, productName: `상품 ${lineItemId}`, originalName: `product ${lineItemId}`, logisticsInfo: { bundleQuantity: 1 } },
      shipment: null,
    }],
  };
}

function page(content: OrderDetailResponseDto[]): PageResponse<OrderDetailResponseDto> {
  return { content, totalElements: content.length, totalPages: 1, size: 500, number: 0 };
}

function renderGrid() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <OrderGrid />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource);
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
  vi.spyOn(document, 'hasFocus').mockReturnValue(true);
  localStorage.clear();
  vi.mocked(fetchCommonCodes).mockResolvedValue({});
  vi.mocked(fetchSyncStatus).mockResolvedValue({});
  vi.mocked(updateOrder).mockResolvedValue({});
  vi.mocked(confirmOrdersBatch).mockResolvedValue({ successCount: 1, failedCount: 0, failedIds: [] });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('D-301 그리드 행 정체성', () => {
  it('재정렬 후 블러해도 이전 주문의 주소가 다른 주문에 커밋되지 않는다', async () => {
    const orderA = detail(594, 801, '김성국', ADDRESS_A, { shippingStatus: 'NEW' });
    const orderB = detail(593, 802, '이정열', ADDRESS_B, { shippingStatus: 'NEW' });
    vi.mocked(fetchOrders)
      .mockResolvedValueOnce(page([orderA, orderB]))
      .mockResolvedValue(page([orderB, orderA]));

    const { container } = renderGrid();

    const input = (await screen.findByDisplayValue(ADDRESS_A)) as HTMLInputElement;
    await act(async () => { input.focus(); });

    await act(async () => { FakeEventSource.instances[0].emit('SYNC_COMPLETED'); });
    await waitFor(() => expect(vi.mocked(fetchOrders).mock.calls.length).toBeGreaterThan(1));
    await waitFor(() => {
      const rows = container.querySelectorAll('tr[data-order-id]');
      expect(rows[0]?.getAttribute('data-order-id')).toBe('593');
    });

    await act(async () => { input.blur(); });

    expect(vi.mocked(updateOrder)).not.toHaveBeenCalled();
  });

  it('주소 편집기는 소유 주문이 바뀌면 이전 드래프트를 버린다', async () => {
    const handleUpdate = vi.fn().mockResolvedValue({});
    const columns = buildOrderColumns({
      getCommonLabel: (_c: string, n: string) => n,
      handleUpdate,
      handleSyncCustoms: () => {},
      handleSyncProductStock: () => {},
      timeAgo: () => '-',
    });
    const found = columns.find(c => c.id === 'shippingInfoPair');
    const addressCell = (found as unknown as { cell: (ctx: { row: { original: RowData } }) => React.ReactNode }).cell;

    const rowA: RowData = { order: { id: 594, address: ADDRESS_A }, lineItem: { id: 801 }, rowType: 'product' };
    const rowB: RowData = { order: { id: 593, address: ADDRESS_B }, lineItem: { id: 802 }, rowType: 'product' };

    const { rerender } = render(<div>{addressCell({ row: { original: rowA } })}</div>);
    const input = screen.getByDisplayValue(ADDRESS_A) as HTMLInputElement;
    await act(async () => { input.focus(); });

    rerender(<div>{addressCell({ row: { original: rowB } })}</div>);
    const after = screen.getByRole('textbox') as HTMLInputElement;
    await act(async () => { fireEvent.focusOut(after); });

    expect(handleUpdate).not.toHaveBeenCalled();
  });
});

describe('D-302 필터 상태의 일괄 처리 대상', () => {
  it('동기화 필터가 켜진 상태에서 선택한 주문만 발주확인된다', async () => {
    const orderA = detail(594, 801, '김성국', ADDRESS_A, { shippingStatus: 'NEW' });
    const orderB = detail(593, 802, '이정열', ADDRESS_B, { shippingStatus: 'NEW', trackingNo: '1234567890' });
    vi.mocked(fetchOrders).mockResolvedValue(page([orderA, orderB]));

    const { container } = renderGrid();
    await screen.findByDisplayValue(ADDRESS_A);

    const chip = await screen.findByRole('button', { name: /마켓 전송 대기/ });
    await act(async () => { fireEvent.click(chip); });

    await waitFor(() => {
      const rows = container.querySelectorAll('tr[data-order-id]');
      expect(rows.length).toBe(3);
      expect(rows[0]?.getAttribute('data-order-id')).toBe('593');
    });

    const firstRow = container.querySelectorAll('tr[data-order-id]')[0];
    const rowCheckbox = firstRow.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    await act(async () => { fireEvent.click(rowCheckbox); });

    const confirmButton = screen.getByRole('button', { name: '선택 주문 확인' });
    await waitFor(() => expect(confirmButton).not.toBeDisabled());
    await act(async () => { fireEvent.click(confirmButton); });

    await waitFor(() => expect(vi.mocked(confirmOrdersBatch)).toHaveBeenCalled());
    expect(vi.mocked(confirmOrdersBatch)).toHaveBeenCalledWith([593]);
  });
});

describe('D-307 배송메시지 낙관적 캐시', () => {
  it('배송메시지를 A→B로 저장한 뒤 다시 A로 되돌려도 저장 요청이 나간다', async () => {
    const order = detail(609, 901, '김성국', ADDRESS_A, { shippingStatus: 'NEW' }, '경비실');
    vi.mocked(fetchOrders).mockResolvedValue(page([order]));

    renderGrid();

    const input = (await screen.findByTitle(/배송메시지/)) as HTMLInputElement;
    expect(input.value).toBe('경비실');

    await act(async () => { input.focus(); });
    await act(async () => { fireEvent.change(input, { target: { value: '경비실 [검증]' } }); });
    await act(async () => { input.blur(); });

    await waitFor(() => expect(vi.mocked(updateOrder)).toHaveBeenCalledWith(609, { message: '경비실 [검증]' }));
    await waitFor(() => expect(input.value).toBe('경비실 [검증]'));

    await act(async () => { input.focus(); });
    await act(async () => { fireEvent.change(input, { target: { value: '경비실' } }); });
    await act(async () => { input.blur(); });

    await waitFor(() => expect(vi.mocked(updateOrder).mock.calls.length).toBe(2));
    expect(vi.mocked(updateOrder).mock.calls[1]).toEqual([609, { message: '경비실' }]);
  });
});
