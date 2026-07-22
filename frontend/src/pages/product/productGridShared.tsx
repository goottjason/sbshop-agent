import type { CSSProperties } from 'react';

// ─── 인라인 편집 공통 저장상태 ───
export type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';
export function statusBorder(status: SaveStatus): string {
  switch (status) {
    case 'dirty': return '#f59e0b';
    case 'saving': return '#3b82f6';
    case 'saved': return '#22c55e';
    case 'error': return '#ef4444';
    default: return '#d1d5db';
  }
}

export const inputStyle: CSSProperties = {
  width: '100%', padding: '4px 6px', fontSize: '12px', border: '1px solid #d1d5db',
  borderRadius: '4px', boxSizing: 'border-box', outline: 'none', backgroundColor: '#fdfdfd',
};

// ─── 필터/표시용 상수 ───
export const MARKET_FILTER_OPTIONS: { id: string; label: string }[] = [
  { id: 'COUPANG', label: '쿠팡' },
  { id: 'SMART_STORE', label: '스마트스토어' },
  { id: 'ELEVEN_STREET', label: '11번가' },
  { id: 'GMARKET', label: 'G마켓' },
  { id: 'AUCTION', label: '옥션' },
  { id: 'CAFE24', label: '카페24' },
];

// 소싱처(벤더) — OrderGrid VENDOR_OPTIONS와 동일 출처. 빈값 제외.
export const VENDOR_OPTIONS: string[] = ['IHB', 'AMZ', 'FTN', 'COK', 'OCD', 'TES', 'VTB'];

export const STOCK_STATUS_OPTIONS: { id: 'IN_STOCK' | 'OUT_OF_STOCK'; label: string }[] = [
  { id: 'IN_STOCK', label: '판매중' },
  { id: 'OUT_OF_STOCK', label: '품절' },
];

// ─── 마켓 등록 배지 (ProductPage.renderMarketBadges 이식) ───
const MARKET_BADGES: { key: string; label: string; color: string }[] = [
  { key: 'COUPANG', label: '쿠팡', color: '#e53935' },
  { key: 'SMART_STORE', label: 'N스토어', color: '#22c55e' },
  { key: 'GMARKET', label: 'G마켓', color: '#16a34a' },
  { key: 'AUCTION', label: '옥션', color: '#dc2626' },
  { key: 'ELEVEN_STREET', label: '11번가', color: '#e11d48' },
];

export function renderMarketBadges(links?: Record<string, string>) {
  if (!links) return <span style={{ color: '#ccc' }}>-</span>;
  const badges = MARKET_BADGES.filter((m) => links[m.key] !== undefined);
  if (badges.length === 0) return <span style={{ color: '#ccc' }}>-</span>;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center', justifyContent: 'center' }}>
      {badges.map((m) => {
        const url = links[m.key];
        const base: CSSProperties = {
          fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 10, lineHeight: 1.5,
          border: `1px solid ${m.color}`,
        };
        if (url) {
          return (
            <a key={m.key} href={url} target="_blank" rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()} title={`${m.label} 상품 페이지 열기`}
              style={{ ...base, color: '#fff', background: m.color, textDecoration: 'none', cursor: 'pointer' }}>
              {m.label}
            </a>
          );
        }
        return (
          <span key={m.key} title={`${m.label} 등록됨 · 링크 식별자 미확보`}
            style={{ ...base, color: m.color, background: '#fff', opacity: 0.55 }}>
            {m.label}
          </span>
        );
      })}
    </div>
  );
}
