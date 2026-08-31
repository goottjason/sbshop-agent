import { useState } from 'react';
import { MARKET_FILTER_OPTIONS, VENDOR_OPTIONS, STOCK_STATUS_OPTIONS } from './productGridShared';

export interface ProductFilters {
  keyword: string;
  categories: string[];
  includeUncategorized: boolean;
  markets: string[];
  vendors: string[];
  stockStatuses: string[];
  inStockOnly: boolean;
  sourceGone: 'ALL' | 'GONE_ONLY' | 'ALIVE_ONLY';
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
  const [includeUncategorized, setIncludeUncategorized] = useState(false);
  const [markets, setMarkets] = useState<string[]>(allMarkets);
  const [vendors, setVendors] = useState<string[]>(allVendors);
  const [stockStatuses, setStockStatuses] = useState<string[]>(allStock);
  const [inStockOnly, setInStockOnly] = useState(false);
  const [sourceGone, setSourceGone] = useState<ProductFilters['sourceGone']>('ALL');

  const toggle = (list: string[], set: (v: string[]) => void, val: string) =>
    set(list.includes(val) ? list.filter((x) => x !== val) : [...list, val]);

  const handleSearch = () =>
    onSearch({ keyword, categories, includeUncategorized, markets, vendors, stockStatuses, inStockOnly, sourceGone });

  const isAllMarkets = markets.length === allMarkets.length;
  const isAllVendors = vendors.length === allVendors.length;
  const isAllStock = stockStatuses.length === allStock.length;
  const isAllCategories = categories.length === categoryOptions.length && includeUncategorized;

  const toggleAllCategories = () => {
    setCategories(isAllCategories ? [] : [...categoryOptions]);
    setIncludeUncategorized(!isAllCategories);
  };

  return (
    <div style={{ backgroundColor: '#fff', borderTop: '2px solid var(--product-primary)', border: '1px solid #e5e7eb', borderTopColor: 'var(--product-primary)', borderTopWidth: 2, borderRadius: '10px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', padding: '14px 20px', marginBottom: '14px', fontSize: '13px' }}>
      <style>{`
        .pf-search:focus { border-color: var(--product-primary); box-shadow: 0 0 0 3px rgba(22,101,52,0.12); }
        .pf-search::placeholder { color: #cbd5e1; }
        .pf-searchbtn:hover { filter: brightness(1.08); box-shadow: 0 2px 8px rgba(22,101,52,0.25); }
      `}</style>
      <div style={{ display: 'flex', borderBottom: '1px solid #f1f5f9', paddingBottom: '8px', marginBottom: '8px' }}>
        <div style={{ flex: 1, ...rowStyle }}>
          <span style={labelStyle}>통합 검색</span>
          <input type="text" className="pf-search" placeholder="상품명, SB코드, 브랜드" value={keyword}
            onChange={(e) => setKeyword(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            style={{ flex: 1, padding: '7px 12px', border: '1px solid #d1d5db', borderRadius: 8, outline: 'none', fontSize: 13, transition: 'border-color .15s, box-shadow .15s' }} />
        </div>
        <div style={{ flex: 1, ...rowStyle }}>
          <span style={labelStyle}>카테고리</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={optLabelStyle}>
              <input type="checkbox" checked={isAllCategories}
                onChange={toggleAllCategories} style={checkboxStyle} />
              전체
            </label>
            {categoryOptions.map((c) => (
              <label key={c} style={optLabelStyle}>
                <input type="checkbox" checked={categories.includes(c)} onChange={() => toggle(categories, setCategories, c)} style={checkboxStyle} />
                {c}
              </label>
            ))}
            <label style={optLabelStyle}>
              <input type="checkbox" checked={includeUncategorized}
                onChange={() => setIncludeUncategorized((v) => !v)} style={checkboxStyle} />
              미분류
            </label>
          </div>
        </div>
      </div>

      <div style={{ display: 'flex', paddingBottom: '8px', marginBottom: '8px', borderBottom: '1px solid #f1f5f9' }}>
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
          <span style={labelStyle}>원본 상태</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            {([
              { id: 'ALL', label: '전체' },
              { id: 'ALIVE_ONLY', label: '정상만' },
              { id: 'GONE_ONLY', label: '폐기 후보만' },
            ] as const).map((o) => (
              <label key={o.id} style={optLabelStyle}>
                <input
                  type="radio"
                  name="sourceGone"
                  checked={sourceGone === o.id}
                  onChange={() => setSourceGone(o.id)}
                  style={checkboxStyle}
                />
                {o.label}
              </label>
            ))}
          </div>
        </div>
      </div>

      <div style={{ display: 'flex' }}>
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
        <button onClick={handleSearch} className="pf-searchbtn" style={{ backgroundColor: 'var(--product-primary)', color: 'white', border: 'none', padding: '9px 40px', fontSize: '13px', fontWeight: 700, cursor: 'pointer', borderRadius: '8px', boxShadow: '0 1px 3px rgba(22,101,52,0.2)', transition: 'filter .15s, box-shadow .15s' }}>검색</button>
      </div>
    </div>
  );
}
