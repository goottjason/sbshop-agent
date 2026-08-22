export const MARKET_LABELS: Record<string, string> = {
  COUPANG: '쿠팡',
  SMART_STORE: 'N스토어',
  ELEVEN_STREET: '11번가',
  GMARKET: 'G마켓',
  AUCTION: '옥션',
  CAFE24: '카페24',
  UNKNOWN: '알 수 없음',
  EMAIL: '이메일',
  COUPANG_SETTLEMENT: '쿠팡 정산',
};

export const ORDER_MARKET_CODES: string[] = [
  'COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION',
];

export function marketLabel(code?: string | null): string {
  if (!code) return '';
  return MARKET_LABELS[code] ?? code;
}

export const SYNC_SOURCE_KEYS: string[] = [
  'COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'GMARKET', 'EMAIL', 'COUPANG_SETTLEMENT',
];

export const SYNC_SOURCE_LABELS: Record<string, string> = {
  ...MARKET_LABELS,
  GMARKET: 'G마켓/옥션',
};

export function syncSourceLabel(code?: string | null): string {
  if (!code) return '';
  return SYNC_SOURCE_LABELS[code] ?? code;
}
