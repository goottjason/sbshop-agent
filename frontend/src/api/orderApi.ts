import { apiClient } from './axios';

export interface OrderDto {
  id?: number;
  marketType?: string;
  marketOrderNo?: string;
  orderDate?: string;
  recipientName?: string;
  recipientPhone?: string;
  zipcode?: string;
  address?: string;
  message?: string;
  ordererName?: string;
  ordererPhone?: string;
  customsData?: {
    customsClearanceNo?: string;
    customsStatus?: string;
    verifiedPerson?: string;
  };
}

export interface OrderLineItemDto {
  id?: number;
  quantity?: number;
  unitPrice?: number;
  isUnipassDone?: boolean;
  purchaseStatus?: 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK';
  sourcingData?: {
    sourcingAccount?: string;
    sourcingOrderNo?: string;
    sourcingAmount?: number;
    logisticsCost?: number;
    discountCode?: string;
    sourcingVendor?: string;
  };
  settlementData?: {
    settlementAmount?: number;
    settlementVerified?: boolean;
  };
  shippingData?: {
    trackingNo?: string;
    shippingStatus?: string;
    shippingCarrier?: string;
    /**
     * 마켓이 이 송장을 갖고 있는가(D-129). 우리가 전송해 성공했거나, 마켓이 실송장을 알려준 경우 true.
     * 송장은 있는데 이 값이 true가 아니면 "저장됨 · 마켓 미반영" 상태다 — {@link isMarketUnsynced} 참고.
     */
    trackingSentToMarket?: boolean | null;
  };
}

export interface ProductDto {
  id?: number;
  sbCode?: string;
  category?: string;
  vendor?: string;
  // Product 엔티티의 flat 필드(둘 다 String). 이전엔 중첩 객체로 잘못 정의돼 상품명이 표시되지 않았다.
  productName?: string;
  originalName?: string;
  sourcingInfo?: {
    sourceUrl?: string;
  };
  stockStatus?: string;
  restockDate?: string;
  // 상품 마지막 갱신시각(재고 크롤 save 시 갱신). "재고 반영 시각" 프록시로 사용.
  updatedAt?: string;
  productSpec?: {
    barcode?: string;
    capacity?: number;
    measureUnit?: string;
  };
  logisticsInfo?: {
    bundleQuantity?: number;
    stock?: number;
    weight?: number;
  };
}

export const fetchCommonCodes = async () => {
  const response = await apiClient.get('/api/v1/common/codes');
  return response.data;
};

/**
 * 이 상품주문이 속한 배송(D-148).
 *
 * 송장의 진실은 발송처(iHerb 발송메일)이고, `marketTrackingNo`는 "마켓이 알고 있는 값"일 뿐이다.
 * 두 값의 불일치가 곧 "마켓 미반영"이며, 화면은 이 비교로 배지를 판정한다.
 */
export interface ShipmentDto {
  id?: number;
  marketShipmentNo?: string;
  trackingNo?: string;
  /** 마켓이 알고 있는 송장. 우리 송장과 다르면 마켓 미반영이다. */
  marketTrackingNo?: string | null;
  /** 마켓이 영구 거부해 사람이 판매자센터에서 직접 고쳐야 하는 상태. */
  manualFixRequired?: boolean | null;
  shippingCarrier?: string;
  deliveryStatus?: string | null;
  /**
   * 이 송장을 무엇이 확인했는가. 'EMAIL'은 iHerb 메일이 확인한 진짜 송장,
   * 'MANUAL'·'MARKET'은 사람·마켓이 넣은 진위 불명 값이다. null은 이 기능 이전의 과거 데이터.
   */
  trackingSource?: 'EMAIL' | 'MANUAL' | 'MARKET' | null;
}

export interface OrderLineItemDetailDto {
  lineItem: OrderLineItemDto;
  product: ProductDto;
  marketRegistration?: unknown;
  shipment?: ShipmentDto | null;
}

export interface OrderGridDto {
  order?: OrderDto;
  lineItem?: OrderLineItemDto;
  product?: ProductDto;
  marketRegistration?: unknown;
  shipment?: ShipmentDto | null;
}

export interface OrderDetailResponseDto {
  order?: OrderDto;
  lineItems: OrderLineItemDetailDto[];
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export const fetchOrders = async (
  page = 0,
  size = 500,
  keyword?: string,
  marketTypes?: string[],
  shippingStatuses?: string[],
  startDate?: string,
  endDate?: string,
  purchaseStatuses?: string[],
  stockStatuses?: string[],
  vendors?: string[],
  customsStatuses?: string[]
): Promise<PageResponse<OrderDetailResponseDto>> => {
  const params = new URLSearchParams();
  params.append('page', String(page));
  params.append('size', String(size));
  if (keyword) params.append('keyword', keyword);
  if (marketTypes) {
    marketTypes.forEach(m => params.append('marketTypes', m));
  }
  if (shippingStatuses) {
    shippingStatuses.forEach(s => params.append('shippingStatuses', s));
  }
  if (purchaseStatuses) {
    purchaseStatuses.forEach(s => params.append('purchaseStatuses', s));
  }
  if (stockStatuses) stockStatuses.forEach(s => params.append('stockStatuses', s));
  if (vendors) vendors.forEach(v => params.append('vendors', v));
  if (customsStatuses) customsStatuses.forEach(c => params.append('customsStatuses', c));
  if (startDate) params.append('startDate', startDate + 'T00:00:00');
  if (endDate) params.append('endDate', endDate + 'T23:59:59');
  
  const { data } = await apiClient.get(`/api/v1/orders?${params.toString()}`);
  return data;
};

// 대시보드 현황 집계용 경량 카운트. size=1로 조회해 totalElements만 사용한다.
export const fetchOrderCount = async (filters: {
  shippingStatuses?: string[];
  customsStatuses?: string[];
  marketTypes?: string[];
} = {}): Promise<number> => {
  const params = new URLSearchParams();
  params.append('page', '0');
  params.append('size', '1');
  filters.shippingStatuses?.forEach((s) => params.append('shippingStatuses', s));
  filters.customsStatuses?.forEach((s) => params.append('customsStatuses', s));
  filters.marketTypes?.forEach((m) => params.append('marketTypes', m));
  const { data } = await apiClient.get<PageResponse<unknown>>(`/api/v1/orders?${params.toString()}`);
  return data.totalElements ?? 0;
};

// Note: updateOrder currently updates Order entity using id.
export const updateOrder = async (id: number, updateData: Record<string, unknown>): Promise<unknown> => {
  const { data } = await apiClient.patch(`/api/v1/orders/${id}`, updateData);
  return data;
};

// 소싱 정보 수정
export const updateSourcingInfo = async (lineItemId: number, data: {
  sourcingAccount?: string;
  sourcingOrderNo?: string;
  sourcingAmount?: number;
  logisticsCost?: number;
  discountCode?: string;
  sourcingVendor?: string;
}): Promise<unknown> => {
  const response = await apiClient.patch(`/api/v1/orders/line-items/${lineItemId}/sourcing`, data);
  return response.data;
};

// 배송 정보 수정
export const updateShippingInfo = async (lineItemId: number, data: {
  trackingNo?: string;
  shippingCarrier?: string;
}): Promise<unknown> => {
  const response = await apiClient.patch(`/api/v1/orders/line-items/${lineItemId}/shipping`, data);
  return response.data;
};

// 라인아이템 자체 필드(유니패스 신고 완료 여부) 수정
// 백엔드 OrderLineItemUpdateRequest는 isUnipassDone만 받는다. 소싱금액/물류비는 updateSourcingInfo로 보낸다.
export const updateOrderLineItem = async (lineItemId: number, data: {
  isUnipassDone?: boolean;
}): Promise<unknown> => {
  const response = await apiClient.patch(`/api/v1/orders/line-items/${lineItemId}`, data);
  return response.data;
};

export const syncCoupangOrders = async (): Promise<{ success: boolean; syncedCount: number; message: string }> => {
  const { data } = await apiClient.post('/api/v1/orders/sync/coupang');
  return data;
};

export const syncSmartStoreOrders = async (): Promise<{ success: boolean; message: string }> => {
  const { data } = await apiClient.post('/api/v1/orders/sync/smartstore');
  return data;
};

export const syncElevenStreetOrders = async (): Promise<{ success: boolean; message: string }> => {
  const { data } = await apiClient.post('/api/v1/orders/sync/elevenstreet');
  return data;
};

export const syncEsmplusOrders = async (): Promise<{ success: boolean; message: string }> => {
  const { data } = await apiClient.post('/api/v1/orders/sync/esmplus');
  return data;
};

export const syncCustomsStatus = async (): Promise<{ success: boolean; message: string }> => {
  const { data } = await apiClient.post('/api/v1/orders/sync/customs');
  return data;
};

export const syncProductStock = async (): Promise<{ success: boolean; message: string }> => {
  const { data } = await apiClient.post('/api/v1/products/sync/stock');
  return data;
};

export interface BulkResult {
  successCount: number;
  failedCount: number;
  failedIds: number[];
  errors?: string[];
}

export interface BulkShipResult extends BulkResult {
  skippedCount?: number;
}

export const shipOrders = async (orderIds: number[]): Promise<BulkShipResult> => {
  const { data } = await apiClient.post('/api/v1/orders/ship', { orderIds });
  return data;
};

export const confirmOrder = async (id: number): Promise<unknown> => {
  const { data } = await apiClient.post(`/api/v1/orders/${id}/confirm`);
  return data;
};

export const confirmOrdersBatch = async (orderIds: number[]): Promise<BulkResult> => {
  const { data } = await apiClient.post('/api/v1/orders/confirm/batch', { orderIds });
  return data;
};

export const cancelOrder = async (id: number): Promise<unknown> => {
  const { data } = await apiClient.post(`/api/v1/orders/${id}/cancel`);
  return data;
};

export interface SyncStatus {
  marketType: string;
  status: string;
  lastSyncAt: string | null;
  errorMessage: string | null;
}

export const fetchSyncStatus = async (): Promise<Record<string, SyncStatus>> => {
  const { data } = await apiClient.get('/api/v1/orders/sync/status');
  return data;
};

// 구매 상태 수정
export const updatePurchaseStatus = async (
  lineItemId: number,
  purchaseStatus: 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK'
): Promise<OrderLineItemDto> => {
  const { data } = await apiClient.patch(
    `/api/v1/orders/line-items/${lineItemId}/purchase-status`,
    { purchaseStatus }
  );
  return data;
};
