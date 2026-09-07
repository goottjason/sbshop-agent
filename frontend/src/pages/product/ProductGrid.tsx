import { useEffect, useMemo, useState } from 'react';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import {
  useReactTable, getCoreRowModel, flexRender, createColumnHelper,
  type RowSelectionState,
} from '@tanstack/react-table';
import { Alert, App as AntApp, Modal as AntModal, Pagination, Segmented, Select, Dropdown } from 'antd';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '../../components/ui/Table';
import { productApi, type ProductList, type ProductQuery } from '../../api/productApi';
import { MarketBadgeCell } from './MarketBadgeCell';
import { ProductFilterPanel, type ProductFilters } from './ProductFilterPanel';
import { EMPTY_PRODUCT_FILTERS, sourceProductUrl } from './productSearch';
import { ProductDetailModal } from './ProductDetailModal';
import { ProductNumericPreviewModal } from './ProductNumericPreviewModal';
import { ProductSourceRefreshModal } from './ProductSourceRefreshModal';
import { ProductContentRefreshModal } from './ProductContentRefreshModal';
import { ProductContentFreshnessCell } from './ProductContentFreshnessCell';
import { ProductBulkValuesModal } from './ProductBulkValuesModal';
import { ProductStockSync } from './ProductStockSync';
import { ProductFieldSyncModal } from './ProductFieldSyncModal';
import { ProductMarketPlusPublicRefresh } from './ProductMarketPlusPublicRefresh';
import { ProductInspectionJobs } from './ProductInspectionJobs';
import { ProductPriceSync } from './ProductPriceSync';
import { ProductRegistrationJobs } from './ProductRegistrationJobs';
import { ProductMarketPlusHistory } from './ProductMarketPlusHistory';
import { MarketPlusReadinessNotice } from './MarketPlusReadinessNotice';
import { MarketPlusObserverNotice } from './MarketPlusObserverNotice';
import { isAxiosError } from 'axios';
import { bulkDeleteProducts } from './productBulkApi';
import { notify } from '../../utils/notify';

const columnHelper = createColumnHelper<ProductList>();

function stockBadge(soldOut: boolean): React.CSSProperties {
  const c = soldOut ? { bg: '#ffebee', text: '#c62828' } : { bg: '#e8f5e9', text: '#2e7d32' };
  return { fontSize: 11, fontWeight: 600, padding: '2px 10px', borderRadius: 4, background: c.bg, color: c.text };
}

function toQuery(page: number, size: number, keyword: string, f: ProductFilters): ProductQuery {
  const q: ProductQuery = { page, size };
  if (keyword) q.keyword = keyword;
  if (f.sbCodes.length > 0) q.sbCodes = f.sbCodes;
  if (f.brands.length > 0) q.brands = f.brands;
  if (f.categories.length > 0) q.categories = f.categories;
  if (f.includeUncategorized) q.includeUncategorized = true;
  if (f.vendors.length > 0) q.vendors = f.vendors;
  if (f.stockStatuses.length > 0) q.stockStatuses = f.stockStatuses;
  if (f.markets.length > 0) q.markets = f.markets;
  if (f.registeredMarkets.length > 0) q.registeredMarkets = f.registeredMarkets;
  if (f.missingMarkets.length > 0) q.missingMarkets = f.missingMarkets;
  if (f.anyMarketSyncIssue) q.anyMarketSyncIssue = true;
  if (f.pendingChangesOnly) q.pendingChangesOnly = true;
  if (f.marketPlusIssue !== 'ALL') q.marketPlusIssue = f.marketPlusIssue;
  if (f.inStockOnly) q.inStockOnly = true;
  if (f.sourceGone && f.sourceGone !== 'ALL') q.sourceGone = f.sourceGone;
  if (f.contentAgeDays != null) q.contentAgeDays = f.contentAgeDays;
  q.contentAgeField = f.contentAgeField;
  return q;
}

export default function ProductGrid() {
  const { modal } = AntApp.useApp();
  const [filters, setFilters] = useState<ProductFilters>(EMPTY_PRODUCT_FILTERS);
  const [density, setDensity] = useState('compact');
  const [sort, setSort] = useState('workspacePriority,asc');
  const [keyword, setKeyword] = useState('');
  const [detailId, setDetailId] = useState<number | null>(null);
  const [marketPlusHistoryId, setMarketPlusHistoryId] = useState<number | null>(null);
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  // 삭제는 건별 마켓 API 호출이라 수십 초가 걸린다. 진행 표시가 없으면 사용자가 버튼을 다시 눌러
  // 확인 모달이 겹쳐 뜬다(D-256). 진행 중에는 버튼을 잠그고 몇 건째인지 보여준다.
  const [deleting, setDeleting] = useState(false);
  const [deleteProgress, setDeleteProgress] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [sourceRefreshIds, setSourceRefreshIds] = useState<number[] | null>(null);
  const [numericPreviewIds, setNumericPreviewIds] = useState<number[] | null>(null);
  const [bulkValuesIds, setBulkValuesIds] = useState<number[] | null>(null);
  const [registrationIds, setRegistrationIds] = useState<number[] | null>(null);
  const [priceSyncIds, setPriceSyncIds] = useState<number[] | null>(null);
  const [fieldSyncIds, setFieldSyncIds] = useState<number[] | null>(null);
  const [publicCheckIds, setPublicCheckIds] = useState<number[] | null>(null);
  const [stockSyncIds, setStockSyncIds] = useState<number[] | null>(null);
  const [inspectionIds, setInspectionIds] = useState<number[] | null>(null);
  const [contentRefreshIds, setContentRefreshIds] = useState<number[] | null>(null);


  const query = useMemo(() => ({ ...toQuery(page, pageSize, keyword, filters), sort }), [page, pageSize, keyword, filters, sort]);

  const { data, isLoading, isFetching, isPlaceholderData, isError, error: searchError, refetch } = useQuery({
    queryKey: ['products', query],
    queryFn: async () => (await productApi.fetchProducts(query)).data,
    placeholderData: keepPreviousData,
    retry: (count, error) => !(isAxiosError(error) && [400, 401, 403].includes(error.response?.status ?? 0)) && count < 2,
  });

  const { data: categoryOptions = [] } = useQuery({
    queryKey: ['product-categories'],
    queryFn: async () => (await productApi.fetchCategories()).data,
  });

  const { data: brandOptions = [], isLoading: brandsLoading, isError: brandsError, refetch: refetchBrands } = useQuery({
    queryKey: ['product-brands'],
    queryFn: async () => (await productApi.fetchBrands()).data,
  });

  const rows = useMemo(() => isError ? [] : data?.content ?? [], [data, isError]);
  const totalElements = data?.totalElements ?? 0;
  const pageCount = Math.max(1, data?.totalPages ?? 1);

  useEffect(() => {
    if (data && page > 0 && page >= pageCount) setPage(pageCount - 1);
  }, [data, page, pageCount]);

  const handleSearch = (f: ProductFilters) => { setKeyword(f.keyword); setFilters(f); setPage(0); setRowSelection({}); };

  const columns = useMemo(() => [
    columnHelper.display({
      id: 'select', header: ({ table }) => (
        <input type="checkbox" aria-label="현재 페이지 상품 선택" checked={table.getIsAllRowsSelected()} disabled={isPlaceholderData || isError}
          ref={(el) => { if (el) el.indeterminate = table.getIsSomeRowsSelected(); }}
          onChange={table.getToggleAllRowsSelectedHandler()}
          style={{ width: 16, height: 16, accentColor: 'var(--product-primary)', cursor: 'pointer' }} />
      ), size: 40,
      cell: ({ row }) => (
        <input type="checkbox" aria-label={`${row.original.sbCode} 선택`} checked={row.getIsSelected()} disabled={!row.getCanSelect()} onChange={row.getToggleSelectedHandler()}
          style={{ width: 16, height: 16, accentColor: 'var(--product-primary)', cursor: 'pointer' }} />
      ),
    }),
    columnHelper.accessor('repImageUrl', {
      id: 'image', header: '이미지', size: 64,
      cell: (info) => info.getValue()
        ? <img src={info.getValue()} alt="상품 썸네일" loading="lazy" style={{ width: 44, height: 44, objectFit: 'contain', borderRadius: 8, border: '1px solid #e5e7eb' }} />
        : <div style={{ width: 44, height: 44, borderRadius: 8, background: '#f1f5f9', border: '1px solid #e5e7eb', margin: '0 auto' }} />,
    }),
    columnHelper.accessor('sbCode', {
      id: 'sbCode', header: 'SB코드', size: 120,
      cell: (info) => <span style={{ fontWeight: 600, color: '#475569' }}>{info.getValue()}</span>,
    }),
    columnHelper.accessor('brand', { id: 'brand', header: '브랜드', size: 100,
      cell: (info) => <span style={{ color: '#64748b' }}>{info.getValue() || '-'}</span> }),
    columnHelper.display({
      id: 'productInfo', header: '상품정보', size: 300,
      cell: ({ row }) => {
        const sourceUrl = sourceProductUrl(row.original.sourcingUrl);
        return <div style={{ textAlign: 'left', minWidth: 0 }} title={row.original.productName}>
          {sourceUrl
            ? <a className="pw-product-name" href={sourceUrl} target="_blank" rel="noopener noreferrer">{row.original.productName} ↗</a>
            : <span className="pw-product-name" title="소싱처 상품 URL 없음">{row.original.productName}</span>}
          <div style={{ fontSize: 11, color: '#94a3b8', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{row.original.originalName || ' '}</div>
        </div>;
      },
    }),
    columnHelper.accessor('category', { id: 'category', header: '카테고리', size: 100,
      cell: (info) => <span style={{ color: '#64748b' }}>{info.getValue() || '-'}</span> }),
    columnHelper.accessor('vendor', { id: 'vendor', header: '소싱처', size: 80,
      cell: (info) => <span style={{ color: '#64748b' }}>{info.getValue() || '-'}</span> }),
    columnHelper.display({
      id: 'sourceGone', header: '원본 상태', size: 130,
      cell: ({ row }) => {
        const r = row.original;
        if (!r.sourceGoneReason) {
          if (!r.lastCrawlError) return <span style={{ color: '#94a3b8' }}>{r.lastCrawlAt ? '소멸 기록 없음' : '확인 이력 없음'}</span>;
          const tried = r.lastCrawlAt ? String(r.lastCrawlAt).slice(0, 10) : null;
          return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }} title={r.lastCrawlError}>
              <span style={{ fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 4,
                background: '#e0e7ff', color: '#3730a3', width: 'fit-content' }}>
                확인 실패
              </span>
              {tried && <span style={{ fontSize: 11, color: '#94a3b8' }}>{tried} 시도</span>}
            </div>
          );
        }
        const label = r.sourceGoneReason === 'DISCONTINUED' ? '단종' : '링크 소멸';
        const since = r.sourceGoneAt ? String(r.sourceGoneAt).slice(0, 10) : null;
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span style={{ fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 4,
              background: '#fef3c7', color: '#92400e', width: 'fit-content' }}>
              {label}
            </span>
            {since && <span style={{ fontSize: 11, color: '#94a3b8' }}>{since}부터</span>}
          </div>
        );
      },
    }),
    columnHelper.accessor('salePrice', {
      id: 'priceStock', header: '기준가 · DB 재고', size: 150,
      cell: (info) => {
        const r = info.row.original;
        const soldOut = r.stockStatus ? r.stockStatus === 'OUT_OF_STOCK' : (r.stock ?? 0) <= 0;
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'center' }}>
            <span style={{ fontWeight: 700, color: '#0f172a' }}>{r.salePrice != null ? `${r.salePrice.toLocaleString()}원` : '-'}</span>
            <span style={stockBadge(soldOut)}>{soldOut ? '품절' : '재고 있음'} · {r.stock?.toLocaleString() ?? '미확인'}</span>
          </div>
        );
      },
    }),
    columnHelper.accessor('bundleQuantity', { id: 'bundleQuantity', header: '묶음', size: 60,
      cell: (info) => info.getValue() != null ? `${info.getValue()}개` : '—' }),
    columnHelper.display({ id: 'contentFreshness', header: '콘텐츠 DB 적용', size: 170,
      cell: ({ row }) => <ProductContentFreshnessCell value={row.original.contentFreshness} sbCode={row.original.sbCode}
        disabled={isPlaceholderData || isError} onOpen={() => setContentRefreshIds([row.original.id])} /> }),
    columnHelper.display({
      id: 'markets', header: '마켓', size: 340,
      cell: ({ row }) => <><MarketBadgeCell product={row.original} onPublished={refetch} onViewHistory={() => setMarketPlusHistoryId(row.original.id)} />
        {(row.original.pendingChanges ?? 0) > 0 && <button className="pw-detail-button" style={{ color: '#b45309', marginTop: 4 }} onClick={() => setDetailId(row.original.id)}>변경 반영 대기 {row.original.pendingChanges}건</button>}</>,
    }),
    columnHelper.display({ id: 'detail', header: '편집', size: 68,
      cell: ({ row }) => <button className="pw-detail-button" aria-label={`${row.original.sbCode} 상세 편집`} onClick={() => setDetailId(row.original.id)}>열기</button> }),
  ], [refetch, isPlaceholderData, isError]);

  const table = useReactTable({
    data: rows,
    columns,
    state: { rowSelection },
    enableRowSelection: !isPlaceholderData && !isError,
    onRowSelectionChange: setRowSelection,
    getRowId: (r) => String(r.id),
    getCoreRowModel: getCoreRowModel(),
  });

  const selectedIds = Object.keys(rowSelection).filter((k) => rowSelection[k]).map(Number);

  const handleBulkDelete = () => {
    if (deleting) return;
    if (selectedIds.length === 0) { notify.warning('삭제할 상품을 선택하세요.'); return; }
    setDeleting(true);
    modal.confirm({
      title: `상품 ${selectedIds.length}개 삭제`,
      content: '선택한 상품을 삭제합니다. 되돌릴 수 없습니다. 진행할까요?',
      okText: '삭제', okType: 'danger', cancelText: '취소',
      onCancel: () => setDeleting(false),
      onOk: async () => {
        try {
          const { deleted, failed } = await bulkDeleteProducts(
            selectedIds, (done, total) => setDeleteProgress(`${done}/${total}`));
          if (failed.length === 0) notify.success(`${deleted}개 삭제 완료`);
          else notify.warning(`${deleted}개 삭제, ${failed.length}개 실패`);
          setRowSelection({});
          refetch();
        } finally {
          setDeleting(false);
          setDeleteProgress(null);
        }
      },
    });
  };

  return (
    <div className={`product-theme pw-density-${density}`} style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: '14px 20px', background: '#f4f6f7' }}>
      <style>{`
        .pg-size {
          appearance: none; -webkit-appearance: none; -moz-appearance: none;
          padding: 7px 30px 7px 12px; border: 1px solid #d1d5db; border-radius: 8px;
          background-color: #fff;
          background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%2364748b' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E");
          background-repeat: no-repeat; background-position: right 10px center;
          font-size: 13px; font-weight: 600; color: #475569; cursor: pointer;
          box-shadow: 0 1px 2px rgba(0,0,0,0.04); transition: border-color .15s, box-shadow .15s;
        }
        .pg-size:hover { border-color: #cbd5e1; }
        .pg-size:focus { outline: none; border-color: var(--product-primary); box-shadow: 0 0 0 3px rgba(22,101,52,0.12); }
        @keyframes pulse { 0%,100% { opacity: 1 } 50% { opacity: .45 } }
      `}</style>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <h2 style={{ margin: 0, fontSize: '19px', fontWeight: 800, color: 'var(--product-primary)', letterSpacing: -0.2 }}>상품 관리</h2>
          <span style={{ fontSize: 12, color: '#64748b', background: '#eef2f7', borderRadius: 999, padding: '3px 10px', fontWeight: 600 }}>
            {isError ? '조회 실패' : isLoading || isPlaceholderData ? '조회 중…' : `검색 ${totalElements.toLocaleString()}건`}
          </span>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          <button className="pw-detail-button" onClick={() => setContentRefreshIds(!isPlaceholderData && !isError ? [...selectedIds] : [])}>이미지·상세 갱신{selectedIds.length > 0 && !isPlaceholderData && !isError ? ` (${selectedIds.length})` : ''}</button>
          <button className="pw-detail-button" onClick={() => setRegistrationIds(!isPlaceholderData && !isError ? [...selectedIds] : [])}>미등록 마켓 등록·작업</button>
          <Dropdown trigger={['click']} menu={{ items: [{ key: 'price', label: '판매가 반영·작업' }, { key: 'stock', label: '판매용 수량 반영·작업' }, { key: 'fields', label: '이미지·상세·기본정보 반영·작업' }, { key: 'public', label: 'G마켓·옥션 공개가격 재조회' }],
            onClick: ({ key }) => { const ids = !isPlaceholderData && !isError ? [...selectedIds] : []; if (key === 'price') setPriceSyncIds(ids); else if (key === 'stock') setStockSyncIds(ids); else if (key === 'public') setPublicCheckIds(ids); else setFieldSyncIds(ids); } }}>
            <button className="pw-detail-button">마켓 반영·작업 ▾</button>
          </Dropdown>
          <button className="pw-detail-button" onClick={() => setInspectionIds(!isPlaceholderData && !isError ? [...selectedIds] : [])}>마켓 상태 확인·작업</button>
          {selectedIds.length > 0 && !isPlaceholderData && !isError && (
            <Dropdown trigger={['click']} menu={{ items: [{ key: 'values', label: '기본정보·이미지·상세 편집' }, { key: 'numeric', label: '가격·수량 계산' }],
              onClick: ({ key }) => { if (key === 'numeric') setNumericPreviewIds([...selectedIds]); else setBulkValuesIds([...selectedIds]); } }}>
              <button className="pw-detail-button">일괄 편집 ({selectedIds.length}) ▾</button>
            </Dropdown>
          )}
          {selectedIds.length > 0 && !isPlaceholderData && !isError && (
            <button onClick={() => setSourceRefreshIds([...selectedIds])} style={{ padding: '8px 16px', backgroundColor: 'var(--product-primary)', color: '#fff', border: 'none', borderRadius: '8px', cursor: 'pointer', fontSize: '13px', fontWeight: 700, boxShadow: '0 1px 2px rgba(0,0,0,0.06)' }}>
              소싱 가격·재고 비교 ({selectedIds.length})
            </button>
          )}
          {selectedIds.length > 0 && !isPlaceholderData && !isError && (
            <button onClick={handleBulkDelete} disabled={deleting} style={{ padding: '8px 16px', backgroundColor: deleting ? '#f1f5f9' : '#fee2e2', color: deleting ? '#94a3b8' : '#b91c1c', border: '1px solid ' + (deleting ? '#e2e8f0' : '#fecaca'), borderRadius: '8px', cursor: deleting ? 'default' : 'pointer', fontSize: '13px', fontWeight: 700 }}>
              {deleting ? `삭제 중… ${deleteProgress ?? ''}` : `선택 삭제 (${selectedIds.length})`}
            </button>
          )}
          <button onClick={() => refetch()} style={{ padding: '8px 16px', backgroundColor: '#fff', color: '#475569', border: '1px solid #e2e8f0', borderRadius: '8px', cursor: 'pointer', fontSize: '13px', fontWeight: 600, boxShadow: '0 1px 2px rgba(0,0,0,0.04)' }}>새로고침</button>
        </div>
      </div>

      <ProductFilterPanel categoryOptions={categoryOptions} brandOptions={brandOptions}
        brandsLoading={brandsLoading} brandsError={brandsError} onRetryBrands={() => { void refetchBrands(); }} onSearch={handleSearch} />
      <MarketPlusReadinessNotice />
      <MarketPlusObserverNotice />
      {filters.marketPlusIssue !== 'ALL' && <p className="pw-change-note" style={{ margin: '0 0 8px' }}>
        현재 연결에서 수집한 G마켓·옥션 전송 이슈 기준입니다. 미수집 상품의 정상 여부는 확인할 수 없으며, 같은 시각·종류의 성공과 실패는 결과 충돌로 표시합니다.
      </p>}

      {numericPreviewIds && <ProductNumericPreviewModal productIds={numericPreviewIds} onClose={() => setNumericPreviewIds(null)} />}
      {bulkValuesIds && <ProductBulkValuesModal productIds={bulkValuesIds} onClose={() => setBulkValuesIds(null)} onSaved={() => { void refetch(); }} />}
      {registrationIds && <ProductRegistrationJobs productIds={registrationIds} onClose={() => { setRegistrationIds(null); void refetch(); }} />}
      {priceSyncIds && <ProductPriceSync productIds={priceSyncIds} onClose={() => { setPriceSyncIds(null); void refetch(); }} />}
      {publicCheckIds && <ProductMarketPlusPublicRefresh productIds={publicCheckIds} onClose={() => { setPublicCheckIds(null); void refetch(); }} />}
      {fieldSyncIds && <ProductFieldSyncModal productIds={fieldSyncIds} onClose={() => { setFieldSyncIds(null); void refetch(); }} />}
      {stockSyncIds && <ProductStockSync productIds={stockSyncIds} onClose={() => { setStockSyncIds(null); void refetch(); }} />}
      {inspectionIds && <ProductInspectionJobs productIds={inspectionIds} onClose={() => { setInspectionIds(null); void refetch(); }} />}
      {sourceRefreshIds && <ProductSourceRefreshModal productIds={sourceRefreshIds} onClose={() => setSourceRefreshIds(null)} onSaved={() => { void refetch(); }} />}
      {contentRefreshIds && <ProductContentRefreshModal productIds={contentRefreshIds} onClose={() => setContentRefreshIds(null)} onSaved={() => { void refetch(); }} />}

      <div className="pw-result-controls">
        <span role="status" style={{ color: '#64748b', fontSize: 12 }}>{isFetching ? '검색 결과 갱신 중…' : isError ? '조회 실패' : `검색 결과 ${totalElements.toLocaleString()}개`}</span>
        <div>
        <Select aria-label="상품 정렬" value={sort} style={{ minWidth: 280 }}
          onChange={value => { setSort(value); setPage(0); setRowSelection({}); }} options={[
            { value: 'workspacePriority,asc', label: '조치 필요 우선 → 콘텐츠 적용 오래된 순' },
            { value: 'contentOldest,asc', label: '콘텐츠 적용 오래된 순' },
            { value: 'sbCode,asc', label: 'SB코드 오름차순' }, { value: 'sbCode,desc', label: 'SB코드 내림차순' },
            { value: 'brand,asc', label: '브랜드 오름차순' }, { value: 'productName,asc', label: '상품명 오름차순' },
            { value: 'priceInfo.salePrice,asc', label: '판매가 낮은 순' }, { value: 'priceInfo.salePrice,desc', label: '판매가 높은 순' },
          ]} />
        <Segmented aria-label="상품 행 간격" value={density} onChange={setDensity}
          options={[{ value: 'compact', label: '촘촘하게' }, { value: 'comfortable', label: '넓게 보기' }]} />
        </div>
      </div>
      {isError && <Alert type="error" showIcon message="상품 목록을 불러오지 못했습니다."
        description={isAxiosError(searchError) && searchError.response?.status === 400 && typeof searchError.response.data?.message === 'string'
          ? searchError.response.data.message : '잠시 후 다시 시도해 주세요.'}
        action={<button className="pw-detail-button" onClick={() => { void refetch(); }}>재시도</button>} />}

      <div style={{ flex: 1, minHeight: 180, position: 'relative', overflow: 'auto', paddingBottom: 4 }}>
        {(isLoading || isPlaceholderData) && (
          <div style={{ position: 'absolute', inset: 0, backgroundColor: 'rgba(255,255,255,0.6)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 10 }}>
            <div style={{ padding: '16px 32px', backgroundColor: 'white', borderRadius: '8px', boxShadow: '0 4px 12px rgba(0,0,0,0.15)', fontSize: '15px', fontWeight: 600, color: 'var(--product-primary)' }}>로딩 중...</div>
          </div>
        )}
        {!isLoading && !isError && rows.length === 0 && (
          <div style={{ padding: 48, textAlign: 'center', color: '#94a3b8' }}>조건에 맞는 상품이 없습니다.</div>
        )}
        <Table fluid minTableWidth={1620} style={{ width: '100%', tableLayout: 'fixed' }}>
          <TableHeader>
            {table.getHeaderGroups().map((hg) => (
              <TableRow key={hg.id}>
                {hg.headers.map((header) => (
                  <TableHead key={header.id} style={{ width: header.getSize(), backgroundColor: '#f9fafb', borderTop: '2px solid var(--product-primary)', fontWeight: 600, color: '#475569', letterSpacing: 0.2 }}>
                    {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
                  </TableHead>
                ))}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {table.getRowModel().rows.map((row) => (
              <TableRow key={row.id}>
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id} style={{ height: density === 'compact' ? 54 : 82, overflow: 'hidden', textOverflow: 'ellipsis', textAlign: cell.column.id === 'productInfo' ? 'left' : 'center' }}>
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <select className="pg-size" value={pageSize}
            onChange={(e) => { setPageSize(Number(e.target.value)); setPage(0); }}>
            {[20, 50, 100, 200].map((n) => <option key={n} value={n}>{n}개씩 보기</option>)}
          </select>
          <span style={{ fontSize: 12, color: '#94a3b8' }}>{isError ? '조회 실패' : isLoading || isPlaceholderData ? '조회 중…' : `검색 ${totalElements.toLocaleString()}건 · ${page + 1}/${pageCount} 페이지`}</span>
        </div>
        <Pagination
          disabled={isLoading || isPlaceholderData || isError}
          current={page + 1}
          pageSize={pageSize}
          total={totalElements}
          showSizeChanger={false}
          size="small"
          onChange={(p) => setPage(p - 1)}
        />
      </div>



      <ProductDetailModal
        productId={detailId}
        open={detailId != null}
        onClose={() => setDetailId(null)}
        onSaved={() => refetch()}
      />
      <AntModal title="상품 전송 이력" open={marketPlusHistoryId !== null} onCancel={() => setMarketPlusHistoryId(null)} footer={null} width={900} destroyOnHidden>
        {marketPlusHistoryId !== null && <ProductMarketPlusHistory key={marketPlusHistoryId} productId={marketPlusHistoryId} initialExpanded />}
      </AntModal>
    </div>
  );
}
