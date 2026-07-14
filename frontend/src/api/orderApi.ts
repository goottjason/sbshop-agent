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
    bundleQuantity?: number;
  };
}

export const fetchCommonCodes = async () => {
  const response = await apiClient.get('/api/v1/common/codes');
  return response.data;
};

export interface OrderLineItemDetailDto {
  lineItem: OrderLineItemDto;
  product: ProductDto;
  marketRegistration?: unknown;
}

export interface OrderGridDto {
  order?: OrderDto;
  lineItem?: OrderLineItemDto;
  product?: ProductDto;
  marketRegistration?: unknown;
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
  endDate?: string
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

export const shipOrders = async (orderIds: number[]): Promise<unknown> => {
  const { data } = await apiClient.post('/api/v1/orders/ship', { orderIds });
  return data;
};

export const confirmOrder = async (id: number): Promise<unknown> => {
  const { data } = await apiClient.post(`/api/v1/orders/${id}/confirm`);
  return data;
};

export const confirmOrdersBatch = async (orderIds: number[]): Promise<{
  successCount: number;
  failedCount: number;
  failedIds: number[];
  errors?: string[];
}> => {
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
