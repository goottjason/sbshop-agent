import type { CSSProperties } from 'react';
import type { ProductList } from '../../api/productApi';

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
// 순서는 화면 표시 순서 그대로다. 카페24가 G마켓·옥션의 선행조건이라 그 앞에 둔다.
export const MARKET_BADGES: { key: string; label: string; bg: string; text: string }[] = [
  { key: 'COUPANG', label: '쿠팡', bg: '#fce4ec', text: '#c2185b' },
  { key: 'SMART_STORE', label: 'N스토어', bg: '#f1f8e9', text: '#689f38' },
  { key: 'CAFE24', label: '카페24', bg: '#ede7f6', text: '#5e35b1' },
  { key: 'GMARKET', label: 'G마켓', bg: '#c8e6c9', text: '#1b5e20' },
  { key: 'AUCTION', label: '옥션', bg: '#fff3e0', text: '#e65100' },
  { key: 'ELEVEN_STREET', label: '11번가', bg: '#e3f2fd', text: '#1565c0' },
];

// ESM 계열(G마켓·옥션)은 Cafe24 등록행을 경유해야 전송할 수 있다.
export const ESM_MARKET_KEYS = ['GMARKET', 'AUCTION'];

// 카페24는 상품페이지 URL을 만들 방법이 없다 — 백엔드가 항상 MarketBadgeState.of(synced, null)로
// 내려준다(ProductController.buildMarketMap). 그래서 이 목록에 있는 마켓은 url이 없어도
// "등록됐지만 링크 미확보"(비정상, linkless)가 아니라 "정상 등록, 원래 링크가 없음"으로 판정한다.
// 다른 마켓(쿠팡·N스토어·11번가)은 링크를 만들 수 있으므로 없으면 진짜 비정상이다 — 여기 넣지 말 것.
export const NO_LINK_MARKET_KEYS = ['CAFE24'];

// 배지 1칸이 가질 수 있는 화면 상태.
//  registered       등록 완료 + 상품페이지 링크 확보 → 채색 배지, 클릭 시 새 탭
//  registeredNoLink 등록 완료 + 애초에 링크를 만들 수 없는 마켓(카페24) → 채색 배지, 클릭 없음(<span>)
//  linkless         등록됐으나 링크 식별자 미확보(비정상) → 채색 테두리 반투명, 클릭 없음
//  missing          미등록 → 점선 배지, 클릭 시 등록
//  blocked          미등록 + 선행조건 미충족(카페24 미등록 상태의 G마켓·옥션) → 흐린 점선, 클릭 불가
export type BadgeVisual = 'registered' | 'registeredNoLink' | 'linkless' | 'missing' | 'blocked';

// MarketBadgeCell.tsx에 두면 react-refresh/only-export-components에 걸린다(컴포넌트 파일은
// 컴포넌트만 export해야 HMR이 안전). 상수/헬퍼 전용인 이 파일로 옮겨 둔다.
export function badgeVisual(product: ProductList, marketKey: string): BadgeVisual {
  const regs = product.marketRegistrations ?? {};
  const state = regs[marketKey];
  if (state) {
    if (state.url) return 'registered';
    return NO_LINK_MARKET_KEYS.includes(marketKey) ? 'registeredNoLink' : 'linkless';
  }
  // 카페24 등록행이 없으면 G마켓·옥션은 마켓플러스로 보낼 수 없다.
  if (ESM_MARKET_KEYS.includes(marketKey) && !regs['CAFE24']) return 'blocked';
  return 'missing';
}
