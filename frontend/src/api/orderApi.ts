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
    trackingSentToMarket?: boolean | null;
  };
}

export interface ProductDto {
  id?: number;
  sbCode?: string;
  category?: string;
  vendor?: string;
  productName?: string;
  originalName?: string;
  sourcingInfo?: {
    sourceUrl?: string;
  };
  stockStatus?: string;
  restockDate?: string;
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

export interface ShipmentDto {
  id?: number;
  marketShipmentNo?: string;
  trackingNo?: string;
  marketTrackingNo?: string | null;
  manualFixRequired?: boolean | null;
  shippingCarrier?: string;
  deliveryStatus?: string | null;
  trackingSource?: 'EMAIL' | 'MANUAL' | 'MARKET' | null;
}

interface OrderLineItemDetailDto {
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

interface BulkResult {
  successCount: number;
  failedCount: number;
  failedIds: number[];
  errors?: string[];
}

interface BulkShipResult extends BulkResult {
  skippedCount?: number;
}

interface SyncStatus {
  marketType: string;
  status: string;
  lastSyncAt: string | null;
  errorMessage: string | null;
}

export const fetchCommonCodes = async () => {
  const response = await apiClient.get('/api/v1/common/codes');
  return response.data;
};

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

export const updateOrder = async (id: number, updateData: Record<string, unknown>): Promise<unknown> => {
  const { data } = await apiClient.patch(`/api/v1/orders/${id}`, updateData);
  return data;
};

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

export const updateShippingInfo = async (lineItemId: number, data: {
  trackingNo?: string;
  shippingCarrier?: string;
}): Promise<unknown> => {
  const response = await apiClient.patch(`/api/v1/orders/line-items/${lineItemId}/shipping`, data);
  return response.data;
};

export const updateOrderLineItem = async (lineItemId: number, data: {
  isUnipassDone?: boolean;
}): Promise<unknown> => {
  const response = await apiClient.patch(`/api/v1/orders/line-items/${lineItemId}`, data);
  return response.data;
};

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

export const shipOrders = async (orderIds: number[]): Promise<BulkShipResult> => {
  const { data } = await apiClient.post('/api/v1/orders/ship', { orderIds });
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

export const fetchSyncStatus = async (): Promise<Record<string, SyncStatus>> => {
  const { data } = await apiClient.get('/api/v1/orders/sync/status');
  return data;
};
