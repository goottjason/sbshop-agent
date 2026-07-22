# 상품 관리 프론트 리팩토링 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 상품 관리 화면(`ProductPage.tsx`, AG Grid)을 통합 주문관리(`OrderGrid.tsx`)와 동일한 UX(상단 검색 필터 + TanStack 데이터 그리드)로 재구현하고, 포레스트 그린 테마·인라인 세트 편집·모달 상세편집·체크박스 일괄삭제를 갖춘다.

**Architecture:** `frontend/src/pages/product/` 폴더에 초점별 파일 분리(main grid / filter panel / price-stock cell / detail modal / shared / mock api). AG Grid 제거, TanStack Table v8 + 기존 공통 `components/ui/Table.tsx` 사용. 서버 상태는 React Query, 인라인 저장은 실제 `price-stock` 엔드포인트, 미구현 백엔드(필드 PATCH·고급필터 서버검색)는 `productMockApi.ts`로 모킹.

**Tech Stack:** React 19, TypeScript ~6.0, Vite 8, @tanstack/react-table 8.21, @tanstack/react-query 5.101, antd 5.22, react-toastify 11, axios.

## Global Constraints

- **테스트 러너 없음.** 이 프론트엔드에는 vitest/jest가 없다. 각 태스크의 검증 게이트는 다음 3종 + 수동 관찰이다. 새 테스트 러너를 추가하지 말 것(범위 밖).
  - 타입: `cd frontend && npx tsc -p tsconfig.app.json --noEmit` → 에러 0
  - 빌드: `cd frontend && npm run build` → 성공
  - 린트: `cd frontend && npm run lint` → 신규 에러 0
  - 수동: `cd frontend && npm run dev` 후 브라우저에서 각 태스크 말미 "수동 확인" 관찰. dev 서버는 vite proxy로 `localhost:8080` 백엔드에 연결됨.
- **프라이머리 컬러:** 포레스트 그린 `#166534`. 전역 `--primary-color`(남색 `#334155`)를 **변경 금지**. 상품 화면 전용 스코프 변수(`--product-primary` 등)만 사용.
- **커밋 규약:** 커밋 메시지 말미에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **push 규약:** Stage 종료 태스크만 `git push origin main`. push 전 운영 배치가 도는 중이 아닌지 확인(배포=api 재시작=배치 중단). 배치 상태는 사용자에게 물어 확인.
- **DB/스키마:** 백엔드 변경은 이번 세션 범위 밖. 미구현 엔드포인트는 프론트 모킹으로만 채운다.
- **모킹 표식:** 모든 모킹 함수에 `// MOCK: 다음 세션 백엔드 구현` 주석 + 최초 호출 시 `console.warn`.

## 파일 구조

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `frontend/src/index.css` | `.product-theme` 스코프 CSS 변수 추가 | 수정 |
| `frontend/src/pages/product/productGridShared.tsx` | 공유 타입·상수·헬퍼(SaveStatus, statusBorder, inputStyle, 마켓/벤더/재고 상수, renderMarketBadges) | 신규 |
| `frontend/src/pages/product/productMockApi.ts` | 미구현 백엔드 모킹(updateProductFields, bulkDeleteProducts) | 신규 |
| `frontend/src/pages/product/ProductFilterPanel.tsx` | 상단 3행 필터(키워드·카테고리·마켓·소싱처·재고상태·재고유무) | 신규 |
| `frontend/src/pages/product/PriceStockEditCell.tsx` | 판매가+판매상태 세트 인라인 편집([전송] 버튼) | 신규 |
| `frontend/src/pages/product/ProductDetailModal.tsx` | 상세 필드 편집 폼 + 이미지 섹션 | 신규 |
| `frontend/src/pages/product/ProductGrid.tsx` | 메인: React Query + TanStack Table + 필터·툴바·일괄삭제 조립 | 신규 |
| `frontend/src/api/productApi.ts` | `ProductList`에 `category?` 추가, `updateProductFields` 타입 export | 수정 |
| `frontend/src/App.tsx` | `products` 라우트를 새 `ProductGrid`로 교체 | 수정 |
| `frontend/src/pages/ProductPage.tsx` | 최종 삭제(대체 완료 후) | 삭제 |

---

## Stage 1 — 그린 테마 + 필터 + 조회 전용 그리드 (Task 1~3, 끝에 push)

### Task 1: 테마 변수 + 공유 모듈

**Files:**
- Modify: `frontend/src/index.css` (`:root` 블록 뒤에 `.product-theme` 추가)
- Create: `frontend/src/pages/product/productGridShared.tsx`
- Modify: `frontend/src/api/productApi.ts:3-23` (`ProductList`에 `category?` 추가)

**Interfaces:**
- Produces:
  - CSS 클래스 `.product-theme` — 자식에게 `--product-primary:#166534`, `--product-hover:#14532d`, `--product-light:#f0fdf4`, `--product-badge-bg:#dcfce7` 제공.
  - `type SaveStatus = 'idle'|'dirty'|'saving'|'saved'|'error'`
  - `function statusBorder(s: SaveStatus): string`
  - `const inputStyle` (셀 input 공통 스타일 객체)
  - `const MARKET_FILTER_OPTIONS: {id:string;label:string}[]` (6마켓)
  - `const VENDOR_OPTIONS: string[]`
  - `const STOCK_STATUS_OPTIONS: {id:'IN_STOCK'|'OUT_OF_STOCK';label:string}[]`
  - `function renderMarketBadges(links?: Record<string,string>): React.ReactNode`
  - `ProductList.category?: string`

- [ ] **Step 1: `ProductList`에 category 필드 추가**

`frontend/src/api/productApi.ts`의 `ProductList` 인터페이스(3~23행)에서 `stockStatus` 앞에 아래 한 줄을 추가한다.

```ts
  // 상품 카테고리(ProductCategory enum). 목록 API가 아직 미포함 → 값 없으면 '-'. 다음 세션 백엔드 확장.
  category?: string;
  // SP-B: 백엔드 StockStatus 열거값. 가격/재고 편집 시 현재 판매상태 시드에 사용.
  stockStatus?: 'IN_STOCK' | 'OUT_OF_STOCK';
```

(기존 `stockStatus` 줄은 그대로 두고 그 앞에 `category?` 두 줄만 삽입)

- [ ] **Step 2: `.product-theme` CSS 변수 추가**

`frontend/src/index.css`의 `:root { ... }` 블록(3~16행) **바로 아래**에 다음을 추가한다.

```css
/* 상품 관리 전용 테마 스코프 — 전역 --primary-color(남색)를 건드리지 않고 포레스트 그린으로 격리 */
.product-theme {
  --product-primary: #166534;
  --product-hover: #14532d;
  --product-light: #f0fdf4;
  --product-badge-bg: #dcfce7;
}
.product-theme ::selection {
  background: #bbf7d0;
}
```

- [ ] **Step 3: 공유 모듈 작성**

`frontend/src/pages/product/productGridShared.tsx` 신규 작성.

```tsx
import type { CSSProperties } from 'react';

// ─── 인라인 편집 공통 저장상태 ───
export type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';
export function statusBorder(status: SaveStatus): string {
  switch (status) {
    case 'dirty': return '#f59e0b';
    case 'saving': return '#3b82f6';
    case 'saved': return '#22c55e';
    case 'error': return '#ef4444';
    default: return '#d1d5db';
  }
}

export const inputStyle: CSSProperties = {
  width: '100%', padding: '4px 6px', fontSize: '12px', border: '1px solid #d1d5db',
  borderRadius: '4px', boxSizing: 'border-box', outline: 'none', backgroundColor: '#fdfdfd',
};

// ─── 필터/표시용 상수 ───
export const MARKET_FILTER_OPTIONS: { id: string; label: string }[] = [
  { id: 'COUPANG', label: '쿠팡' },
  { id: 'SMART_STORE', label: '스마트스토어' },
  { id: 'ELEVEN_STREET', label: '11번가' },
  { id: 'GMARKET', label: 'G마켓' },
  { id: 'AUCTION', label: '옥션' },
  { id: 'CAFE24', label: '카페24' },
];

// 소싱처(벤더) — OrderGrid VENDOR_OPTIONS와 동일 출처. 빈값 제외.
export const VENDOR_OPTIONS: string[] = ['IHB', 'AMZ', 'FTN', 'COK', 'OCD', 'TES', 'VTB'];

export const STOCK_STATUS_OPTIONS: { id: 'IN_STOCK' | 'OUT_OF_STOCK'; label: string }[] = [
  { id: 'IN_STOCK', label: '판매중' },
  { id: 'OUT_OF_STOCK', label: '품절' },
];

// ─── 마켓 등록 배지 (ProductPage.renderMarketBadges 이식) ───
const MARKET_BADGES: { key: string; label: string; color: string }[] = [
  { key: 'COUPANG', label: '쿠팡', color: '#e53935' },
  { key: 'SMART_STORE', label: 'N스토어', color: '#22c55e' },
  { key: 'GMARKET', label: 'G마켓', color: '#16a34a' },
  { key: 'AUCTION', label: '옥션', color: '#dc2626' },
  { key: 'ELEVEN_STREET', label: '11번가', color: '#e11d48' },
];

export function renderMarketBadges(links?: Record<string, string>) {
  if (!links) return <span style={{ color: '#ccc' }}>-</span>;
  const badges = MARKET_BADGES.filter((m) => links[m.key] !== undefined);
  if (badges.length === 0) return <span style={{ color: '#ccc' }}>-</span>;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center', justifyContent: 'center' }}>
      {badges.map((m) => {
        const url = links[m.key];
        const base: CSSProperties = {
          fontSize: 11, fontWeight: 600, padding: '2px 7px', borderRadius: 10, lineHeight: 1.5,
          border: `1px solid ${m.color}`,
        };
        if (url) {
          return (
            <a key={m.key} href={url} target="_blank" rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()} title={`${m.label} 상품 페이지 열기`}
              style={{ ...base, color: '#fff', background: m.color, textDecoration: 'none', cursor: 'pointer' }}>
              {m.label}
            </a>
          );
        }
        return (
          <span key={m.key} title={`${m.label} 등록됨 · 링크 식별자 미확보`}
            style={{ ...base, color: m.color, background: '#fff', opacity: 0.55 }}>
            {m.label}
          </span>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 4: 타입/빌드 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run lint`
Expected: 에러 0 (아직 어디서도 import하지 않으므로 unused 경고만 없으면 통과 — `renderMarketBadges` 등은 export라 unused 아님)

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/index.css frontend/src/pages/product/productGridShared.tsx frontend/src/api/productApi.ts
git commit -m "$(cat <<'EOF'
feat(product): 그린 테마 스코프 + 상품그리드 공유 모듈(상수·헬퍼·배지)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 상단 필터 패널

**Files:**
- Create: `frontend/src/pages/product/ProductFilterPanel.tsx`

**Interfaces:**
- Consumes: `MARKET_FILTER_OPTIONS`, `VENDOR_OPTIONS`, `STOCK_STATUS_OPTIONS` (Task 1)
- Produces:
  - `interface ProductFilters { keyword: string; categories: string[]; markets: string[]; vendors: string[]; stockStatuses: string[]; inStockOnly: boolean }`
  - `function ProductFilterPanel({ categoryOptions, onSearch }: { categoryOptions: string[]; onSearch: (f: ProductFilters) => void }): JSX.Element`

- [ ] **Step 1: 필터 패널 작성**

`frontend/src/pages/product/ProductFilterPanel.tsx` 신규 작성. 색상은 전역 남색 대신 `--product-primary`를 참조한다(부모가 `.product-theme` 래퍼).

```tsx
import { useState } from 'react';
import { MARKET_FILTER_OPTIONS, VENDOR_OPTIONS, STOCK_STATUS_OPTIONS } from './productGridShared';

export interface ProductFilters {
  keyword: string;
  categories: string[];
  markets: string[];
  vendors: string[];
  stockStatuses: string[];
  inStockOnly: boolean;
}

const rowStyle = { display: 'flex', alignItems: 'center' } as const;
const labelStyle = { width: '120px', fontWeight: 600, color: '#555', flexShrink: 0 } as const;
const checkboxStyle = { marginRight: '6px', accentColor: 'var(--product-primary)', width: '16px', height: '16px', cursor: 'pointer' } as const;
const optLabelStyle = { display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' } as const;

export function ProductFilterPanel({ categoryOptions, onSearch }: { categoryOptions: string[]; onSearch: (f: ProductFilters) => void }) {
  const allMarkets = MARKET_FILTER_OPTIONS.map((m) => m.id);
  const allVendors = VENDOR_OPTIONS;
  const allStock = STOCK_STATUS_OPTIONS.map((s) => s.id as string);

  const [keyword, setKeyword] = useState('');
  const [categories, setCategories] = useState<string[]>([]);
  const [markets, setMarkets] = useState<string[]>(allMarkets);
  const [vendors, setVendors] = useState<string[]>(allVendors);
  const [stockStatuses, setStockStatuses] = useState<string[]>(allStock);
  const [inStockOnly, setInStockOnly] = useState(false);

  const toggle = (list: string[], set: (v: string[]) => void, val: string) =>
    set(list.includes(val) ? list.filter((x) => x !== val) : [...list, val]);

  const handleSearch = () => onSearch({ keyword, categories, markets, vendors, stockStatuses, inStockOnly });

  const isAllMarkets = markets.length === allMarkets.length;
  const isAllVendors = vendors.length === allVendors.length;
  const isAllStock = stockStatuses.length === allStock.length;
  const isAllCategories = categoryOptions.length > 0 && categories.length === categoryOptions.length;

  return (
    <div style={{ backgroundColor: '#f8f9fa', borderTop: '2px solid var(--product-primary)', borderBottom: '1px solid #ddd', padding: '12px 20px', marginBottom: '12px', fontSize: '13px' }}>
      {/* Row 1: 통합검색 + 카테고리 */}
      <div style={{ display: 'flex', borderBottom: '1px solid #eaeaea', paddingBottom: '8px', marginBottom: '8px' }}>
        <div style={{ flex: 1, ...rowStyle }}>
          <span style={labelStyle}>통합 검색</span>
          <input type="text" placeholder="상품명, SB코드, 브랜드" value={keyword}
            onChange={(e) => setKeyword(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            style={{ flex: 1, padding: '6px 12px', border: '1px solid #ccc', outline: 'none' }} />
        </div>
        <div style={{ flex: 1, ...rowStyle }}>
          <span style={labelStyle}>카테고리</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={optLabelStyle}>
              <input type="checkbox" checked={isAllCategories}
                onChange={() => setCategories(isAllCategories ? [] : [...categoryOptions])} style={checkboxStyle} />
              전체
            </label>
            {categoryOptions.length === 0 && <span style={{ color: '#aaa', fontSize: 13 }}>(로드된 상품 없음)</span>}
            {categoryOptions.map((c) => (
              <label key={c} style={optLabelStyle}>
                <input type="checkbox" checked={categories.includes(c)} onChange={() => toggle(categories, setCategories, c)} style={checkboxStyle} />
                {c}
              </label>
            ))}
          </div>
        </div>
      </div>

      {/* Row 2: 마켓 등록상태 + 소싱처 */}
      <div style={{ display: 'flex', paddingBottom: '8px', marginBottom: '8px', borderBottom: '1px solid #eaeaea' }}>
        <div style={{ flex: 1, ...rowStyle }}>
          <span style={labelStyle}>마켓 등록상태</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={optLabelStyle}>
              <input type="checkbox" checked={isAllMarkets} onChange={() => setMarkets(isAllMarkets ? [] : allMarkets)} style={checkboxStyle} />
              전체
            </label>
            {MARKET_FILTER_OPTIONS.map((m) => (
              <label key={m.id} style={optLabelStyle}>
                <input type="checkbox" checked={markets.includes(m.id)} onChange={() => toggle(markets, setMarkets, m.id)} style={checkboxStyle} />
                {m.label}
              </label>
            ))}
          </div>
        </div>
        <div style={{ flex: 1, ...rowStyle }}>
          <span style={labelStyle}>소싱처</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={optLabelStyle}>
              <input type="checkbox" checked={isAllVendors} onChange={() => setVendors(isAllVendors ? [] : allVendors)} style={checkboxStyle} />
              전체
            </label>
            {VENDOR_OPTIONS.map((v) => (
              <label key={v} style={optLabelStyle}>
                <input type="checkbox" checked={vendors.includes(v)} onChange={() => toggle(vendors, setVendors, v)} style={checkboxStyle} />
                {v}
              </label>
            ))}
          </div>
        </div>
      </div>

      {/* Row 3: 재고·판매상태 + 재고유무 */}
      <div style={{ display: 'flex' }}>
        <div style={{ flex: 1, ...rowStyle }}>
          <span style={labelStyle}>재고·판매상태</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={optLabelStyle}>
              <input type="checkbox" checked={isAllStock} onChange={() => setStockStatuses(isAllStock ? [] : allStock)} style={checkboxStyle} />
              전체
            </label>
            {STOCK_STATUS_OPTIONS.map((s) => (
              <label key={s.id} style={optLabelStyle}>
                <input type="checkbox" checked={stockStatuses.includes(s.id)} onChange={() => toggle(stockStatuses, setStockStatuses, s.id)} style={checkboxStyle} />
                {s.label}
              </label>
            ))}
          </div>
        </div>
        <div style={{ flex: 1, ...rowStyle }}>
          <span style={labelStyle}>재고유무</span>
          <label style={optLabelStyle}>
            <input type="checkbox" checked={inStockOnly} onChange={() => setInStockOnly((v) => !v)} style={checkboxStyle} />
            재고 있는 상품만
          </label>
        </div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'center', marginTop: '12px' }}>
        <button onClick={handleSearch} style={{ backgroundColor: 'var(--product-primary)', color: 'white', border: 'none', padding: '8px 32px', fontSize: '13px', fontWeight: 'bold', cursor: 'pointer', borderRadius: '4px' }}>검색</button>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 타입/린트 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run lint`
Expected: 에러 0

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/pages/product/ProductFilterPanel.tsx
git commit -m "$(cat <<'EOF'
feat(product): 상단 필터 패널(키워드·카테고리·마켓·소싱처·재고상태·재고유무, 그린)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 메인 그리드 (조회 전용) + 라우트 교체

**Files:**
- Create: `frontend/src/pages/product/ProductGrid.tsx`
- Modify: `frontend/src/App.tsx` (products 라우트 import 교체)

**Interfaces:**
- Consumes: `ProductFilterPanel`, `ProductFilters` (Task 2); `renderMarketBadges` (Task 1); `productApi.fetchProducts`, `ProductList` (기존)
- Produces: `export default function ProductGrid` (라우트 컴포넌트)
- 이후 Task 5/7/8에서 이 파일에 인라인 셀·모달·일괄삭제를 배선한다. 본 태스크는 조회·필터·표시까지만.

- [ ] **Step 1: 메인 그리드 작성 (조회 전용)**

`frontend/src/pages/product/ProductGrid.tsx` 신규 작성. OrderGrid처럼 최초 500건 고정 로드 후, 키워드는 서버 전송, 고급필터(카테고리·마켓·소싱처·재고상태·재고유무)는 로드된 행에 클라이언트 필터링한다.

```tsx
import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  useReactTable, getCoreRowModel, flexRender, createColumnHelper,
} from '@tanstack/react-table';
import { toast } from 'react-toastify';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '../../components/ui/Table';
import { productApi, type ProductList } from '../../api/productApi';
import { renderMarketBadges } from './productGridShared';
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
    if (f.markets.length > 0 && f.markets.length < 6) {
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
          <div style={{ fontSize: 11, color: '#94a3b8' }}>{row.original.originalName || ' '}</div>
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
```

> 참고: 마지막 `void toast;`는 Task 5에서 toast를 실제 사용하며 제거된다. lint의 no-unused-import를 피하기 위한 임시 표식이다. lint가 `void toast;`를 unused로 잡으면 이 줄과 `toast` import를 함께 제거하고 Task 5에서 다시 추가한다.

- [ ] **Step 2: App.tsx 라우트 교체**

`frontend/src/App.tsx`에서 `ProductPage` import(및 lazy)를 새 `ProductGrid`로 교체한다. 기존이 lazy면 동일 방식으로:

```tsx
// 변경 전 (예)
const ProductPage = lazy(() => import('./pages/ProductPage'));
// 변경 후
const ProductGrid = lazy(() => import('./pages/product/ProductGrid'));
```

그리고 라우트 element를 교체:

```tsx
<Route path="products" element={<ProductGrid />} />
```

(App.tsx의 실제 import/라우트 표기가 lazy가 아니면 그 표기에 맞춰 교체. 기존 `ProductPage` 참조가 남지 않도록 확인.)

- [ ] **Step 3: 타입/빌드/린트 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build && npm run lint`
Expected: 모두 성공, 에러 0

- [ ] **Step 4: 수동 확인**

Run: `cd frontend && npm run dev` → 브라우저 `/sbshop-agent/products` 접속. 확인:
- 상단 제목 "상품 관리"가 **포레스트 그린**(#166534), 사이드바/주문화면 남색은 그대로.
- 필터 패널 top-border가 그린, [검색] 버튼 그린.
- 그리드에 이미지/SB코드/브랜드/상품정보/카테고리/소싱처/판매가·상태/재고/마켓 컬럼 표시.
- 키워드 입력 후 [검색] → 서버 재조회. 마켓/소싱처/재고 체크 해제 → 표시 행수 감소(로드수 유지).

- [ ] **Step 5: 커밋 + push (Stage 1 종료)**

```bash
git add frontend/src/pages/product/ProductGrid.tsx frontend/src/App.tsx
git commit -m "$(cat <<'EOF'
feat(product): TanStack 그리드로 상품관리 재구현(조회·필터·그린테마), AG Grid 라우트 교체

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

push 전 사용자에게 "운영 배치 도는 중 아닌지" 확인 후:
```bash
git push origin main
```

---

## Stage 2 — 인라인 판매가+판매상태 세트 편집 (Task 4~5, 끝에 push)

### Task 4: PriceStockEditCell

**Files:**
- Create: `frontend/src/pages/product/PriceStockEditCell.tsx`

**Interfaces:**
- Consumes: `inputStyle` (Task 1)
- Produces: `function PriceStockEditCell({ salePrice, soldOut, onSave }: { salePrice: number; soldOut: boolean; onSave: (v: { price: number; soldOut: boolean }) => Promise<unknown> }): JSX.Element`

- [ ] **Step 1: 셀 작성 (ShippingEditCell 패턴 — 명시적 [전송])**

`frontend/src/pages/product/PriceStockEditCell.tsx` 신규.

```tsx
import { useEffect, useRef, useState } from 'react';
import { inputStyle } from './productGridShared';

// 판매가 + 판매상태(품절) 세트 편집. blur 자동저장이 아니라 명시적 [전송] 버튼으로 1회 커밋.
// (마켓 API 실호출이므로 OrderGrid의 ShippingEditCell과 동일한 규율)
export function PriceStockEditCell({ salePrice, soldOut, onSave }: {
  salePrice: number;
  soldOut: boolean;
  onSave: (v: { price: number; soldOut: boolean }) => Promise<unknown>;
}) {
  const [draftPrice, setDraftPrice] = useState(String(salePrice ?? 0));
  const [draftSoldOut, setDraftSoldOut] = useState(soldOut);
  const [sending, setSending] = useState(false);
  const focusedInside = useRef(false);

  useEffect(() => {
    if (!focusedInside.current) { setDraftPrice(String(salePrice ?? 0)); setDraftSoldOut(soldOut); }
  }, [salePrice, soldOut]);

  const priceNum = Number(draftPrice) || 0;
  const changed = priceNum !== (salePrice ?? 0) || draftSoldOut !== soldOut;
  const canSend = changed && !sending && priceNum >= 0;
  const border = changed ? '#f59e0b' : '#d1d5db';

  const send = () => {
    if (!canSend) return;
    setSending(true);
    onSave({ price: priceNum, soldOut: draftSoldOut })
      .catch(() => { /* 실패 토스트·롤백은 mutation onError가 처리 */ })
      .finally(() => setSending(false));
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '3px' }}
      onFocus={() => { focusedInside.current = true; }}
      onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) focusedInside.current = false; }}>
      <input type="number" min={0} value={draftPrice} placeholder="판매가"
        style={{ ...inputStyle, textAlign: 'right', borderColor: border, borderWidth: changed ? 2 : 1 }}
        onChange={(e) => setDraftPrice(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') send(); else if (e.key === 'Escape') { setDraftPrice(String(salePrice ?? 0)); setDraftSoldOut(soldOut); } }} />
      <select value={draftSoldOut ? 'OUT' : 'IN'}
        style={{ ...inputStyle, textAlign: 'center', borderColor: border, borderWidth: changed ? 2 : 1, color: draftSoldOut ? '#dc2626' : '#16a34a' }}
        onChange={(e) => setDraftSoldOut(e.target.value === 'OUT')}>
        <option value="IN">판매중</option>
        <option value="OUT">품절</option>
      </select>
      <button type="button" onClick={send} disabled={!canSend}
        style={{ fontSize: '11px', padding: '3px 6px', borderRadius: '4px', border: 'none', cursor: canSend ? 'pointer' : 'default',
          backgroundColor: canSend ? 'var(--product-primary)' : '#e5e7eb', color: canSend ? '#fff' : '#9ca3af' }}>
        {sending ? '전송중…' : '전송'}
      </button>
    </div>
  );
}
```

- [ ] **Step 2: 타입/린트 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run lint`
Expected: 에러 0

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/pages/product/PriceStockEditCell.tsx
git commit -m "$(cat <<'EOF'
feat(product): 판매가+판매상태 세트 인라인 셀(명시적 전송, ShippingEditCell 패턴)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 인라인 셀 배선 + 낙관적 업데이트 + 결과 토스트

**Files:**
- Modify: `frontend/src/pages/product/ProductGrid.tsx` (priceStock 컬럼을 `PriceStockEditCell`로 교체, mutation 추가)

**Interfaces:**
- Consumes: `PriceStockEditCell` (Task 4); `productApi.updatePriceStock`, `PriceStockSyncResult` (기존)
- Produces: 없음(내부 배선)

- [ ] **Step 1: import 추가 + toast 정식 사용**

`ProductGrid.tsx` 상단 import에 추가하고, 파일 끝 `void toast;` 줄과 그 주석을 제거한다.

```tsx
import { useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
// ...
import { productApi, type ProductList, type PriceStockSyncResult } from '../../api/productApi';
import { PriceStockEditCell } from './PriceStockEditCell';
```

- [ ] **Step 2: 마켓 라벨 헬퍼 + 결과 토스트 함수 추가**

`ProductGrid` 컴포넌트 함수 본문 상단(useQuery 위)에 추가한다.

```tsx
  const queryClient = useQueryClient();

  const MARKET_LABELS: Record<string, string> = {
    COUPANG: '쿠팡', SMART_STORE: '스토어', ELEVEN_STREET: '11번가', GMARKET: 'G마켓', AUCTION: '옥션', CAFE24: '카페24',
  };
  const marketLabel = (c: string) => MARKET_LABELS[c] || c;

  const surfacePriceStockResult = (result?: PriceStockSyncResult) => {
    const synced = result?.synced ?? [];
    const skipped = result?.skipped ?? [];
    const failedEntries = Object.entries(result?.failed ?? {});
    const syncedMsg = synced.length > 0 ? ` — ${synced.map(marketLabel).join(', ')} 반영 완료` : '';
    if (failedEntries.length > 0) {
      toast.warn(`저장됨${syncedMsg}. 단, ${failedEntries.length}개 마켓 반영 실패: ${failedEntries.map(([m]) => marketLabel(m)).join(', ')}`);
    } else if (synced.length > 0) {
      toast.success(`수정 완료${syncedMsg}`);
    } else if (skipped.length > 0) {
      toast.success(`수정 완료 (연동 마켓 없음: ${skipped.map(marketLabel).join(', ')})`);
    } else {
      toast.success('수정 완료 (연동된 마켓 없음)');
    }
  };
```

- [ ] **Step 3: mutation + 낙관적 캐시 패치 추가**

`surfacePriceStockResult` 아래에 추가한다. 낙관적 패치는 `['products', keyword]` 캐시의 해당 상품 salePrice/stockStatus만 수정한다.

```tsx
  const priceStockMutation = useMutation({
    mutationFn: ({ id, price, soldOut }: { id: number; price: number; soldOut: boolean }) =>
      productApi.updatePriceStock(id, price, soldOut).then((r) => r.data as PriceStockSyncResult),
    onMutate: async ({ id, price, soldOut }) => {
      const key = ['products', keyword];
      await queryClient.cancelQueries({ queryKey: key });
      const prev = queryClient.getQueryData<ProductList[]>(key);
      queryClient.setQueryData<ProductList[]>(key, (old) =>
        (old ?? []).map((p) => p.id === id ? { ...p, salePrice: price, stockStatus: soldOut ? 'OUT_OF_STOCK' : 'IN_STOCK' } : p));
      return { prev, key };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(ctx.key, ctx.prev);
      toast.error('판매가/판매상태 저장 실패');
    },
    onSuccess: (result) => surfacePriceStockResult(result),
  });
```

- [ ] **Step 4: priceStock 컬럼을 편집 셀로 교체**

`columns`의 `priceStock` accessor(`id: 'priceStock'`) `cell`을 아래로 교체한다.

```tsx
    columnHelper.accessor('salePrice', {
      id: 'priceStock', header: '판매가·상태', size: 160,
      cell: (info) => {
        const r = info.row.original;
        return (
          <PriceStockEditCell
            salePrice={r.salePrice ?? 0}
            soldOut={r.stockStatus === 'OUT_OF_STOCK'}
            onSave={({ price, soldOut }) => priceStockMutation.mutateAsync({ id: r.id, price, soldOut })}
          />
        );
      },
    }),
```

`columns`의 `useMemo` 의존성 배열에 `priceStockMutation`을 추가한다: `], [priceStockMutation]);`

- [ ] **Step 5: 타입/빌드/린트 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build && npm run lint`
Expected: 모두 성공. (`void toast;` 제거로 unused 아님)

- [ ] **Step 6: 수동 확인**

`npm run dev` → `/products`. 판매가·상태 셀에서 판매가 변경 또는 판매중↔품절 변경 시:
- 보더가 앰버(변경됨)로, [전송] 버튼이 그린으로 활성.
- [전송] 클릭 → 낙관적으로 즉시 반영 + 성공/부분실패 토스트. 실패 시 원복 + 에러 토스트.
- 변경 없이 [전송] 비활성.

- [ ] **Step 7: 커밋 + push (Stage 2 종료)**

```bash
git add frontend/src/pages/product/ProductGrid.tsx
git commit -m "$(cat <<'EOF'
feat(product): 판매가+판매상태 인라인 세트 편집 배선(price-stock 실호출·낙관적 업데이트·결과 토스트)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
배치 확인 후 `git push origin main`

---

## Stage 3 — 상세 모달 편집(모킹) + 체크박스 일괄삭제 (Task 6~8, 끝에 push)

### Task 6: 모킹 API + productApi 타입

**Files:**
- Create: `frontend/src/pages/product/productMockApi.ts`
- Modify: `frontend/src/api/productApi.ts` (편집 필드 타입 export)

**Interfaces:**
- Consumes: `productApi.deleteProduct` (기존)
- Produces:
  - `interface ProductEditFields { brand; productName; baseName; originalName; category; costPrice; salePrice; marginRate; stock; weight; bundleQuantity; barcode; capacity; measureUnit; vendor; manufacturer; origin; hsCode; sourceUrl; memo; detailHtml }` (모두 optional)
  - `function updateProductFields(id: number, fields: Partial<ProductEditFields>): Promise<{ ok: true }>` (MOCK)
  - `function bulkDeleteProducts(ids: number[]): Promise<{ deleted: number; failed: number[] }>` (실동작: 단건 DELETE 루프)

- [ ] **Step 1: 편집 필드 타입을 productApi.ts에 추가**

`frontend/src/api/productApi.ts`의 `ProductDetail` 인터페이스 아래에 추가한다.

```ts
// 상세 모달 편집 대상 필드(평탄화). 백엔드 PATCH 미구현 → productMockApi로 모킹(다음 세션 구현).
export interface ProductEditFields {
  brand: string;
  productName: string;
  baseName: string;
  originalName: string;
  category: string;
  costPrice: number;
  salePrice: number;
  marginRate: number;
  stock: number;
  weight: number;
  bundleQuantity: number;
  barcode: string;
  capacity: number;
  measureUnit: string;
  vendor: string;
  manufacturer: string;
  origin: string;
  hsCode: string;
  sourceUrl: string;
  memo: string;
  detailHtml: string;
}
```

- [ ] **Step 2: 모킹 API 작성**

`frontend/src/pages/product/productMockApi.ts` 신규.

```ts
import { productApi, type ProductEditFields } from '../../api/productApi';

let warnedUpdate = false;

// MOCK: 다음 세션 백엔드 구현 (PATCH /api/v1/products/{id})
// 현재는 서버 반영 없이 지연 후 성공만 반환. 호출부는 낙관적 로컬 반영으로 UX 완성.
export function updateProductFields(id: number, fields: Partial<ProductEditFields>): Promise<{ ok: true }> {
  if (!warnedUpdate) {
    console.warn('[MOCK] updateProductFields: 백엔드 PATCH 미구현 — 로컬 반영만. 다음 세션 구현 예정.');
    warnedUpdate = true;
  }
  console.warn('[MOCK] updateProductFields', id, fields);
  return new Promise((resolve) => setTimeout(() => resolve({ ok: true }), 300));
}

// 일괄 삭제: 신규 bulk 엔드포인트 대신 기존 단건 DELETE를 순차 호출(실동작).
// 다음 세션에 POST /api/v1/products/bulk-delete로 교체(인터페이스 유지).
export async function bulkDeleteProducts(ids: number[]): Promise<{ deleted: number; failed: number[] }> {
  const failed: number[] = [];
  let deleted = 0;
  for (const id of ids) {
    try {
      await productApi.deleteProduct(id);
      deleted += 1;
    } catch {
      failed.push(id);
    }
  }
  return { deleted, failed };
}
```

- [ ] **Step 3: 타입/린트 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run lint`
Expected: 에러 0

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/api/productApi.ts frontend/src/pages/product/productMockApi.ts
git commit -m "$(cat <<'EOF'
feat(product): 편집필드 타입 + 모킹 API(updateProductFields 모킹·bulkDelete 단건루프)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 상세 편집 모달 + 그리드 배선

**Files:**
- Create: `frontend/src/pages/product/ProductDetailModal.tsx`
- Modify: `frontend/src/pages/product/ProductGrid.tsx` (productInfo 클릭 → 모달 오픈, 모달 렌더)

**Interfaces:**
- Consumes: `productApi.fetchProductDetail`, `ProductDetail`, `ImageUploadResult` (기존); `updateProductFields` (Task 6); antd
- Produces: `function ProductDetailModal({ productId, open, onClose, onSaved }: { productId: number | null; open: boolean; onClose: () => void; onSaved: () => void }): JSX.Element`

- [ ] **Step 1: 상세 편집 모달 작성**

`frontend/src/pages/product/ProductDetailModal.tsx` 신규. 읽기 전용 Descriptions를 편집 폼(antd Form)으로 바꾸고, 이미지 섹션은 기존 ProductPage 로직을 유지한다.

```tsx
import { useEffect, useRef, useState } from 'react';
import {
  Modal, Form, Input, InputNumber, Button, Space, Spin, Image, Divider,
  Typography, Collapse, message, Tooltip, Popconfirm,
} from 'antd';
import { UploadOutlined, LinkOutlined, CloudDownloadOutlined } from '@ant-design/icons';
import { productApi, type ProductDetail, type ImageUploadResult, type ProductEditFields } from '../../api/productApi';
import { updateProductFields } from './productMockApi';

const { TextArea } = Input;

export function ProductDetailModal({ productId, open, onClose, onSaved }: {
  productId: number | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<ProductEditFields>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [detail, setDetail] = useState<ProductDetail | null>(null);
  const [urlInput, setUrlInput] = useState('');
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open || productId == null) return;
    setLoading(true);
    setUrlInput('');
    productApi.fetchProductDetail(productId)
      .then((res) => {
        const d = res.data as ProductDetail;
        setDetail(d);
        form.setFieldsValue({
          brand: d.brand, productName: d.productName, baseName: d.baseName, originalName: d.originalName,
          category: d.category, costPrice: d.priceInfo?.costPrice, salePrice: d.priceInfo?.salePrice,
          marginRate: d.priceInfo?.marginRate, stock: d.logisticsInfo?.stock, weight: d.logisticsInfo?.weight,
          bundleQuantity: d.logisticsInfo?.bundleQuantity, barcode: d.productSpec?.barcode,
          capacity: d.productSpec?.capacity, measureUnit: d.productSpec?.measureUnit,
          vendor: d.sourcingInfo?.vendor, manufacturer: d.sourcingInfo?.manufacturer,
          origin: d.sourcingInfo?.origin, hsCode: d.sourcingInfo?.hsCode, sourceUrl: d.sourcingInfo?.sourceUrl,
          memo: d.memo, detailHtml: d.detailHtml,
        });
      })
      .catch(() => message.error('상품 상세 조회에 실패했습니다.'))
      .finally(() => setLoading(false));
  }, [open, productId, form]);

  const refreshDetail = async () => {
    if (productId == null) return;
    try {
      const res = await productApi.fetchProductDetail(productId);
      setDetail(res.data as ProductDetail);
    } catch { message.error('상세 정보 갱신 실패'); }
  };

  const handleSave = async () => {
    if (productId == null) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      await updateProductFields(productId, values); // MOCK
      message.success('상품 정보 저장됨 (백엔드 반영은 다음 세션 구현)');
      onSaved();
      onClose();
    } catch {
      message.error('상품 정보 저장 실패');
    } finally {
      setSaving(false);
    }
  };

  const handleFilesSelected = async (files: FileList | null) => {
    if (productId == null || !files || files.length === 0) return;
    const fd = new FormData();
    Array.from(files).forEach((f) => fd.append('images', f));
    setUploading(true);
    try {
      const res = await productApi.uploadImages(productId, fd);
      const r = res.data as ImageUploadResult;
      message.success(`${r.imagesSucceeded}장 업로드 완료`);
      await refreshDetail();
    } catch {
      message.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleUploadByUrl = async () => {
    if (productId == null) return;
    const urls = urlInput.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
    if (urls.length === 0) { message.warning('이미지 URL을 입력하세요.'); return; }
    setUploading(true);
    try {
      await productApi.uploadImagesByUrl(productId, urls);
      message.success(`${urls.length}개 이미지 등록 완료`);
      setUrlInput('');
      await refreshDetail();
    } catch {
      message.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally { setUploading(false); }
  };

  const handleCrawl = async () => {
    if (productId == null) return;
    if (detail?.sourcingInfo?.vendor !== 'IHB') {
      message.warning('이 벤더는 아직 소스이미지 크롤을 지원하지 않습니다 (현재 iHerb 상품만 지원).');
      return;
    }
    setUploading(true);
    try {
      await productApi.crawlAndUpload(productId);
      message.success('소스이미지 크롤·업로드 완료');
      await refreshDetail();
    } catch { message.error('소스 이미지 크롤·업로드에 실패했습니다.'); }
    finally { setUploading(false); }
  };

  const num = (label: string, name: keyof ProductEditFields) => (
    <Form.Item label={label} name={name} style={{ marginBottom: 8 }}>
      <InputNumber style={{ width: '100%' }} />
    </Form.Item>
  );
  const txt = (label: string, name: keyof ProductEditFields, span2 = false) => (
    <Form.Item label={label} name={name} style={{ marginBottom: 8, gridColumn: span2 ? '1 / -1' : undefined }}>
      <Input />
    </Form.Item>
  );

  return (
    <Modal
      title={detail ? `상품 편집 · ${detail.productName}` : '상품 편집'}
      open={open}
      onCancel={onClose}
      width={880}
      footer={[
        <Button key="cancel" onClick={onClose}>닫기</Button>,
        <Button key="save" type="primary" loading={saving} onClick={handleSave}
          style={{ background: '#166534', borderColor: '#166534' }}>저장</Button>,
      ]}
    >
      {loading || !detail ? (
        <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
      ) : (
        <Form form={form} layout="vertical" size="small">
          <Typography.Text type="secondary">SB코드: {detail.sbCode}</Typography.Text>
          <Divider style={{ margin: '10px 0' }}>기본 정보</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
            {txt('브랜드', 'brand')}
            {txt('카테고리', 'category')}
            {txt('상품명', 'productName', true)}
            {txt('기본명', 'baseName')}
            {txt('원문명', 'originalName')}
          </div>

          <Divider style={{ margin: '10px 0' }}>가격</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0 16px' }}>
            {num('원가', 'costPrice')}{num('판매가', 'salePrice')}{num('마진율(%)', 'marginRate')}
          </div>

          <Divider style={{ margin: '10px 0' }}>물류·스펙</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0 16px' }}>
            {num('재고', 'stock')}{num('무게', 'weight')}{num('묶음수량', 'bundleQuantity')}
            {txt('바코드', 'barcode')}{num('용량', 'capacity')}{txt('단위', 'measureUnit')}
          </div>

          <Divider style={{ margin: '10px 0' }}>소싱</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
            {txt('소싱처', 'vendor')}{txt('제조사', 'manufacturer')}
            {txt('원산지', 'origin')}{txt('HS코드', 'hsCode')}
            {txt('소스 URL', 'sourceUrl', true)}
          </div>

          <Form.Item label="메모" name="memo" style={{ marginTop: 8 }}>
            <TextArea rows={2} />
          </Form.Item>

          <Divider style={{ margin: '10px 0' }}>이미지</Divider>
          <Typography.Text type="secondary">등록 이미지 (hosted)</Typography.Text>
          <div style={{ marginTop: 8, marginBottom: 12 }}>
            {detail.hostedImages && detail.hostedImages.length > 0 ? (
              <Image.PreviewGroup>
                <Space wrap>{detail.hostedImages.map((url, i) => (
                  <Image key={`h-${i}`} src={url} width={72} height={72} style={{ objectFit: 'cover', borderRadius: 4 }} />
                ))}</Space>
              </Image.PreviewGroup>
            ) : <Typography.Text type="secondary"> 없음</Typography.Text>}
          </div>
          <Space direction="vertical" style={{ width: '100%' }} size="small">
            <Space wrap>
              <input ref={fileInputRef} type="file" accept="image/*" multiple style={{ display: 'none' }}
                onChange={(e) => handleFilesSelected(e.target.files)} />
              <Button icon={<UploadOutlined />} loading={uploading} onClick={() => fileInputRef.current?.click()}>파일 업로드</Button>
              <Popconfirm title="소스 이미지 크롤·업로드"
                description="크롤한 이미지를 R2에 업로드하고 연동된 모든 마켓에 재게시합니다. 진행할까요?"
                okText="진행" cancelText="취소" onConfirm={handleCrawl}>
                <Tooltip title={detail.sourcingInfo?.vendor !== 'IHB' ? '현재 iHerb 상품만 지원' : ''}>
                  <Button icon={<CloudDownloadOutlined />} loading={uploading}>소스 이미지 크롤</Button>
                </Tooltip>
              </Popconfirm>
            </Space>
            <TextArea placeholder="이미지 URL을 줄바꿈 또는 쉼표로 구분해 입력" value={urlInput}
              onChange={(e) => setUrlInput(e.target.value)} rows={2} />
            <Button type="primary" icon={<LinkOutlined />} loading={uploading} onClick={handleUploadByUrl}
              style={{ background: '#166534', borderColor: '#166534' }}>URL로 등록</Button>
          </Space>

          {detail.detailHtml && (
            <Collapse style={{ marginTop: 12 }} items={[{
              key: 'detailHtml', label: '상세 설명 (HTML, 읽기전용)',
              children: <iframe title="detailHtml" sandbox="" srcDoc={detail.detailHtml}
                style={{ width: '100%', height: 320, border: '1px solid #eee' }} />,
            }]} />
          )}
        </Form>
      )}
    </Modal>
  );
}
```

> 참고: `detailHtml`은 폼 필드로 로드하되 편집 UI는 iframe 읽기전용으로 노출한다(HTML 직접편집 위험 회피). 저장 시 폼값(원본 유지)이 그대로 전달된다.

- [ ] **Step 2: ProductGrid에 모달 배선**

`ProductGrid.tsx`에 import·상태·오픈 핸들러·렌더를 추가한다.

import 추가:
```tsx
import { ProductDetailModal } from './ProductDetailModal';
```

컴포넌트 본문에 상태 추가(useState 근처):
```tsx
  const [detailId, setDetailId] = useState<number | null>(null);
```

`productInfo` 컬럼 `cell`의 최상위 `div`에 onClick 추가:
```tsx
        <div onClick={() => setDetailId(row.original.id)}
          style={{ textAlign: 'left', cursor: 'pointer', color: 'var(--product-primary)' }} title="상세 보기">
```

`columns` useMemo 의존성에 `[priceStockMutation]` 유지(setDetailId는 안정적이라 불필요하나, lint 경고 시 `setDetailId` 추가). return 최상위 `div` 내부 맨 끝(닫는 `</div>` 앞)에 모달 렌더:
```tsx
      <ProductDetailModal
        productId={detailId}
        open={detailId != null}
        onClose={() => setDetailId(null)}
        onSaved={() => refetch()}
      />
```

- [ ] **Step 3: 타입/빌드/린트 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build && npm run lint`
Expected: 모두 성공

- [ ] **Step 4: 수동 확인**

`/products`에서 상품정보(상품명) 클릭 → 편집 모달. 필드 수정 후 [저장] → "다음 세션 구현" 안내 토스트 + 콘솔에 `[MOCK] updateProductFields`. 이미지 파일/URL 업로드는 실제 동작(기존 엔드포인트).

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/product/ProductDetailModal.tsx frontend/src/pages/product/ProductGrid.tsx
git commit -m "$(cat <<'EOF'
feat(product): 상세 편집 모달(내부필드 폼·이미지 유지·저장 모킹) + 그리드 배선

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: 체크박스 일괄 삭제

**Files:**
- Modify: `frontend/src/pages/product/ProductGrid.tsx` (select 컬럼 + rowSelection + 툴바 [선택 삭제])

**Interfaces:**
- Consumes: `bulkDeleteProducts` (Task 6); antd `Modal.confirm`
- Produces: 없음(내부 배선)

- [ ] **Step 1: import·상태·rowSelection 추가**

import 추가:
```tsx
import type { RowSelectionState } from '@tanstack/react-table';
import { Modal as AntModal } from 'antd';
import { bulkDeleteProducts } from './productMockApi';
```

컴포넌트 본문 상태 추가:
```tsx
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
```

`useReactTable` 호출에 selection 배선(id를 상품 id로 안정화):
```tsx
  const table = useReactTable({
    data: rows,
    columns,
    state: { rowSelection },
    enableRowSelection: true,
    onRowSelectionChange: setRowSelection,
    getRowId: (r) => String(r.id),
    getCoreRowModel: getCoreRowModel(),
  });
```

- [ ] **Step 2: select 컬럼을 columns 맨 앞에 추가**

`columns` useMemo 배열 맨 앞에 삽입:
```tsx
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
```

- [ ] **Step 3: 일괄 삭제 핸들러 추가**

컴포넌트 본문(return 앞)에 추가:
```tsx
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
```

- [ ] **Step 4: 툴바에 [선택 삭제] 버튼 추가**

상단 헤더 우측(새로고침 버튼 옆, 같은 줄)에 삭제 버튼을 추가한다. 헤더 우측 영역을 아래로 교체:
```tsx
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {selectedIds.length > 0 && (
            <button onClick={handleBulkDelete} style={{ padding: '8px 16px', backgroundColor: '#fee2e2', color: '#b91c1c', border: '1px solid #fecaca', borderRadius: '4px', cursor: 'pointer', fontSize: '13px', fontWeight: 700 }}>
              선택 삭제 ({selectedIds.length})
            </button>
          )}
          <button onClick={() => refetch()} style={{ padding: '8px 16px', backgroundColor: '#fff', color: '#333', border: '1px solid #ddd', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}>새로고침</button>
        </div>
```

- [ ] **Step 5: 타입/빌드/린트 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build && npm run lint`
Expected: 모두 성공

- [ ] **Step 6: 수동 확인**

`/products`에서 행 체크박스 선택 → 헤더에 "선택 삭제 (N)" 빨강 버튼 등장. 전체선택 헤더 체크박스 동작. [선택 삭제] → 확인 다이얼로그 → 삭제 후 목록 갱신·토스트. (단건 DELETE 실동작이므로 실제 삭제됨 — 테스트 시 주의)

- [ ] **Step 7: 커밋 + push (Stage 3 종료)**

```bash
git add frontend/src/pages/product/ProductGrid.tsx
git commit -m "$(cat <<'EOF'
feat(product): 체크박스 다중선택 일괄삭제(확인 다이얼로그·단건 DELETE 루프·토스트)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
배치 확인 후 `git push origin main`

---

## Stage 4 — 폴리시 + 레거시 정리 (Task 9, 끝에 push)

### Task 9: 고급필터 모킹 표식 · 빈/로딩 폴리시 · ProductPage 삭제

**Files:**
- Modify: `frontend/src/pages/product/ProductGrid.tsx` (모킹 배너 + 카테고리 미제공 안내)
- Delete: `frontend/src/pages/ProductPage.tsx`

**Interfaces:**
- Consumes: 없음
- Produces: 없음

- [ ] **Step 1: 고급필터 모킹 배너 추가**

`ProductGrid.tsx` return 최상위 `div` 내부, 헤더와 필터패널 사이에 안내 배너를 추가한다(카테고리/서버검색 미구현 표식).

```tsx
      <div style={{ fontSize: 12, color: '#92400e', background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 6, padding: '6px 10px', marginBottom: 8 }}>
        고급필터(카테고리·마켓·소싱처·재고상태)는 현재 로드된 500건 대상 클라이언트 필터입니다. 카테고리는 목록 API 확장 후 활성화됩니다. (다음 세션 백엔드 구현)
      </div>
```

- [ ] **Step 2: ProductPage.tsx 삭제 및 잔존 참조 확인**

```bash
cd /Users/jasonair/Projects/sbshop-agent
grep -rn "pages/ProductPage" frontend/src || echo "no refs"
```
Expected: `no refs` (Task 3에서 라우트 교체 완료). 참조 없으면 삭제:
```bash
rm frontend/src/pages/ProductPage.tsx
```
참조가 남아 있으면 해당 파일에서 `ProductGrid`로 교체 후 삭제.

- [ ] **Step 3: 타입/빌드/린트 검증**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build && npm run lint`
Expected: 모두 성공 (ProductPage 삭제로 AG Grid import가 소스에서 사라짐 — 빌드 정상)

- [ ] **Step 4: 수동 확인**

`/products`: 모킹 배너 노출. 전체 기능(조회·필터·인라인 세트편집·상세모달편집·일괄삭제) 회귀 없이 동작. 콘솔 에러 없음.

- [ ] **Step 5: 커밋 + push (Stage 4 종료)**

```bash
git add -A frontend/src
git commit -m "$(cat <<'EOF'
chore(product): 고급필터 모킹 배너 + 레거시 ProductPage(AG Grid) 제거

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
배치 확인 후 `git push origin main`

---

## 다음 세션 백엔드 TODO (이번 범위 밖, 모킹 대체 대상)

1. `PATCH /api/v1/products/{id}` — `ProductEditFields` 부분 업데이트 → `updateProductFields` 모킹 교체.
2. `POST /api/v1/products/bulk-delete` — id 배열 일괄 삭제 → `bulkDeleteProducts` 단건루프 교체.
3. 목록 API 확장 — 응답에 `category` 포함 + 서버 쿼리 파라미터(`categories`, `markets`, `vendors`, `stockStatuses`, `inStockOnly`)로 고급필터 서버사이드화 → `applyClientFilters` 축소.

## Self-Review 결과

- **스펙 커버리지:** 그린테마(T1)·필터6종(T2)·TanStack 그리드(T3)·인라인 세트편집(T4~5)·상세모달 편집(T6~7)·일괄삭제(T8)·모킹규약(T6,T9)·단계별 push(각 Stage 종료) — 스펙 전 항목 태스크 매핑됨.
- **플레이스홀더:** 없음(모든 코드 스텝에 완전한 코드 포함).
- **타입 일관성:** `ProductFilters`(T2)·`ProductEditFields`(T6)·`PriceStockEditCell` 시그니처(T4→T5)·`applyClientFilters`(T3) 명칭·필드 일치 확인.
- **비목표:** ProductRegisterPage/BatchUpdatePage 불변, 서버정렬 UI 제외, 행병합 없음 — 준수.
