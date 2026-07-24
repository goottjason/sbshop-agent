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
  { id: 'SMART_STORE', label: 'N스토어' },
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

// ─── 마켓 등록 배지 ───
// 통합 주문 관리 배지와 동일한 파스텔 팔레트(연배경 + 채도 낮춘 글자색)를 채용한다.
const MARKET_BADGES: { key: string; label: string; bg: string; text: string }[] = [
  { key: 'COUPANG', label: '쿠팡', bg: '#fce4ec', text: '#c2185b' },
  { key: 'SMART_STORE', label: 'N스토어', bg: '#e8f5e9', text: '#2e7d32' },
  { key: 'GMARKET', label: 'G마켓', bg: '#dcedc8', text: '#33691e' },
  { key: 'AUCTION', label: '옥션', bg: '#fff3e0', text: '#e65100' },
  { key: 'ELEVEN_STREET', label: '11번가', bg: '#e3f2fd', text: '#1565c0' },
];

export function renderMarketBadges(links?: Record<string, string>) {
  if (!links) return <span style={{ color: '#ccc' }}>-</span>;
  const badges = MARKET_BADGES.filter((m) => links[m.key] !== undefined);
  if (badges.length === 0) return <span style={{ color: '#ccc' }}>-</span>;
  return (
    // nowrap: 5개 마켓이 한 줄에 모두 보이도록(줄바꿈 방지). 컬럼 폭은 ProductGrid에서 확보.
    <div style={{ display: 'flex', flexWrap: 'nowrap', gap: 3, alignItems: 'center', justifyContent: 'center' }}>
      {badges.map((m) => {
        const url = links[m.key];
        const base: CSSProperties = {
          fontSize: 11, fontWeight: 600, padding: '2px 6px', borderRadius: 4, lineHeight: 1.5,
          whiteSpace: 'nowrap',
        };
        if (url) {
          return (
            <a key={m.key} href={url} target="_blank" rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()} title={`${m.label} 상품 페이지 열기`}
              style={{ ...base, color: m.text, background: m.bg, textDecoration: 'none', cursor: 'pointer' }}>
              {m.label}
            </a>
          );
        }
        return (
          <span key={m.key} title={`${m.label} 등록됨 · 링크 식별자 미확보`}
            style={{ ...base, color: m.text, background: '#fff', border: `1px solid ${m.text}`, opacity: 0.5 }}>
            {m.label}
          </span>
        );
      })}
    </div>
  );
}
