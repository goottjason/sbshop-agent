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
import { MARKET_FILTER_OPTIONS } from './productGridShared';
import { MarketBadgeCell } from './MarketBadgeCell';
import { ProductFilterPanel, type ProductFilters } from './ProductFilterPanel';
import { ProductDetailModal } from './ProductDetailModal';
import { bulkDeleteProducts } from './productMockApi';

const columnHelper = createColumnHelper<ProductList>();
const DEFAULT_FILTERS: ProductFilters = {
  keyword: '', categories: [], markets: [], vendors: [], stockStatuses: [], inStockOnly: false,
};

// 재고 배지(읽기전용): 통합 주문 관리와 동일한 파스텔 톤. 있음(연녹)·품절(연적) 중 하나만 표시.
function stockBadge(soldOut: boolean): React.CSSProperties {
  const c = soldOut ? { bg: '#ffebee', text: '#c62828' } : { bg: '#e8f5e9', text: '#2e7d32' };
  return { fontSize: 11, fontWeight: 600, padding: '2px 10px', borderRadius: 4, background: c.bg, color: c.text };
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
    // 값이 객체로 바뀌었지만 "키 존재 = 등록"이라는 판정은 그대로다.
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
      cell: ({ row }) => <MarketBadgeCell product={row.original} />,
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
      `}</style>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <h2 style={{ margin: 0, fontSize: '19px', fontWeight: 800, color: 'var(--product-primary)', letterSpacing: -0.2 }}>상품 관리</h2>
          <span style={{ fontSize: 12, color: '#64748b', background: '#eef2f7', borderRadius: 999, padding: '3px 10px', fontWeight: 600 }}>
            표시 {rows.length.toLocaleString()} · 로드 {allRows.length.toLocaleString()}
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

      <div style={{ fontSize: 11.5, color: '#94a3b8', display: 'flex', alignItems: 'center', gap: 6, padding: '0 2px', marginBottom: 10 }}>
        <span style={{ display: 'inline-flex', width: 14, height: 14, borderRadius: 999, background: '#e2e8f0', color: '#64748b', fontSize: 10, fontWeight: 700, alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>i</span>
        고급필터는 로드된 500건 대상 클라이언트 필터입니다. 카테고리는 목록 API 확장 후 활성화됩니다.
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
        {/* 좌: 페이지 크기 선택(페이지네이션과 분리) + 카운트 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <select className="pg-size" value={pageSize}
            onChange={(e) => { setPageSize(Number(e.target.value)); setPage(0); }}>
            {[20, 50, 100, 200].map((n) => <option key={n} value={n}>{n}개씩 보기</option>)}
          </select>
          <span style={{ fontSize: 12, color: '#94a3b8' }}>표시 {rows.length.toLocaleString()}건 · {safePage + 1}/{pageCount} 페이지</span>
        </div>
        {/* 우: 페이지 이동(숫자만 — 크기 변경·바로가기 제거) */}
        <Pagination
          current={safePage + 1}
          pageSize={pageSize}
          total={rows.length}
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
