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
import type { OrderGridDto, ProductDto, OrderDto, OrderLineItemDto, OrderDetailResponseDto, PageResponse } from '../api/orderApi';
import { formatPhone } from '../utils/phone';
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
import { useSearchParams } from 'react-router-dom';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '../components/ui/Table';

type RowData = OrderGridDto & { isFirstLineItem?: boolean; lineItemCount?: number; totalRowCount?: number; rowType?: string; isSecondRow?: boolean; isThirdRow?: boolean };
const columnHelper = createColumnHelper<RowData>();

// 인라인 편집 컨트롤 공통 스타일. 크기는 index.css의 밀도 토큰(--field-pad/--field-fs)을 따른다.
// 이 값은 행 높이(--row-h)와 연동된다 — 구매정보 셀이 이 컨트롤 4개를 rowSpan=3 안에 쌓기 때문.
const inputStyle = { width: '100%', padding: 'var(--field-pad)', fontSize: 'var(--field-fs)', border: '1px solid #d1d5db', borderRadius: '4px', boxSizing: 'border-box' as const, outline: 'none', backgroundColor: '#fdfdfd' };

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

// 상단 툴바 버튼 — 제목·동기화 상태와 한 줄을 공유하므로 컴팩트하게.
const toolbarBtnBase = { padding: '4px 10px', border: 'none', borderRadius: '6px', cursor: 'pointer', fontSize: '12px', fontWeight: 600, whiteSpace: 'nowrap' as const };
const toolbarBtn = { ...toolbarBtnBase, backgroundColor: 'var(--primary-color)', color: '#fff', boxShadow: '0 1px 2px rgba(0,0,0,0.06)' };

// 구매계정 이메일을 도메인 축약 라벨로 표시(값은 원본 이메일 유지).
//   @gmail.com→G · @skku.edu/@g.skku.edu→SKKU · @naver.com→NAVER · @daum.net→DAUM · @nate.com→NATE
// 예) kimjongwon0907@gmail.com → "kimjongwon0907 G", jongwon@skku.edu → "jongwon SKKU"
export function shortAccountLabel(email: string): string {
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

// 금액 입력 표시용: 숫자를 천단위 콤마 문자열로 포맷(값 저장은 숫자로 파싱).
// type="number"는 콤마를 못 담으므로 type="text"+inputMode로 두고 표시만 포맷한다. 음수·소수 없음(금액·물류비).
const formatThousands = (v: string | number): string => {
  const digits = String(v).replace(/[^\d]/g, '');
  return digits === '' ? '' : Number(digits).toLocaleString();
};
const parseThousands = (v: string): number => Number(v.replace(/[^\d]/g, '')) || 0;

// 정산 정보(실구매가+물류비) 통합 인라인 편집 셀.
// 두 숫자를 한 세트로 → 컨테이너 밖으로 포커스가 나갈 때 변경분을 1회 sourcing 저장.
// 입력 즉시 천단위 콤마 자동 표시(예: 12000 → 12,000).
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
// 종결 상태는 마켓 전송 자체가 불가하므로 미반영 경고 대상이 아니다.
const NO_SEND_STATUSES = ['CANCELED', 'RETURNED', 'EXCHANGED'];
// 마켓이 송장 보유를 확인해 준 상태 — D-129 이전에 동기화된 행은 플래그가 null이라 이걸로 보정한다.
// 동기화가 다시 돌면 플래그가 채워지므로 이 폴백은 시간이 지나며 자연히 무의미해진다.
const MARKET_CONFIRMED_STATUSES = ['SHIPPED', 'DELIVERED'];

/**
 * "저장됨 · 마켓 미반영" 판정 — 송장은 우리 DB에 있는데 마켓에는 반영되지 않은 상태(D-129).
 *
 * D-127(11번가 송장 전송이 항상 실패)이 오래 눈에 띄지 않았던 이유가 이 구분이 화면에 없었기
 * 때문이다. D-125가 전송 실패에도 로컬 송장을 보존하도록 바꾼 뒤로는 화면상 송장이 멀쩡히
 * 보여서, 마켓에 한 건도 안 들어가고 있다는 사실이 드러나지 않았다.
 */
const isMarketUnsynced = (lineItem?: OrderLineItemDto): boolean => {
  const shipping = lineItem?.shippingData;
  const tracking = (shipping?.trackingNo || '').trim();
  if (!tracking) return false;                                   // 송장이 없으면 반영할 것도 없다
  if (shipping?.trackingSentToMarket === true) return false;     // 마켓 보유 확인됨
  const status = shipping?.shippingStatus || '';
  if (NO_SEND_STATUSES.includes(status)) return false;
  if (MARKET_CONFIRMED_STATUSES.includes(status)) return false;  // 레거시 행 보정
  return true;
};

function ShippingEditCell({ carrier, trackingNo, marketUnsynced, onSave }: {
  carrier: string;
  trackingNo: string;
  marketUnsynced: boolean;
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
      {marketUnsynced && (
        <span
          title="송장은 저장됐지만 마켓에는 아직 반영되지 않았습니다. 전송을 다시 시도하거나 마켓 판매자센터를 확인하세요."
          style={{ fontSize: '10px', fontWeight: 700, color: '#b45309', backgroundColor: '#fef3c7',
            border: '1px solid #fcd34d', borderRadius: '4px', padding: '1px 4px', whiteSpace: 'nowrap' }}
        >
          ⚠ 마켓 미반영
        </span>
      )}
      <button type="button" onClick={send} disabled={!canSend}
        style={{ fontSize: '11px', padding: '1px 6px', borderRadius: '4px', border: 'none', cursor: canSend ? 'pointer' : 'default',
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
    // 해당 lineItem을 가진 주문만 새 객체로 교체하고 나머지 주문은 참조를 그대로 유지한다.
    // 이 참조 안정성이 행 메모이제이션(변경된 주문의 행만 재렌더)의 전제가 된다.
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

// ─── 메모된 주문 행 ───
// 셀 1개를 편집하면 낙관적 캐시 패치로 "그 주문의 행 객체"만 새 참조가 되고(patch* 참조 보존),
// 나머지 행은 original 참조가 동일하므로 이 memo가 재렌더를 건너뛴다. 결과적으로 편집 시
// 변경된 주문의 3행만 재렌더 → 수백~수천 셀 전체 재렌더로 인한 굼뜸이 사라진다.
// row 인스턴스는 매 렌더 새로 생성되므로 비교에서 의도적으로 무시하고, 안정적인
// original/isSelected/isOrderBoundary/colCount만 비교한다(스킵 시 이전 렌더 출력은 동일 데이터라 안전).
interface OrderTableRowProps {
  row: Row<RowData>;
  isSelected: boolean;
  isOrderBoundary: boolean;
  colCount: number;
  colScale: number;
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
                // 폭은 table-layout:fixed가 헤더 기준으로 비율 확장 → 셀에는 최소폭만 두고 maxWidth 캡은 제거.
                width: cell.column.getSize(),
                minWidth: cell.column.getSize(),
                height: 'var(--row-h)', // 행 높이 — index.css 밀도 토큰
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
  // row 인스턴스 identity는 무시(매 렌더 새로 생성됨). 데이터·선택·경계·컬럼수가 같으면 재렌더 스킵.
  prev.row.original === next.row.original
  && prev.isSelected === next.isSelected
  && prev.isOrderBoundary === next.isOrderBoundary
  && prev.colCount === next.colCount,
);

// 종결(취소/반품/교환) 상태 — 기본 조회에서 제외한다.
const TERMINAL_STATUSES = ['CANCELED', 'RETURNED', 'EXCHANGED'];
const ALL_STATUSES = ['UNKNOWN', 'NEW', 'PREPARING', 'DISPATCHED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RETURNED', 'EXCHANGED'];
// 통합 주문 관리 진입 시 기본 표시 상태(종결상태 제외). 유저가 필터에서 별도 체크해야 종결건이 보인다.
const DEFAULT_VISIBLE_STATUSES = ALL_STATUSES.filter(s => !TERMINAL_STATUSES.includes(s));

// 필터 패널 펼침 상태 기억 키
const FILTER_OPEN_KEY = 'sbshop.orderFilter.open';

// 상단 필터 패널 컴포넌트 (UI)
function OrderFilterPanel({ onSearch }: { onSearch: (keyword: string, markets: string[], statuses: string[], startDate: string, endDate: string, purchaseStatuses: string[], stockStatuses: string[], vendors: string[]) => void }) {
   const allMarkets = ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'];
  const allStatuses = ALL_STATUSES;
  const allPurchaseStatuses = ['NOT_PURCHASED', 'PURCHASED', 'WAITING_STOCK'];
  const allStockStatuses = ['IN_STOCK', 'OUT_OF_STOCK'];
  const allVendors = VENDOR_OPTIONS.filter(v => v !== '');

  const [selectedMarkets, setSelectedMarkets] = useState<string[]>(allMarkets);
  // 기본 조회에서 종결상태(취소/반품/교환)는 제외 — 보고 싶으면 유저가 직접 체크.
  const [selectedStatuses, setSelectedStatuses] = useState<string[]>(DEFAULT_VISIBLE_STATUSES);
  const [selectedPurchaseStatuses, setSelectedPurchaseStatuses] = useState<string[]>(allPurchaseStatuses);
  const [selectedStockStatuses, setSelectedStockStatuses] = useState<string[]>(allStockStatuses);
  const [selectedVendors, setSelectedVendors] = useState<string[]>(allVendors);
  const [keyword, setKeyword] = useState('');

  // 날짜 기본값: 1개월 전 ~ 오늘
  const today = new Date();
  const oneMonthAgo = new Date(today);
  oneMonthAgo.setMonth(oneMonthAgo.getMonth() - 1);
  const fmt = (d: Date) => d.toISOString().split('T')[0];

  const [startDate, setStartDate] = useState(fmt(oneMonthAgo));
  const [endDate, setEndDate] = useState(fmt(today));
  const [activePeriod, setActivePeriod] = useState(2); // 기본: 1개월

  // 필터 패널 펼침 상태 — 기본 접힘(화면당 주문 건수 확보), 사용자의 선택은 브라우저에 기억한다.
  const [open, setOpen] = useState(() => localStorage.getItem(FILTER_OPEN_KEY) === '1');
  useEffect(() => { localStorage.setItem(FILTER_OPEN_KEY, open ? '1' : '0'); }, [open]);

  const isAllMarketsSelected = selectedMarkets.length === allMarkets.length;
  const isAllStatusesSelected = selectedStatuses.length === allStatuses.length;
  // 진입 기본값(종결상태 제외)과 동일한지 — 요약칩에서 '기본'으로 표시하고 강조하지 않는다.
  const isDefaultStatuses = selectedStatuses.length === DEFAULT_VISIBLE_STATUSES.length
    && DEFAULT_VISIBLE_STATUSES.every(s => selectedStatuses.includes(s));
  const isAllPurchaseSelected = selectedPurchaseStatuses.length === allPurchaseStatuses.length;
  const isAllStockSelected = selectedStockStatuses.length === allStockStatuses.length;
  const isAllVendorsSelected = selectedVendors.length === allVendors.length;

  const handleSearch = () => {
    // stockStatuses/vendors는 백엔드에서 correlated EXISTS 서브쿼리(Product.stockStatus,
    // OrderLineItem.productId, sourcingData.sourcingVendor — 모두 nullable/미설정 가능)로 필터링된다.
    // SQL IN(...)은 NULL을 매치하지 않으므로, "전체 선택" 상태에서 명시적 전체 목록을 보내면
    // 상품/재고/소싱 메타데이터가 없는 라인아이템의 주문이 검색 결과에서 조용히 누락된다.
    // 전체 선택(기본값)은 빈 배열(=백엔드 no-op, 무필터)로 보내고, 실제 부분선택일 때만 목록을 보낸다.
    const stockFilter = isAllStockSelected ? [] : selectedStockStatuses;
    const vendorFilter = isAllVendorsSelected ? [] : selectedVendors;
    onSearch(keyword, selectedMarkets, selectedStatuses, startDate, endDate, selectedPurchaseStatuses, stockFilter, vendorFilter);
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
  const toggleStock = (val: string) => setSelectedStockStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);
  const toggleVendor = (val: string) => setSelectedVendors(prev => prev.includes(val) ? prev.filter(v => v !== val) : [...prev, val]);

  // 접힘 상태의 요약칩 — 비기본값은 강조색으로, 필터가 걸린 걸 접힌 채로도 알 수 있게 한다.
  const chips: { label: string; active: boolean }[] = [
    { label: `${startDate.slice(5).replace('-', '.')} ~ ${endDate.slice(5).replace('-', '.')}`, active: activePeriod !== 2 },
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
      {/* 요약 바 — 접힘/펼침 공통. 접힌 상태에선 검색어·검색 버튼까지 여기 노출해 펼칠 필요를 없앤다. */}
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
        {/* 펼친 상태에선 아래 컨트롤이 같은 정보를 보여주므로 요약칩은 접힘 전용. */}
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
      {/* Row 1: Period and Search */}
      <div style={{ display: 'flex', borderBottom: '1px solid #eaeaea', paddingBottom: '6px', marginBottom: '6px' }}>
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
              { id: 'SMART_STORE', label: 'N스토어' },
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

      {/* Row 3: 구매상태 / 재고상태 */}
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

      {/* Row 4: 소싱처 */}
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
    SMART_STORE: 'N스토어',
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

  const [searchParams] = useSearchParams();
  // 대시보드 드릴다운 등에서 URL 쿼리파라미터로 넘어온 경우, 이를 초기 필터로 사용.
  // 관련 파라미터가 하나도 없으면 기존 기본값(종결상태 제외 등)을 그대로 유지.
  const initialFromUrl = useMemo(() => {
    const getAll = (k: string) => searchParams.getAll(k);
    const markets = getAll('markets');
    const statuses = getAll('statuses');
    const stockStatuses = getAll('stockStatuses');
    const vendors = getAll('vendors');
    const customsStatuses = getAll('customsStatuses');
    const keyword = searchParams.get('keyword') ?? '';
    // 대시보드 드릴다운은 기간 무관 조회(전체 기간)를 의도하고 startDate/endDate를 의도적으로 생략할 수 있다.
    // 이 경우 그리드 기본값(1개월 전)으로 클램프하면 오래된 주문(예: 40일 지연 NEW)이 숨겨지므로 undefined(무제한)로 둔다.
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
    // react-query v5의 mutateAsync는 렌더 간 안정 참조라 handleUpdate가 안정된다
    // → columns useMemo가 매 렌더 재생성되지 않아 전체 그리드 재렌더가 사라진다.
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

  // 선택한 주문을 엑셀로 내려받는다. 그리드는 주문상품 1건을 3행(order/product/fulfillment)에
  // 나눠 그리므로, 어느 행을 골랐든 주문상품 단위로 접어서 중복 없이 1건 1행으로 내보낸다.
  const handleExportExcel = async () => {
    const selectedIndices = Object.keys(rowSelection).filter(k => rowSelection[k]);
    if (selectedIndices.length === 0) {
      toast.warn('엑셀로 내려받을 주문을 선택해주세요.');
      return;
    }

    const seen = new Set<string>();
    const rows: OrderGridDto[] = [];
    // processedData 순서(=화면 정렬)를 유지해야 엑셀과 화면을 나란히 대조할 수 있다.
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

  // 주문 데이터를 그리드 행(주문/상품/발송 3행 × lineItem)으로 평탄화 + 병합정보 계산.
  // data가 바뀔 때만 재계산하고, 변경되지 않은 주문의 행 객체는 이전 참조를 재사용한다
  // → 낙관적 캐시 패치로 한 주문만 바뀌면 그 주문의 행만 새 참조가 되어, 메모된 행이
  //   변경된 주문의 3행만 재렌더한다(셀 1개 편집에 전체 그리드가 재렌더되던 문제 제거).
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
      const totalRowCount = lineItems.length * ROW_TYPES.length; // 주문 전체 병합 행 수
      const lineItemCount = lineItems.length;
      lineItems.forEach((li, liIndex) => {
        ROW_TYPES.forEach((rowType) => {
          const isFirst = rowType === 'order' && liIndex === 0; // 주문의 첫 order 행에만 구분선/병합
          const key = `${li.lineItem?.id ?? `idx${liIndex}`}-${rowType}`;
          const cached = prev.get(key);
          const reusable = cached
            && cached.order === item.order
            && cached.lineItem === li.lineItem
            && cached.product === li.product
            && cached.marketRegistration === li.marketRegistration
            && cached.isFirstLineItem === isFirst
            && cached.lineItemCount === lineItemCount
            && cached.totalRowCount === totalRowCount;
          const row: RowData = reusable ? cached! : {
            order: item.order,
            lineItem: li.lineItem,
            product: li.product,
            marketRegistration: li.marketRegistration,
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

    // ─── 2줄 컬럼 (행 1과 행 2에 각각 표시) ───
    // 주문자정보: 행1=수취인명(주문자명), 행2=휴대폰
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
    // 배송정보: 행1=우편번호|배송메시지, 행2=주소
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

    // 수량: 상품정보 옆 배치(라인아이템 병합 rowSpan=3).
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

    // ─── Row 1 전용 컬럼 (주문 행에만 표시) ───
    // ─── 2줄 병합 컬럼 (행 1, 행 2) ───
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
              marketUnsynced={isMarketUnsynced(row.original.lineItem)}
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
    data: processedData,
    columns,
    state: { rowSelection },
    enableRowSelection: true,
    columnResizeMode: 'onChange',
    onRowSelectionChange: setRowSelection,
    getCoreRowModel: getCoreRowModel(),
  });

  // 반응형 컬럼 확장: 뷰 폭이 총 컬럼폭보다 넓으면 그 비율(scale)만큼 모든 컬럼이 늘어난다
  // (table-layout:fixed + width:100%가 폭은 자동 확장). frozen 컬럼의 고정 left 오프셋은
  // 이 scale로 함께 보정해야 늘어난 컬럼과 정렬이 어긋나지 않는다.
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
      {/* 툴바 — 제목·동기화 상태·액션을 한 줄에 병합(이전 2줄 82px → 34px). */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '12px', marginBottom: '6px', flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', minWidth: 0 }}>
          <h2 style={{ margin: 0, fontSize: '15px', fontWeight: 800, color: 'var(--primary-color)', letterSpacing: -0.2, whiteSpace: 'nowrap' }}>통합 주문 관리</h2>
          {syncStatuses && (
            <div style={{ display: 'flex', gap: '9px', alignItems: 'center', flexWrap: 'wrap' }}>
              {Object.entries(marketLabels).map(([key, label]) => {
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
                    // 주문 경계: 다음 행이 다른 주문(또는 마지막 행)이면 이 행이 주문의 마지막 행.
                    // 그 위치에 전체 폭 회색 구분선(스페이서 행)을 삽입해 주문 단위를 구분한다.
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
