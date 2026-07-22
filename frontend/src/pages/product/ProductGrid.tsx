import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  useReactTable, getCoreRowModel, flexRender, createColumnHelper,
  type RowSelectionState,
} from '@tanstack/react-table';
import { toast } from 'react-toastify';
import { Modal as AntModal, Pagination, InputNumber } from 'antd';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '../../components/ui/Table';
import { productApi, type ProductList } from '../../api/productApi';
import { batchApi } from '../../api/batchApi';
import { renderMarketBadges, MARKET_FILTER_OPTIONS } from './productGridShared';
import { ProductFilterPanel, type ProductFilters } from './ProductFilterPanel';
import { ProductDetailModal } from './ProductDetailModal';
import { bulkDeleteProducts } from './productMockApi';

const columnHelper = createColumnHelper<ProductList>();
const DEFAULT_FILTERS: ProductFilters = {
  keyword: '', categories: [], markets: [], vendors: [], stockStatuses: [], inStockOnly: false,
};

// 판매상태 배지(읽기전용): 활성만 채색(판매중=그린, 품절=레드), 비활성=옅은 아웃라인.
function statusPill(active: boolean, kind: 'in' | 'out'): React.CSSProperties {
  const color = kind === 'in' ? '#16a34a' : '#dc2626';
  return {
    fontSize: 11, fontWeight: 700, padding: '1px 8px', borderRadius: 999, border: `1px solid ${color}`,
    color: active ? '#fff' : color, background: active ? color : '#fff', opacity: active ? 1 : 0.45,
  };
}

// 로드된 500건에 고급필터를 적용(서버는 keyword만). 카테고리/마켓/소싱처/재고상태·재고유무.
export function applyClientFilters(rows: ProductList[], f: ProductFilters): ProductList[] {
  return rows.filter((r) => {
    if (f.categories.length > 0 && !(r.category && f.categories.includes(r.category))) return false;
    if (f.vendors.length > 0 && !(r.vendor && f.vendors.includes(r.vendor))) return false;
    if (f.stockStatuses.length > 0) {
      const st = r.stockStatus ?? (r.stock > 0 ? 'IN_STOCK' : 'OUT_OF_STOCK');
      if (!f.stockStatuses.includes(st)) return false;
    }
    if (f.inStockOnly && !(r.stock > 0)) return false;
    // 마켓 등록상태: 선택 마켓 중 하나라도 등록돼 있으면 통과. 전체선택이면 통과.
    if (f.markets.length > 0 && f.markets.length < MARKET_FILTER_OPTIONS.length) {
      const regs = r.marketRegistrations ?? {};
      const hit = f.markets.some((m) => regs[m] !== undefined);
      if (!hit) return false;
    }
    return true;
  });
}

export default function ProductGrid() {
  const [filters, setFilters] = useState<ProductFilters>(DEFAULT_FILTERS);
  const [keyword, setKeyword] = useState('');
  const [detailId, setDetailId] = useState<number | null>(null);
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  // 선택 상품 가격/재고 일괄 업데이트 모달(배치 crawl-and-update 3파라미터)
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkSubmitting, setBulkSubmitting] = useState(false);
  const [marginRate, setMarginRate] = useState<number | null>(15);
  const [couponRate, setCouponRate] = useState<number | null>(20);
  const [minMarginPrice, setMinMarginPrice] = useState<number | null>(5000);

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['products', keyword],
    queryFn: async () => {
      const res = await productApi.fetchProducts(0, 500, keyword || undefined);
      return (res.data.content ?? []) as ProductList[];
    },
  });

  const allRows = useMemo(() => data ?? [], [data]);
  const rows = useMemo(() => applyClientFilters(allRows, filters), [allRows, filters]);
  // 필터링된 결과에 클라이언트 페이지네이션 적용. 현재 페이지가 범위를 벗어나면 마지막 페이지로 보정.
  const pageCount = Math.max(1, Math.ceil(rows.length / pageSize));
  const safePage = Math.min(page, pageCount - 1);
  const pageRows = useMemo(
    () => rows.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [rows, safePage, pageSize],
  );
  const categoryOptions = useMemo(
    () => Array.from(new Set(allRows.map((r) => r.category).filter((c): c is string => !!c))).sort(),
    [allRows],
  );

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
          style={{ textAlign: 'left', cursor: 'pointer', color: 'var(--product-primary)' }} title="상세 보기">
          <div style={{ fontWeight: 600 }}>{row.original.productName}</div>
          <div style={{ fontSize: 11, color: '#94a3b8' }}>{row.original.originalName || ' '}</div>
        </div>
      ),
    }),
    columnHelper.accessor('category', { id: 'category', header: '카테고리', size: 100,
      cell: (info) => <span style={{ color: '#64748b' }}>{info.getValue() || '-'}</span> }),
    columnHelper.accessor('vendor', { id: 'vendor', header: '소싱처', size: 80,
      cell: (info) => <span style={{ color: '#64748b' }}>{info.getValue() || '-'}</span> }),
    columnHelper.accessor('salePrice', {
      id: 'priceStock', header: '판매가·재고', size: 150,
      cell: (info) => {
        const r = info.row.original;
        const soldOut = r.stockStatus === 'OUT_OF_STOCK';
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'center' }}>
            <span style={{ fontWeight: 700, color: '#0f172a' }}>{r.salePrice ? `${r.salePrice.toLocaleString()}원` : '-'}</span>
            <div style={{ display: 'flex', gap: 4 }}>
              <span style={statusPill(!soldOut, 'in')}>판매중</span>
              <span style={statusPill(soldOut, 'out')}>품절</span>
            </div>
          </div>
        );
      },
    }),
    columnHelper.display({
      id: 'markets', header: '마켓', size: 220,
      cell: ({ row }) => renderMarketBadges(row.original.marketRegistrations),
    }),
  ], []);

  const table = useReactTable({
    data: pageRows,
    columns,
    state: { rowSelection },
    enableRowSelection: true,
    onRowSelectionChange: setRowSelection,
    getRowId: (r) => String(r.id),
    getCoreRowModel: getCoreRowModel(),
  });

  const selectedIds = Object.keys(rowSelection).filter((k) => rowSelection[k]).map(Number);

  const handleBulkDelete = () => {
    if (selectedIds.length === 0) { toast.warning('삭제할 상품을 선택하세요.'); return; }
    AntModal.confirm({
      title: `상품 ${selectedIds.length}개 삭제`,
      content: '선택한 상품을 삭제합니다. 되돌릴 수 없습니다. 진행할까요?',
      okText: '삭제', okType: 'danger', cancelText: '취소',
      onOk: async () => {
        const { deleted, failed } = await bulkDeleteProducts(selectedIds);
        if (failed.length === 0) toast.success(`${deleted}개 삭제 완료`);
        else toast.warn(`${deleted}개 삭제, ${failed.length}개 실패`);
        setRowSelection({});
        refetch();
      },
    });
  };

  // 선택 상품 크롤 기반 가격/재고 일괄 업데이트(배치). 마켓별 실수수료로 판매가 재산정 후 연동 마켓 반영.
  const handleBulkUpdate = async () => {
    if (selectedIds.length === 0) { toast.warning('업데이트할 상품을 선택하세요.'); return; }
    setBulkSubmitting(true);
    try {
      const res = await batchApi.crawlAndUpdate(selectedIds, marginRate ?? 15, couponRate ?? 20, minMarginPrice ?? 5000);
      const batchId = (res.data as Record<string, string> | undefined)?.batchId;
      toast.success(`가격/재고 업데이트 배치 시작 (${selectedIds.length}건${batchId ? ` · ${batchId}` : ''}). 진행 현황에서 결과를 확인하세요.`);
      setBulkOpen(false);
      setRowSelection({});
    } catch {
      toast.error('배치 시작 실패 — 소싱 URL이 없는 상품이 포함됐을 수 있습니다.');
    } finally {
      setBulkSubmitting(false);
    }
  };

  return (
    <div className="product-theme" style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: '16px 24px', background: '#f8f9fa' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
          <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 700, color: 'var(--product-primary)' }}>상품 관리</h2>
          <span style={{ fontSize: 13, color: '#94a3b8' }}>표시 {rows.length.toLocaleString()} / 로드 {allRows.length.toLocaleString()}개</span>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {selectedIds.length > 0 && (
            <button onClick={() => setBulkOpen(true)} style={{ padding: '8px 16px', backgroundColor: 'var(--product-primary)', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '13px', fontWeight: 700 }}>
              선택 가격/재고 업데이트 ({selectedIds.length})
            </button>
          )}
          {selectedIds.length > 0 && (
            <button onClick={handleBulkDelete} style={{ padding: '8px 16px', backgroundColor: '#fee2e2', color: '#b91c1c', border: '1px solid #fecaca', borderRadius: '4px', cursor: 'pointer', fontSize: '13px', fontWeight: 700 }}>
              선택 삭제 ({selectedIds.length})
            </button>
          )}
          <button onClick={() => refetch()} style={{ padding: '8px 16px', backgroundColor: '#fff', color: '#333', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>새로고침</button>
        </div>
      </div>

      <div style={{ fontSize: 12, color: '#92400e', background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 6, padding: '6px 10px', marginBottom: 8 }}>
        고급필터(카테고리·마켓·소싱처·재고상태)는 현재 로드된 500건 대상 클라이언트 필터입니다. 카테고리는 목록 API 확장 후 활성화됩니다. (다음 세션 백엔드 구현)
      </div>

      <ProductFilterPanel categoryOptions={categoryOptions} onSearch={handleSearch} />

      <div style={{ flex: 1, backgroundColor: 'white', position: 'relative', overflow: 'auto' }}>
        {isLoading && (
          <div style={{ position: 'absolute', inset: 0, backgroundColor: 'rgba(255,255,255,0.6)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 10 }}>
            <div style={{ padding: '16px 32px', backgroundColor: 'white', borderRadius: '8px', boxShadow: '0 4px 12px rgba(0,0,0,0.15)', fontSize: '15px', fontWeight: 600, color: 'var(--product-primary)' }}>로딩 중...</div>
          </div>
        )}
        {!isLoading && rows.length === 0 && (
          <div style={{ padding: 48, textAlign: 'center', color: '#94a3b8' }}>조건에 맞는 상품이 없습니다.</div>
        )}
        <Table style={{ width: '100%' }}>
          <TableHeader>
            {table.getHeaderGroups().map((hg) => (
              <TableRow key={hg.id}>
                {hg.headers.map((header) => (
                  <TableHead key={header.id} style={{ width: header.getSize(), backgroundColor: '#f9fafb', borderTop: '2px solid var(--product-primary)', borderRight: '1px solid #e5e7eb' }}>
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
                  <TableCell key={cell.id} style={{ borderRight: '1px solid #e5e7eb', height: 56, textAlign: ['productInfo'].includes(cell.column.id) ? 'left' : 'center' }}>
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <div style={{ marginTop: 10, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ fontSize: 12, color: '#94a3b8' }}>표시 {rows.length.toLocaleString()}건 · {safePage + 1}/{pageCount} 페이지</span>
        <Pagination
          current={safePage + 1}
          pageSize={pageSize}
          total={rows.length}
          showSizeChanger
          pageSizeOptions={[20, 50, 100, 200]}
          showQuickJumper
          size="small"
          onChange={(p, s) => { setPage(p - 1); setPageSize(s); }}
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
