import { useState, type CSSProperties } from 'react';
import { Modal as AntModal } from 'antd';
import { toast } from 'react-toastify';
import { productApi, type ProductList } from '../../api/productApi';
import { sourcingApi } from '../../api/sourcingApi';
import { MARKET_BADGES, badgeVisual, ESM_MARKET_KEYS } from './productGridShared';

const baseStyle: CSSProperties = {
  fontSize: 11, fontWeight: 600, padding: '2px 6px', borderRadius: 4, lineHeight: 1.5,
  whiteSpace: 'nowrap',
};

export function MarketBadgeCell({ product, onPublished }:
  { product: ProductList; onPublished: () => void }) {
  const regs = product.marketRegistrations ?? {};
  // 등록 진행/실패는 서버에 저장되지 않는 화면 세션 상태다(새로고침하면 서버 상태로 복원).
  const [publishing, setPublishing] = useState<string | null>(null);
  const [failed, setFailed] = useState<Record<string, string>>({});

  const publish = (marketKey: string, label: string) => {
    AntModal.confirm({
      title: `${label} 등록`,
      content: `'${label}'에 해당 상품을 등록하시겠습니까?`,
      okText: '등록', cancelText: '취소',
      onOk: async () => {
        setPublishing(marketKey);
        setFailed((f) => { const next = { ...f }; delete next[marketKey]; return next; });
        try {
          await sourcingApi.publishToMarket(product.id, marketKey);
          toast.success(`${label} 등록 완료 — ${product.sbCode}`);
          onPublished();
        } catch (e) {
          // 실패를 조용히 삼키지 않는다. 사유를 배지 툴팁과 토스트 양쪽에 남긴다.
          const msg = extractError(e);
          setFailed((f) => ({ ...f, [marketKey]: msg }));
          toast.error(`${label} 등록 실패 — ${msg}`);
        } finally {
          setPublishing(null);
        }
      },
    });
  };

  // G마켓·옥션: 자동 등록이 불가능하므로 사람을 마켓플러스로 데려간다.
  // 조회를 먼저 끝내고 다이얼로그 확인(사용자 제스처) 안에서 새 탭을 연다 — 조회 후에 열면 팝업이 차단된다.
  const handoff = async (marketKey: string, label: string) => {
    setPublishing(marketKey);
    setFailed((f) => { const next = { ...f }; delete next[marketKey]; return next; });
    try {
      const { data } = await productApi.getMarketPlusHandoff(product.id, marketKey);
      AntModal.confirm({
        title: `${label} 전송 (마켓플러스)`,
        content: `${label}는 상품등록 API가 없어 마켓플러스에서 직접 보내야 합니다. ${data.guide}`,
        okText: '마켓플러스 열기', cancelText: '취소',
        onOk: () => {
          navigator.clipboard?.writeText(data.cafe24ProductCode);
          window.open(data.marketplusUrl, '_blank', 'noopener');
          toast.info(`상품코드 ${data.cafe24ProductCode} 를 복사했습니다.`);
        },
      });
    } catch (e) {
      const msg = extractError(e);
      setFailed((f) => ({ ...f, [marketKey]: msg }));
      toast.error(`${label} 전송 준비 실패 — ${msg}`);
    } finally {
      setPublishing(null);
    }
  };

  return (
    // nowrap: 6개 마켓이 한 줄에 모두 보이도록(줄바꿈 방지). 컬럼 폭은 ProductGrid에서 확보.
    <div style={{ display: 'flex', flexWrap: 'nowrap', gap: 3, alignItems: 'center', justifyContent: 'center' }}>
      {MARKET_BADGES.map((m) => {
        if (publishing === m.key) {
          return (
            <span key={m.key} title={`${m.label} 등록 진행 중`}
              style={{ ...baseStyle, color: '#475569', background: '#f1f5f9',
                border: '1px solid #cbd5e1', animation: 'pulse 1.2s ease-in-out infinite' }}>
              등록중…
            </span>
          );
        }
        if (failed[m.key]) {
          return (
            <span key={m.key} title={`${m.label} 등록 실패 — ${failed[m.key]} (다시 클릭하면 재시도)`}
              onClick={(e) => {
                e.stopPropagation();
                if (ESM_MARKET_KEYS.includes(m.key)) handoff(m.key, m.label);
                else publish(m.key, m.label);
              }}
              style={{ ...baseStyle, color: '#dc2626', background: '#fff',
                border: '1px dashed #dc2626', cursor: 'pointer' }}>
              {m.label}
            </span>
          );
        }
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
          <span key={m.key}
            title={ESM_MARKET_KEYS.includes(m.key)
              ? `${m.label} 미등록 — 클릭하면 마켓플러스로 이동합니다`
              : `${m.label} 미등록 — 클릭하면 등록합니다`}
            onClick={(e) => {
              e.stopPropagation();
              if (ESM_MARKET_KEYS.includes(m.key)) handoff(m.key, m.label);
              else publish(m.key, m.label);
            }}
            style={{ ...baseStyle, color: '#94a3b8', background: '#fff',
              border: '1px dashed #cbd5e1', cursor: 'pointer' }}>
            {m.label}
          </span>
        );
      })}
    </div>
  );
}

// axios 오류에서 사용자에게 보여줄 사유를 뽑는다. 백엔드는 { message } 또는 { error }로 내려준다.
function extractError(e: unknown): string {
  const res = (e as { response?: { data?: Record<string, unknown>; status?: number } }).response;
  const data = res?.data;
  const msg = (data?.message ?? data?.error) as string | undefined;
  if (msg) return msg;
  if (res?.status === 409) return '카페24 등록이 먼저 필요합니다';
  return '알 수 없는 오류';
}
