import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

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

import { fetchOrders, fetchCommonCodes, fetchSyncStatus } from '../../../api/orderApi';
import OrderGrid from '../OrderGrid';

class FakeEventSource {
  static CLOSED = 2;
  readyState = 1;
  onerror: (() => void) | null = null;
  addEventListener() {}
  removeEventListener() {}
  close() { this.readyState = 2; }
}

class FakeResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function renderGridAt(url: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <OrderGrid />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('EventSource', FakeEventSource);
  vi.stubGlobal('ResizeObserver', FakeResizeObserver);
  localStorage.clear();
  vi.mocked(fetchCommonCodes).mockResolvedValue({});
  vi.mocked(fetchSyncStatus).mockResolvedValue({});
  vi.mocked(fetchOrders).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, size: 500, number: 0 });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('주문 그리드 URL 필터', () => {
  it('URL의 claimStages를 클레임 타입과 함께 조회에 넘긴다', async () => {
    renderGridAt('/orders?claimTypes=CANCEL&claimTypes=RETURN&claimStages=REQUESTED&claimStages=IN_PROGRESS');
    await waitFor(() => expect(vi.mocked(fetchOrders)).toHaveBeenCalled());
    const args = vi.mocked(fetchOrders).mock.calls[0];
    expect(args[11]).toEqual(['CANCEL', 'RETURN']);
    expect(args[12]).toEqual(['REQUESTED', 'IN_PROGRESS']);
  });

  it('claimStages가 없으면 단계 조건 없이 조회한다', async () => {
    renderGridAt('/orders?claimTypes=CANCEL');
    await waitFor(() => expect(vi.mocked(fetchOrders)).toHaveBeenCalled());
    const stages = vi.mocked(fetchOrders).mock.calls[0][12];
    expect(stages ?? []).toEqual([]);
  });
});
