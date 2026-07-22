import { useEffect, useRef, useState } from 'react';
import { inputStyle } from './productGridShared';

// 판매가 + 판매상태(품절) 세트 편집. blur 자동저장이 아니라 명시적 [전송] 버튼으로 1회 커밋.
// (마켓 API 실호출이므로 OrderGrid의 ShippingEditCell과 동일한 규율)
export function PriceStockEditCell({ salePrice, soldOut, onSave }: {
  salePrice: number;
  soldOut: boolean;
  onSave: (v: { price: number; soldOut: boolean }) => Promise<unknown>;
}) {
  const [draftPrice, setDraftPrice] = useState(String(salePrice ?? 0));
  const [draftSoldOut, setDraftSoldOut] = useState(soldOut);
  const [sending, setSending] = useState(false);
  const focusedInside = useRef(false);

  useEffect(() => {
    if (!focusedInside.current) { setDraftPrice(String(salePrice ?? 0)); setDraftSoldOut(soldOut); }
  }, [salePrice, soldOut]);

  const priceNum = Number(draftPrice) || 0;
  const changed = priceNum !== (salePrice ?? 0) || draftSoldOut !== soldOut;
  const canSend = changed && !sending && priceNum >= 0;
  const border = changed ? '#f59e0b' : '#d1d5db';

  const send = () => {
    if (!canSend) return;
    setSending(true);
    onSave({ price: priceNum, soldOut: draftSoldOut })
      .catch(() => { /* 실패 토스트·롤백은 mutation onError가 처리 */ })
      .finally(() => setSending(false));
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '3px' }}
      onFocus={() => { focusedInside.current = true; }}
      onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) focusedInside.current = false; }}>
      <input type="number" min={0} value={draftPrice} placeholder="판매가"
        style={{ ...inputStyle, textAlign: 'right', borderColor: border, borderWidth: changed ? 2 : 1 }}
        onChange={(e) => setDraftPrice(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') send(); else if (e.key === 'Escape') { setDraftPrice(String(salePrice ?? 0)); setDraftSoldOut(soldOut); } }} />
      <select value={draftSoldOut ? 'OUT' : 'IN'}
        style={{ ...inputStyle, textAlign: 'center', borderColor: border, borderWidth: changed ? 2 : 1, color: draftSoldOut ? '#dc2626' : '#16a34a' }}
        onChange={(e) => setDraftSoldOut(e.target.value === 'OUT')}>
        <option value="IN">판매중</option>
        <option value="OUT">품절</option>
      </select>
      <button type="button" onClick={send} disabled={!canSend}
        style={{ fontSize: '11px', padding: '3px 6px', borderRadius: '4px', border: 'none', cursor: canSend ? 'pointer' : 'default',
          backgroundColor: canSend ? 'var(--product-primary)' : '#e5e7eb', color: canSend ? '#fff' : '#9ca3af' }}>
        {sending ? '전송중…' : '전송'}
      </button>
    </div>
  );
}
