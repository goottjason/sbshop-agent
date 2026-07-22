import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  useReactTable, getCoreRowModel, flexRender, createColumnHelper,
} from '@tanstack/react-table';
import { toast } from 'react-toastify';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '../../components/ui/Table';
import { productApi, type ProductList } from '../../api/productApi';
import { renderMarketBadges, MARKET_FILTER_OPTIONS } from './productGridShared';
import { ProductFilterPanel, type ProductFilters } from './ProductFilterPanel';

const columnHelper = createColumnHelper<ProductList>();
const DEFAULT_FILTERS: ProductFilters = {
  keyword: '', categories: [], markets: [], vendors: [], stockStatuses: [], inStockOnly: false,
};

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

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['products', keyword],
    queryFn: async () => {
      const res = await productApi.fetchProducts(0, 500, keyword || undefined);
      return (res.data.content ?? []) as ProductList[];
    },
  });

  const allRows = useMemo(() => data ?? [], [data]);
  const rows = useMemo(() => applyClientFilters(allRows, filters), [allRows, filters]);
  const categoryOptions = useMemo(
    () => Array.from(new Set(allRows.map((r) => r.category).filter((c): c is string => !!c))).sort(),
    [allRows],
  );

  const handleSearch = (f: ProductFilters) => { setKeyword(f.keyword); setFilters(f); };

  const columns = useMemo(() => [
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
        <div style={{ textAlign: 'left', cursor: 'pointer', color: 'var(--product-primary)' }}
          title="상세 보기">
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
      id: 'priceStock', header: '판매가·상태', size: 160,
      cell: (info) => {
        const r = info.row.original;
        const soldOut = r.stockStatus === 'OUT_OF_STOCK';
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2, alignItems: 'flex-end' }}>
            <span style={{ fontWeight: 600, color: '#0f172a' }}>{r.salePrice ? `${r.salePrice.toLocaleString()}원` : '-'}</span>
            <span style={{ fontSize: 11, color: soldOut ? '#dc2626' : '#16a34a' }}>{soldOut ? '품절' : '판매중'}</span>
          </div>
        );
      },
    }),
    columnHelper.display({
      id: 'stock', header: '재고', size: 80,
      cell: ({ row }) => <span style={{ color: '#334155' }}>{row.original.stock ?? '-'}</span>,
    }),
    columnHelper.display({
      id: 'markets', header: '마켓', size: 220,
      cell: ({ row }) => renderMarketBadges(row.original.marketRegistrations),
    }),
  ], []);

  const table = useReactTable({ data: rows, columns, getCoreRowModel: getCoreRowModel() });

  return (
    <div className="product-theme" style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: '16px 24px', background: '#f8f9fa' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
          <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 700, color: 'var(--product-primary)' }}>상품 관리</h2>
          <span style={{ fontSize: 13, color: '#94a3b8' }}>표시 {rows.length.toLocaleString()} / 로드 {allRows.length.toLocaleString()}개</span>
        </div>
        <button onClick={() => refetch()} style={{ padding: '8px 16px', backgroundColor: '#fff', color: '#333', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>새로고침</button>
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
    </div>
  );
}

// 조회 실패 토스트는 axios 인터셉터/상위에서 처리. 필요 시 useQuery onError 확장.
void toast;
