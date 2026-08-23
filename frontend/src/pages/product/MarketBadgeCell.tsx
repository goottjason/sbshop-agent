import { useState, type CSSProperties } from 'react';
import { Modal as AntModal, InputNumber } from 'antd';
import { productApi, type ProductList } from '../../api/productApi';
import { sourcingApi } from '../../api/sourcingApi';
import { fetchPricePolicy } from '../../api/pricePolicyApi';
import {
  MARKET_BADGES, badgeVisual, ESM_MARKET_KEYS,
  DEFAULT_MARKET_MARGIN_RATE, DEFAULT_MARKET_COUPON_RATE, DEFAULT_MARKET_MIN_MARGIN_PRICE,
} from './productGridShared';
import { notify } from '../../utils/notify';

const baseStyle: CSSProperties = {
  fontSize: 11, fontWeight: 600, padding: '2px 6px', borderRadius: 4, lineHeight: 1.5,
  whiteSpace: 'nowrap',
};

function extractError(e: unknown): string {
  const res = (e as { response?: { data?: Record<string, unknown>; status?: number } }).response;
  const data = res?.data;
  const msg = (data?.message ?? data?.error) as string | undefined;
  if (msg) return msg;
  if (res?.status === 409) return '카페24 등록이 먼저 필요합니다';
  return '알 수 없는 오류';
}

export function MarketBadgeCell({ product, onPublished }:
  { product: ProductList; onPublished: () => void }) {
  const regs = product.marketRegistrations ?? {};
  const [publishing, setPublishing] = useState<string | null>(null);
  const [failed, setFailed] = useState<Record<string, string>>({});

  const [publishTarget, setPublishTarget] = useState<{ key: string; label: string } | null>(null);
  const [confirmSubmitting, setConfirmSubmitting] = useState(false);
  const [marginRate, setMarginRate] = useState<number | null>(DEFAULT_MARKET_MARGIN_RATE);
  const [couponRate, setCouponRate] = useState<number | null>(DEFAULT_MARKET_COUPON_RATE);
  const [minMarginPrice, setMinMarginPrice] = useState<number | null>(DEFAULT_MARKET_MIN_MARGIN_PRICE);

  const publish = async (marketKey: string, label: string) => {
    setPublishTarget({ key: marketKey, label });
    try {
      const policy = await fetchPricePolicy();
      setMarginRate(policy.marginRate ?? DEFAULT_MARKET_MARGIN_RATE);
      setCouponRate(policy.couponRate ?? DEFAULT_MARKET_COUPON_RATE);
      setMinMarginPrice(policy.minMarginPrice ?? DEFAULT_MARKET_MIN_MARGIN_PRICE);
    } catch {
      setMarginRate(DEFAULT_MARKET_MARGIN_RATE);
      setCouponRate(DEFAULT_MARKET_COUPON_RATE);
      setMinMarginPrice(DEFAULT_MARKET_MIN_MARGIN_PRICE);
    }
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
      notify.success(`${label} 등록 완료 — ${product.sbCode}`);
      setPublishTarget(null);
      onPublished();
    } catch (e) {
      const msg = extractError(e);
      setFailed((f) => ({ ...f, [marketKey]: msg }));
      notify.error(`${label} 등록 실패 — ${msg}`);
      setPublishTarget(null);
    } finally {
      setPublishing(null);
      setConfirmSubmitting(false);
    }
  };

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
          const copied = navigator.clipboard?.writeText(data.cafe24ProductCode);
          window.open(data.marketplusUrl, '_blank', 'noopener');
          if (copied) {
            copied
              .then(() => notify.info(`상품코드 ${data.cafe24ProductCode} 를 복사했습니다.`))
              .catch(() => notify.warning(`복사하지 못했습니다 — 상품코드 ${data.cafe24ProductCode}`));
          } else {
            notify.warning(`복사하지 못했습니다 — 상품코드 ${data.cafe24ProductCode}`);
          }
        },
      });
    } catch (e) {
      const msg = extractError(e);
      setFailed((f) => ({ ...f, [marketKey]: msg }));
      notify.error(`${label} 전송 준비 실패 — ${msg}`);
    } finally {
      setPublishing(null);
    }
  };

  return (
    <>
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
