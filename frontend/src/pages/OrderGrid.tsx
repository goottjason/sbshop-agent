import React, { useState, useMemo, useEffect, useCallback, useRef } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  createColumnHelper,
  type Row,
} from '@tanstack/react-table';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchOrders, updateOrder, updateOrderLineItem, updateSourcingInfo, updateShippingInfo, shipOrders, syncCustomsStatus, syncCoupangOrders, syncSmartStoreOrders, syncElevenStreetOrders, syncEsmplusOrders, fetchCommonCodes, confirmOrdersBatch, cancelOrder, syncProductStock, fetchSyncStatus, updatePurchaseStatus } from '../api/orderApi';
import type { OrderGridDto, ProductDto, OrderDto, OrderLineItemDto, OrderDetailResponseDto, PageResponse, ShipmentDto } from '../api/orderApi';
import type { KstPeriodRange } from '../utils/datetime';
import { formatPhone } from '../utils/phone';
import { toKstDate, kstDateString, kstDateStringOffset, kstPeriodRanges } from '../utils/datetime';
import { ORDER_MARKET_CODES, SYNC_SOURCE_KEYS, marketLabel, syncSourceLabel } from '../utils/marketLabels';
import { toast } from 'react-toastify';
import { useSearchParams } from 'react-router-dom';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '../components/ui/Table';

interface StockCellInfo {
  badge: 'IN_STOCK' | 'OUT_OF_STOCK' | 'NONE';
  restockDate?: string;
  updatedAt?: string;
}

type RowData = OrderGridDto & { isFirstLineItem?: boolean; lineItemCount?: number; totalRowCount?: number; rowType?: string; isSecondRow?: boolean; isThirdRow?: boolean };

type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';

type MarketSyncState = 'none' | 'synced' | 'waiting' | 'manual' | 'unknown';

type OrdersCache = PageResponse<OrderDetailResponseDto>;

interface OrderTableRowProps {
  row: Row<RowData>;
  isSelected: boolean;
  isOrderBoundary: boolean;
  colCount: number;
  colScale: number;
}

const columnHelper = createColumnHelper<RowData>();

const inputStyle = { width: '100%', padding: 'var(--field-pad)', fontSize: 'var(--field-fs)', border: '1px solid #d1d5db', borderRadius: '4px', boxSizing: 'border-box' as const, outline: 'none', backgroundColor: '#fdfdfd' };

const CARRIER_LABELS: Record<string, string> = {
  CJ_LOGISTICS: 'CJ대한통운',
  HANJIN: '한진택배',
  KOREA_POST: '우체국',
  LOTTE_LOGISTICS: '롯데택배',
  HYUNDAI_LOGISTICS: '현대택배',
  ROCKET: '쿠팡로켓',
};

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

const toolbarBtnBase = { padding: '4px 10px', border: 'none', borderRadius: '6px', cursor: 'pointer', fontSize: '12px', fontWeight: 600, whiteSpace: 'nowrap' as const };

const toolbarBtn = { ...toolbarBtnBase, backgroundColor: 'var(--primary-color)', color: '#fff', boxShadow: '0 1px 2px rgba(0,0,0,0.06)' };

const NO_SEND_STATUSES = ['CANCELED', 'RETURNED', 'EXCHANGED'];

const SYNC_BADGE: Record<'synced' | 'waiting' | 'manual' | 'unknown', { text: string; title: string; fg: string; bg: string; line: string }> = {
  manual: {
    text: '수정요망',
    title: '마켓이 송장 수정을 거부했습니다(배송중 등). 재시도로는 해결되지 않으니 마켓 판매자센터에서 직접 수정하세요. 고치면 다음 동기화에서 이 표시가 사라집니다.',
    fg: '#a52432', bg: '#fdeef0', line: '#f0aab3',
  },
  waiting: {
    text: '대기중',
    title: '송장은 저장됐지만 마켓에는 아직 반영되지 않았습니다. 다음 사이클에 자동으로 다시 시도합니다.',
    fg: '#92600c', bg: '#fdf4e0', line: '#eccb8a',
  },
  synced: {
    text: '반영됨',
    title: '마켓도 같은 송장을 갖고 있습니다.',
    fg: '#1a6b4f', bg: '#e8f5ef', line: '#a8d8c3',
  },
  unknown: {
    text: '미확인',
    title: '마켓에 전송한 기록은 있지만, 마켓이 어떤 송장을 갖고 있는지 아직 확인하지 못했습니다. '
      + '반영 여부를 단정할 수 없어 그대로 표시합니다(구매확정 등으로 조회 목록에서 벗어난 주문이 여기 해당합니다).',
    fg: '#5a6270', bg: '#f2f3f5', line: '#d3d7dd',
  },
};

const SOURCE_ICON = {
  EMAIL: {
    path: 'M638-80 468-250l56-56 114 114 226-226 56 56L638-80ZM480-520l320-200H160l320 200Zm0 80L160-640v400h206l80 80H160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v174l-80 80v-174L480-440Zm0 0Zm0-80Zm0 80Z',
    title: 'iHerb 발송메일이 확인해 준 진짜 송장입니다.',
    color: '#1a6b4f',
  },
  MANUAL: {
    path: 'M480-240Zm-320 80v-112q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q37 0 73 4.5t72 14.5l-67 68q-20-3-39-5t-39-2q-56 0-111 13.5T260-306q-9 5-14.5 14t-5.5 20v32h240v80H160Zm400 40v-123l221-220q9-9 20-13t22-4q12 0 23 4.5t20 13.5l37 37q8 9 12.5 20t4.5 22q0 11-4 22.5T903-340L683-120H560Zm300-263-37-37 37 37ZM620-180h38l121-122-18-19-19-18-122 121v38Zm141-141-19-18 37 37-18-19ZM367-527q-47-47-47-113t47-113q47-47 113-47t113 47q47 47 47 113t-47 113q-47 47-113 47t-113-47Zm169.5-56.5Q560-607 560-640t-23.5-56.5Q513-720 480-720t-56.5 23.5Q400-673 400-640t23.5 56.5Q447-560 480-560t56.5-23.5ZM480-640Z',
    title: '사람이나 마켓이 넣은 값입니다. 진짜인지 가송장인지 알 수 없습니다 — iHerb 메일이 도착하면 자동으로 진짜 송장으로 바뀝니다.',
    color: '#92600c',
  },
  LEGACY: {
    path: 'm424-296 282-282-56-56-226 226-114-114-56 56 170 170Zm56 216q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z',
    title: '이 기능이 생기기 전에 처리된 주문이라 출처가 기록돼 있지 않습니다(대부분 배송이 끝난 건입니다).',
    color: '#c4c8ce',
  },
} as const;

const ORDER_SPANNED_COLUMNS = ['select', 'orderInfo', 'shippingStatus'];

const LINEITEM_SPANNED_COLUMNS = ['sbCode', 'stockInfo', 'quantity', 'unipass', 'purchaseStatus', 'fulfillmentInfoPair', 'sourcingInfoPair'];

const TWO_ROW_COLUMNS = ['ordererInfo', 'customsInfo', 'shippingInfoPair', 'productNamePair', 'financialInfoPair'];

const ORDER_COLUMNS: string[] = [];

const PRODUCT_COLUMNS: string[] = [];

const TERMINAL_STATUSES = ['CANCELED', 'RETURNED', 'EXCHANGED'];

const ALL_STATUSES = ['UNKNOWN', 'NEW', 'PREPARING', 'DISPATCHED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RETURNED', 'EXCHANGED'];

const DEFAULT_VISIBLE_STATUSES = ALL_STATUSES.filter(s => !TERMINAL_STATUSES.includes(s));

const FILTER_OPEN_KEY = 'sbshop.orderFilter.open';

function stockCellInfo(product?: ProductDto): StockCellInfo {
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

function shortAccountLabel(email: string): string {
  if (!email) return '';
  const at = email.lastIndexOf('@');
  if (at < 0) return email;
  const local = email.slice(0, at);
  const domain = email.slice(at + 1).toLowerCase();
  const DOMAIN_TAGS: Record<string, string> = {
    'gmail.com': 'G',
    'skku.edu': 'SKKU',
    'g.skku.edu': 'SKKU',
    'naver.com': 'NAVER',
    'daum.net': 'DAUM',
    'nate.com': 'NATE',
  };
  const tag = DOMAIN_TAGS[domain];
  return tag ? `${local} ${tag}` : email;
}

function statusBorder(status: SaveStatus): string {
  switch (status) {
    case 'dirty': return '#f59e0b';
    case 'saving': return '#3b82f6';
    case 'saved': return '#22c55e';
    case 'error': return '#ef4444';
    default: return '#d1d5db';
  }
}

function blurLeftToPage(e: React.FocusEvent<HTMLElement>): boolean {
  if (e.currentTarget.contains(e.relatedTarget as Node | null)) return false;
  return document.hasFocus();
}

const formatThousands = (v: string | number): string => {
  const digits = String(v).replace(/[^\d]/g, '');
  return digits === '' ? '' : Number(digits).toLocaleString();
};

const parseThousands = (v: string): number => Number(v.replace(/[^\d]/g, '')) || 0;

const marketSyncState = (lineItem?: OrderLineItemDto, shipment?: ShipmentDto | null): MarketSyncState => {
  const shipping = lineItem?.shippingData;
  const tracking = (shipping?.trackingNo || '').trim();
  if (!tracking) return 'none';
  const status = shipping?.shippingStatus || '';
  if (NO_SEND_STATUSES.includes(status)) return 'none';
  const marketTracking = (shipment?.marketTrackingNo || '').trim();
  if (marketTracking) return marketTracking === tracking ? 'synced' : (shipment?.manualFixRequired ? 'manual' : 'waiting');
  if (shipment?.manualFixRequired) return 'manual';
  if (shipping?.trackingSentToMarket !== true && status !== 'DELIVERED') return 'waiting';
  return 'unknown';
};

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
    content: cache.content.map(item => {
      if (!(item.lineItems || []).some(d => d.lineItem?.id === lineItemId)) return item;
      return {
        ...item,
        lineItems: (item.lineItems || []).map(d =>
          d.lineItem?.id === lineItemId ? { ...d, lineItem: mutate(d.lineItem) } : d),
      };
    }),
  };
}

function InlineInput({ value, onCommit, type = 'text', align = 'left', title, selectAllOnFocus = false }: {
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

function FinancialEditCell({ sourcingAmount, logisticsCost, onSave }: {
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

function SourceIcon({ source }: { source?: 'EMAIL' | 'MANUAL' | 'MARKET' | null }) {
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

function ShippingEditCell({ carrier, trackingNo, syncState, marketTrackingNo, trackingSource, onSave }: {
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

const OrderTableRow = React.memo(function OrderTableRow({ row, isOrderBoundary, colCount, colScale }: OrderTableRowProps) {
  const baseBgCol = row.original.isFirstLineItem ? '#ffffff' : row.original.isSecondRow ? '#fdfdfd' : '#f9f9f9';
  return (
    <>
      <TableRow data-order-id={row.original.order?.id ?? undefined} style={{ backgroundColor: baseBgCol }}>
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
                height: 'var(--row-h)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'normal',
                wordBreak: 'break-word',
                padding: 'var(--cell-pad)',
                textAlign: ['shippingInfoPair', 'productNamePair'].includes(cell.column.id) ? 'left' : 'center',
                position: isFrozen ? 'sticky' : undefined,
                left: isFrozen ? (freezeLeft ?? 0) * colScale : undefined,
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
      {isOrderBoundary && (
        <tr aria-hidden="true">
          <td colSpan={colCount} style={{ padding: 0, height: '2px', backgroundColor: '#9ca3af' }} />
        </tr>
      )}
    </>
  );
}, (prev, next) =>
  prev.row.original === next.row.original
  && prev.isSelected === next.isSelected
  && prev.isOrderBoundary === next.isOrderBoundary
  && prev.colCount === next.colCount,
);

const PERIOD_DEFAULT = 'DEFAULT';
const PERIOD_CUSTOM = 'CUSTOM';
const PERIOD_FIXED_LABELS: Record<string, string> = { TODAY: '오늘', THIS_WEEK: '이번주' };
const periodLabel = (preset: KstPeriodRange) => PERIOD_FIXED_LABELS[preset.id] ?? `${preset.month}월`;

function OrderFilterPanel({ onSearch }: { onSearch: (keyword: string, markets: string[], statuses: string[], startDate: string, endDate: string, purchaseStatuses: string[], stockStatuses: string[], vendors: string[]) => void }) {
  const allMarkets = ORDER_MARKET_CODES;
  const allStatuses = ALL_STATUSES;
  const allPurchaseStatuses = ['NOT_PURCHASED', 'PURCHASED', 'WAITING_STOCK'];
  const allStockStatuses = ['IN_STOCK', 'OUT_OF_STOCK'];
  const allVendors = VENDOR_OPTIONS.filter(v => v !== '');
  const [selectedMarkets, setSelectedMarkets] = useState<string[]>(allMarkets);
  const [selectedStatuses, setSelectedStatuses] = useState<string[]>(DEFAULT_VISIBLE_STATUSES);
  const [selectedPurchaseStatuses, setSelectedPurchaseStatuses] = useState<string[]>(allPurchaseStatuses);
  const [selectedStockStatuses, setSelectedStockStatuses] = useState<string[]>(allStockStatuses);
  const [selectedVendors, setSelectedVendors] = useState<string[]>(allVendors);
  const [keyword, setKeyword] = useState('');
  const [startDate, setStartDate] = useState(kstDateStringOffset({ months: -1 }));
  const [endDate, setEndDate] = useState(kstDateString());
  const [activePeriod, setActivePeriod] = useState<string>(PERIOD_DEFAULT);
  const [today, setToday] = useState(kstDateString);
  useEffect(() => {
    const timer = setInterval(() => {
      const now = kstDateString();
      setToday(prev => prev === now ? prev : now);
    }, 60000);
    return () => clearInterval(timer);
  }, []);
  const periodPresets = useMemo(() => kstPeriodRanges(today), [today]);

  const [open, setOpen] = useState(() => localStorage.getItem(FILTER_OPEN_KEY) === '1');
  useEffect(() => { localStorage.setItem(FILTER_OPEN_KEY, open ? '1' : '0'); }, [open]);

  const isAllMarketsSelected = selectedMarkets.length === allMarkets.length;
  const isAllStatusesSelected = selectedStatuses.length === allStatuses.length;
  const isDefaultStatuses = selectedStatuses.length === DEFAULT_VISIBLE_STATUSES.length
    && DEFAULT_VISIBLE_STATUSES.every(s => selectedStatuses.includes(s));
  const isAllPurchaseSelected = selectedPurchaseStatuses.length === allPurchaseStatuses.length;
  const isAllStockSelected = selectedStockStatuses.length === allStockStatuses.length;
  const isAllVendorsSelected = selectedVendors.length === allVendors.length;
  const handleSearch = () => {
    const stockFilter = isAllStockSelected ? [] : selectedStockStatuses;
    const vendorFilter = isAllVendorsSelected ? [] : selectedVendors;
    onSearch(keyword, selectedMarkets, selectedStatuses, startDate, endDate, selectedPurchaseStatuses, stockFilter, vendorFilter);
  };
  const handlePeriod = (id: string) => {
    const preset = kstPeriodRanges().find(p => p.id === id);
    if (!preset) return;
    setActivePeriod(id);
    setStartDate(preset.start);
    setEndDate(preset.end);
  };
  const toggleMarket = (val: string) => setSelectedMarkets(prev => prev.includes(val) ? prev.filter(m => m !== val) : [...prev, val]);
  const toggleStatus = (val: string) => setSelectedStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);
  const togglePurchase = (val: string) => setSelectedPurchaseStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);
  const toggleStock = (val: string) => setSelectedStockStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);
  const toggleVendor = (val: string) => setSelectedVendors(prev => prev.includes(val) ? prev.filter(v => v !== val) : [...prev, val]);

  const chips: { label: string; active: boolean }[] = [
    { label: `${startDate.slice(5).replace('-', '.')} ~ ${endDate.slice(5).replace('-', '.')}`, active: activePeriod !== PERIOD_DEFAULT },
    { label: `마켓 ${isAllMarketsSelected ? '전체' : selectedMarkets.length}`, active: !isAllMarketsSelected },
    {
      label: `상태 ${isAllStatusesSelected ? '전체' : isDefaultStatuses ? '기본' : selectedStatuses.length}`,
      active: !isAllStatusesSelected && !isDefaultStatuses,
    },
    { label: `구매 ${isAllPurchaseSelected ? '전체' : selectedPurchaseStatuses.length}`, active: !isAllPurchaseSelected },
    { label: `재고 ${isAllStockSelected ? '전체' : selectedStockStatuses.length}`, active: !isAllStockSelected },
    { label: `소싱 ${isAllVendorsSelected ? '전체' : selectedVendors.length}`, active: !isAllVendorsSelected },
  ];
  return (
    <div style={{ backgroundColor: '#fff', border: '1px solid #e5e7eb', borderTop: '2px solid var(--primary-color)', borderRadius: '10px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', padding: open ? '8px 14px 10px' : '5px 14px', marginBottom: '6px', fontSize: '13px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px', minHeight: '26px', ...(open ? { borderBottom: '1px solid #eaeaea', paddingBottom: '6px', marginBottom: '6px' } : {}) }}>
        <button
          type="button"
          onClick={() => setOpen(o => !o)}
          style={{ display: 'flex', alignItems: 'center', gap: '4px', background: 'none', border: 'none', cursor: 'pointer', padding: '2px 4px', fontSize: '12px', fontWeight: 700, color: 'var(--primary-color)', flexShrink: 0 }}
          aria-expanded={open}
        >
          <span style={{ display: 'inline-block', transform: open ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }}>▸</span>
          필터
        </button>
        <div style={{ display: 'flex', gap: '5px', alignItems: 'center', flexWrap: 'wrap', minWidth: 0 }}>
          {!open && chips.map(c => (
            <span
              key={c.label}
              style={{
                fontSize: '11px', padding: '2px 7px', borderRadius: '10px', whiteSpace: 'nowrap',
                backgroundColor: c.active ? '#e0e7ff' : '#f3f4f6',
                color: c.active ? '#3730a3' : '#6b7280',
                fontWeight: c.active ? 600 : 400,
              }}
            >
              {c.label}
            </span>
          ))}
        </div>
        {!open && (
          <>
            <input
              type="text" placeholder="주문번호, 수취인명, 통관번호, 휴대폰, SB코드, 상품명, 송장번호…"
              value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()}
              style={{ flex: 1, minWidth: '160px', padding: '3px 9px', border: '1px solid #ccc', borderRadius: '4px', outline: 'none', fontSize: '12px' }}
            />
            <button onClick={handleSearch} style={{ backgroundColor: 'var(--primary-color)', color: 'white', border: 'none', padding: '4px 18px', fontSize: '12px', fontWeight: 'bold', cursor: 'pointer', borderRadius: '4px', flexShrink: 0 }}>검색</button>
          </>
        )}
      </div>
      {open && (
      <>
      <div style={{ display: 'flex', borderBottom: '1px solid #eaeaea', paddingBottom: '6px', marginBottom: '6px' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555', flexShrink: 0 }}>조회기간 (주문일)</span>
          <div style={{ display: 'flex', border: '1px solid #ccc', borderRadius: '4px', overflow: 'hidden', marginRight: '12px', flexShrink: 0 }}>
            {periodPresets.map((preset, idx) => (
              <button key={preset.id} onClick={() => handlePeriod(preset.id)} style={{ padding: '6px 12px', border: 'none', background: activePeriod === preset.id ? 'var(--primary-color)' : '#f8f9fa', borderLeft: idx === 0 ? 'none' : '1px solid #ccc', color: activePeriod === preset.id ? '#fff' : '#333', fontWeight: activePeriod === preset.id ? 600 : 400, cursor: 'pointer', whiteSpace: 'nowrap' }}>
                {periodLabel(preset)}
              </button>
            ))}
          </div>
          <input type="date" value={startDate} onChange={e => { setStartDate(e.target.value); setActivePeriod(PERIOD_CUSTOM); }} style={{ padding: '5px', border: '1px solid #ccc', flexShrink: 0 }} />
          <span style={{ margin: '0 8px', flexShrink: 0 }}>~</span>
          <input type="date" value={endDate} onChange={e => { setEndDate(e.target.value); setActivePeriod(PERIOD_CUSTOM); }} style={{ padding: '5px', border: '1px solid #ccc', flexShrink: 0 }} />
        </div>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>통합 검색</span>
          <input type="text" placeholder="주문번호, 수취인명, 주문자명, 통관번호, 휴대폰, SB코드, 등록상품명, 영문상품명, 송장번호" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()} style={{ flex: 1, padding: '6px 12px', border: '1px solid #ccc', outline: 'none' }} />
        </div>
      </div>
      <div style={{ display: 'flex', paddingBottom: '0' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>마켓채널</span>
          <div style={{ display: 'flex', gap: '16px' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllMarketsSelected} onChange={() => setSelectedMarkets(isAllMarketsSelected ? [] : allMarkets)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {ORDER_MARKET_CODES.map(code => (
              <label key={code} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedMarkets.includes(code)} onChange={() => toggleMarket(code)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {marketLabel(code)}
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
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>재고상태</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllStockSelected} onChange={() => setSelectedStockStatuses(isAllStockSelected ? [] : allStockStatuses)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {[
              { id: 'IN_STOCK', label: '있음' },
              { id: 'OUT_OF_STOCK', label: '품절' }
            ].map(ss => (
              <label key={ss.id} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedStockStatuses.includes(ss.id)} onChange={() => toggleStock(ss.id)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {ss.label}
              </label>
            ))}
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', paddingTop: '8px', marginTop: '8px', borderTop: '1px solid #eaeaea' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>소싱처</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllVendorsSelected} onChange={() => setSelectedVendors(isAllVendorsSelected ? [] : allVendors)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {allVendors.map(vendor => (
              <label key={vendor} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedVendors.includes(vendor)} onChange={() => toggleVendor(vendor)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {vendor}
              </label>
            ))}
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'center', marginTop: '8px' }}>
        <button onClick={handleSearch} style={{ backgroundColor: 'var(--primary-color)', color: 'white', border: 'none', padding: '5px 32px', fontSize: '12px', fontWeight: 'bold', cursor: 'pointer', borderRadius: '4px' }}>검색</button>
      </div>
      </>
      )}
    </div>
  );
}

const OrderGrid: React.FC = () => {
  const [isSyncing, setIsSyncing] = useState(false);
  const isSyncingRef = useRef(false);
  useEffect(() => { isSyncingRef.current = isSyncing; }, [isSyncing]);
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
  const defaultStart = kstDateStringOffset({ months: -1 });
  const defaultEnd = kstDateString();
  const [searchParams] = useSearchParams();
  const initialFromUrl = useMemo(() => {
    const getAll = (k: string) => searchParams.getAll(k);
    const markets = getAll('markets');
    const statuses = getAll('statuses');
    const stockStatuses = getAll('stockStatuses');
    const vendors = getAll('vendors');
    const customsStatuses = getAll('customsStatuses');
    const keyword = searchParams.get('keyword') ?? '';
    const startDate = searchParams.get('startDate') ?? undefined;
    const endDate = searchParams.get('endDate') ?? undefined;
    const hasAny = markets.length || statuses.length || stockStatuses.length || vendors.length || customsStatuses.length || searchParams.get('keyword') || searchParams.get('startDate') || searchParams.get('endDate');
    return hasAny ? {
      keyword,
      markets: markets.length ? markets : ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'],
      statuses: statuses.length ? statuses : DEFAULT_VISIBLE_STATUSES,
      purchaseStatuses: ['NOT_PURCHASED', 'PURCHASED', 'WAITING_STOCK'],
      stockStatuses, vendors, customsStatuses, startDate, endDate,
    } : null;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const [queryParams, setQueryParams] = useState<{keyword?: string, markets?: string[], statuses?: string[], purchaseStatuses?: string[], stockStatuses?: string[], vendors?: string[], customsStatuses?: string[], startDate?: string, endDate?: string}>(initialFromUrl ?? {
    keyword: '',
    markets: ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'],
    statuses: DEFAULT_VISIBLE_STATUSES,
    purchaseStatuses: ['NOT_PURCHASED', 'PURCHASED', 'WAITING_STOCK'],
    startDate: defaultStart,
    endDate: defaultEnd
  });
  const [searchTrigger, setSearchTrigger] = useState(0);
  const { data, isLoading: queryLoading, refetch } = useQuery({
    queryKey: ['orders', queryParams, searchTrigger],
    queryFn: () => fetchOrders(0, 500, queryParams.keyword, queryParams.markets, queryParams.statuses, queryParams.startDate, queryParams.endDate, queryParams.purchaseStatuses, queryParams.stockStatuses, queryParams.vendors, queryParams.customsStatuses)
  });
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
    onSuccess: () => {
      toast.success('송장/배송 정보가 마켓에 반영되었습니다.');
      queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
    onError: (err: unknown) => {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '배송정보 저장 중 오류가 발생했습니다.');
      toast.error(msg);
      queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });
  const handleUpdate = useCallback((orderId: number, lineItemId: number, field: string, value: unknown): Promise<unknown> => {
    if (field.startsWith('order.')) {
      const actualField = field.replace('order.', '');
      return orderMutation.mutateAsync({ id: orderId, updates: { [actualField]: value } });
    } else if (field === 'lineItem.isUnipassDone') {
      return lineItemMutation.mutateAsync({ id: lineItemId, updates: { isUnipassDone: value as boolean } });
    } else if (field === 'lineItem.financial') {
      const v = value as { sourcingAmount: number; logisticsCost: number };
      return sourcingMutation.mutateAsync({ id: lineItemId, updates: v });
    } else if (field === 'lineItem.sourcing') {
      const v = value as { sourcingAccount: string; sourcingVendor: string; sourcingOrderNo: string; discountCode: string };
      return sourcingMutation.mutateAsync({ id: lineItemId, updates: v });
    } else if (field === 'lineItem.shipping') {
      const v = value as { shippingCarrier: string; trackingNo: string };
      return shippingMutation.mutateAsync({ id: lineItemId, updates: { trackingNo: v.trackingNo, shippingCarrier: v.shippingCarrier } });
    } else if (field === 'lineItem.purchaseStatus') {
      return purchaseStatusMutation.mutateAsync({ id: lineItemId, status: value as 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK' });
    }
    return Promise.resolve();
  }, [orderMutation.mutateAsync, lineItemMutation.mutateAsync, sourcingMutation.mutateAsync, shippingMutation.mutateAsync, purchaseStatusMutation.mutateAsync]);
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
      toast.error(`${syncSourceLabel(marketType)} 동기화 실패: ${errorMsg}`);
    });
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
        setTimeout(() => setIsSyncing(false), 30000);
      } else {
        toast.error(res.message || 'N스토어 동기화에 실패했습니다.');
        setIsSyncing(false);
      }
    } catch {
      toast.error('N스토어 동기화 중 오류가 발생했습니다.');
      setIsSyncing(false);
    }
  };
  const handleSyncCoupang = async () => {
    setIsSyncing(true);
    try {
      const res = await syncCoupangOrders();
      if (res.success) {
        refetch();
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
  const handleExportExcel = async () => {
    const selectedIndices = Object.keys(rowSelection).filter(k => rowSelection[k]);
    if (selectedIndices.length === 0) {
      toast.warn('엑셀로 내려받을 주문을 선택해주세요.');
      return;
    }
    const seen = new Set<string>();
    const rows: OrderGridDto[] = [];
    processedData.forEach((row, index) => {
      if (!rowSelection[String(index)]) return;
      const key = String(row.lineItem?.id ?? `${row.order?.id}-${index}`);
      if (seen.has(key)) return;
      seen.add(key);
      rows.push(row);
    });

    if (rows.length === 0) return;
    try {
      const { exportOrdersToExcel } = await import('../utils/orderExcelExport');
      await exportOrdersToExcel(rows, getCommonLabel);
      toast.success(`${rows.length}건을 엑셀로 내려받았습니다.`);
    } catch {
      toast.error('엑셀 생성 중 오류가 발생했습니다.');
    }
  };
  const rowCacheRef = useRef<Map<string, RowData>>(new Map());
  const ROW_TYPES = ['order', 'product', 'fulfillment'] as const;
  const processedData = useMemo(() => {
    const content = data?.content;
    if (!content || content.length === 0) { rowCacheRef.current = new Map(); return [] as RowData[]; }
    const prev = rowCacheRef.current;
    const next = new Map<string, RowData>();
    const result: RowData[] = [];
    content.forEach((item) => {
      const lineItems = item.lineItems || [];
      const totalRowCount = lineItems.length * ROW_TYPES.length;
      const lineItemCount = lineItems.length;
      lineItems.forEach((li, liIndex) => {
        ROW_TYPES.forEach((rowType) => {
          const isFirst = rowType === 'order' && liIndex === 0;
          const key = `${li.lineItem?.id ?? `idx${liIndex}`}-${rowType}`;
          const cached = prev.get(key);
          const reusable = cached
            && cached.order === item.order
            && cached.lineItem === li.lineItem
            && cached.product === li.product
            && cached.marketRegistration === li.marketRegistration
            && cached.shipment === li.shipment
            && cached.isFirstLineItem === isFirst
            && cached.lineItemCount === lineItemCount
            && cached.totalRowCount === totalRowCount;
          const row: RowData = reusable ? cached! : {
            order: item.order,
            lineItem: li.lineItem,
            product: li.product,
            marketRegistration: li.marketRegistration,
            shipment: li.shipment,
            rowType,
            isFirstLineItem: isFirst,
            isSecondRow: rowType === 'product',
            isThirdRow: rowType === 'fulfillment',
            lineItemCount,
            totalRowCount,
          };
          next.set(key, row);
          result.push(row);
        });
      });
    });
    rowCacheRef.current = next;
    return result;
  }, [data]);
  const [syncFilter, setSyncFilter] = useState<'manual' | 'waiting' | 'unknown' | null>(null);
  const syncCounts = useMemo(() => {
    let manual = 0;
    let waiting = 0;
    let unknown = 0;
    processedData.forEach((row) => {
      if (row.rowType !== 'order') return;
      const state = marketSyncState(row.lineItem, row.shipment);
      if (state === 'manual') manual += 1;
      else if (state === 'waiting') waiting += 1;
      else if (state === 'unknown') unknown += 1;
    });
    return { manual, waiting, unknown };
  }, [processedData]);
  const visibleData = useMemo(() => {
    if (!syncFilter) return processedData;
    const keep = new Set<number>();
    processedData.forEach((row) => {
      if (marketSyncState(row.lineItem, row.shipment) === syncFilter && row.lineItem?.id) {
        keep.add(row.lineItem.id);
      }
    });
    return processedData.filter((row) => row.lineItem?.id && keep.has(row.lineItem.id));
  }, [processedData, syncFilter]);

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
      size: 130,
      cell: ({ row }) => {
        const dateObj = row.original.order?.orderDate ? new Date(row.original.order.orderDate as string) : null;
        const dateStr = dateObj ? `${dateObj.getFullYear()}-${String(dateObj.getMonth() + 1).padStart(2, '0')}-${String(dateObj.getDate()).padStart(2, '0')} ${String(dateObj.getHours()).padStart(2, '0')}:${String(dateObj.getMinutes()).padStart(2, '0')}` : '-';
        const market = row.original.order?.marketType || '';
        const orderNo = row.original.order?.marketOrderNo || '-';
        const marketColorMap: Record<string, { bg: string; text: string }> = {
          'SMART_STORE': { bg: '#f1f8e9', text: '#689f38' },
          'COUPANG': { bg: '#fce4ec', text: '#c2185b' },
          'ELEVEN_STREET': { bg: '#e3f2fd', text: '#1565c0' },
          'CAFE24': { bg: '#fffde7', text: '#fbc02d' },
          'GMARKET': { bg: '#c8e6c9', text: '#1b5e20' },
          'AUCTION': { bg: '#fff3e0', text: '#e65100' }
        };
        const style = marketColorMap[market] || { bg: '#f5f5f5', text: '#666' };
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '3px', lineHeight: '1.2', fontSize: '12px', textAlign: 'center' }}>
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
      size: 90,
      meta: { frozen: true, freezeLeft: 170 },
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
        return val ? <span style={{ backgroundColor: style.bg, color: style.text, padding: '2px 6px', borderRadius: '4px', fontWeight: 600 }}>{getCommonLabel('shippingStatus', val)}</span> : '-';
      }
    }),
    columnHelper.display({
      id: 'ordererInfo',
      header: '주문자정보',
      size: 120,
      meta: { frozen: true, freezeLeft: 260 },
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          return <span>{formatPhone(row.original.order?.recipientPhone) || '-'}</span>;
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
          if (verifiedPerson === 'RECIPIENT') recipientColor = '#1565c0';
          else if (verifiedPerson === 'ORDERER') ordererColor = '#1565c0';
        } else if (customsStatus === 'INVALID_PCCC' || customsStatus === 'INVALID_PHONE' || customsStatus === 'INVALID_ZIPCODE') {
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
      size: 126,
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
          return <span style={{ backgroundColor: style.bg, color: style.text, padding: '2px 6px', borderRadius: '4px', fontWeight: 600, fontSize: '12px', whiteSpace: 'pre-line', lineHeight: '1.3' }}>{getCommonLabel('customsStatus', val)}</span>;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const val = row.original.order?.customsData?.customsClearanceNo || '';
        return <InlineInput value={val} onCommit={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.customsClearanceNo', v)} />;
      }
    }),
    columnHelper.display({
      id: 'shippingInfoPair',
      header: '배송정보',
      size: 190,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          const val = row.original.order?.address || '';
          return <InlineInput value={val} onCommit={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.address', v)} />;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const zipcode = row.original.order?.zipcode || '';
        const message = row.original.order?.message || '';
        return (
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '12px', paddingLeft: '4px' }}>
            <span style={{ fontWeight: 500, flexShrink: 0 }}>{zipcode || '-'}</span>
            <span style={{ color: '#999', flexShrink: 0 }}>|</span>
            <InlineInput
              value={message}
              selectAllOnFocus
              title="배송메시지 — 클릭하면 전체 선택됩니다"
              onCommit={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.message', v)}
            />
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'sbCode',
      header: '상품코드',
      size: 110,
      cell: ({ row }) => {
        const val = row.original.product?.sbCode;
        if (!val) {
          return (
            <div
              title="마켓 상품을 우리 상품에 연결하지 못했습니다. 재고·정산·소싱이 이 건을 건너뜁니다."
              style={{ textAlign: 'center', fontWeight: 700, fontSize: '11px', color: '#c62828', background: '#ffebee', border: '1px solid #ef9a9a', borderRadius: '4px', padding: '1px 4px' }}
            >
              미매핑
            </div>
          );
        }
        return <div style={{ textAlign: 'center', fontWeight: 600, fontSize: '12px' }}>{val}</div>;
      }
    }),
    columnHelper.display({
      id: 'productNamePair',
      header: '상품정보',
      size: 315,
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
    columnHelper.display({
      id: 'quantity',
      header: '수량',
      size: 56,
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
    columnHelper.display({
      id: 'stockInfo',
      header: () => (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
          재고
          <svg onClick={handleSyncProductStock} xmlns="http://www.w3.org/2000/svg" height="18px" viewBox="0 -960 960 960" width="18px" fill="#555" style={{ cursor: 'pointer' }}>
            <path d="M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z"/>
          </svg>
        </div>
      ),
      size: 64,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') return null;
        if (row.original.rowType === 'fulfillment') return null;
        const info = stockCellInfo(row.original.product);
        let badge = <span style={{ color: '#999' }}>-</span>;
        if (info.badge === 'IN_STOCK') badge = <span style={{ backgroundColor: '#e8f5e9', color: '#2e7d32', padding: '2px 6px', borderRadius: '4px', fontWeight: 600 }}>있음</span>;
        if (info.badge === 'OUT_OF_STOCK') badge = <span style={{ backgroundColor: '#ffebee', color: '#c62828', padding: '2px 6px', borderRadius: '4px', fontWeight: 600 }}>품절</span>;
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', alignItems: 'center' }}>
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
      size: 190,
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
              syncState={marketSyncState(row.original.lineItem, row.original.shipment)}
              marketTrackingNo={row.original.shipment?.marketTrackingNo || undefined}
              trackingSource={row.original.shipment?.trackingSource ?? null}
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
  ], [handleUpdate, commonCodes, getCommonLabel]);
  const table = useReactTable({
    data: visibleData,
    columns,
    state: { rowSelection },
    enableRowSelection: true,
    columnResizeMode: 'onChange',
    onRowSelectionChange: setRowSelection,
    getCoreRowModel: getCoreRowModel(),
  });

  const totalColWidth = table.getTotalSize();
  const [colScale, setColScale] = useState(1);
  useEffect(() => {
    const el = gridScrollRef.current;
    if (!el) return;
    const recompute = () => {
      const avail = el.clientWidth;
      setColScale(totalColWidth > 0 && avail > totalColWidth ? avail / totalColWidth : 1);
    };
    recompute();
    const ro = new ResizeObserver(recompute);
    ro.observe(el);
    return () => ro.disconnect();
  }, [totalColWidth]);
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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '12px', marginBottom: '6px', flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', minWidth: 0 }}>
          <h2 style={{ margin: 0, fontSize: '15px', fontWeight: 800, color: 'var(--primary-color)', letterSpacing: -0.2, whiteSpace: 'nowrap' }}>통합 주문 관리</h2>
          {syncStatuses && (
            <div style={{ display: 'flex', gap: '9px', alignItems: 'center', flexWrap: 'wrap' }}>
              {SYNC_SOURCE_KEYS.map((key) => {
                const label = syncSourceLabel(key);
                const s = syncStatuses[key];
                return (
                  <span key={key} style={{ fontSize: '11px', color: '#666', display: 'flex', alignItems: 'center', gap: '4px', whiteSpace: 'nowrap' }}>
                    <span style={{ width: '7px', height: '7px', borderRadius: '50%', backgroundColor: s ? syncDotColor(s.status) : '#9e9e9e', display: 'inline-block' }} />
                    {label}
                    <span style={{ color: '#aaa' }}>{s ? timeAgo(s.lastSyncAt) : '전'}</span>
                  </span>
                );
              })}
            </div>
          )}
        </div>
        <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
          <button onClick={handleSyncSmartStore} style={toolbarBtn}>N스토어 동기화</button>
          <button onClick={handleSyncCoupang} style={toolbarBtn}>쿠팡 동기화</button>
          <button onClick={handleSyncElevenStreet} style={toolbarBtn}>11번가 동기화</button>
          <button onClick={handleSyncEsmplus} style={toolbarBtn}>G마켓/옥션 동기화</button>
          <button onClick={handleConfirmOrders} disabled={!canConfirmSelected} style={{ ...toolbarBtnBase, backgroundColor: canConfirmSelected ? '#e8f5e9' : '#f5f5f5', color: canConfirmSelected ? '#2e7d32' : '#999', border: `1px solid ${canConfirmSelected ? '#c8e6c9' : '#e0e0e0'}`, cursor: canConfirmSelected ? 'pointer' : 'not-allowed', fontWeight: 'bold', opacity: canConfirmSelected ? 1 : 0.6 }}>선택 주문 확인</button>
          <button onClick={handleCancelOrders} style={{ ...toolbarBtnBase, backgroundColor: '#ffebee', color: '#c62828', border: '1px solid #ffcdd2', fontWeight: 'bold' }}>선택 주문 거부</button>
          <button onClick={handleShipSelected} style={{ ...toolbarBtnBase, backgroundColor: '#fff', color: '#333', border: '1px solid #ddd' }}>선택 발송</button>
          <button onClick={handleExportExcel} style={{ ...toolbarBtnBase, backgroundColor: '#fff', color: '#217346', border: '1px solid #c8e6c9' }}>엑셀 다운로드</button>
        </div>
      </div>
      <OrderFilterPanel onSearch={(keyword, markets, statuses, startDate, endDate, purchaseStatuses, stockStatuses, vendors) => { setQueryParams(prev => ({ keyword, markets, statuses, purchaseStatuses, startDate, endDate, stockStatuses, vendors, customsStatuses: prev.customsStatuses })); setSearchTrigger(c => c + 1); }} />
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
          {(syncCounts.manual > 0 || syncCounts.waiting > 0 || syncCounts.unknown > 0 || syncFilter) && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '8px 4px', flexWrap: 'wrap' }}>
              {[
                { key: 'manual' as const, label: '마켓 수동수정 필요', count: syncCounts.manual, fg: '#a52432', bg: '#fdeef0', line: '#f0aab3' },
                { key: 'waiting' as const, label: '마켓 전송 대기', count: syncCounts.waiting, fg: '#92600c', bg: '#fdf4e0', line: '#eccb8a' },
                { key: 'unknown' as const, label: '마켓 값 미확인', count: syncCounts.unknown, fg: '#5a6270', bg: '#f2f3f5', line: '#d3d7dd' },
              ].map(chip => {
                const on = syncFilter === chip.key;
                return (
                  <button key={chip.key} type="button"
                    onClick={() => setSyncFilter(on ? null : chip.key)}
                    title={chip.key === 'manual'
                      ? '마켓이 송장 수정을 거부한 건입니다. 마켓 판매자센터에서 직접 수정해야 합니다.'
                      : '아직 마켓에 반영되지 않았지만 다음 사이클에 자동으로 다시 시도합니다.'}
                    style={{
                      display: 'inline-flex', alignItems: 'center', gap: '6px', cursor: 'pointer',
                      fontSize: '12px', fontWeight: 600, padding: '4px 10px', borderRadius: '999px',
                      border: `1px solid ${on ? chip.line : '#d1d5db'}`,
                      backgroundColor: on ? chip.bg : 'transparent',
                      color: on ? chip.fg : '#6b7280',
                    }}>
                    {chip.label}
                    <span style={{ backgroundColor: on ? chip.fg : '#d1d5db', color: on ? chip.bg : '#fff',
                      borderRadius: '999px', padding: '0 6px', fontSize: '11px' }}>{chip.count}</span>
                  </button>
                );
              })}
              {syncFilter && (
                <button type="button" onClick={() => setSyncFilter(null)}
                  style={{ fontSize: '11px', color: '#6b7280', background: 'none', border: 'none', cursor: 'pointer', textDecoration: 'underline' }}>
                  필터 해제
                </button>
              )}
            </div>
          )}
          <Table fluid minTableWidth={totalColWidth} style={{ tableLayout: 'fixed', width: '100%' }}>
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
                          left: isFrozen ? (freezeLeft ?? 0) * colScale : undefined,
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
                {(() => {
                  const rows = table.getRowModel().rows;
                  const colCount = table.getVisibleLeafColumns().length;
                  return rows.map((row, rowIdx) => {
                    const isOrderBoundary = rows[rowIdx + 1]?.original.order?.id !== row.original.order?.id;
                    return (
                      <OrderTableRow
                        key={row.id}
                        row={row}
                        isSelected={row.getIsSelected()}
                        isOrderBoundary={isOrderBoundary}
                        colCount={colCount}
                        colScale={colScale}
                      />
                    );
                  });
                })()}
              </TableBody>
            </Table>
        </div>
      </div>
    </div>
  );
};

export default OrderGrid;
