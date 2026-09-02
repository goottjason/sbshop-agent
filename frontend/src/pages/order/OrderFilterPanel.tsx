import { useState, useMemo, useEffect } from 'react';
import type { KstPeriodRange } from '../../utils/datetime';
import { kstDateString, kstDateStringOffset, kstPeriodRanges } from '../../utils/datetime';
import { ORDER_MARKET_CODES, marketLabel } from '../../utils/marketLabels';
import { VENDOR_OPTIONS, ALL_STATUSES, DEFAULT_VISIBLE_STATUSES, FILTER_OPEN_KEY, CLAIM_TYPE_OPTIONS, ALL_CLAIM_TYPES } from './constants';

const PERIOD_DEFAULT = 'DEFAULT';
const PERIOD_CUSTOM = 'CUSTOM';
const PERIOD_FIXED_LABELS: Record<string, string> = { TODAY: '오늘', THIS_WEEK: '이번주' };
const periodLabel = (preset: KstPeriodRange) => PERIOD_FIXED_LABELS[preset.id] ?? `${preset.month}월`;

export default function OrderFilterPanel({ onSearch }: { onSearch: (keyword: string, markets: string[], statuses: string[], startDate: string, endDate: string, purchaseStatuses: string[], stockStatuses: string[], vendors: string[], claimTypes: string[]) => void }) {
  const allMarkets = ORDER_MARKET_CODES;
  const allStatuses = ALL_STATUSES;
  const allPurchaseStatuses = ['NOT_PURCHASED', 'PURCHASED', 'WAITING_STOCK'];
  const allStockStatuses = ['IN_STOCK', 'OUT_OF_STOCK'];
  const allVendors = VENDOR_OPTIONS.filter(v => v !== '');
  const allClaimTypes = ALL_CLAIM_TYPES;
  const [selectedMarkets, setSelectedMarkets] = useState<string[]>(allMarkets);
  const [selectedStatuses, setSelectedStatuses] = useState<string[]>(DEFAULT_VISIBLE_STATUSES);
  const [selectedPurchaseStatuses, setSelectedPurchaseStatuses] = useState<string[]>(allPurchaseStatuses);
  const [selectedStockStatuses, setSelectedStockStatuses] = useState<string[]>(allStockStatuses);
  const [selectedVendors, setSelectedVendors] = useState<string[]>(allVendors);
  const [selectedClaimTypes, setSelectedClaimTypes] = useState<string[]>(allClaimTypes);
  const [keyword, setKeyword] = useState('');
  const [startDate, setStartDate] = useState(kstDateStringOffset({ months: -1 }));
  const [endDate, setEndDate] = useState(kstDateString());
  const [activePeriod, setActivePeriod] = useState<string>(PERIOD_DEFAULT);
  const [today, setToday] = useState(kstDateString);
  useEffect(() => {
    const timer = setInterval(() => {
      const now = kstDateString();
      setToday(prev => prev === now ? prev : now);
    }, 60000);
    return () => clearInterval(timer);
  }, []);
  const periodPresets = useMemo(() => kstPeriodRanges(today), [today]);

  const [open, setOpen] = useState(() => localStorage.getItem(FILTER_OPEN_KEY) === '1');
  useEffect(() => { localStorage.setItem(FILTER_OPEN_KEY, open ? '1' : '0'); }, [open]);

  const isAllMarketsSelected = selectedMarkets.length === allMarkets.length;
  const isAllStatusesSelected = selectedStatuses.length === allStatuses.length;
  const isDefaultStatuses = selectedStatuses.length === DEFAULT_VISIBLE_STATUSES.length
    && DEFAULT_VISIBLE_STATUSES.every(s => selectedStatuses.includes(s));
  const isAllPurchaseSelected = selectedPurchaseStatuses.length === allPurchaseStatuses.length;
  const isAllStockSelected = selectedStockStatuses.length === allStockStatuses.length;
  const isAllVendorsSelected = selectedVendors.length === allVendors.length;
  const isAllClaimTypesSelected = selectedClaimTypes.length === allClaimTypes.length;
  const handleSearch = () => {
    const stockFilter = isAllStockSelected ? [] : selectedStockStatuses;
    const vendorFilter = isAllVendorsSelected ? [] : selectedVendors;
    const claimTypeFilter = isAllClaimTypesSelected ? [] : selectedClaimTypes;
    onSearch(keyword, selectedMarkets, selectedStatuses, startDate, endDate, selectedPurchaseStatuses, stockFilter, vendorFilter, claimTypeFilter);
  };
  const handlePeriod = (id: string) => {
    const preset = kstPeriodRanges().find(p => p.id === id);
    if (!preset) return;
    setActivePeriod(id);
    setStartDate(preset.start);
    setEndDate(preset.end);
  };
  const toggleMarket = (val: string) => setSelectedMarkets(prev => prev.includes(val) ? prev.filter(m => m !== val) : [...prev, val]);
  const toggleStatus = (val: string) => setSelectedStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);
  const togglePurchase = (val: string) => setSelectedPurchaseStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);
  const toggleStock = (val: string) => setSelectedStockStatuses(prev => prev.includes(val) ? prev.filter(s => s !== val) : [...prev, val]);
  const toggleVendor = (val: string) => setSelectedVendors(prev => prev.includes(val) ? prev.filter(v => v !== val) : [...prev, val]);
  const toggleClaimType = (val: string) => setSelectedClaimTypes(prev => prev.includes(val) ? prev.filter(c => c !== val) : [...prev, val]);

  const chips: { label: string; active: boolean }[] = [
    { label: `${startDate.slice(5).replace('-', '.')} ~ ${endDate.slice(5).replace('-', '.')}`, active: activePeriod !== PERIOD_DEFAULT },
    { label: `마켓 ${isAllMarketsSelected ? '전체' : selectedMarkets.length}`, active: !isAllMarketsSelected },
    {
      label: `상태 ${isAllStatusesSelected ? '전체' : isDefaultStatuses ? '기본' : selectedStatuses.length}`,
      active: !isAllStatusesSelected && !isDefaultStatuses,
    },
    { label: `구매 ${isAllPurchaseSelected ? '전체' : selectedPurchaseStatuses.length}`, active: !isAllPurchaseSelected },
    { label: `재고 ${isAllStockSelected ? '전체' : selectedStockStatuses.length}`, active: !isAllStockSelected },
    { label: `소싱 ${isAllVendorsSelected ? '전체' : selectedVendors.length}`, active: !isAllVendorsSelected },
    { label: `클레임 ${isAllClaimTypesSelected ? '전체' : selectedClaimTypes.length}`, active: !isAllClaimTypesSelected },
  ];
  return (
    <div style={{ backgroundColor: '#fff', border: '1px solid #e5e7eb', borderTop: '2px solid var(--primary-color)', borderRadius: '10px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', padding: open ? '8px 14px 10px' : '5px 14px', marginBottom: '6px', fontSize: '13px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px', minHeight: '26px', ...(open ? { borderBottom: '1px solid #eaeaea', paddingBottom: '6px', marginBottom: '6px' } : {}) }}>
        <button
          type="button"
          onClick={() => setOpen(o => !o)}
          style={{ display: 'flex', alignItems: 'center', gap: '4px', background: 'none', border: 'none', cursor: 'pointer', padding: '2px 4px', fontSize: '12px', fontWeight: 700, color: 'var(--primary-color)', flexShrink: 0 }}
          aria-expanded={open}
        >
          <span style={{ display: 'inline-block', transform: open ? 'rotate(90deg)' : 'none', transition: 'transform 0.15s' }}>▸</span>
          필터
        </button>
        <div style={{ display: 'flex', gap: '5px', alignItems: 'center', flexWrap: 'wrap', minWidth: 0 }}>
          {!open && chips.map(c => (
            <span
              key={c.label}
              style={{
                fontSize: '11px', padding: '2px 7px', borderRadius: '10px', whiteSpace: 'nowrap',
                backgroundColor: c.active ? '#e0e7ff' : '#f3f4f6',
                color: c.active ? '#3730a3' : '#6b7280',
                fontWeight: c.active ? 600 : 400,
              }}
            >
              {c.label}
            </span>
          ))}
        </div>
        {!open && (
          <>
            <input
              type="text" placeholder="주문번호, 수취인명, 통관번호, 휴대폰, SB코드, 상품명, 송장번호…"
              value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()}
              style={{ flex: 1, minWidth: '160px', padding: '3px 9px', border: '1px solid #ccc', borderRadius: '4px', outline: 'none', fontSize: '12px' }}
            />
            <button onClick={handleSearch} style={{ backgroundColor: 'var(--primary-color)', color: 'white', border: 'none', padding: '4px 18px', fontSize: '12px', fontWeight: 'bold', cursor: 'pointer', borderRadius: '4px', flexShrink: 0 }}>검색</button>
          </>
        )}
      </div>
      {open && (
      <>
      <div style={{ display: 'flex', borderBottom: '1px solid #eaeaea', paddingBottom: '6px', marginBottom: '6px' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555', flexShrink: 0 }}>조회기간 (주문일)</span>
          <div style={{ display: 'flex', border: '1px solid #ccc', borderRadius: '4px', overflow: 'hidden', marginRight: '12px', flexShrink: 0 }}>
            {periodPresets.map((preset, idx) => (
              <button key={preset.id} onClick={() => handlePeriod(preset.id)} style={{ padding: '6px 12px', border: 'none', background: activePeriod === preset.id ? 'var(--primary-color)' : '#f8f9fa', borderLeft: idx === 0 ? 'none' : '1px solid #ccc', color: activePeriod === preset.id ? '#fff' : '#333', fontWeight: activePeriod === preset.id ? 600 : 400, cursor: 'pointer', whiteSpace: 'nowrap' }}>
                {periodLabel(preset)}
              </button>
            ))}
          </div>
          <input type="date" value={startDate} onChange={e => { setStartDate(e.target.value); setActivePeriod(PERIOD_CUSTOM); }} style={{ padding: '5px', border: '1px solid #ccc', flexShrink: 0 }} />
          <span style={{ margin: '0 8px', flexShrink: 0 }}>~</span>
          <input type="date" value={endDate} onChange={e => { setEndDate(e.target.value); setActivePeriod(PERIOD_CUSTOM); }} style={{ padding: '5px', border: '1px solid #ccc', flexShrink: 0 }} />
        </div>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>통합 검색</span>
          <input type="text" placeholder="주문번호, 수취인명, 주문자명, 통관번호, 휴대폰, SB코드, 등록상품명, 영문상품명, 송장번호" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()} style={{ flex: 1, padding: '6px 12px', border: '1px solid #ccc', outline: 'none' }} />
        </div>
      </div>
      <div style={{ display: 'flex', paddingBottom: '0' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>마켓채널</span>
          <div style={{ display: 'flex', gap: '16px' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllMarketsSelected} onChange={() => setSelectedMarkets(isAllMarketsSelected ? [] : allMarkets)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {ORDER_MARKET_CODES.map(code => (
              <label key={code} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedMarkets.includes(code)} onChange={() => toggleMarket(code)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {marketLabel(code)}
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
              { id: 'DISPATCHED', label: '배송지시' },
              { id: 'SHIPPED', label: '배송중' },
              { id: 'DELIVERED', label: '배송완료' },
              { id: 'CONFIRMED', label: '구매확정' }
            ].map(status => (
              <label key={status.id} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedStatuses.includes(status.id)} onChange={() => toggleStatus(status.id)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {status.label}
              </label>
            ))}
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', paddingTop: '8px', marginTop: '8px', borderTop: '1px solid #eaeaea' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>구매상태</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllPurchaseSelected} onChange={() => setSelectedPurchaseStatuses(isAllPurchaseSelected ? [] : allPurchaseStatuses)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {[
              { id: 'NOT_PURCHASED', label: '미구매' },
              { id: 'PURCHASED', label: '구매완료' },
              { id: 'WAITING_STOCK', label: '입고대기' }
            ].map(ps => (
              <label key={ps.id} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedPurchaseStatuses.includes(ps.id)} onChange={() => togglePurchase(ps.id)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {ps.label}
              </label>
            ))}
          </div>
        </div>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>재고상태</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllStockSelected} onChange={() => setSelectedStockStatuses(isAllStockSelected ? [] : allStockStatuses)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {[
              { id: 'IN_STOCK', label: '있음' },
              { id: 'OUT_OF_STOCK', label: '품절' }
            ].map(ss => (
              <label key={ss.id} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedStockStatuses.includes(ss.id)} onChange={() => toggleStock(ss.id)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {ss.label}
              </label>
            ))}
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', paddingTop: '8px', marginTop: '8px', borderTop: '1px solid #eaeaea' }}>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>소싱처</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllVendorsSelected} onChange={() => setSelectedVendors(isAllVendorsSelected ? [] : allVendors)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {allVendors.map(vendor => (
              <label key={vendor} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedVendors.includes(vendor)} onChange={() => toggleVendor(vendor)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {vendor}
              </label>
            ))}
          </div>
        </div>
        <div style={{ flex: 1, display: 'flex', alignItems: 'center' }}>
          <span style={{ width: '120px', fontWeight: 600, color: '#555' }}>클레임</span>
          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
              <input type="checkbox" checked={isAllClaimTypesSelected} onChange={() => setSelectedClaimTypes(isAllClaimTypesSelected ? [] : allClaimTypes)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
              전체
            </label>
            {CLAIM_TYPE_OPTIONS.map(claim => (
              <label key={claim.id} style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', fontSize: '14px', color: '#333' }}>
                <input type="checkbox" checked={selectedClaimTypes.includes(claim.id)} onChange={() => toggleClaimType(claim.id)} style={{ marginRight: '6px', accentColor: 'var(--primary-color)', width: '16px', height: '16px', cursor: 'pointer' }} />
                {claim.label}
              </label>
            ))}
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'center', marginTop: '8px' }}>
        <button onClick={handleSearch} style={{ backgroundColor: 'var(--primary-color)', color: 'white', border: 'none', padding: '5px 32px', fontSize: '12px', fontWeight: 'bold', cursor: 'pointer', borderRadius: '4px' }}>검색</button>
      </div>
      </>
      )}
    </div>
  );
}
