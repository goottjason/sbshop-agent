import React, { useState, useMemo, useEffect, useCallback, useRef } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  createColumnHelper,
} from '@tanstack/react-table';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchOrders, updateOrder, updateOrderLineItem, updateSourcingInfo, updateShippingInfo, shipOrders, syncCustomsStatus, syncCoupangOrders, syncSmartStoreOrders, syncElevenStreetOrders, syncEsmplusOrders, fetchCommonCodes, confirmOrdersBatch, cancelOrder, syncProductStock, fetchSyncStatus, updatePurchaseStatus } from '../api/orderApi';
import type { OrderGridDto, ProductDto, OrderDto, OrderLineItemDto, OrderDetailResponseDto, PageResponse } from '../api/orderApi';
import { toKstDate } from '../utils/datetime';

// 재고현황 셀 표시 규칙(순수 함수, 테스트 가능):
// - IN_STOCK → 구입가능 뱃지만(재입고일 행 없음)
// - OUT_OF_STOCK + restockDate → 품절 뱃지 + 입고일 행
// - OUT_OF_STOCK + 무재입고일 → 품절 뱃지만
// - 그 외(null/undefined) → '-'
// updatedAt은 존재할 때만 상대시각으로 표시(재고 반영시각 프록시).
export interface StockCellInfo {
  badge: 'IN_STOCK' | 'OUT_OF_STOCK' | 'NONE';
  restockDate?: string;
  updatedAt?: string;
}
export function stockCellInfo(product?: ProductDto): StockCellInfo {
  const status = product?.stockStatus;
  const updatedAt = product?.updatedAt || undefined;
  if (status === 'IN_STOCK') {
    return { badge: 'IN_STOCK', updatedAt };
  }
  if (status === 'OUT_OF_STOCK') {
    return { badge: 'OUT_OF_STOCK', restockDate: product?.restockDate || undefined, updatedAt };
  }
  return { badge: 'NONE', updatedAt };
}
import { toast } from 'react-toastify';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '../components/ui/Table';

type RowData = OrderGridDto & { isFirstLineItem?: boolean; lineItemCount?: number; totalRowCount?: number; rowType?: string; isSecondRow?: boolean; isThirdRow?: boolean };
const columnHelper = createColumnHelper<RowData>();

const inputStyle = { width: '100%', padding: '4px 6px', fontSize: '12px', border: '1px solid #d1d5db', borderRadius: '4px', boxSizing: 'border-box' as const, outline: 'none', backgroundColor: '#fdfdfd' };

// 택배사 enum → 한글 표시명 (단일 출처). 매핑되지 않은 값(ETC, 빈값, null 등)은 '-'로 표시.
const CARRIER_LABELS: Record<string, string> = {
  CJ_LOGISTICS: 'CJ대한통운',
  HANJIN: '한진택배',
  KOREA_POST: '우체국',
  LOTTE_LOGISTICS: '롯데택배',
  HYUNDAI_LOGISTICS: '현대택배',
  ROCKET: '쿠팡로켓',
};
// 인라인 select용 택배사 옵션(빈값=지우기 → '-')
const CARRIER_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '-' },
  ...Object.entries(CARRIER_LABELS).map(([value, label]) => ({ value, label })),
];

const ACCOUNT_OPTIONS = [
  '',
  'shouldbe.shopping@gmail.com', 'kimjw8712@gmail.com', '369butterfly369@gmail.com',
  'younzara@gmail.com', 'kimjongwon0907@gmail.com', 'spreadyourwings33@gmail.com',
  'goottjason@gmail.com', 'gootkimjw8712@gmail.com', 'kimsubi.0007@gmail.com',
  'mariahcarey0815@gmail.com', 'jongwon@skku.edu', 'tomkim8712@gmail.com',
  'kimshou31@gmail.com', 'kimshou825@gmail.com', 'inegg@g.skku.edu',
  'wavesea88@naver.com', 'ordinary_things@naver.com', 'younzara@naver.com',
  'palme86@naver.com', 'tonyworld@daum.net', 'oasis_0907@daum.net',
  'dnglglzpzp@daum.net', 'younzara@nate.com',
];
const VENDOR_OPTIONS = ['', 'IHB', 'AMZ', 'FTN', 'COK', 'OCD', 'TES', 'VTB'];

// ─── 인라인 즉시편집 공통 저장상태 언어 ───
// 모든 자동저장 셀이 동일한 시각 신호를 공유한다:
//   dirty(앰버)=변경됨·미저장, saving(파랑)=전송중, saved(초록)=저장완료 플래시, error(빨강)=실패·원복
type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';
function statusBorder(status: SaveStatus): string {
  switch (status) {
    case 'dirty': return '#f59e0b';
    case 'saving': return '#3b82f6';
    case 'saved': return '#22c55e';
    case 'error': return '#ef4444';
    default: return '#d1d5db';
  }
}

// blur가 "페이지 내 다른 영역으로 이탈"인지 판정 → 이때만 저장한다.
//   - 컨테이너 내부 이동(select↔input) → false (계속 편집 중)
//   - 창/탭 전환(Alt-Tab 등, document.hasFocus()=false) → false (복붙 위해 잠깐 나간 것, 저장 안 함)
//   - 그 외(페이지 내 다른 셀/빈 영역 클릭 or Tab) → true (저장)
function blurLeftToPage(e: React.FocusEvent<HTMLElement>): boolean {
  if (e.currentTarget.contains(e.relatedTarget as Node | null)) return false;
  return document.hasFocus();
}

// 인라인 자동저장 입력(통관번호·주소 등 단일 필드).
// 더블클릭 없이 항상 편집 가능. 포커스 아웃(blur/Tab) 시 변경분만 저장한다.
//   - Enter: 커밋(=blur 유발). 필수 아님 — Tab/클릭 이탈만으로도 저장됨.
//   - Escape: 원복 후 이탈. 저장 성공=초록 플래시, 실패=빨강+원복.
//   - 외부 값(동기화·낙관적 패치)이 바뀌면 편집 중이 아닐 때만 draft에 반영.
function InlineInput({ value, onCommit, type = 'text', align = 'left', title }: {
  value: string;
  onCommit: (v: string) => Promise<unknown>;
  type?: string;
  align?: 'left' | 'center';
  title?: string;
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
      onFocus={() => { focused.current = true; }}
      onChange={(e) => { setDraft(e.target.value); setStatus('dirty'); }}
      // 페이지 내 다른 영역으로 이탈할 때만 저장. Alt-Tab(창 전환)에서는 draft 유지·저장 보류.
      onBlur={() => { if (document.hasFocus()) commit(); }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
        else if (e.key === 'Escape') { setDraft(value); setStatus('idle'); (e.target as HTMLInputElement).blur(); }
      }}
    />
  );
}

// 정산 정보(실구매가+물류비) 통합 인라인 편집 셀.
// 두 숫자를 한 세트로 → 컨테이너 밖으로 포커스가 나갈 때 변경분을 1회 sourcing 저장.
function FinancialEditCell({ sourcingAmount, logisticsCost, onSave }: {
  sourcingAmount: number;
  logisticsCost: number;
  onSave: (v: { sourcingAmount: number; logisticsCost: number }) => Promise<unknown>;
}) {
  const [amt, setAmt] = useState(String(sourcingAmount));
  const [cost, setCost] = useState(String(logisticsCost));
  const [status, setStatus] = useState<SaveStatus>('idle');
  const focusedInside = useRef(false);

  useEffect(() => {
    if (!focusedInside.current) { setAmt(String(sourcingAmount)); setCost(String(logisticsCost)); }
  }, [sourcingAmount, logisticsCost]);

  const commit = () => {
    focusedInside.current = false;
    const nAmt = Number(amt) || 0, nCost = Number(cost) || 0;
    if (nAmt === sourcingAmount && nCost === logisticsCost) { setStatus('idle'); return; }
    setStatus('saving');
    onSave({ sourcingAmount: nAmt, logisticsCost: nCost })
      .then(() => { setStatus('saved'); setTimeout(() => setStatus('idle'), 800); })
      .catch(() => { setAmt(String(sourcingAmount)); setCost(String(logisticsCost)); setStatus('error'); setTimeout(() => setStatus('idle'), 1200); });
  };

  const border = statusBorder(status);
  const bw = status === 'idle' ? 1 : 2;
  const numKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
    else if (e.key === 'Escape') { setAmt(String(sourcingAmount)); setCost(String(logisticsCost)); setStatus('idle'); (e.target as HTMLInputElement).blur(); }
  };

  return (
    <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}
      onFocus={() => { focusedInside.current = true; }}
      onBlur={(e) => { if (blurLeftToPage(e)) commit(); }}>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px', textAlign: 'center' }}>실구매가</div>
        <input type="number" value={amt} style={{ ...inputStyle, textAlign: 'center', borderColor: border, borderWidth: bw }}
          onChange={(e) => { setAmt(e.target.value); setStatus('dirty'); }} onKeyDown={numKeyDown} />
      </div>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px', textAlign: 'center' }}>물류비</div>
        <input type="number" value={cost} style={{ ...inputStyle, textAlign: 'center', borderColor: border, borderWidth: bw }}
          onChange={(e) => { setCost(e.target.value); setStatus('dirty'); }} onKeyDown={numKeyDown} />
      </div>
    </div>
  );
}

// 배송 정보(택배사+송장) 통합 인라인 편집 셀.
// 택배사와 송장번호는 한 세트 → 더블클릭 시 두 컨트롤이 함께 열리고, 편집 종료 시
// 두 값을 1회 onSave로 넘겨 shippingMutation(=updateShippingInfo)을 한 번만 호출한다.
// 이렇게 하면 "택배사만 바꿔서 새 택배사 + 옛 송장"이 마켓에 전송되는 불일치가 사라진다.
//   - 더블클릭 → 편집 모드(택배사 select autofocus + 송장 input)
//   - 컨테이너 blur(포커스가 두 컨트롤 밖으로 나감) 또는 Enter → 저장
//   - Escape → 취소
//   - 실제로 값이 바뀐 경우에만 onSave 호출(무의미한 마켓 호출 방지)
// 배송 정보(택배사+송장) — 항상 편집 가능 + 명시적 [전송] 버튼.
// 다른 자동저장 셀과 달리 마켓 API 실호출(실패 시 백엔드 롤백)이므로 blur 자동저장이 아니라
// 사용자가 명시적으로 전송한다. 택배사·송장이 둘 다 있고 저장값과 다를 때만 버튼 활성.
//   - dirty(앰버 보더): 변경됐지만 아직 미전송  ·  전송중: 스피너  ·  성공/실패: onSave가 토스트로 알림
function ShippingEditCell({ carrier, trackingNo, onSave }: {
  carrier: string;
  trackingNo: string;
  onSave: (v: { shippingCarrier: string; trackingNo: string }) => Promise<unknown>;
}) {
  const [draftCarrier, setDraftCarrier] = useState(carrier);
  const [draftTracking, setDraftTracking] = useState(trackingNo);
  const [sending, setSending] = useState(false);
  const focusedInside = useRef(false);

  // 동기화/전송 성공으로 외부 값이 바뀌면 편집 중이 아닐 때만 draft에 반영.
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
      .catch(() => { /* 실패 토스트·롤백은 mutation onError가 처리 */ })
      .finally(() => setSending(false));
  };

  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', gap: '3px' }}
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
      <button type="button" onClick={send} disabled={!canSend}
        style={{ fontSize: '11px', padding: '3px 6px', borderRadius: '4px', border: 'none', cursor: canSend ? 'pointer' : 'default',
          backgroundColor: canSend ? '#3b82f6' : '#e5e7eb', color: canSend ? '#fff' : '#9ca3af' }}>
        {sending ? '전송중…' : '전송'}
      </button>
    </div>
  );
}

// 구매 정보(구매계정+공급처+구매주문번호+할인코드) — 항상 편집 가능.
// 네 필드는 한 세트 → 컨테이너 밖(blur/Tab)으로 포커스가 나갈 때 변경분을 1회 onSave로 저장.
// Alt-Tab·브라우저 탭전환(relatedTarget=null)에서는 저장하지 않는다.
function SourcingEditCell({ sourcingAccount, sourcingVendor, sourcingOrderNo, discountCode, onSave }: {
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
      style={{ display: 'flex', flexDirection: 'column', gap: '3px' }}
      onFocus={() => { focusedInside.current = true; }}
      onBlur={(e) => { if (blurLeftToPage(e)) commit(); }}
    >
      <select value={draftAccount} style={fieldStyle} onChange={(e) => { setDraftAccount(e.target.value); markDirty(); }} onKeyDown={onKeyDown}>
        {ACCOUNT_OPTIONS.map(a => <option key={a} value={a}>{a || '(계정 선택)'}</option>)}
      </select>
      <select value={draftVendor} style={fieldStyle} onChange={(e) => { setDraftVendor(e.target.value); markDirty(); }} onKeyDown={onKeyDown}>
        {VENDOR_OPTIONS.map(v => <option key={v} value={v}>{v || '(공급처 선택)'}</option>)}
      </select>
      <input type="text" value={draftOrderNo} placeholder="구매주문번호" style={fieldStyle} onChange={(e) => { setDraftOrderNo(e.target.value); markDirty(); }} onKeyDown={onKeyDown} />
      <input type="text" value={draftDiscount} placeholder="할인코드" style={fieldStyle} onChange={(e) => { setDraftDiscount(e.target.value); markDirty(); }} onKeyDown={onKeyDown} />
    </div>
  );
}

// ─── 낙관적 업데이트: 전체 refetch 없이 캐시의 해당 order/lineItem만 패치 ───
// 저장 즉시 화면 반영(왕복 대기·깜빡임 제거), 실패 시 스냅샷으로 롤백.
type OrdersCache = PageResponse<OrderDetailResponseDto>;
function patchOrderInCache(cache: OrdersCache | undefined, orderId: number, mutate: (o: OrderDto) => OrderDto): OrdersCache | undefined {
  if (!cache) return cache;
  return {
    ...cache,
    content: cache.content.map(item =>
      item.order?.id === orderId ? { ...item, order: mutate(item.order) } : item),
  };
}
function patchLineItemInCache(cache: OrdersCache | undefined, lineItemId: number, mutate: (li: OrderLineItemDto) => OrderLineItemDto): OrdersCache | undefined {
  if (!cache) return cache;
  return {
    ...cache,
    content: cache.content.map(item => ({
      ...item,
      lineItems: (item.lineItems || []).map(d =>
        d.lineItem?.id === lineItemId ? { ...d, lineItem: mutate(d.lineItem) } : d),
    })),
  };
}

// 주문 전체 병합 컬럼 (rowSpan = 해당 주문의 전체 행 수)
const ORDER_SPANNED_COLUMNS = ['select', 'orderInfo', 'shippingStatus'];

// 라인아이템 병합 컬럼 (rowSpan = 3)
const LINEITEM_SPANNED_COLUMNS = ['sbCode', 'stockInfo', 'quantity', 'unipass', 'purchaseStatus', 'fulfillmentInfoPair', 'sourcingInfoPair'];

// 2줄 컬럼 (행1, 행2에만 표시, 행3에서는 셀 자체를 렌더링하지 않음)
const TWO_ROW_COLUMNS = ['ordererInfo', 'customsInfo', 'shippingInfoPair', 'productNamePair', 'financialInfoPair'];

// Row 1 전용 컬럼 (주문 행에만 표시)
const ORDER_COLUMNS: string[] = [];

// Row 2 전용 컬럼 (상품 행에만 표시)
const PRODUCT_COLUMNS: string[] = [];

// 상단 필터 패널 컴포넌트 (UI)
function OrderFilterPanel({ onSearch }: { onSearch: (keyword: string, markets: string[], statuses: string[], startDate: string, endDate: string, purchaseStatuses: string[]) => void }) {
   const allMarkets = ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'];
  const allStatuses = ['UNKNOWN', 'NEW', 'PREPARING', 'DISPATCHED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RETURNED', 'EXCHANGED'];
  const allPurchaseStatuses = ['NOT_PURCHASED', 'PURCHASED', 'WAITING_STOCK'];

  const [selectedMarkets, setSelectedMarkets] = useState<string[]>(allMarkets);
  const [selectedStatuses, setSelectedStatuses] = useState<string[]>(allStatuses);
  const [selectedPurchaseStatuses, setSelectedPurchaseStatuses] = useState<string[]>(allPurchaseStatuses);
  const [keyword, setKeyword] = useState('');

  // 날짜 기본값: 1개월 전 ~ 오늘
  const today = new Date();
  const oneMonthAgo = new Date(today);
  oneMonthAgo.setMonth(oneMonthAgo.getMonth() - 1);
  const fmt = (d: Date) => d.toISOString().split('T')[0];

  const [startDate, setStartDate] = useState(fmt(oneMonthAgo));
  const [endDate, setEndDate] = useState(fmt(today));
  const [activePeriod, setActivePeriod] = useState(2); // 기본: 1개월

  const isAllMarketsSelected = selectedMarkets.length === allMarkets.length;
  const isAllStatusesSelected = selectedStatuses.length === allStatuses.length;
  const isAllPurchaseSelected = selectedPurchaseStatuses.length === allPurchaseStatuses.length;

  const handleSearch = () => {
    onSearch(keyword, selectedMarkets, selectedStatuses, startDate, endDate, selectedPurchaseStatuses);
  };

  const handlePeriod = (idx: number) => {
    setActivePeriod(idx);
    const end = new Date();
    const start = new Date();
    if (idx === 0) { /* 오늘 */ }
    else if (idx === 1) { start.setDate(start.getDate() - 7); }
    else if (idx === 2) { start.setMonth(start.getMonth() - 1); }
    else if (idx === 3) { start.setMonth(start.getMonth() - 3); }
    setStartDate(fmt(start));
    setEndDate(fmt(end));
  };

  const toggleMarket = (val: string) => setSelectedMarkets(prev => prev.includes(val) ? prev.filter(m => m !== val) : [...prev, val]);
  const toggleStatus = (val: string) => setSelectedStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);
  const togglePurchase = (val: string) => setSelectedPurchaseStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);

  return (
    <div style={{ backgroundColor: '#f8f9fa', borderTop: '2px solid var(--primary-color)', borderBottom: '1px solid #ddd', padding: '12px 20px', marginBottom: '12px', fontSize: '13px' }}>
      {/* Row 1: Period and Search */}
      <div style={{ display: 'flex', borderBottom: '1px solid #eaeaea', paddingBottom: '8px', marginBottom: '8px' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555', flexShrink: 0 }}>조회기간 (주문일)</span>
          <div style={{ display: 'flex', border: '1px solid #ccc', borderRadius: '4px', overflow: 'hidden', marginRight: '12px', flexShrink: 0 }}>
            {['오늘', '1주일', '1개월', '3개월'].map((label, idx) => (
              <button key={label} onClick={() => handlePeriod(idx)} style={{ padding: '6px 12px', border: 'none', background: activePeriod === idx ? 'var(--primary-color)' : '#f8f9fa', borderLeft: idx === 0 ? 'none' : '1px solid #ccc', color: activePeriod === idx ? '#fff' : '#333', fontWeight: activePeriod === idx ? 600 : 400, cursor: 'pointer', whiteSpace: 'nowrap' }}>
                {label}
              </button>
            ))}
          </div>
          <input type="date" value={startDate} onChange={e => { setStartDate(e.target.value); setActivePeriod(-1); }} style={{ padding: '5px', border: '1px solid #ccc', flexShrink: 0 }} />
          <span style={{ margin: '0 8px', flexShrink: 0 }}>~</span>
          <input type="date" value={endDate} onChange={e => { setEndDate(e.target.value); setActivePeriod(-1); }} style={{ padding: '5px', border: '1px solid #ccc', flexShrink: 0 }} />
        </div>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>통합 검색</span>
          <input type="text" placeholder="주문번호, 수취인명, 주문자명, 통관번호, 휴대폰, SB코드, 등록상품명, 영문상품명, 송장번호" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()} style={{ flex: 1, padding: '6px 12px', border: '1px solid #ccc', outline: 'none' }} />
        </div>
      </div>

      {/* Row 2: Market and Status */}
      <div style={{ display: 'flex', paddingBottom: '0' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>마켓채널</span>
          <div style={{ display: 'flex', gap: '16px' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllMarketsSelected} onChange={() => setSelectedMarkets(isAllMarketsSelected ? [] : allMarkets)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {[
              { id: 'COUPANG', label: '쿠팡' },
              { id: 'SMART_STORE', label: '스마트스토어' },
               { id: 'ELEVEN_STREET', label: '11번가' },
               { id: 'CAFE24', label: '카페24' },
               { id: 'GMARKET', label: 'G마켓' },
               { id: 'AUCTION', label: '옥션' }
            ].map(market => (
              <label key={market.id} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedMarkets.includes(market.id)} onChange={() => toggleMarket(market.id)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {market.label}
              </label>
            ))}
          </div>
        </div>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>전송상태</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllStatusesSelected} onChange={() => setSelectedStatuses(isAllStatusesSelected ? [] : allStatuses)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {[
              { id: 'UNKNOWN', label: '알수없음' },
              { id: 'NEW', label: '결제완료' },
              { id: 'PREPARING', label: '구매준비' },
              { id: 'DISPATCHED', label: '배송지시' },
              { id: 'SHIPPED', label: '배송중' },
              { id: 'DELIVERED', label: '배송완료' },
              { id: 'CANCELED', label: '취소됨' },
              { id: 'RETURNED', label: '반품됨' },
              { id: 'EXCHANGED', label: '교환됨' }
            ].map(status => (
              <label key={status.id} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedStatuses.includes(status.id)} onChange={() => toggleStatus(status.id)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {status.label}
              </label>
            ))}
          </div>
        </div>
      </div>

      {/* Row 3: 구매상태 */}
      <div style={{ display: 'flex', paddingTop: '8px', marginTop: '8px', borderTop: '1px solid #eaeaea' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>구매상태</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllPurchaseSelected} onChange={() => setSelectedPurchaseStatuses(isAllPurchaseSelected ? [] : allPurchaseStatuses)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {[
              { id: 'NOT_PURCHASED', label: '미구매' },
              { id: 'PURCHASED', label: '구매완료' },
              { id: 'WAITING_STOCK', label: '입고대기' }
            ].map(ps => (
              <label key={ps.id} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedPurchaseStatuses.includes(ps.id)} onChange={() => togglePurchase(ps.id)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {ps.label}
              </label>
            ))}
          </div>
        </div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'center', marginTop: '12px' }}>
        <button onClick={handleSearch} style={{ backgroundColor: 'var(--primary-color)', color: 'white', border: 'none', padding: '8px 32px', fontSize: '13px', fontWeight: 'bold', cursor: 'pointer', borderRadius: '4px' }}>검색</button>
      </div>
    </div>
  );
}

const OrderGrid: React.FC = () => {
  const [rowData, setRowData] = useState<RowData[]>([]);
  const [isSyncing, setIsSyncing] = useState(false);
  // isSyncing의 최신값을 SSE onerror 콜백(이펙트 클로저)에서 stale 없이 읽기 위한 ref (D-023)
  const isSyncingRef = useRef(false);
  useEffect(() => { isSyncingRef.current = isSyncing; }, [isSyncing]);

  // 행 호버(같은 주문 그룹 전체 음영): React state 대신 DOM 클래스 토글로 처리한다.
  // 이전엔 hoveredOrderId state가 매 호버마다 전체 그리드(수백~수천 행)를 리렌더해 음영 지연이 발생했다.
  const gridScrollRef = useRef<HTMLDivElement>(null);
  const hoveredOrderRef = useRef<string | null>(null);
  const applyRowHover = useCallback((e: React.MouseEvent) => {
    const root = gridScrollRef.current;
    if (!root) return;
    const tr = (e.target as HTMLElement).closest('tr[data-order-id]') as HTMLElement | null;
    const id = tr?.dataset.orderId ?? null;
    if (id === hoveredOrderRef.current) return;
    if (hoveredOrderRef.current !== null) {
      root.querySelectorAll(`tr[data-order-id="${hoveredOrderRef.current}"]`).forEach(el => el.classList.remove('og-row-hover'));
    }
    hoveredOrderRef.current = id;
    if (id !== null) {
      root.querySelectorAll(`tr[data-order-id="${id}"]`).forEach(el => el.classList.add('og-row-hover'));
    }
  }, []);
  const clearRowHover = useCallback(() => {
    const root = gridScrollRef.current;
    if (root && hoveredOrderRef.current !== null) {
      root.querySelectorAll(`tr[data-order-id="${hoveredOrderRef.current}"]`).forEach(el => el.classList.remove('og-row-hover'));
    }
    hoveredOrderRef.current = null;
  }, []);

  const { data: syncStatuses } = useQuery({
    queryKey: ['syncStatus'],
    queryFn: fetchSyncStatus,
    refetchInterval: 60000,
  });

  const { data: commonCodes } = useQuery({
    queryKey: ['commonCodes'],
    queryFn: fetchCommonCodes,
  });

  const getCommonLabel = useCallback((category: string, name: string) => {
    if (!commonCodes || !commonCodes[category]) return name;
    const item = commonCodes[category].find((c: { name: string; label: string }) => c.name === name);
    return item ? item.label : name;
  }, [commonCodes]);

  const timeAgo = (dateStr: string | null): string => {
    // 백엔드 시각은 zone 없는 UTC 벽시계값(LocalDateTime.now())이므로 toKstDate로 UTC로 파싱해야
    // 경과시간이 맞다. raw new Date(naive)는 브라우저 로컬(KST)로 오해석해 9시간 어긋난다(동기화 바·재고 반영시각 공용).
    const d = toKstDate(dateStr);
    if (!d) return '-';
    const diff = Date.now() - d.getTime();
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return '방금';
    if (mins < 60) return `${mins}분 전`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours}시간 전`;
    return `${Math.floor(hours / 24)}일 전`;
  };

  const marketLabels: Record<string, string> = {
    COUPANG: '쿠팡',
    SMART_STORE: '스마트스토어',
    ELEVEN_STREET: '11번가',
    GMARKET: 'G마켓/옥션',
    EMAIL: '이메일',
    COUPANG_SETTLEMENT: '쿠팡 정산',
  };

  const syncDotColor = (status: string): string => {
    switch (status) {
      case 'COMPLETED': return '#4caf50';
      case 'FAILED': return '#f44336';
      case 'RUNNING': return '#ff9800';
      default: return '#9e9e9e';
    }
  };

  const [rowSelection, setRowSelection] = useState<Record<string, boolean>>({});
  const queryClient = useQueryClient();
  // 기본 날짜: 1개월 전 ~ 오늘
  const defaultStart = (() => { const d = new Date(); d.setMonth(d.getMonth() - 1); return d.toISOString().split('T')[0]; })();
  const defaultEnd = new Date().toISOString().split('T')[0];

  const [queryParams, setQueryParams] = useState<{keyword?: string, markets?: string[], statuses?: string[], purchaseStatuses?: string[], startDate?: string, endDate?: string}>({
    keyword: '',
    markets: ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'],
    statuses: ['UNKNOWN', 'NEW', 'PREPARING', 'DISPATCHED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RETURNED', 'EXCHANGED'],
    purchaseStatuses: ['NOT_PURCHASED', 'PURCHASED', 'WAITING_STOCK'],
    startDate: defaultStart,
    endDate: defaultEnd
  });
  const [searchTrigger, setSearchTrigger] = useState(0);

  const { data, isLoading: queryLoading, refetch } = useQuery({
    queryKey: ['orders', queryParams, searchTrigger],
    queryFn: () => fetchOrders(0, 500, queryParams.keyword, queryParams.markets, queryParams.statuses, queryParams.startDate, queryParams.endDate, queryParams.purchaseStatuses)
  });

  useEffect(() => {
    if (data) {
      const flattened = data.content.flatMap(item =>
        (item.lineItems || []).map(li => ([
          {
            order: item.order,
            lineItem: li.lineItem,
            product: li.product,
            marketRegistration: li.marketRegistration,
            rowType: 'order',
          },
          {
            order: item.order,
            lineItem: li.lineItem,
            product: li.product,
            marketRegistration: li.marketRegistration,
            rowType: 'product',
          },
          {
            order: item.order,
            lineItem: li.lineItem,
            product: li.product,
            marketRegistration: li.marketRegistration,
            rowType: 'fulfillment',
          }
        ]))
      ).flat();
      setRowData(flattened);
    }
  }, [data]);

  // 낙관적 스냅샷 + 캐시 패치. 반환값(이전 상태)은 onError 롤백에 쓴다.
  type CacheSnapshot = ReturnType<typeof queryClient.getQueriesData<OrdersCache>>;
  const optimisticPatch = useCallback(async (patch: (c: OrdersCache | undefined) => OrdersCache | undefined): Promise<CacheSnapshot> => {
    await queryClient.cancelQueries({ queryKey: ['orders'] });
    const previous = queryClient.getQueriesData<OrdersCache>({ queryKey: ['orders'] });
    queryClient.setQueriesData<OrdersCache>({ queryKey: ['orders'] }, (old) => patch(old));
    return previous;
  }, [queryClient]);
  const rollback = useCallback((previous?: CacheSnapshot) => {
    previous?.forEach(([key, snap]) => queryClient.setQueryData(key, snap));
  }, [queryClient]);

  const orderMutation = useMutation({
    mutationFn: ({ id, updates }: { id: number; updates: Record<string, unknown> }) => updateOrder(id, updates),
    onMutate: async ({ id, updates }) => ({
      previous: await optimisticPatch(c => patchOrderInCache(c, id, o => {
        const next: OrderDto = { ...o };
        if ('address' in updates) next.address = updates.address as string;
        if ('customsClearanceNo' in updates) next.customsData = { ...o.customsData, customsClearanceNo: updates.customsClearanceNo as string };
        return next;
      })),
    }),
    onError: (_e, _v, ctx) => { rollback(ctx?.previous); toast.error('저장에 실패했습니다.'); },
  });
  const lineItemMutation = useMutation({
    mutationFn: ({ id, updates }: { id: number; updates: { isUnipassDone?: boolean } }) => updateOrderLineItem(id, updates),
    onMutate: async ({ id, updates }) => ({
      previous: await optimisticPatch(c => patchLineItemInCache(c, id, li => ({ ...li, ...updates }))),
    }),
    onError: (_e, _v, ctx) => { rollback(ctx?.previous); toast.error('저장에 실패했습니다.'); },
  });
  const sourcingMutation = useMutation({
    mutationFn: ({ id, updates }: { id: number; updates: { sourcingAmount?: number; logisticsCost?: number; sourcingAccount?: string; sourcingVendor?: string; sourcingOrderNo?: string; discountCode?: string } }) => updateSourcingInfo(id, updates),
    onMutate: async ({ id, updates }) => ({
      previous: await optimisticPatch(c => patchLineItemInCache(c, id, li => ({ ...li, sourcingData: { ...li.sourcingData, ...updates } }))),
    }),
    onError: (_e, _v, ctx) => { rollback(ctx?.previous); toast.error('구매/정산 정보 저장에 실패했습니다.'); },
  });
  const purchaseStatusMutation = useMutation({
    mutationFn: ({ id, status }: { id: number; status: 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK' }) => updatePurchaseStatus(id, status),
    onMutate: async ({ id, status }) => ({
      previous: await optimisticPatch(c => patchLineItemInCache(c, id, li => ({ ...li, purchaseStatus: status }))),
    }),
    onError: (_e, _v, ctx) => { rollback(ctx?.previous); toast.error('구매상태 저장에 실패했습니다.'); },
  });
  const shippingMutation = useMutation({
    mutationFn: ({ id, updates }: { id: number; updates: { trackingNo?: string; shippingCarrier?: string } }) => updateShippingInfo(id, updates),
    // 200 응답 = 마켓 전파가 실제로 성공한 경우만(실패는 백엔드가 롤백 후 500). 성공을 초록 토스트로 확인.
    onSuccess: () => {
      toast.success('송장/배송 정보가 마켓에 반영되었습니다.');
      queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
    // 마켓 반영 실패 시 백엔드가 @Transactional 롤백 후 500을 반환한다.
    // 사유를 토스트로 표면화하고, 그리드 셀이 롤백된 원본 값으로 되돌아오도록 orders 쿼리를 무효화(refetch)한다.
    onError: (err: unknown) => {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '배송정보 저장 중 오류가 발생했습니다.');
      toast.error(msg);
      queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });

  // 모든 셀 저장의 단일 진입점. mutateAsync의 Promise를 반환 → 각 셀이 성공/실패로
  // 초록 플래시/빨강 원복을 표시한다. 다중필드 셀(정산·구매정보)은 1회 요청으로 배칭된다.
  const handleUpdate = useCallback((orderId: number, lineItemId: number, field: string, value: unknown): Promise<unknown> => {
    if (field.startsWith('order.')) {
      const actualField = field.replace('order.', '');
      return orderMutation.mutateAsync({ id: orderId, updates: { [actualField]: value } });
    } else if (field === 'lineItem.isUnipassDone') {
      return lineItemMutation.mutateAsync({ id: lineItemId, updates: { isUnipassDone: value as boolean } });
    } else if (field === 'lineItem.financial') {
      // 실구매가+물류비 한 세트 → sourcing 1회 호출.
      const v = value as { sourcingAmount: number; logisticsCost: number };
      return sourcingMutation.mutateAsync({ id: lineItemId, updates: v });
    } else if (field === 'lineItem.sourcing') {
      // 구매계정+공급처+구매주문번호+할인코드 한 세트 → sourcing 1회 호출.
      const v = value as { sourcingAccount: string; sourcingVendor: string; sourcingOrderNo: string; discountCode: string };
      return sourcingMutation.mutateAsync({ id: lineItemId, updates: v });
    } else if (field === 'lineItem.shipping') {
      // 택배사+송장 한 세트 → updateShippingInfo 1회 → 마켓 API 1회 호출.
      const v = value as { shippingCarrier: string; trackingNo: string };
      return shippingMutation.mutateAsync({ id: lineItemId, updates: { trackingNo: v.trackingNo, shippingCarrier: v.shippingCarrier } });
    } else if (field === 'lineItem.purchaseStatus') {
      return purchaseStatusMutation.mutateAsync({ id: lineItemId, status: value as 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK' });
    }
    return Promise.resolve();
  }, [orderMutation, lineItemMutation, sourcingMutation, shippingMutation, purchaseStatusMutation]);

  useEffect(() => {
    const eventSource = new EventSource('/sbshop-agent/api/v1/notifications/subscribe');
    eventSource.addEventListener('SYNC_COMPLETED', () => {
      setIsSyncing(false);
      refetch();
    });
    eventSource.addEventListener('SYNC_FAILED', (event) => {
      setIsSyncing(false);
      const parts = event.data.split('|');
      const marketType = parts[0] || '';
      const errorMsg = parts[2] || '동기화 중 오류가 발생했습니다.';
      const marketLabel = marketLabels[marketType] || marketType;
      toast.error(`${marketLabel} 동기화 실패: ${errorMsg}`);
    });
    // D-023: SSE 연결이 영구 실패(CLOSED)하면 SYNC_COMPLETED/FAILED가 도달하지 않아
    // 로딩 오버레이가 무한 고착된다. 동기화 중이었다면 로딩을 해제하고 사용자에게 알린다.
    eventSource.onerror = () => {
      if (eventSource.readyState === EventSource.CLOSED) {
        if (isSyncingRef.current) {
          setIsSyncing(false);
          toast.error('실시간 동기화 연결이 끊어졌습니다. 잠시 후 다시 시도해주세요.');
        }
      }
    };
    return () => eventSource.close();
  }, [refetch]);

  const handleSyncCustoms = async () => {
    setIsSyncing(true);
    try {
      const res = await syncCustomsStatus();
      if (res.success) {
        refetch();
      } else {
        toast.error(res.message || '통관 상태 동기화에 실패했습니다.');
      }
    } catch {
      toast.error('통관 상태 동기화 중 오류가 발생했습니다.');
    } finally {
      setIsSyncing(false);
    }
  };

  const handleSyncSmartStore = async () => {
    setIsSyncing(true);
    try {
      const res = await syncSmartStoreOrders();
      if (res.success) {
        refetch();
        // D-023: SSE(SYNC_COMPLETED/FAILED) 미도달 시 로딩 영구 고착 방지 안전장치
        setTimeout(() => setIsSyncing(false), 30000);
      } else {
        toast.error(res.message || '스마트스토어 동기화에 실패했습니다.');
        setIsSyncing(false);
      }
    } catch {
      toast.error('스마트스토어 동기화 중 오류가 발생했습니다.');
      setIsSyncing(false);
    }
  };

  const handleSyncCoupang = async () => {
    setIsSyncing(true);
    try {
      const res = await syncCoupangOrders();
      if (res.success) {
        refetch();
        // D-023: SSE(SYNC_COMPLETED/FAILED) 미도달 시 로딩 영구 고착 방지 안전장치
        setTimeout(() => setIsSyncing(false), 30000);
      } else {
        toast.error(res.message || '쿠팡 동기화에 실패했습니다.');
        setIsSyncing(false);
      }
    } catch {
      toast.error('쿠팡 동기화 중 오류가 발생했습니다.');
      setIsSyncing(false);
    }
  };

  const handleSyncElevenStreet = async () => {
    setIsSyncing(true);
    try {
      const res = await syncElevenStreetOrders();
      if (res.success) {
        refetch();
        // D-023: SSE(SYNC_COMPLETED/FAILED) 미도달 시 로딩 영구 고착 방지 안전장치
        setTimeout(() => setIsSyncing(false), 30000);
      } else {
        toast.error(res.message || '11번가 동기화에 실패했습니다.');
        setIsSyncing(false);
      }
    } catch {
      toast.error('11번가 동기화 중 오류가 발생했습니다.');
      setIsSyncing(false);
    }
  };

  const handleSyncEsmplus = async () => {
    setIsSyncing(true);
    try {
      const res = await syncEsmplusOrders();
      if (res.success) {
        refetch();
        // D-023: SSE(SYNC_COMPLETED/FAILED) 미도달 시 로딩 영구 고착 방지 안전장치
        setTimeout(() => setIsSyncing(false), 30000);
      } else {
        toast.error(res.message || 'G마켓/옥션 동기화에 실패했습니다.');
        setIsSyncing(false);
      }
    } catch {
      toast.error('G마켓/옥션 동기화 중 오류가 발생했습니다.');
      setIsSyncing(false);
    }
  };

  const handleSyncProductStock = async () => {
    setIsSyncing(true);
    try {
      const res = await syncProductStock();
      if (res.success) {
        // 재고 동기화는 마켓 동기화와 달리 완료 이벤트(SSE)가 없는 백그라운드 크롤(수 초~수 분)이라,
        // 고정 3초 refetch만으로는 화면 변화가 없어 "반응 없음"으로 체감됐다(D-057).
        // ① 시작을 즉시 토스트로 명확히 알리고 ② 오버레이는 짧게 풀되 ③ 지연 refetch를 다단계로 걸어
        // 크롤이 끝나는 대로 갱신이 반영되게 한다.
        toast.info('재고 동기화를 시작했습니다. 완료까지 다소 시간이 걸릴 수 있어 잠시 후 자동으로 새로고침됩니다.');
        setTimeout(() => { refetch(); setIsSyncing(false); }, 3000);
        setTimeout(() => { refetch(); }, 15000);
      } else {
        toast.error(res.message || '재고 동기화에 실패했습니다.');
        setIsSyncing(false);
      }
    } catch {
      toast.error('재고 동기화 중 오류가 발생했습니다.');
      setIsSyncing(false);
    }
  };

  const handleConfirmOrders = async () => {
    const selectedIds = Object.keys(rowSelection).filter(k => rowSelection[k as keyof typeof rowSelection]);
    if (selectedIds.length === 0) {
      toast.warn('확인할 주문을 선택해주세요.');
      return;
    }
    
    const orderIdsToConfirm = Array.from(new Set(selectedIds.map(indexStr => {
      const row = processedData[parseInt(indexStr, 10)];
      return row.order?.id;
    }).filter(id => id !== undefined))) as number[];

    if (orderIdsToConfirm.length === 0) return;

    try {
      const result = await confirmOrdersBatch(orderIdsToConfirm);
      if (result.failedCount === 0) {
        toast.success(`${result.successCount}건의 주문이 확인되었습니다.`);
      } else {
        if (result.successCount > 0) {
          toast.warn(`${result.successCount}건 성공, ${result.failedCount}건 실패`);
        } else {
          toast.error(`주문 확인 실패: ${result.errors?.[0] || '알 수 없는 오류'}`);
        }
      }
      setRowSelection({});
      refetch();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message || '주문 확인 처리 중 오류가 발생했습니다.';
      toast.error(msg);
    }
  };

  const handleCancelOrders = async () => {
    const selectedIds = Object.keys(rowSelection).filter(k => rowSelection[k as keyof typeof rowSelection]);
    if (selectedIds.length === 0) {
      toast.warn('거부할 주문을 선택해주세요.');
      return;
    }
    
    if (!window.confirm('선택한 주문을 정말로 취소(거부) 처리하시겠습니까?')) return;

    const orderIdsToCancel = Array.from(new Set(selectedIds.map(indexStr => {
      const row = processedData[parseInt(indexStr, 10)];
      return row.order?.id;
    }).filter(id => id !== undefined)));

    if (orderIdsToCancel.length === 0) return;

    try {
      await Promise.all(orderIdsToCancel.map(id => cancelOrder(id as number)));
      setRowSelection({});
      refetch();
      toast.success(`${orderIdsToCancel.length}건 취소(거부) 처리되었습니다.`);
    } catch {
      toast.error('주문 취소 처리 중 오류가 발생했습니다.');
    }
  };

  const processedData = useMemo(() => {
    if (!rowData || rowData.length === 0) return [];
    const orderCounts: Record<number, number> = {};
    const orderLineItemCounts: Record<number, number> = {};
    rowData.forEach((row) => {
      const id = row.order?.id || 0;
      orderCounts[id] = (orderCounts[id] || 0) + 1;
      if (row.rowType === 'order') {
        orderLineItemCounts[id] = (orderLineItemCounts[id] || 0) + 1;
      }
    });

    let currentOrderId = -1;
    return rowData.map((row) => {
      const id = row.order?.id || 0;
      const isFirst = id !== currentOrderId && row.rowType === 'order';
      if (id !== currentOrderId && row.rowType === 'order') currentOrderId = id;
      return {
        ...row,
        isFirstLineItem: isFirst,
        isSecondRow: row.rowType === 'product',
        isThirdRow: row.rowType === 'fulfillment',
        lineItemCount: orderLineItemCounts[id],
        totalRowCount: orderCounts[id],
      };
    });
  }, [rowData]);

  const canConfirmSelected = useMemo(() => {
    const selectedIndices = Object.keys(rowSelection).filter(k => rowSelection[k]);
    if (selectedIndices.length === 0) return false;
    return selectedIndices.every(idx => {
      const row = processedData[parseInt(idx, 10)];
      const status = row?.lineItem?.shippingData?.shippingStatus;
      return status === 'NEW';
    });
  }, [rowSelection, processedData]);

  const columns = useMemo(() => [
    // ─── 주문 전체 병합 컬럼 (rowSpan = 전체 행 수) ───
    columnHelper.display({
      id: 'select',
      header: ({ table }) => (
        <input type="checkbox" checked={table.getIsAllRowsSelected()} onChange={table.getToggleAllRowsSelectedHandler()} />
      ),
      cell: ({ row }) => (
        <input type="checkbox" checked={row.getIsSelected()} onChange={row.getToggleSelectedHandler()} />
      ),
      size: 40,
      meta: { frozen: true, freezeLeft: 0 },
    }),
    columnHelper.display({
      id: 'orderInfo',
      header: '주문정보',
      size: 150,
      cell: ({ row }) => {
        const dateObj = row.original.order?.orderDate ? new Date(row.original.order.orderDate as string) : null;
        const dateStr = dateObj ? `${dateObj.getFullYear()}-${String(dateObj.getMonth() + 1).padStart(2, '0')}-${String(dateObj.getDate()).padStart(2, '0')} ${String(dateObj.getHours()).padStart(2, '0')}:${String(dateObj.getMinutes()).padStart(2, '0')}` : '-';
        const market = row.original.order?.marketType || '';
        const orderNo = row.original.order?.marketOrderNo || '-';
        const marketColorMap: Record<string, { bg: string; text: string }> = {
          'SMART_STORE': { bg: '#e8f5e9', text: '#2e7d32' },
          'COUPANG': { bg: '#fce4ec', text: '#c2185b' },
          'ELEVEN_STREET': { bg: '#e3f2fd', text: '#1565c0' },
          'CAFE24': { bg: '#fffde7', text: '#fbc02d' },
          'GMARKET': { bg: '#fff9c4', text: '#f57f17' },
          'AUCTION': { bg: '#fce4ec', text: '#d32f2f' }
        };
        const style = marketColorMap[market] || { bg: '#f5f5f5', text: '#666' };
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', lineHeight: '1.2', fontSize: '12px', textAlign: 'center' }}>
            <div>{dateStr}</div>
            <div><span style={{ backgroundColor: style.bg, color: style.text, padding: '2px 6px', borderRadius: '4px', fontWeight: 600, fontSize: '11px' }}>{getCommonLabel('marketType', market)}</span></div>
            <div style={{ fontSize: '11px', color: '#555' }}>{orderNo}</div>
          </div>
        );
      },
      meta: { frozen: true, freezeLeft: 40 },
    }),
    columnHelper.accessor('lineItem.shippingData.shippingStatus', {
      id: 'shippingStatus',
      header: '주문상태',
      size: 100,
      meta: { frozen: true, freezeLeft: 190 },
      cell: info => {
        const val = info.getValue() as string;
        const colorMap: Record<string, { bg: string; text: string }> = {
          'UNKNOWN':    { bg: '#f5f5f5', text: '#666' },
          'NEW':        { bg: '#e0f7fa', text: '#006064' },
          'PREPARING':  { bg: '#fff3e0', text: '#e65100' },
          'DISPATCHED': { bg: '#fce4ec', text: '#880e4f' },
          'SHIPPED':    { bg: '#f1f8e9', text: '#558b2f' },
          'DELIVERED':  { bg: '#e1f5fe', text: '#0277bd' },
          'CANCELED':   { bg: '#ffebee', text: '#c62828' },
          'RETURNED':   { bg: '#f3e5f5', text: '#6a1b9a' },
          'EXCHANGED':  { bg: '#e8eaf6', text: '#283593' }
        };
        const style = colorMap[val] || { bg: '#f5f5f5', text: '#666' };
        return val ? <span style={{ backgroundColor: style.bg, color: style.text, padding: '4px 8px', borderRadius: '4px', fontWeight: 600 }}>{getCommonLabel('shippingStatus', val)}</span> : '-';
      }
    }),

    // ─── 2줄 컬럼 (행 1과 행 2에 각각 표시) ───
    // 주문자정보: 행1=수취인명(주문자명), 행2=휴대폰
    columnHelper.display({
      id: 'ordererInfo',
      header: '주문자정보',
      size: 120,
      meta: { frozen: true, freezeLeft: 290 },
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          return <span>{row.original.order?.recipientPhone || '-'}</span>;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const recipientName = (row.original.order?.recipientName || '').trim();
        const ordererName = (row.original.order?.ordererName || '').trim();
        const customsStatus = row.original.order?.customsData?.customsStatus;
        if (!recipientName) return '-';
        if (!ordererName || recipientName === ordererName) {
          return <span style={{ fontWeight: 600 }}>{recipientName}</span>;
        }
        const verifiedPerson = row.original.order?.customsData?.verifiedPerson;
        let recipientColor = '#000';
        let ordererColor = '#000';
        if (customsStatus === 'VALID') {
          // VALID일 때만 verifiedPerson에 따라 파란색 표시
          if (verifiedPerson === 'RECIPIENT') recipientColor = '#1565c0';
          else if (verifiedPerson === 'ORDERER') ordererColor = '#1565c0';
        } else if (customsStatus === 'INVALID_PCCC' || customsStatus === 'INVALID_PHONE' || customsStatus === 'INVALID_ZIPCODE') {
          // INVALID_* 상태일 때 주황색 표시
          recipientColor = '#e65100';
          ordererColor = '#e65100';
        }
        return (
          <div style={{ lineHeight: '1.3', fontSize: '12px' }}>
            <div style={{ color: recipientColor, fontWeight: 600 }}>{recipientName}</div>
            <div style={{ color: ordererColor, fontSize: '11px' }}>({ordererName})</div>
          </div>
        );
      }
    }),
    // 통관정보: 행1=통관번호, 행2=통관상태 뱃지
    columnHelper.display({
      id: 'customsInfo',
      header: () => (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
          통관정보
          <svg onClick={handleSyncCustoms} xmlns="http://www.w3.org/2000/svg" height="18px" viewBox="0 -960 960 960" width="18px" fill="#555" style={{ cursor: 'pointer' }}>
            <path d="M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z"/>
          </svg>
        </div>
      ),
      size: 120,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          const val = row.original.order?.customsData?.customsStatus;
          if (!val) return '-';
          const customsColorMap: Record<string, { bg: string; text: string }> = {
            'VALID': { bg: '#e8f5e9', text: '#2e7d32' },
            'INVALID_PCCC': { bg: '#ffebee', text: '#c62828' },
            'INVALID_PHONE': { bg: '#fff3e0', text: '#e65100' },
            'INVALID_ZIPCODE': { bg: '#fff8e1', text: '#f57f17' },
            'PENDING': { bg: '#f5f5f5', text: '#666' },
          };
          const style = customsColorMap[val] || { bg: '#f5f5f5', text: '#666' };
          return <span style={{ backgroundColor: style.bg, color: style.text, padding: '4px 8px', borderRadius: '4px', fontWeight: 600, fontSize: '12px', whiteSpace: 'pre-line', lineHeight: '1.3' }}>{getCommonLabel('customsStatus', val)}</span>;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const val = row.original.order?.customsData?.customsClearanceNo || '';
        return <InlineInput value={val} onCommit={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.customsClearanceNo', v)} />;
      }
    }),
    // 배송정보: 행1=우편번호|배송메시지, 행2=주소
    columnHelper.display({
      id: 'shippingInfoPair',
      header: '배송정보',
      size: 240,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          const val = row.original.order?.address || '';
          return <InlineInput value={val} onCommit={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.address', v)} />;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const zipcode = row.original.order?.zipcode || '';
        const message = row.original.order?.message || '';
        return (
          <div style={{ fontSize: '12px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', textAlign: 'left', paddingLeft: '4px' }}>
            <span style={{ fontWeight: 500 }}>{zipcode || '-'}</span>
            <span style={{ margin: '0 4px', color: '#999' }}>|</span>
            <span style={{ color: '#666' }}>{message || '-'}</span>
          </div>
        );
      }
    }),

    // ─── 라인아이템 병합 컬럼 (rowSpan=3) ───
    columnHelper.display({
      id: 'sbCode',
      header: '상품코드',
      size: 110,
      cell: ({ row }) => {
        const val = row.original.product?.sbCode || '-';
        return <div style={{ textAlign: 'center', fontWeight: 600, fontSize: '12px' }}>{val}</div>;
      }
    }),

    // 상품정보: 행1=등록상품명, 행2=영문상품명
    columnHelper.display({
      id: 'productNamePair',
      header: '상품정보',
      size: 300,
      cell: ({ row }) => {
        const url = row.original.product?.sourcingInfo?.sourceUrl;
        if (row.original.rowType === 'product') {
          const name = row.original.product?.originalName || '';
          return <div style={{ textAlign: 'left', paddingLeft: '8px', overflow: 'hidden', textOverflow: 'ellipsis' }}>{url ? <a href={url} target="_blank" rel="noopener noreferrer" style={{ color: '#1565c0', textDecoration: 'none' }}>{name}</a> : name}</div>;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const name = row.original.product?.productName || '';
        return <div style={{ textAlign: 'left', paddingLeft: '8px', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</div>;
      }
    }),

    // ─── Row 1 전용 컬럼 (주문 행에만 표시) ───
    // ─── 2줄 병합 컬럼 (행 1, 행 2) ───
    columnHelper.display({
      id: 'stockInfo',
      header: () => (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
          재고현황
          <svg onClick={handleSyncProductStock} xmlns="http://www.w3.org/2000/svg" height="18px" viewBox="0 -960 960 960" width="18px" fill="#555" style={{ cursor: 'pointer' }}>
            <path d="M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z"/>
          </svg>
        </div>
      ),
      size: 100,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') return null;
        if (row.original.rowType === 'fulfillment') return null;
        const info = stockCellInfo(row.original.product);
        let badge = <span style={{ color: '#999' }}>-</span>;
        if (info.badge === 'IN_STOCK') badge = <span style={{ backgroundColor: '#e8f5e9', color: '#2e7d32', padding: '4px 8px', borderRadius: '4px', fontWeight: 600 }}>구입가능</span>;
        if (info.badge === 'OUT_OF_STOCK') badge = <span style={{ backgroundColor: '#ffebee', color: '#c62828', padding: '4px 8px', borderRadius: '4px', fontWeight: 600 }}>품절</span>;
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', alignItems: 'center' }}>
            <div>{badge}</div>
            {info.restockDate && (
              <div style={{ fontSize: '11px', color: '#666' }}>{`입고: ${info.restockDate}`}</div>
            )}
            {info.updatedAt && (
              <div style={{ fontSize: '10px', color: '#999' }}>{timeAgo(info.updatedAt)}</div>
            )}
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'sourcingInfoPair',
      header: '구매 정보',
      size: 200,
      // 라인아이템 병합 셀(rowSpan=3): 상품코드처럼 라인아이템 첫 행에 1회만 렌더된다.
      // 구매계정/공급처/구매주문번호/할인코드를 4줄 stack으로 표시하고,
      // 더블클릭 시 통합 편집(네 필드를 한 세트로 1회 저장).
      cell: ({ row }) => {
        const orderId = row.original.order?.id || 0;
        const lineItemId = row.original.lineItem?.id || 0;
        const sourcingAccount = row.original.lineItem?.sourcingData?.sourcingAccount || '';
        const sourcingVendor = row.original.lineItem?.sourcingData?.sourcingVendor || '';
        const sourcingOrderNo = row.original.lineItem?.sourcingData?.sourcingOrderNo || '';
        const discountCode = row.original.lineItem?.sourcingData?.discountCode || '';
        return (
          <SourcingEditCell
            sourcingAccount={sourcingAccount}
            sourcingVendor={sourcingVendor}
            sourcingOrderNo={sourcingOrderNo}
            discountCode={discountCode}
            onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.sourcing', v)}
          />
        );
      }
    }),
    columnHelper.display({
      id: 'fulfillmentInfoPair',
      header: '배송 정보',
      size: 150,
      // 라인아이템 병합 셀(rowSpan=3): 상품코드처럼 라인아이템 첫 행에 1회만 렌더된다.
      // 택배사+송장을 2줄 stack으로 표시하고, 더블클릭 시 통합 편집(한 세트로 1회 저장).
      cell: ({ row }) => {
        const carrier = row.original.lineItem?.shippingData?.shippingCarrier || '';
        const trackingNo = row.original.lineItem?.shippingData?.trackingNo || '';
        const orderId = row.original.order?.id || 0;
        const lineItemId = row.original.lineItem?.id || 0;
        return (
          <div style={{ fontSize: '12px', textAlign: 'center' }}>
            <ShippingEditCell
              carrier={carrier}
              trackingNo={trackingNo}
              onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.shipping', v)}
            />
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'financialInfoPair',
      header: '정산 정보',
      size: 160,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          const sourcingAmount = (row.original.lineItem?.sourcingData?.sourcingAmount || 0) as number;
          const logisticsCost = (row.original.lineItem?.sourcingData?.logisticsCost || 0) as number;
          return (
            <FinancialEditCell
              sourcingAmount={sourcingAmount}
              logisticsCost={logisticsCost}
              onSave={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'lineItem.financial', v)}
            />
          );
        }
        if (row.original.rowType === 'fulfillment') return null;
        
        const settlementAmount = (row.original.lineItem?.settlementData?.settlementAmount || 0) as number;
        const sourcingAmount = (row.original.lineItem?.sourcingData?.sourcingAmount || 0) as number;
        const logisticsCost = (row.original.lineItem?.sourcingData?.logisticsCost || 0) as number;
        const profit = settlementAmount - sourcingAmount - logisticsCost;
        
        return (
          <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
            <div style={{ flex: 1, textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px' }}>정산금액</div>
              <div style={{ fontWeight: 'bold', color: '#1565c0', fontSize: '13px' }}>{settlementAmount.toLocaleString()}</div>
            </div>
            <div style={{ flex: 1, textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px' }}>순수익</div>
              <div style={{ fontWeight: 'bold', color: profit > 0 ? '#2e7d32' : '#d32f2f', fontSize: '13px' }}>{profit.toLocaleString()}</div>
            </div>
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'unipass',
      header: '유니패스',
      size: 70,
      cell: ({ row }) => {
        if (row.original.rowType !== 'order') return null;
        const isDone = row.original.lineItem?.isUnipassDone;
        return <div style={{ textAlign: 'center' }}><input type="checkbox" checked={!!isDone} onChange={(e) => { void handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'lineItem.isUnipassDone', e.target.checked).catch(() => {}); }} /></div>;
      }
    }),
    columnHelper.accessor('lineItem.purchaseStatus', {
      id: 'purchaseStatus',
      header: '구매상태',
      size: 100,
      cell: info => {
        const row = info.row.original;
        const lineItemId = row.lineItem?.id;
        const currentVal = (info.getValue() as string) || 'NOT_PURCHASED';

        const PURCHASE_OPTIONS = [
          { value: 'NOT_PURCHASED', label: '미구매' },
          { value: 'PURCHASED',     label: '구매완료' },
          { value: 'WAITING_STOCK', label: '입고대기' },
        ] as const;

        const handleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
          if (!lineItemId) return;
          void handleUpdate(row.order?.id || 0, lineItemId, 'lineItem.purchaseStatus', e.target.value).catch(() => {});
        };

        return (
          <select value={currentVal} onChange={handleChange}
            style={{ fontSize: '12px', padding: '2px 4px', borderRadius: '4px' }}>
            {PURCHASE_OPTIONS.map(opt => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        );
      }
    }),
    // ─── Row 1 전용 컬럼 (주문 행에만 표시) ───
    columnHelper.display({
      id: 'quantity',
      header: '수량',
      size: 70,
      cell: ({ row }) => {
        const qty = (row.original.lineItem?.quantity || 1) as number;
        const bundle = (row.original.product?.logisticsInfo?.bundleQuantity || 1) as number;
        const total = qty * bundle;
        return (
          <div style={{ textAlign: 'center', lineHeight: '1.4' }}>
            <div style={{ fontSize: '12px', color: '#666' }}>
              {bundle} × <span style={qty >= 2 ? { fontWeight: 'bold', color: '#d32f2f' } : {}}>{qty}</span>
            </div>
            <div style={{ fontWeight: 'bold', fontSize: '13px', color: '#1e293b' }}>{total}</div>
          </div>
        );
      }
    }),
  ], [handleUpdate, commonCodes, getCommonLabel]);

  const table = useReactTable({
    data: processedData,
    columns,
    state: { rowSelection },
    enableRowSelection: true,
    columnResizeMode: 'onChange',
    onRowSelectionChange: setRowSelection,
    getCoreRowModel: getCoreRowModel(),
  });

  const handleShipSelected = async () => {
    const selectedRows = table.getSelectedRowModel().rows;
    const orderIds = Array.from(new Set(selectedRows.map(r => r.original.order?.id))).filter(id => id);
    if (orderIds.length === 0) {
      toast.warning('발송 처리할 주문을 선택해주세요.');
      return;
    }
    try {
      const result = await shipOrders(orderIds as number[]);
      const skippedSuffix = result.skippedCount ? `, ${result.skippedCount}건 건너뜀` : '';
      if (result.failedCount === 0) {
        toast.success(`${result.successCount}건 발송 처리되었습니다.${skippedSuffix}`);
      } else {
        const failDetail = result.failedIds?.length
          ? ` (실패 주문번호: ${result.failedIds.join(', ')})`
          : '';
        if (result.successCount > 0) {
          toast.warn(`${result.successCount}건 성공, ${result.failedCount}건 실패${skippedSuffix}${failDetail} · ${result.errors?.[0] || ''}`);
        } else {
          toast.error(`발송 실패: ${result.errors?.[0] || '알 수 없는 오류'}${failDetail}`);
        }
      }
      refetch();
    } catch {
      toast.error('발송 처리 중 오류가 발생했습니다.');
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: '0', backgroundColor: '#f8fafc', borderRadius: '0' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
        <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 600, color: '#1e293b' }}>통합 주문 관리</h2>
        <div style={{ display: 'flex', gap: '12px' }}>
          <button onClick={handleSyncSmartStore} style={{ padding: '8px 16px', backgroundColor: 'var(--primary-color)', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>스마트스토어 동기화</button>
          <button onClick={handleSyncCoupang} style={{ padding: '8px 16px', backgroundColor: 'var(--primary-color)', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>쿠팡 동기화</button>
          <button onClick={handleSyncElevenStreet} style={{ padding: '8px 16px', backgroundColor: 'var(--primary-color)', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>11번가 동기화</button>
          <button onClick={handleSyncEsmplus} style={{ padding: '8px 16px', backgroundColor: 'var(--primary-color)', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>G마켓/옥션 동기화</button>
          <button onClick={handleConfirmOrders} disabled={!canConfirmSelected} style={{ padding: '8px 16px', backgroundColor: canConfirmSelected ? '#e8f5e9' : '#f5f5f5', color: canConfirmSelected ? '#2e7d32' : '#999', border: `1px solid ${canConfirmSelected ? '#c8e6c9' : '#e0e0e0'}`, borderRadius: '4px', cursor: canConfirmSelected ? 'pointer' : 'not-allowed', fontSize: '13px', fontWeight: 'bold', opacity: canConfirmSelected ? 1 : 0.6 }}>선택 주문 확인</button>
          <button onClick={handleCancelOrders} style={{ padding: '8px 16px', backgroundColor: '#ffebee', color: '#c62828', border: '1px solid #ffcdd2', borderRadius: '4px', cursor: 'pointer', fontSize: '13px', fontWeight: 'bold' }}>선택 주문 거부</button>
          <button onClick={handleShipSelected} style={{ padding: '8px 16px', backgroundColor: '#fff', color: '#333', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>선택 발송</button>
        </div>
      </div>
      {syncStatuses && (
        <div style={{ display: 'flex', gap: '14px', marginBottom: '12px', alignItems: 'center', flexWrap: 'wrap', paddingLeft: '2px' }}>
          <span style={{ fontSize: '12px', fontWeight: 600, color: '#888' }}>동기화</span>
          {Object.entries(marketLabels).map(([key, label]) => {
            const s = syncStatuses[key];
            return (
              <span key={key} style={{ fontSize: '12px', color: '#666', display: 'flex', alignItems: 'center', gap: '5px' }}>
                <span style={{ width: '8px', height: '8px', borderRadius: '50%', backgroundColor: s ? syncDotColor(s.status) : '#9e9e9e', display: 'inline-block' }} />
                {label}
                <span style={{ color: '#aaa' }}>{s ? timeAgo(s.lastSyncAt) : '전'}</span>
              </span>
            );
          })}
        </div>
      )}
      <OrderFilterPanel onSearch={(keyword, markets, statuses, startDate, endDate, purchaseStatuses) => { setQueryParams({ keyword, markets, statuses, purchaseStatuses, startDate, endDate }); setSearchTrigger(c => c + 1); }} />

      <div style={{ flex: 1, backgroundColor: 'white', display: 'flex', flexDirection: 'column', position: 'relative', overflow: 'hidden' }}>
        <div className="force-scrollbar" ref={gridScrollRef} onMouseOver={applyRowHover} onMouseLeave={clearRowHover} style={{ flex: 1, overflow: 'scroll' }}>
          {(queryLoading || isSyncing) && (
            <div style={{
              position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
              backgroundColor: 'rgba(255, 255, 255, 0.6)',
              display: 'flex', justifyContent: 'center', alignItems: 'center',
              zIndex: 10
            }}>
              <div style={{
                padding: '16px 32px', backgroundColor: 'white', borderRadius: '8px',
                boxShadow: '0 4px 12px rgba(0,0,0,0.15)', fontSize: '15px', fontWeight: 600, color: 'var(--primary-color)'
              }}>
                로딩 및 동기화 중...
              </div>
            </div>
          )}
          <style>{`
            /* 같은 주문 그룹 전체 호버 음영 — TD에 !important로 적용해 frozen 셀 인라인 배경까지 즉시 덮는다(리렌더 없음). */
            .og-row-hover > td {
              background-color: #f1f5f9 !important;
            }
            .force-scrollbar::-webkit-scrollbar {
              width: 14px;
              height: 14px;
              background-color: #f8fafc;
            }
            .force-scrollbar::-webkit-scrollbar-thumb {
              background-color: #cbd5e1;
              border-radius: 8px;
              border: 3px solid #f8fafc;
            }
            .force-scrollbar::-webkit-scrollbar-thumb:hover {
              background-color: #94a3b8;
            }
            .force-scrollbar::-webkit-scrollbar-corner {
              background-color: #f8fafc;
            }
          `}</style>
          <Table style={{ tableLayout: 'fixed', width: 'max-content' }}>
              <TableHeader>
                {table.getHeaderGroups().map(headerGroup => (
                  <TableRow key={headerGroup.id}>
                    {headerGroup.headers.map(header => {
                      const meta = header.column.columnDef.meta as { frozen?: boolean; freezeLeft?: number } | undefined;
                      const isFrozen = meta?.frozen;
                      const freezeLeft = meta?.freezeLeft;
                      return (
                      <TableHead 
                        key={header.id}
                        style={{ 
                          width: header.getSize(), 
                          minWidth: header.getSize(),
                          backgroundColor: '#f9fafb', 
                          borderRight: '1px solid #e5e7eb', 
                          borderTop: '2px solid var(--primary-color)',
                          borderBottom: '1px solid #e5e7eb',
                          position: 'sticky',
                          top: 0,
                          left: isFrozen ? freezeLeft : undefined,
                          zIndex: isFrozen ? 4 : 3,
                          boxShadow: isFrozen ? '2px 0 4px rgba(0,0,0,0.1)' : undefined,
                          textAlign: ['shippingInfoPair', 'productNamePair'].includes(header.column.id) ? 'left' : 'center',
                        }}
                      >
                        {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
                        <div
                          onMouseDown={header.getResizeHandler()}
                          onTouchStart={header.getResizeHandler()}
                          style={{
                            position: 'absolute', right: 0, top: 0, height: '100%', width: '6px',
                            background: header.column.getIsResizing() ? '#00b050' : 'transparent',
                            cursor: 'col-resize', touchAction: 'none'
                          }}
                        />
                      </TableHead>
                      );
                    })}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows.map(row => {
                  const baseBgCol = row.original.isFirstLineItem ? '#ffffff' : row.original.isSecondRow ? '#fdfdfd' : '#f9f9f9';
                  return (
                  <TableRow
                    key={row.id}
                    data-order-id={row.original.order?.id ?? undefined}
                    style={{ backgroundColor: baseBgCol }}
                    onMouseEnter={undefined}
                    onMouseLeave={undefined}
                  >
                    {row.getVisibleCells().map(cell => {
                      const isOrderSpanned = ORDER_SPANNED_COLUMNS.includes(cell.column.id);
                      const isLineItemSpanned = LINEITEM_SPANNED_COLUMNS.includes(cell.column.id);
                      const isTwoRowColumn = TWO_ROW_COLUMNS.includes(cell.column.id);
                      const isOrderColumn = ORDER_COLUMNS.includes(cell.column.id);
                      const isProductColumn = PRODUCT_COLUMNS.includes(cell.column.id);
                      if (isOrderSpanned && !row.original.isFirstLineItem) return null;
                      if (isLineItemSpanned && row.original.rowType !== 'order') return null;
                      if (isTwoRowColumn && row.original.rowType === 'fulfillment') return null;
                      if (isOrderColumn && row.original.rowType !== 'order') return null;
                      if (isProductColumn && row.original.rowType !== 'product') return null;
                      const meta = cell.column.columnDef.meta as { frozen?: boolean; freezeLeft?: number } | undefined;
                      const isFrozen = meta?.frozen;
                      const freezeLeft = meta?.freezeLeft;
                      return (
                        <TableCell 
                          key={cell.id} 
                          rowSpan={isOrderSpanned ? row.original.totalRowCount || 1 : isLineItemSpanned ? 3 : 1}
                          style={{ 
                            borderRight: '1px solid #e5e7eb', 
                            width: cell.column.getSize(), 
                            minWidth: cell.column.getSize(),
                            maxWidth: cell.column.getSize(), 
                            height: '48px', // 행 높이를 48px로 고정
                            overflow: 'hidden', 
                            textOverflow: 'ellipsis', 
                            whiteSpace: 'normal',
                            wordBreak: 'break-word',
                            padding: '6px 8px',
                            textAlign: ['shippingInfoPair', 'productNamePair'].includes(cell.column.id) ? 'left' : 'center',
                            position: isFrozen ? 'sticky' : undefined,
                            left: isFrozen ? freezeLeft : undefined,
                            zIndex: isFrozen ? 2 : undefined,
                            backgroundColor: isFrozen ? baseBgCol : undefined,
                            boxShadow: isFrozen ? '2px 0 4px rgba(0,0,0,0.1)' : undefined,
                          }}
                        >
                          {flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </TableCell>
                      );
                    })}
                  </TableRow>
                  );
                })}
              </TableBody>
            </Table>
        </div>
      </div>
    </div>
  );
};

export default OrderGrid;
