import type { CSSProperties } from 'react';
import type { ProductList } from '../../api/productApi';

export type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';

type BadgeVisual = 'registered' | 'registeredNoLink' | 'linkless' | 'pending' | 'missing' | 'blocked'
  | 'deleted' | 'failed';

export const inputStyle: CSSProperties = {
  width: '100%', padding: '4px 6px', fontSize: '12px', border: '1px solid #d1d5db',
  borderRadius: '4px', boxSizing: 'border-box', outline: 'none', backgroundColor: '#fdfdfd',
};

export const MARKET_FILTER_OPTIONS: { id: string; label: string }[] = [
  { id: 'COUPANG', label: '쿠팡' },
  { id: 'SMART_STORE', label: 'N스토어' },
  { id: 'ELEVEN_STREET', label: '11번가' },
  { id: 'GMARKET', label: 'G마켓' },
  { id: 'AUCTION', label: '옥션' },
  { id: 'CAFE24', label: '카페24' },
];

export const VENDOR_OPTIONS: string[] = ['IHB', 'AMZ', 'FTN', 'COK', 'OCD', 'TES', 'VTB'];

export const STOCK_STATUS_OPTIONS: { id: 'IN_STOCK' | 'OUT_OF_STOCK'; label: string }[] = [
  { id: 'IN_STOCK', label: '판매중' },
  { id: 'OUT_OF_STOCK', label: '품절' },
];

export const MARKET_BADGES: { key: string; label: string; bg: string; text: string }[] = [
  { key: 'COUPANG', label: '쿠팡', bg: '#fce4ec', text: '#c2185b' },
  { key: 'SMART_STORE', label: 'N스토어', bg: '#f1f8e9', text: '#689f38' },
  { key: 'CAFE24', label: '카페24', bg: '#ede7f6', text: '#5e35b1' },
  { key: 'GMARKET', label: 'G마켓', bg: '#c8e6c9', text: '#1b5e20' },
  { key: 'AUCTION', label: '옥션', bg: '#fff3e0', text: '#e65100' },
  { key: 'ELEVEN_STREET', label: '11번가', bg: '#e3f2fd', text: '#1565c0' },
];

export const ESM_MARKET_KEYS = ['GMARKET', 'AUCTION'];

const NO_LINK_MARKET_KEYS = ['CAFE24'];

export const DEFAULT_MARKET_MARGIN_RATE = 15;

export const DEFAULT_MARKET_COUPON_RATE = 20;

export const DEFAULT_MARKET_MIN_MARGIN_PRICE = 5000;

export function statusBorder(status: SaveStatus): string {
  switch (status) {
    case 'dirty': return '#f59e0b';
    case 'saving': return '#3b82f6';
    case 'saved': return '#22c55e';
    case 'error': return '#ef4444';
    default: return '#d1d5db';
  }
}

export const UNSYNC_REASON_LABEL: Record<string, string> = {
  DELETED_ON_MARKET: '마켓에서 삭제된 상품입니다',
  VALIDATION_FAILED: '마켓이 데이터를 거부했습니다 — 상품 정보를 고쳐야 합니다',
  TRANSIENT_ERROR: '일시 오류로 반영되지 않았습니다 — 재시도하면 풀릴 수 있습니다',
  NEVER_SYNCED: '한 번도 동기화된 적이 없습니다',
};

export function badgeVisual(product: ProductList, marketKey: string): BadgeVisual {
  const regs = product.marketRegistrations ?? {};
  const state = regs[marketKey];
  if (state) {
    if (state.status === 'DELETED') return 'deleted';
    if (state.status === 'FAILED') return 'failed';
    if (state.status === 'PENDING') return 'pending';
    if (state.url) return 'registered';
    return NO_LINK_MARKET_KEYS.includes(marketKey) ? 'registeredNoLink' : 'linkless';
  }
  if (ESM_MARKET_KEYS.includes(marketKey) && !regs['CAFE24']) return 'blocked';
  return 'missing';
}
