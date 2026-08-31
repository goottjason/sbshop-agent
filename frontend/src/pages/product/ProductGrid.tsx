import { useEffect, useMemo, useState } from 'react';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import {
  useReactTable, getCoreRowModel, flexRender, createColumnHelper,
  type RowSelectionState,
} from '@tanstack/react-table';
import { App as AntApp, Modal as AntModal, Pagination, InputNumber } from 'antd';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '../../components/ui/Table';
import { productApi, type ProductList, type ProductQuery } from '../../api/productApi';
import { batchApi } from '../../api/batchApi';
import { MARKET_FILTER_OPTIONS, VENDOR_OPTIONS } from './productGridShared';
import { MarketBadgeCell } from './MarketBadgeCell';
import { ProductFilterPanel, type ProductFilters } from './ProductFilterPanel';
import { ProductDetailModal } from './ProductDetailModal';
import { bulkDeleteProducts } from './productBulkApi';
import { notify } from '../../utils/notify';

const columnHelper = createColumnHelper<ProductList>();
const DEFAULT_FILTERS: ProductFilters = {
  keyword: '', categories: [], includeUncategorized: false, markets: [], vendors: [], stockStatuses: [], inStockOnly: false, sourceGone: 'ALL',
};

function stockBadge(soldOut: boolean): React.CSSProperties {
  const c = soldOut ? { bg: '#ffebee', text: '#c62828' } : { bg: '#e8f5e9', text: '#2e7d32' };
  return { fontSize: 11, fontWeight: 600, padding: '2px 10px', borderRadius: 4, background: c.bg, color: c.text };
}

function toQuery(page: number, size: number, keyword: string, f: ProductFilters): ProductQuery {
  const q: ProductQuery = { page, size };
  if (keyword) q.keyword = keyword;
  if (f.categories.length > 0) q.categories = f.categories;
  if (f.includeUncategorized) q.includeUncategorized = true;
  if (f.vendors.length > 0 && f.vendors.length < VENDOR_OPTIONS.length) q.vendors = f.vendors;
  if (f.stockStatuses.length === 1) q.stockStatuses = f.stockStatuses;
  if (f.markets.length > 0 && f.markets.length < MARKET_FILTER_OPTIONS.length) q.markets = f.markets;
  if (f.inStockOnly) q.inStockOnly = true;
  if (f.sourceGone && f.sourceGone !== 'ALL') q.sourceGone = f.sourceGone;
  return q;
}

export default function ProductGrid() {
  const { modal } = AntApp.useApp();
  const [filters, setFilters] = useState<ProductFilters>(DEFAULT_FILTERS);
  const [keyword, setKeyword] = useState('');
  const [detailId, setDetailId] = useState<number | null>(null);
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkSubmitting, setBulkSubmitting] = useState(false);
  const [marginRate, setMarginRate] = useState<number | null>(15);
  const [couponRate, setCouponRate] = useState<number | null>(20);
  const [minMarginPrice, setMinMarginPrice] = useState<number | null>(5000);

  const query = useMemo(() => toQuery(page, pageSize, keyword, filters), [page, pageSize, keyword, filters]);

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['products', query],
    queryFn: async () => (await productApi.fetchProducts(query)).data,
    placeholderData: keepPreviousData,
  });

  const { data: categoryOptions = [] } = useQuery({
    queryKey: ['product-categories'],
    queryFn: async () => (await productApi.fetchCategories()).data,
  });

  const rows = useMemo(() => data?.content ?? [], [data]);
  const totalElements = data?.totalElements ?? 0;
  const pageCount = Math.max(1, data?.totalPages ?? 1);

  useEffect(() => {
    if (data && page > 0 && page >= pageCount) setPage(pageCount - 1);
  }, [data, page, pageCount]);

  const handleSearch = (f: ProductFilters) => { setKeyword(f.keyword); setFilters(f); setPage(0); };

  const columns = useMemo(() => [
    columnHelper.display({
      id: 'select', header: ({ table }) => (
        <input type="checkbox" checked={table.getIsAllRowsSelected()}
          ref={(el) => { if (el) el.indeterminate = table.getIsSomeRowsSelected(); }}
          onChange={table.getToggleAllRowsSelectedHandler()}
          style={{ width: 16, height: 16, accentColor: 'var(--product-primary)', cursor: 'pointer' }} />
      ), size: 40,
      cell: ({ row }) => (
        <input type="checkbox" checked={row.getIsSelected()} onChange={row.getToggleSelectedHandler()}
          style={{ width: 16, height: 16, accentColor: 'var(--product-primary)', cursor: 'pointer' }} />
      ),
    }),
    columnHelper.accessor('repImageUrl', {
      id: 'image', header: '이미지', size: 64,
      cell: (info) => info.getValue()
        ? <img src={info.getValue()} style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 8, border: '1px solid #e5e7eb' }} />
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
      cell: ({ row }) => (
        <div onClick={() => setDetailId(row.original.id)}
          style={{ textAlign: 'left', cursor: 'pointer', minWidth: 0 }} title={row.original.productName}>
          <div style={{ fontWeight: 600, color: '#1e293b', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{row.original.productName}</div>
          <div style={{ fontSize: 11, color: '#94a3b8', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{row.original.originalName || ' '}</div>
        </div>
      ),
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
          if (!r.lastCrawlError) return <span style={{ color: '#94a3b8' }}>정상</span>;
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
      id: 'priceStock', header: '판매가·재고', size: 150,
      cell: (info) => {
        const r = info.row.original;
        const soldOut = r.stockStatus === 'OUT_OF_STOCK';
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'center' }}>
            <span style={{ fontWeight: 700, color: '#0f172a' }}>{r.salePrice ? `${r.salePrice.toLocaleString()}원` : '-'}</span>
            <span style={stockBadge(soldOut)}>{soldOut ? '품절' : '있음'}</span>
          </div>
        );
      },
    }),
    columnHelper.display({
      id: 'markets', header: '마켓', size: 340,
      cell: ({ row }) => <MarketBadgeCell product={row.original} onPublished={refetch} />,
    }),
  ], [refetch]);

  const table = useReactTable({
    data: rows,
    columns,
    state: { rowSelection },
    enableRowSelection: true,
    onRowSelectionChange: setRowSelection,
    getRowId: (r) => String(r.id),
    getCoreRowModel: getCoreRowModel(),
  });

  const selectedIds = Object.keys(rowSelection).filter((k) => rowSelection[k]).map(Number);

  const handleBulkDelete = () => {
    if (selectedIds.length === 0) { notify.warning('삭제할 상품을 선택하세요.'); return; }
    modal.confirm({
      title: `상품 ${selectedIds.length}개 삭제`,
      content: '선택한 상품을 삭제합니다. 되돌릴 수 없습니다. 진행할까요?',
      okText: '삭제', okType: 'danger', cancelText: '취소',
      onOk: async () => {
        const { deleted, failed } = await bulkDeleteProducts(selectedIds);
        if (failed.length === 0) notify.success(`${deleted}개 삭제 완료`);
        else notify.warning(`${deleted}개 삭제, ${failed.length}개 실패`);
        setRowSelection({});
        refetch();
      },
    });
  };

  const handleBulkUpdate = async () => {
    if (selectedIds.length === 0) { notify.warning('업데이트할 상품을 선택하세요.'); return; }
    setBulkSubmitting(true);
    try {
      const res = await batchApi.crawlAndUpdate(selectedIds, marginRate ?? 15, couponRate ?? 20, minMarginPrice ?? 5000);
      const batchId = (res.data as Record<string, string> | undefined)?.batchId;
      notify.success(`가격/재고 업데이트 배치 시작 (${selectedIds.length}건${batchId ? ` · ${batchId}` : ''}). 진행 현황에서 결과를 확인하세요.`);
      setBulkOpen(false);
      setRowSelection({});
    } catch {
      notify.error('배치 시작 실패 — 소싱 URL이 없는 상품이 포함됐을 수 있습니다.');
    } finally {
      setBulkSubmitting(false);
    }
  };

  return (
    <div className="product-theme" style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: '16px 24px', background: '#f8f9fa' }}>
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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <h2 style={{ margin: 0, fontSize: '19px', fontWeight: 800, color: 'var(--product-primary)', letterSpacing: -0.2 }}>상품 관리</h2>
          <span style={{ fontSize: 12, color: '#64748b', background: '#eef2f7', borderRadius: 999, padding: '3px 10px', fontWeight: 600 }}>
            전체 {totalElements.toLocaleString()}건
          </span>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {selectedIds.length > 0 && (
            <button onClick={() => setBulkOpen(true)} style={{ padding: '8px 16px', backgroundColor: 'var(--product-primary)', color: '#fff', border: 'none', borderRadius: '8px', cursor: 'pointer', fontSize: '13px', fontWeight: 700, boxShadow: '0 1px 2px rgba(0,0,0,0.06)' }}>
              선택 가격/재고 업데이트 ({selectedIds.length})
            </button>
          )}
          {selectedIds.length > 0 && (
            <button onClick={handleBulkDelete} style={{ padding: '8px 16px', backgroundColor: '#fee2e2', color: '#b91c1c', border: '1px solid #fecaca', borderRadius: '8px', cursor: 'pointer', fontSize: '13px', fontWeight: 700 }}>
              선택 삭제 ({selectedIds.length})
            </button>
          )}
          <button onClick={() => refetch()} style={{ padding: '8px 16px', backgroundColor: '#fff', color: '#475569', border: '1px solid #e2e8f0', borderRadius: '8px', cursor: 'pointer', fontSize: '13px', fontWeight: 600, boxShadow: '0 1px 2px rgba(0,0,0,0.04)' }}>새로고침</button>
        </div>
      </div>

      <ProductFilterPanel categoryOptions={categoryOptions} onSearch={handleSearch} />

      <div style={{ flex: 1, position: 'relative', overflow: 'auto', paddingBottom: 4 }}>
        {isLoading && (
          <div style={{ position: 'absolute', inset: 0, backgroundColor: 'rgba(255,255,255,0.6)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 10 }}>
            <div style={{ padding: '16px 32px', backgroundColor: 'white', borderRadius: '8px', boxShadow: '0 4px 12px rgba(0,0,0,0.15)', fontSize: '15px', fontWeight: 600, color: 'var(--product-primary)' }}>로딩 중...</div>
          </div>
        )}
        {!isLoading && rows.length === 0 && (
          <div style={{ padding: 48, textAlign: 'center', color: '#94a3b8' }}>조건에 맞는 상품이 없습니다.</div>
        )}
        <Table fluid minTableWidth={1080} style={{ width: '100%', tableLayout: 'fixed' }}>
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
                  <TableCell key={cell.id} style={{ height: 56, overflow: 'hidden', textOverflow: 'ellipsis', textAlign: cell.column.id === 'productInfo' ? 'left' : 'center' }}>
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
          <span style={{ fontSize: 12, color: '#94a3b8' }}>전체 {totalElements.toLocaleString()}건 · {page + 1}/{pageCount} 페이지</span>
        </div>
        <Pagination
          current={page + 1}
          pageSize={pageSize}
          total={totalElements}
          showSizeChanger={false}
          size="small"
          onChange={(p) => setPage(p - 1)}
        />
      </div>

      <AntModal
        title={`선택 상품 가격/재고 업데이트 (${selectedIds.length}개)`}
        open={bulkOpen}
        onCancel={() => setBulkOpen(false)}
        onOk={handleBulkUpdate}
        okText="적용"
        cancelText="취소"
        confirmLoading={bulkSubmitting}
        okButtonProps={{ style: { background: 'var(--product-primary)', borderColor: 'var(--product-primary)' } }}
      >
        <div style={{ fontSize: 12, color: '#92400e', background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 6, padding: '8px 10px', marginBottom: 14 }}>
          선택 상품을 소싱처에서 크롤해 마켓별 실수수료로 판매가를 재산정하고 연동 마켓에 반영합니다. 비동기 배치로 실행되며, 소싱 URL이 없는 상품은 실패할 수 있습니다.
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <span style={{ color: '#374151' }}>마진율 (%)</span>
            <InputNumber min={0} value={marginRate} onChange={setMarginRate} style={{ width: 160 }} />
          </label>
          <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <span style={{ color: '#374151' }}>쿠폰율 (구매시 할인율, %)</span>
            <InputNumber min={0} value={couponRate} onChange={setCouponRate} style={{ width: 160 }} />
          </label>
          <label style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <span style={{ color: '#374151' }}>최소 마진가 (원)</span>
            <InputNumber min={0} step={100} value={minMarginPrice} onChange={setMinMarginPrice} style={{ width: 160 }} />
          </label>
        </div>
      </AntModal>

      <ProductDetailModal
        productId={detailId}
        open={detailId != null}
        onClose={() => setDetailId(null)}
        onSaved={() => refetch()}
      />
    </div>
  );
}
