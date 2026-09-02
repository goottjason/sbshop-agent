import React, { useState, useEffect, useRef } from 'react';
import type { SaveStatus, MarketSyncState } from './types';
import { inputStyle, CARRIER_OPTIONS, ACCOUNT_OPTIONS, VENDOR_OPTIONS, SYNC_BADGE, SOURCE_ICON } from './constants';
import { shortAccountLabel, statusBorder, blurLeftToPage, formatThousands, parseThousands } from './helpers';

export function InlineInput({ value, onCommit, type = 'text', align = 'left', title, selectAllOnFocus = false }: {
  value: string;
  onCommit: (v: string) => Promise<unknown>;
  type?: string;
  align?: 'left' | 'center';
  title?: string;
  selectAllOnFocus?: boolean;
}) {
  const [draft, setDraft] = useState(value);
  const [status, setStatus] = useState<SaveStatus>('idle');
  const focused = useRef(false);

  useEffect(() => { if (!focused.current) setDraft(value); }, [value]);

  const commit = () => {
    focused.current = false;
    if (draft === value) { setStatus('idle'); return; }
    setStatus('saving');
    onCommit(draft)
      .then(() => { setStatus('saved'); setTimeout(() => setStatus('idle'), 800); })
      .catch(() => { setDraft(value); setStatus('error'); setTimeout(() => setStatus('idle'), 1200); });
  };

  return (
    <input
      type={type}
      value={draft}
      title={title}
      style={{ ...inputStyle, textAlign: align, borderColor: statusBorder(status), borderWidth: status === 'idle' ? 1 : 2 }}
      onFocus={(e) => {
        focused.current = true;
        if (selectAllOnFocus) {
          const el = e.currentTarget;
          setTimeout(() => el.select(), 0);
        }
      }}
      onChange={(e) => { setDraft(e.target.value); setStatus('dirty'); }}
      onBlur={() => { if (document.hasFocus()) commit(); }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
        else if (e.key === 'Escape') { setDraft(value); setStatus('idle'); (e.target as HTMLInputElement).blur(); }
      }}
    />
  );
}

export function FinancialEditCell({ sourcingAmount, logisticsCost, onSave }: {
  sourcingAmount: number;
  logisticsCost: number;
  onSave: (v: { sourcingAmount: number; logisticsCost: number }) => Promise<unknown>;
}) {
  const [amt, setAmt] = useState(formatThousands(sourcingAmount));
  const [cost, setCost] = useState(formatThousands(logisticsCost));
  const [status, setStatus] = useState<SaveStatus>('idle');
  const focusedInside = useRef(false);

  useEffect(() => {
    if (!focusedInside.current) { setAmt(formatThousands(sourcingAmount)); setCost(formatThousands(logisticsCost)); }
  }, [sourcingAmount, logisticsCost]);

  const commit = () => {
    focusedInside.current = false;
    const nAmt = parseThousands(amt), nCost = parseThousands(cost);
    if (nAmt === sourcingAmount && nCost === logisticsCost) { setStatus('idle'); return; }
    setStatus('saving');
    onSave({ sourcingAmount: nAmt, logisticsCost: nCost })
      .then(() => { setStatus('saved'); setTimeout(() => setStatus('idle'), 800); })
      .catch(() => { setAmt(formatThousands(sourcingAmount)); setCost(formatThousands(logisticsCost)); setStatus('error'); setTimeout(() => setStatus('idle'), 1200); });
  };

  const border = statusBorder(status);
  const bw = status === 'idle' ? 1 : 2;
  const numKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
    else if (e.key === 'Escape') { setAmt(formatThousands(sourcingAmount)); setCost(formatThousands(logisticsCost)); setStatus('idle'); (e.target as HTMLInputElement).blur(); }
  };

  return (
    <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}
      onFocus={() => { focusedInside.current = true; }}
      onBlur={(e) => { if (blurLeftToPage(e)) commit(); }}>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px', textAlign: 'center' }}>실구매가</div>
        <input type="text" inputMode="numeric" value={amt} style={{ ...inputStyle, textAlign: 'center', borderColor: border, borderWidth: bw }}
          onChange={(e) => { setAmt(formatThousands(e.target.value)); setStatus('dirty'); }} onKeyDown={numKeyDown} />
      </div>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px', textAlign: 'center' }}>물류비</div>
        <input type="text" inputMode="numeric" value={cost} style={{ ...inputStyle, textAlign: 'center', borderColor: border, borderWidth: bw }}
          onChange={(e) => { setCost(formatThousands(e.target.value)); setStatus('dirty'); }} onKeyDown={numKeyDown} />
      </div>
    </div>
  );
}

export function SourceIcon({ source }: { source?: 'EMAIL' | 'MANUAL' | 'MARKET' | null }) {
  const key = source === 'EMAIL' ? 'EMAIL' : source ? 'MANUAL' : 'LEGACY';
  const { path, title, color } = SOURCE_ICON[key];
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960" width="14" height="14"
      fill="currentColor" style={{ flexShrink: 0, color }} aria-label={title}>
      <title>{title}</title>
      <path d={path} />
    </svg>
  );
}


export function TrackingCompareCell({ carrier, trackingNo, marketTrackingNo }: {
  carrier: string;
  trackingNo: string;
  marketTrackingNo?: string;
}) {
  const carrierLabel = CARRIER_OPTIONS.find(o => o.value === carrier)?.label || carrier;
  const market = marketTrackingNo?.trim() || '';
  const mail = trackingNo?.trim() || '';

  if (!market && !mail) {
    return <span style={{ color: '#d1d5db' }}>—</span>;
  }

  const main = market || mail;
  const mismatch = !!market && !!mail && market !== mail;
  const notSent = !market && !!mail;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1px', lineHeight: 1.35 }}>
      {carrierLabel && carrierLabel !== '-' && (
        <span style={{ fontSize: '10.5px', color: '#9ca3af' }}>{carrierLabel}</span>
      )}
      <span style={{ fontSize: '12.5px', fontWeight: 600, color: '#374151', letterSpacing: '0.01em' }}>
        {main}
      </span>
      {mismatch && (
        <span title="마켓에 박힌 송장과 메일로 받은 송장이 다릅니다 — 구매자는 위 번호를 추적합니다"
          style={{ fontSize: '10.5px', color: '#dc2626', display: 'flex', alignItems: 'center', gap: '3px' }}>
          <span style={{ fontWeight: 700 }}>메일</span>
          <span>{mail}</span>
        </span>
      )}
      {notSent && (
        <span title="아직 마켓에 전송되지 않았습니다"
          style={{ fontSize: '10px', fontWeight: 700, color: '#b45309', backgroundColor: '#fffbeb',
            border: '1px solid #fde68a', borderRadius: '4px', padding: '0 4px' }}>
          미전송
        </span>
      )}
    </div>
  );
}

export function ShippingEditCell({ carrier, trackingNo, syncState, marketTrackingNo, trackingSource, onSave }: {
  carrier: string;
  trackingNo: string;
  syncState: MarketSyncState;
  marketTrackingNo?: string;
  trackingSource?: 'EMAIL' | 'MANUAL' | 'MARKET' | null;
  onSave: (v: { shippingCarrier: string; trackingNo: string }) => Promise<unknown>;
}) {
  const [draftCarrier, setDraftCarrier] = useState(carrier);
  const [draftTracking, setDraftTracking] = useState(trackingNo);
  const [sending, setSending] = useState(false);
  const focusedInside = useRef(false);

  useEffect(() => {
    if (!focusedInside.current) { setDraftCarrier(carrier); setDraftTracking(trackingNo); }
  }, [carrier, trackingNo]);
  const changed = draftCarrier !== carrier || draftTracking !== trackingNo;
  const canSend = !!draftCarrier && !!draftTracking && changed && !sending;
  const border = changed ? '#f59e0b' : '#d1d5db';
  const send = () => {
    if (!canSend) return;
    setSending(true);
    onSave({ shippingCarrier: draftCarrier, trackingNo: draftTracking })
      .catch(() => {})
      .finally(() => setSending(false));
  };

  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}
      onFocus={() => { focusedInside.current = true; }}
      onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) focusedInside.current = false; }}
    >
      <select value={draftCarrier} style={{ ...inputStyle, textAlign: 'center', borderColor: border, borderWidth: changed ? 2 : 1 }}
        onChange={(e) => setDraftCarrier(e.target.value)}>
        <option value="" disabled hidden>택배사 선택</option>
        {CARRIER_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <input type="text" value={draftTracking} placeholder="송장번호"
        style={{ ...inputStyle, textAlign: 'center', borderColor: border, borderWidth: changed ? 2 : 1 }}
        onChange={(e) => setDraftTracking(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') send(); else if (e.key === 'Escape') { setDraftCarrier(carrier); setDraftTracking(trackingNo); } }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
        {syncState !== 'none' && (
          <span
            title={SYNC_BADGE[syncState].title}
            style={{ fontSize: '10px', fontWeight: 700, color: SYNC_BADGE[syncState].fg,
              backgroundColor: SYNC_BADGE[syncState].bg, border: `1px solid ${SYNC_BADGE[syncState].line}`,
              borderRadius: '4px', padding: '1px 4px', whiteSpace: 'nowrap' }}
          >
            {SYNC_BADGE[syncState].text}
          </span>
        )}
        <SourceIcon source={trackingSource} />
        <button type="button" onClick={send} disabled={!canSend}
          style={{ marginLeft: 'auto', fontSize: '11px', padding: '1px 6px', borderRadius: '4px', border: 'none',
            cursor: canSend ? 'pointer' : 'default',
            backgroundColor: canSend ? '#3b82f6' : '#e5e7eb', color: canSend ? '#fff' : '#9ca3af' }}>
          {sending ? '전송중…' : '전송'}
        </button>
      </div>
      {syncState === 'manual' && marketTrackingNo && (
        <div style={{ fontSize: '10.5px', color: '#9ca3af', display: 'flex', gap: '5px', alignItems: 'baseline' }}>
          <span style={{ color: '#a52432', fontWeight: 600 }}>마켓</span>
          <span style={{ textDecoration: 'line-through' }}>{marketTrackingNo}</span>
        </div>
      )}
    </div>
  );
}

export function SourcingEditCell({ sourcingAccount, sourcingVendor, sourcingOrderNo, discountCode, onSave }: {
  sourcingAccount: string;
  sourcingVendor: string;
  sourcingOrderNo: string;
  discountCode: string;
  onSave: (v: { sourcingAccount: string; sourcingVendor: string; sourcingOrderNo: string; discountCode: string }) => Promise<unknown>;
}) {
  const [draftAccount, setDraftAccount] = useState(sourcingAccount);
  const [draftVendor, setDraftVendor] = useState(sourcingVendor);
  const [draftOrderNo, setDraftOrderNo] = useState(sourcingOrderNo);
  const [draftDiscount, setDraftDiscount] = useState(discountCode);
  const [status, setStatus] = useState<SaveStatus>('idle');
  const focusedInside = useRef(false);
  useEffect(() => {
    if (!focusedInside.current) {
      setDraftAccount(sourcingAccount); setDraftVendor(sourcingVendor);
      setDraftOrderNo(sourcingOrderNo); setDraftDiscount(discountCode);
    }
  }, [sourcingAccount, sourcingVendor, sourcingOrderNo, discountCode]);
  const commit = () => {
    focusedInside.current = false;
    if (draftAccount === sourcingAccount && draftVendor === sourcingVendor && draftOrderNo === sourcingOrderNo && draftDiscount === discountCode) {
      setStatus('idle'); return;
    }
    setStatus('saving');
    onSave({ sourcingAccount: draftAccount, sourcingVendor: draftVendor, sourcingOrderNo: draftOrderNo, discountCode: draftDiscount })
      .then(() => { setStatus('saved'); setTimeout(() => setStatus('idle'), 800); })
      .catch(() => {
        setDraftAccount(sourcingAccount); setDraftVendor(sourcingVendor);
        setDraftOrderNo(sourcingOrderNo); setDraftDiscount(discountCode);
        setStatus('error'); setTimeout(() => setStatus('idle'), 1200);
      });
  };
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') (e.target as HTMLElement).blur();
    else if (e.key === 'Escape') {
      setDraftAccount(sourcingAccount); setDraftVendor(sourcingVendor);
      setDraftOrderNo(sourcingOrderNo); setDraftDiscount(discountCode); setStatus('idle');
    }
  };
  const markDirty = () => setStatus('dirty');
  const border = statusBorder(status);
  const bw = status === 'idle' ? 1 : 2;
  const fieldStyle = { ...inputStyle, textAlign: 'center' as const, borderColor: border, borderWidth: bw };
  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}
      onFocus={() => { focusedInside.current = true; }}
      onBlur={(e) => { if (blurLeftToPage(e)) commit(); }}
    >
      <select value={draftAccount} style={fieldStyle} onChange={(e) => { setDraftAccount(e.target.value); markDirty(); }} onKeyDown={onKeyDown}>
        {ACCOUNT_OPTIONS.map(a => <option key={a} value={a}>{a ? shortAccountLabel(a) : '(계정 선택)'}</option>)}
      </select>
      <select value={draftVendor} style={fieldStyle} onChange={(e) => { setDraftVendor(e.target.value); markDirty(); }} onKeyDown={onKeyDown}>
        {VENDOR_OPTIONS.map(v => <option key={v} value={v}>{v || '(공급처 선택)'}</option>)}
      </select>
      <input type="text" value={draftOrderNo} placeholder="구매주문번호" style={fieldStyle} onChange={(e) => { setDraftOrderNo(e.target.value); markDirty(); }} onKeyDown={onKeyDown} />
      <input type="text" value={draftDiscount} placeholder="할인코드" style={fieldStyle} onChange={(e) => { setDraftDiscount(e.target.value); markDirty(); }} onKeyDown={onKeyDown} />
    </div>
  );
}
