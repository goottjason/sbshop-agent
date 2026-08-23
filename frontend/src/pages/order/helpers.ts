import type * as React from 'react';
import type { ProductDto, OrderDto, OrderLineItemDto, ShipmentDto } from '../../api/orderApi';
import type { StockCellInfo, SaveStatus, MarketSyncState, OrdersCache } from './types';
import { NO_SEND_STATUSES } from './constants';

export function stockCellInfo(product?: ProductDto): StockCellInfo {
  const status = product?.stockStatus;
  const updatedAt = product?.updatedAt || undefined;
  if (status === 'IN_STOCK') {
    return { badge: 'IN_STOCK', updatedAt };
  }
  if (status === 'OUT_OF_STOCK') {
    return { badge: 'OUT_OF_STOCK', restockDate: product?.restockDate || undefined, updatedAt };
  }
  return { badge: 'NONE', updatedAt };
}

export function shortAccountLabel(email: string): string {
  if (!email) return '';
  const at = email.lastIndexOf('@');
  if (at < 0) return email;
  const local = email.slice(0, at);
  const domain = email.slice(at + 1).toLowerCase();
  const DOMAIN_TAGS: Record<string, string> = {
    'gmail.com': 'G',
    'skku.edu': 'SKKU',
    'g.skku.edu': 'SKKU',
    'naver.com': 'NAVER',
    'daum.net': 'DAUM',
    'nate.com': 'NATE',
  };
  const tag = DOMAIN_TAGS[domain];
  return tag ? `${local} ${tag}` : email;
}

export function statusBorder(status: SaveStatus): string {
  switch (status) {
    case 'dirty': return '#f59e0b';
    case 'saving': return '#3b82f6';
    case 'saved': return '#22c55e';
    case 'error': return '#ef4444';
    default: return '#d1d5db';
  }
}

export function blurLeftToPage(e: React.FocusEvent<HTMLElement>): boolean {
  if (e.currentTarget.contains(e.relatedTarget as Node | null)) return false;
  return document.hasFocus();
}

export const formatThousands = (v: string | number): string => {
  const digits = String(v).replace(/[^\d]/g, '');
  return digits === '' ? '' : Number(digits).toLocaleString();
};

export const parseThousands = (v: string): number => Number(v.replace(/[^\d]/g, '')) || 0;

export const marketSyncState = (lineItem?: OrderLineItemDto, shipment?: ShipmentDto | null): MarketSyncState => {
  const shipping = lineItem?.shippingData;
  const tracking = (shipping?.trackingNo || '').trim();
  if (!tracking) return 'none';
  const status = shipping?.shippingStatus || '';
  if (NO_SEND_STATUSES.includes(status)) return 'none';
  const marketTracking = (shipment?.marketTrackingNo || '').trim();
  if (marketTracking) return marketTracking === tracking ? 'synced' : (shipment?.manualFixRequired ? 'manual' : 'waiting');
  if (shipment?.manualFixRequired) return 'manual';
  if (shipping?.trackingSentToMarket !== true && status !== 'DELIVERED') return 'waiting';
  return 'unknown';
};

export function patchOrderInCache(cache: OrdersCache | undefined, orderId: number, mutate: (o: OrderDto) => OrderDto): OrdersCache | undefined {
  if (!cache) return cache;
  return {
    ...cache,
    content: cache.content.map(item =>
      item.order?.id === orderId ? { ...item, order: mutate(item.order) } : item),
  };
}

export function patchLineItemInCache(cache: OrdersCache | undefined, lineItemId: number, mutate: (li: OrderLineItemDto) => OrderLineItemDto): OrdersCache | undefined {
  if (!cache) return cache;
  return {
    ...cache,
    content: cache.content.map(item => {
      if (!(item.lineItems || []).some(d => d.lineItem?.id === lineItemId)) return item;
      return {
        ...item,
        lineItems: (item.lineItems || []).map(d =>
          d.lineItem?.id === lineItemId ? { ...d, lineItem: mutate(d.lineItem) } : d),
      };
    }),
  };
}
