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
  sourcingData?: {
    sourcingAccount?: string;
    sourcingOrderNo?: string;
    sourcingAmount?: number;
    discountCode?: string;
  };
  settlementData?: {
    settlementAmount?: number;
    shippingFee?: number;
    settlementVerified?: boolean;
  };
  shippingData?: {
    trackingNo?: string;
    isUnipassDone?: boolean;
    shippingStatus?: string;
    shippingCarrier?: string;
  };
}

export interface ProductDto {
  id?: number;
  sbCode?: string;
  category?: string;
  vendor?: string;
  productName?: {
    productName?: string;
    originalName?: string;
  };
  sourcingInfo?: {
    url?: string;
  };
  stockStatus?: string;
  restockDate?: string;
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
  marketRegistration?: any;
}

export interface OrderGridDto {
  order?: OrderDto;
  lineItem?: OrderLineItemDto;
  product?: ProductDto;
  marketRegistration?: any;
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

// Note: updateOrder currently updates Order entity using id.
export const updateOrder = async (id: number, updateData: any): Promise<any> => {
  const { data } = await apiClient.patch(`/api/v1/orders/${id}`, updateData);
  return data;
};

export const updateOrderLineItem = async (id: number, updateData: any): Promise<any> => {
  const { data } = await apiClient.patch(`/api/v1/orders/line-items/${id}`, updateData);
  return data;
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

export const shipOrders = async (orderIds: number[]): Promise<any> => {
  const { data } = await apiClient.post('/api/v1/orders/ship', { orderIds });
  return data;
};

export const confirmOrder = async (id: number): Promise<any> => {
  const { data } = await apiClient.post(`/api/v1/orders/${id}/confirm`);
  return data;
};

export const confirmOrdersBatch = async (orderIds: number[]): Promise<{
  success: boolean;
  result: {
    successCount: number;
    failedCount: number;
    failedIds: number[];
    errors?: string[];
  };
}> => {
  const { data } = await apiClient.post('/api/v1/orders/confirm/batch', { orderIds });
  return data;
};

export const cancelOrder = async (id: number): Promise<any> => {
  const { data } = await apiClient.post(`/api/v1/orders/${id}/cancel`);
  return data;
};

// 구매 처리 (PREPARING → PURCHASED)
export const purchaseItem = async (lineItemId: number, data: {
  sourcingAccount?: string;
  sourcingOrderNo: string;
  discountCode?: string;
  sourcingVendor?: string;
}): Promise<any> => {
  const response = await apiClient.post(`/api/v1/orders/line-items/${lineItemId}/purchase`, data);
  return response.data;
};

// 배송 처리 (PURCHASED → SHIPPED)
export const shipItem = async (lineItemId: number, data: {
  trackingNo: string;
  carrier: string;
}): Promise<any> => {
  const response = await apiClient.post(`/api/v1/orders/line-items/${lineItemId}/ship`, data);
  return response.data;
};

// 송장 수정 (SHIPPED 상태)
export const updateTracking = async (lineItemId: number, data: {
  trackingNo: string;
  carrier: string;
}): Promise<any> => {
  const response = await apiClient.put(`/api/v1/orders/line-items/${lineItemId}/tracking`, data);
  return response.data;
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
