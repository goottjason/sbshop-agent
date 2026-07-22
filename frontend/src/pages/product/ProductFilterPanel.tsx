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
