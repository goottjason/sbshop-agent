import React, { useState, useMemo, useEffect, useCallback, useRef } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  flexRender,
} from '@tanstack/react-table';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { fetchOrders, updateOrder, updateOrderLineItem, updateSourcingInfo, updateShippingInfo, shipOrders, syncCustomsStatus, syncCoupangOrders, syncSmartStoreOrders, syncElevenStreetOrders, syncEsmplusOrders, fetchCommonCodes, confirmOrdersBatch, cancelOrder, syncProductStock, fetchSyncStatus, updatePurchaseStatus } from '../../api/orderApi';
import type { OrderGridDto, OrderDto } from '../../api/orderApi';
import { toKstDate, kstDateString, kstDateStringOffset } from '../../utils/datetime';
import { SYNC_SOURCE_KEYS, syncSourceLabel } from '../../utils/marketLabels';
import { toast } from 'react-toastify';
import { useSearchParams } from 'react-router-dom';
import { Table, TableHeader, TableBody, TableRow, TableHead } from '../../components/ui/Table';
import type { RowData, OrdersCache } from './types';
import { toolbarBtn, toolbarBtnBase, DEFAULT_VISIBLE_STATUSES } from './constants';
import { marketSyncState, patchOrderInCache, patchLineItemInCache } from './helpers';
import { buildOrderColumns } from './orderColumns';
import OrderTableRow from './OrderTableRow';
import OrderFilterPanel from './OrderFilterPanel';

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
      const { exportOrdersToExcel } = await import('../../utils/orderExcelExport');
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
  const columns = useMemo(() => buildOrderColumns({
    getCommonLabel, handleUpdate, handleSyncCustoms, handleSyncProductStock, timeAgo,
  }), [handleUpdate, commonCodes, getCommonLabel]);
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
