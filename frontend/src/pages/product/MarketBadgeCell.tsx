import type { CSSProperties } from 'react';
import type { ProductList } from '../../api/productApi';
import { MARKET_BADGES, badgeVisual } from './productGridShared';

const baseStyle: CSSProperties = {
  fontSize: 11, fontWeight: 600, padding: '2px 6px', borderRadius: 4, lineHeight: 1.5,
  whiteSpace: 'nowrap',
};

export function MarketBadgeCell({ product }: { product: ProductList }) {
  const regs = product.marketRegistrations ?? {};
  return (
    // nowrap: 6개 마켓이 한 줄에 모두 보이도록(줄바꿈 방지). 컬럼 폭은 ProductGrid에서 확보.
    <div style={{ display: 'flex', flexWrap: 'nowrap', gap: 3, alignItems: 'center', justifyContent: 'center' }}>
      {MARKET_BADGES.map((m) => {
        const visual = badgeVisual(product, m.key);
        if (visual === 'registered') {
          return (
            <a key={m.key} href={regs[m.key].url as string} target="_blank" rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()} title={`${m.label} 상품 페이지 열기`}
              style={{ ...baseStyle, color: m.text, background: m.bg, textDecoration: 'none', cursor: 'pointer' }}>
              {m.label}
            </a>
          );
        }
        if (visual === 'linkless') {
          return (
            <span key={m.key} title={`${m.label} 등록됨 · 링크 식별자 미확보`}
              style={{ ...baseStyle, color: m.text, background: '#fff', border: `1px solid ${m.text}`, opacity: 0.5 }}>
              {m.label}
            </span>
          );
        }
        if (visual === 'blocked') {
          return (
            <span key={m.key} title={`${m.label} — 카페24 등록 후 가능`}
              style={{ ...baseStyle, color: '#cbd5e1', background: '#fff', border: '1px dashed #e2e8f0',
                cursor: 'not-allowed' }}>
              {m.label}
            </span>
          );
        }
        return (
          <span key={m.key} title={`${m.label} 미등록`}
            style={{ ...baseStyle, color: '#94a3b8', background: '#fff', border: '1px dashed #cbd5e1' }}>
            {m.label}
          </span>
        );
      })}
    </div>
  );
}
