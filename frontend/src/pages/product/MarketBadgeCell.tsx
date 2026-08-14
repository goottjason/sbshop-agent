import { useState, type CSSProperties } from 'react';
import { Modal as AntModal, InputNumber } from 'antd';
import { toast } from 'react-toastify';
import { productApi, type ProductList } from '../../api/productApi';
import { sourcingApi } from '../../api/sourcingApi';
import {
  MARKET_BADGES, badgeVisual, ESM_MARKET_KEYS,
  DEFAULT_MARKET_MARGIN_RATE, DEFAULT_MARKET_COUPON_RATE, DEFAULT_MARKET_MIN_MARGIN_PRICE,
} from './productGridShared';

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

  // 결함 B: 등록가가 쿠폰율·최소마진 미반영으로 목표가보다 크게 높게 올라간다(정기 재가격
  // 배치는 D-093으로 비활성). AntModal.confirm의 문구만으로는 값을 받을 수 없어 제어형 Modal로
  // 바꾸고, ProductGrid.tsx 일괄 업데이트 모달과 같은 기본값(15/20/5000)으로 입력받는다.
  const [publishTarget, setPublishTarget] = useState<{ key: string; label: string } | null>(null);
  const [confirmSubmitting, setConfirmSubmitting] = useState(false);
  const [marginRate, setMarginRate] = useState<number | null>(DEFAULT_MARKET_MARGIN_RATE);
  const [couponRate, setCouponRate] = useState<number | null>(DEFAULT_MARKET_COUPON_RATE);
  const [minMarginPrice, setMinMarginPrice] = useState<number | null>(DEFAULT_MARKET_MIN_MARGIN_PRICE);

  const publish = (marketKey: string, label: string) => {
    setPublishTarget({ key: marketKey, label });
  };

  const confirmPublish = async () => {
    if (!publishTarget) return;
    const { key: marketKey, label } = publishTarget;
    setConfirmSubmitting(true);
    setPublishing(marketKey);
    setFailed((f) => { const next = { ...f }; delete next[marketKey]; return next; });
    try {
      await sourcingApi.publishToMarket(product.id, marketKey, {
        marginRate: marginRate ?? DEFAULT_MARKET_MARGIN_RATE,
        couponRate: couponRate ?? DEFAULT_MARKET_COUPON_RATE,
        minMarginPrice: minMarginPrice ?? DEFAULT_MARKET_MIN_MARGIN_PRICE,
      });
      toast.success(`${label} 등록 완료 — ${product.sbCode}`);
      setPublishTarget(null);
      onPublished();
    } catch (e) {
      // 실패를 조용히 삼키지 않는다. 사유를 배지 툴팁과 토스트 양쪽에 남긴다.
      const msg = extractError(e);
      setFailed((f) => ({ ...f, [marketKey]: msg }));
      toast.error(`${label} 등록 실패 — ${msg}`);
      setPublishTarget(null);
    } finally {
      setPublishing(null);
      setConfirmSubmitting(false);
    }
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
          // window.open은 사용자 제스처 콜스택 안에서 동기 실행돼야 팝업이 차단되지 않는다.
          // writeText를 await하면 그 콜스택이 끊기므로, 순서는 그대로 두고 성공/실패 토스트만
          // 프로미스가 끝난 뒤 .then()/.catch()로 나중에 띄운다 — 실패를 조용히 삼키지 않는다.
          const copied = navigator.clipboard?.writeText(data.cafe24ProductCode);
          window.open(data.marketplusUrl, '_blank', 'noopener');
          if (copied) {
            copied
              .then(() => toast.info(`상품코드 ${data.cafe24ProductCode} 를 복사했습니다.`))
              .catch(() => toast.warning(`복사하지 못했습니다 — 상품코드 ${data.cafe24ProductCode}`));
          } else {
            toast.warning(`복사하지 못했습니다 — 상품코드 ${data.cafe24ProductCode}`);
          }
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
    <>
    {/* nowrap: 6개 마켓이 한 줄에 모두 보이도록(줄바꿈 방지). 컬럼 폭은 ProductGrid에서 확보. */}
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
        if (visual === 'registeredNoLink') {
          // 카페24는 상품페이지 URL을 만들 방법이 없다 — 정상 등록이므로 채색은 registered와 동일,
          // 다만 열 링크가 없으므로 <a>가 아니라 <span>이고 클릭도 없다.
          return (
            <span key={m.key} title={`${m.label} 등록됨`}
              style={{ ...baseStyle, color: m.text, background: m.bg }}>
              {m.label}
            </span>
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
        if (visual === 'pending') {
          // 등록 요청은 커밋됐지만(고아 방지) 마켓 동기화가 안 끝난 상태 — 성공/실패를 알 수 없다.
          // 재시도 버튼을 두면 실제로는 등록에 성공했는데 행 갱신만 실패한 경우 마켓에 상품이
          // 두 번 올라간다. 그래서 클릭을 아예 막고, 사용자가 마켓에서 직접 확인하도록 안내한다.
          return (
            <span key={m.key}
              title={`${m.label} 등록 미완료 — 등록 요청은 접수됐으나 마켓 동기화 결과를 확인하지 못했습니다. ${m.label}에서 실제 등록 여부를 직접 확인하세요. 중복 등록 위험 때문에 여기서는 재시도를 지원하지 않습니다.`}
              style={{ ...baseStyle, color: '#b45309', background: '#fffbeb', border: '1px solid #f59e0b', cursor: 'default' }}>
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
    <AntModal
      title={publishTarget ? `${publishTarget.label} 등록` : ''}
      open={publishTarget != null}
      onCancel={() => setPublishTarget(null)}
      onOk={confirmPublish}
      okText="등록" cancelText="취소"
      confirmLoading={confirmSubmitting}
    >
      {publishTarget && (
        <>
          <p>{`'${publishTarget.label}'에 해당 상품을 등록하시겠습니까?`}</p>
          {/* 쿠폰율·최소마진 미반영으로 등록가가 목표가보다 높게 올라가는 문제(결함 B) —
              정기 재가격 배치가 비활성이라 등록 시점에 직접 입력받는다. */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 12 }}>
            <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ color: '#374151' }}>마진율 (%)</span>
              <InputNumber min={0} value={marginRate} onChange={setMarginRate} style={{ width: 140 }} />
            </label>
            <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ color: '#374151' }}>쿠폰율 (구매시 할인율, %)</span>
              <InputNumber min={0} value={couponRate} onChange={setCouponRate} style={{ width: 140 }} />
            </label>
            <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ color: '#374151' }}>최소 마진가 (원)</span>
              <InputNumber min={0} step={100} value={minMarginPrice} onChange={setMinMarginPrice} style={{ width: 140 }} />
            </label>
          </div>
        </>
      )}
    </AntModal>
    </>
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
