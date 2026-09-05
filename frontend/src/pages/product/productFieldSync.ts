import type { FieldSyncResponse } from '../../api/productApi';

export type SyncableField = 'brand' | 'productName' | 'manufacturer';

export const SYNCABLE_FIELDS: SyncableField[] = ['brand', 'productName', 'manufacturer'];

export const SYNC_FIELD_LABEL: Record<SyncableField, string> = {
  brand: '브랜드',
  productName: '상품명',
  manufacturer: '제조사',
};

export const SYNC_FIELD_TO_MARKET_FIELD: Record<SyncableField, string> = {
  brand: 'BRAND',
  productName: 'PRODUCT_NAME',
  manufacturer: 'MANUFACTURER',
};

export const SYNC_MARKETS = ['SMART_STORE', 'ELEVEN_STREET', 'CAFE24'] as const;
export type SyncMarket = typeof SYNC_MARKETS[number];

export const SYNC_MARKET_CHIP_LABEL: Record<SyncMarket, string> = {
  SMART_STORE: '스',
  ELEVEN_STREET: '11',
  CAFE24: '카',
};

export const LOCKED_MARKET = 'COUPANG';

const MARKET_FIELD_SUPPORT: Record<SyncMarket, Set<SyncableField>> = {
  SMART_STORE: new Set(['brand', 'productName', 'manufacturer']),
  ELEVEN_STREET: new Set(['brand', 'productName', 'manufacturer']),
  CAFE24: new Set(['brand', 'productName']),
};

export function fieldSupportedByMarket(field: SyncableField, market: string): boolean {
  const supported = MARKET_FIELD_SUPPORT[market as SyncMarket];
  return supported ? supported.has(field) : false;
}

export function changedSyncableFields<T extends Partial<Record<SyncableField, unknown>>>(
  baseline: T,
  fields: T,
): SyncableField[] {
  return SYNCABLE_FIELDS.filter((f) => (baseline[f] ?? '') !== (fields[f] ?? ''));
}

export function marketSupportsAnyField(market: string, changedFields: SyncableField[]): boolean {
  return changedFields.some((f) => fieldSupportedByMarket(f, market));
}

export type FieldSyncResult = FieldSyncResponse;

export function mergeSyncResult(
  prev: FieldSyncResult | null,
  next: FieldSyncResult,
  requestedMarkets: string[],
): FieldSyncResult {
  if (!prev) return next;
  const strip = (markets: string[]) => markets.filter((m) => !requestedMarkets.includes(m));
  const failed: Record<string, string> = {};
  Object.entries(prev.failed).forEach(([m, reason]) => {
    if (!requestedMarkets.includes(m)) failed[m] = reason;
  });
  Object.assign(failed, next.failed);
  return {
    success: prev.success && next.success,
    batchId: next.batchId,
    synced: [...strip(prev.synced), ...next.synced],
    skipped: [...strip(prev.skipped), ...next.skipped],
    failed,
  };
}

export type SyncRowStatus = 'synced' | 'skipped' | 'failed';

export interface SyncRow {
  market: string;
  status: SyncRowStatus;
  reason?: string;
}

export function buildSyncRows(result: FieldSyncResult): SyncRow[] {
  const rows: SyncRow[] = [];
  for (const market of SYNC_MARKETS) {
    if (result.synced.includes(market)) rows.push({ market, status: 'synced' });
    else if (result.skipped.includes(market)) rows.push({ market, status: 'skipped' });
    else if (result.failed[market]) rows.push({ market, status: 'failed', reason: result.failed[market] });
  }
  return rows;
}
