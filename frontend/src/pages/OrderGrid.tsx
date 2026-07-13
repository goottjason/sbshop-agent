import React, { useState, useMemo, useEffect, useCallback, useRef } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
  createColumnHelper,
} from '@tanstack/react-table';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchOrders, updateOrder, updateOrderLineItem, updateSourcingInfo, updateShippingInfo, shipOrders, syncCustomsStatus, syncCoupangOrders, syncSmartStoreOrders, syncElevenStreetOrders, syncEsmplusOrders, fetchCommonCodes, confirmOrdersBatch, cancelOrder, deleteOrder, syncProductStock, fetchSyncStatus } from '../api/orderApi';
import type { OrderGridDto, ProductDto } from '../api/orderApi';

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
const carrierLabel = (c?: string | null) => (c && CARRIER_LABELS[c]) ? CARRIER_LABELS[c] : '-';
// 인라인 select용 택배사 옵션(빈값=지우기 → '-')
const CARRIER_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '-' },
  ...Object.entries(CARRIER_LABELS).map(([value, label]) => ({ value, label })),
];

// 더블클릭 → 편집, blur/Enter 저장, Escape 취소하는 인라인 편집 셀.
// value가 바뀐 경우에만 onSave 호출. 행 선택 등 상위 이벤트로의 전파는 막는다.
function InlineEditCell({ value, display, onSave, type = 'text', options }: {
  value: string;
  display?: string;
  onSave: (v: string) => void;
  type?: 'text' | 'select';
  options?: { value: string; label: string }[];
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);
  const inputRef = useRef<HTMLInputElement>(null);
  const selectRef = useRef<HTMLSelectElement>(null);

  useEffect(() => {
    if (editing) {
      setDraft(value);
      // autofocus (다음 tick에 ref가 마운트됨)
      setTimeout(() => {
        if (type === 'select') selectRef.current?.focus();
        else { inputRef.current?.focus(); inputRef.current?.select(); }
      }, 0);
    }
  }, [editing, value, type]);

  const commit = (next: string) => {
    setEditing(false);
    if (next !== value) onSave(next);
  };

  if (editing) {
    if (type === 'select') {
      return (
        <select
          ref={selectRef}
          value={draft}
          style={{ ...inputStyle, textAlign: 'center' }}
          onClick={(e) => e.stopPropagation()}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={(e) => commit(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') commit((e.target as HTMLSelectElement).value);
            else if (e.key === 'Escape') { e.stopPropagation(); setEditing(false); }
          }}
        >
          {(options || []).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      );
    }
    return (
      <input
        ref={inputRef}
        type="text"
        value={draft}
        style={{ ...inputStyle, textAlign: 'center' }}
        onClick={(e) => e.stopPropagation()}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={(e) => commit(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit((e.target as HTMLInputElement).value);
          else if (e.key === 'Escape') { e.stopPropagation(); setEditing(false); }
        }}
      />
    );
  }

  return (
    <span
      onDoubleClick={(e) => { e.stopPropagation(); setEditing(true); }}
      title="더블클릭하여 편집"
      style={{ display: 'block', cursor: 'text', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
    >
      {display ?? value ?? '-'}
    </span>
  );
}

// 주문 전체 병합 컬럼 (rowSpan = 해당 주문의 전체 행 수)
const ORDER_SPANNED_COLUMNS = ['select', 'orderInfo', 'shippingStatus'];

// 라인아이템 병합 컬럼 (rowSpan = 3)
const LINEITEM_SPANNED_COLUMNS = ['sbCode', 'stockInfo', 'quantity', 'unipass'];

// 2줄 컬럼 (행1, 행2에만 표시, 행3에서는 셀 자체를 렌더링하지 않음)
const TWO_ROW_COLUMNS = ['ordererInfo', 'customsInfo', 'shippingInfoPair', 'productNamePair', 'sourcingInfoPair', 'fulfillmentInfoPair', 'financialInfoPair'];

// Row 1 전용 컬럼 (주문 행에만 표시)
const ORDER_COLUMNS: string[] = [];

// Row 2 전용 컬럼 (상품 행에만 표시)
const PRODUCT_COLUMNS: string[] = [];

// 상단 필터 패널 컴포넌트 (UI)
function OrderFilterPanel({ onSearch }: { onSearch: (keyword: string, markets: string[], statuses: string[], startDate: string, endDate: string) => void }) {
   const allMarkets = ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'];
  const allStatuses = ['UNKNOWN', 'NEW', 'PREPARING', 'PURCHASED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RETURNED', 'EXCHANGED'];
  
  const [selectedMarkets, setSelectedMarkets] = useState<string[]>(allMarkets);
  const [selectedStatuses, setSelectedStatuses] = useState<string[]>(allStatuses);
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

  const handleSearch = () => {
    onSearch(keyword, selectedMarkets, selectedStatuses, startDate, endDate);
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
              { id: 'PURCHASED', label: '구매완료' },
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
    if (!dateStr) return '-';
    const diff = Date.now() - new Date(dateStr).getTime();
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

  const [queryParams, setQueryParams] = useState<{keyword?: string, markets?: string[], statuses?: string[], startDate?: string, endDate?: string}>({
    keyword: '',
    markets: ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'],
    statuses: ['UNKNOWN', 'NEW', 'PREPARING', 'PURCHASED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RETURNED', 'EXCHANGED'],
    startDate: defaultStart,
    endDate: defaultEnd
  });
  const [searchTrigger, setSearchTrigger] = useState(0);

  const { data, isLoading: queryLoading, refetch } = useQuery({
    queryKey: ['orders', queryParams, searchTrigger],
    queryFn: () => fetchOrders(0, 500, queryParams.keyword, queryParams.markets, queryParams.statuses, queryParams.startDate, queryParams.endDate)
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

  const orderMutation = useMutation({
    mutationFn: ({ id, updates }: { id: number; updates: Record<string, unknown> }) => updateOrder(id, updates),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['orders'] }),
  });
  const lineItemMutation = useMutation({
    mutationFn: ({ id, updates }: { id: number; updates: { isUnipassDone?: boolean } }) => updateOrderLineItem(id, updates),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['orders'] }),
  });
  const sourcingMutation = useMutation({
    mutationFn: ({ id, updates }: { id: number; updates: { sourcingAmount?: number; logisticsCost?: number; sourcingAccount?: string; sourcingVendor?: string; sourcingOrderNo?: string; discountCode?: string } }) => updateSourcingInfo(id, updates),
    onSuccess: () => {
      toast.success('구매 정보가 저장되었습니다.');
      queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
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

  // sibling: 배송 엔드포인트(trackingNo+shippingCarrier 동시 전송)에서 편집하지 않은 반대편 필드의
  // 현재 행 값을 함께 보내기 위한 선택 인자. 배송 모달이 두 필드를 함께 저장하던 동작을 그대로 유지한다.
  const handleUpdate = useCallback((orderId: number, lineItemId: number, field: string, value: unknown, sibling?: string) => {
    if (field.startsWith('order.')) {
      const actualField = field.replace('order.', '');
      orderMutation.mutate({ id: orderId, updates: { [actualField]: value } });
    } else if (field === 'lineItem.isUnipassDone') {
      lineItemMutation.mutate({ id: lineItemId, updates: { isUnipassDone: value as boolean } });
    } else if (field === 'lineItem.sourcingAmount') {
      // 소싱금액은 SourcingData 필드 → 소싱 엔드포인트로 전송(라인아이템 PATCH DTO에는 없음)
      sourcingMutation.mutate({ id: lineItemId, updates: { sourcingAmount: value as number } });
    } else if (field === 'lineItem.logisticsCost') {
      // 물류비도 SourcingData 필드 → 소싱 엔드포인트로 전송
      sourcingMutation.mutate({ id: lineItemId, updates: { logisticsCost: value as number } });
    } else if (field === 'lineItem.sourcingAccount') {
      sourcingMutation.mutate({ id: lineItemId, updates: { sourcingAccount: value as string } });
    } else if (field === 'lineItem.sourcingVendor') {
      sourcingMutation.mutate({ id: lineItemId, updates: { sourcingVendor: value as string } });
    } else if (field === 'lineItem.sourcingOrderNo') {
      sourcingMutation.mutate({ id: lineItemId, updates: { sourcingOrderNo: value as string } });
    } else if (field === 'lineItem.discountCode') {
      sourcingMutation.mutate({ id: lineItemId, updates: { discountCode: value as string } });
    } else if (field === 'lineItem.trackingNo') {
      // 배송 엔드포인트는 trackingNo+shippingCarrier를 함께 받는다(배송 모달과 동일). 반대편은 현재 행 값 유지.
      shippingMutation.mutate({ id: lineItemId, updates: { trackingNo: value as string, shippingCarrier: sibling ?? '' } });
    } else if (field === 'lineItem.shippingCarrier') {
      shippingMutation.mutate({ id: lineItemId, updates: { shippingCarrier: value as string, trackingNo: sibling ?? '' } });
    }
  }, [orderMutation, lineItemMutation, sourcingMutation, shippingMutation]);

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
          'UNKNOWN': { bg: '#f5f5f5', text: '#666' },
          'NEW': { bg: '#e0f7fa', text: '#006064' },
          'PREPARING': { bg: '#fff3e0', text: '#e65100' },
          'PURCHASED': { bg: '#fffde7', text: '#fbc02d' },
          'SHIPPED': { bg: '#f1f8e9', text: '#558b2f' },
          'DELIVERED': { bg: '#e1f5fe', text: '#0277bd' },
          'CANCELED': { bg: '#ffebee', text: '#c62828' },
          'RETURNED': { bg: '#f3e5f5', text: '#6a1b9a' },
          'EXCHANGED': { bg: '#e8eaf6', text: '#283593' }
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
        return <input style={inputStyle} defaultValue={val} onBlur={(e) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.customsClearanceNo', e.target.value)} />;
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
          return <input style={inputStyle} defaultValue={val} onBlur={(e) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.address', e.target.value)} />;
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
      cell: ({ row }) => {
        const orderId = row.original.order?.id || 0;
        const lineItemId = row.original.lineItem?.id || 0;
        if (row.original.rowType === 'product') {
          // 상품 행: 구매주문번호 / 할인코드 (더블클릭 인라인 편집)
          const sourcingOrderNo = row.original.lineItem?.sourcingData?.sourcingOrderNo || '';
          const discountCode = row.original.lineItem?.sourcingData?.discountCode || '';
          return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', fontSize: '12px', textAlign: 'center' }}>
              <InlineEditCell value={sourcingOrderNo} display={sourcingOrderNo || '-'} onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.sourcingOrderNo', v)} />
              <InlineEditCell value={discountCode} display={discountCode ? `(${discountCode})` : '-'} onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.discountCode', v)} />
            </div>
          );
        }
        if (row.original.rowType === 'fulfillment') return null;
        // 주문 행: 구매계정 / 공급처 (더블클릭 인라인 편집)
        const account = row.original.lineItem?.sourcingData?.sourcingAccount || '';
        const vendor = row.original.lineItem?.sourcingData?.sourcingVendor || '';
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', fontSize: '12px', textAlign: 'center', fontWeight: 600, color: '#1565c0' }}>
            <InlineEditCell value={account} display={account || '-'} onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.sourcingAccount', v)} />
            <InlineEditCell value={vendor} display={vendor || '-'} onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.sourcingVendor', v)} />
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'fulfillmentInfoPair',
      header: '배송 정보',
      size: 150,
      cell: ({ row }) => {
        const orderId = row.original.order?.id || 0;
        const lineItemId = row.original.lineItem?.id || 0;
        const carrier = row.original.lineItem?.shippingData?.shippingCarrier || '';
        const trackingNo = row.original.lineItem?.shippingData?.trackingNo || '';
        if (row.original.rowType === 'product') {
          // 상품 행: 송장번호 (더블클릭 인라인 편집). 배송 엔드포인트는 택배사도 함께 받으므로 sibling으로 현재 택배사 전달.
          return (
            <div style={{ fontSize: '12px', textAlign: 'center' }}>
              <InlineEditCell value={trackingNo} display={trackingNo || '-'} onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.trackingNo', v, carrier)} />
            </div>
          );
        }
        if (row.original.rowType === 'fulfillment') return null;
        // 주문 행: 택배사 select (더블클릭 인라인 편집). 미매핑/ETC/빈값은 '-'로 표시.
        return (
          <div style={{ fontSize: '12px', color: '#666', textAlign: 'center' }}>
            <InlineEditCell
              type="select"
              options={CARRIER_OPTIONS}
              value={carrier}
              display={carrierLabel(carrier)}
              onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.shippingCarrier', v, trackingNo)}
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
          const sourcingAmount = row.original.lineItem?.sourcingData?.sourcingAmount || 0;
          const logisticsCost = row.original.lineItem?.sourcingData?.logisticsCost || 0;
          return (
            <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px', textAlign: 'center' }}>실구매가</div>
                <input style={{...inputStyle, textAlign: 'center'}} type="number" defaultValue={sourcingAmount} onBlur={(e) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'lineItem.sourcingAmount', Number(e.target.value))} />
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px', textAlign: 'center' }}>물류비</div>
                <input style={{...inputStyle, textAlign: 'center'}} type="number" defaultValue={logisticsCost} onBlur={(e) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'lineItem.logisticsCost', Number(e.target.value))} />
              </div>
            </div>
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
        return <div style={{ textAlign: 'center' }}><input type="checkbox" checked={!!isDone} onChange={(e) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'lineItem.isUnipassDone', e.target.checked)} /></div>;
      }
    }),
    // ─── Row 1 전용 컬럼 (주문 행에만 표시) ───
    columnHelper.display({
      id: 'quantity',
      header: '수량',
      size: 70,
      cell: ({ row }) => {
        const qty = (row.original.lineItem?.quantity || 1) as number;
        const bundle = (row.original.product?.productSpec?.bundleQuantity || 1) as number;
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
      await shipOrders(orderIds as number[]);
      refetch();
      toast.success(`${orderIds.length}건 발송 처리되었습니다.`);
    } catch {
      toast.error('발송 처리 중 오류가 발생했습니다.');
    }
  };

  const handleDeleteSelected = async () => {
    const selectedRows = table.getSelectedRowModel().rows;
    const orderIds = Array.from(new Set(selectedRows.map(r => r.original.order?.id))).filter(id => id);
    if (orderIds.length === 0) {
      toast.warning('삭제할 주문을 선택해주세요.');
      return;
    }
    if (!window.confirm(`선택한 ${orderIds.length}개의 주문을 삭제하시겠습니까?`)) return;
    try {
      await Promise.all(orderIds.map(id => deleteOrder(id as number)));
      setRowSelection({});
      refetch();
      toast.success(`${orderIds.length}건 삭제 완료`);
    } catch {
      toast.error('주문 삭제 중 오류가 발생했습니다.');
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
          <button onClick={handleDeleteSelected} style={{ padding: '8px 16px', backgroundColor: '#fff', color: '#333', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>선택 삭제</button>
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
      <OrderFilterPanel onSearch={(keyword, markets, statuses, startDate, endDate) => { setQueryParams({ keyword, markets, statuses, startDate, endDate }); setSearchTrigger(c => c + 1); }} />

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
