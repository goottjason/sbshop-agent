import { useState } from 'react';
import { Button, Checkbox, Input, Modal, Select, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { MARKET_FILTER_OPTIONS, VENDOR_OPTIONS, STOCK_STATUS_OPTIONS } from './productGridShared';
import { EMPTY_PRODUCT_FILTERS, parseSbCodes } from './productSearch';
import './productWorkspace.css';

export interface ProductFilters {
  keyword: string;
  sbCodes: string[];
  brands: string[];
  categories: string[];
  includeUncategorized: boolean;
  markets: string[];
  vendors: string[];
  stockStatuses: string[];
  inStockOnly: boolean;
  sourceGone: 'ALL' | 'GONE_ONLY' | 'ALIVE_ONLY';
}

interface Props {
  categoryOptions: string[];
  brandOptions: string[];
  brandsLoading: boolean;
  brandsError: boolean;
  onRetryBrands: () => void;
  onSearch: (filters: ProductFilters) => void;
}

export function ProductFilterPanel({ categoryOptions, brandOptions, brandsLoading, brandsError, onRetryBrands, onSearch }: Props) {
  const [filters, setFilters] = useState<ProductFilters>(EMPTY_PRODUCT_FILTERS);
  const [codesOpen, setCodesOpen] = useState(false);
  const [codesText, setCodesText] = useState('');
  const codes = parseSbCodes(codesText);
  const set = <K extends keyof ProductFilters>(key: K, value: ProductFilters[K]) =>
    setFilters((previous) => ({ ...previous, [key]: value }));
  const apply = (next: ProductFilters) => { setFilters(next); onSearch(next); };

  return (
    <section className="pw-search" aria-label="상품 검색 및 필터">
      <form onSubmit={(event) => { event.preventDefault(); onSearch(filters); }}>
        <div className="pw-searchbar">
          <Input size="large" prefix={<SearchOutlined />} aria-label="상품 통합 검색"
            placeholder="상품명, SB코드, 브랜드, 바코드 검색" allowClear
            value={filters.keyword} onChange={(event) => set('keyword', event.target.value)} />
          <Button size="large" onClick={() => { setCodesText(filters.sbCodes.join('\n')); setCodesOpen(true); }}>
            여러 SB코드 붙여넣기{filters.sbCodes.length > 0 ? ' (' + filters.sbCodes.length + ')' : ''}
          </Button>
          <Button size="large" type="primary" htmlType="submit">검색</Button>
        </div>
        <div className="pw-filter-grid">
          <div className="pw-filter">
            <label htmlFor="pw-vendors">소싱처</label>
            <Select id="pw-vendors" mode="multiple" allowClear placeholder="모든 소싱처"
              value={filters.vendors} onChange={(value) => set('vendors', value)}
              options={VENDOR_OPTIONS.map((value) => ({ value, label: value }))} />
          </div>
          <div className="pw-filter">
            <label htmlFor="pw-brands">브랜드</label>
            <Select id="pw-brands" mode="multiple" showSearch allowClear placeholder="브랜드 검색·선택"
              optionFilterProp="label" loading={brandsLoading} disabled={brandsError}
              value={filters.brands} onChange={(value) => set('brands', value)}
              options={brandOptions.map((value) => ({ value, label: value }))} maxTagCount="responsive" />
            {brandsError && <span role="alert">브랜드 목록 조회 실패 <Button type="link" size="small" onClick={onRetryBrands}>재시도</Button></span>}
          </div>
          <div className="pw-filter">
            <label htmlFor="pw-stock">재고 상태</label>
            <Select id="pw-stock" mode="multiple" allowClear placeholder="모든 재고 상태"
              value={filters.stockStatuses} onChange={(value) => set('stockStatuses', value)}
              options={STOCK_STATUS_OPTIONS.map((option) => ({ value: option.id, label: option.label }))} />
          </div>
        </div>
        <details className="pw-more-filters">
          <summary>카테고리 · 마켓 연결 · 원본 상태</summary>
          <div className="pw-filter-grid">
            <div className="pw-filter">
              <label htmlFor="pw-categories">카테고리</label>
              <Select id="pw-categories" mode="multiple" allowClear placeholder="모든 카테고리"
                value={filters.categories} onChange={(value) => set('categories', value)}
                options={categoryOptions.map((value) => ({ value, label: value }))} />
              <Checkbox checked={filters.includeUncategorized} onChange={(event) => set('includeUncategorized', event.target.checked)}>미분류 포함</Checkbox>
            </div>
            <div className="pw-filter">
              <label htmlFor="pw-markets">마켓 연결 기록</label>
              <Select id="pw-markets" mode="multiple" allowClear placeholder="마켓 제한 없음"
                value={filters.markets} onChange={(value) => set('markets', value)}
                options={MARKET_FILTER_OPTIONS.map((option) => ({ value: option.id, label: option.label }))} />
              <small>선택한 마켓 중 하나 이상에 연결 기록이 있는 상품</small>
            </div>
            <div className="pw-filter">
              <label htmlFor="pw-source-state">소싱처 원본 상태</label>
              <Select id="pw-source-state" value={filters.sourceGone} onChange={(value) => set('sourceGone', value)}
                options={[{ value: 'ALL', label: '전체' }, { value: 'ALIVE_ONLY', label: '원본 소멸 기록 없음' }, { value: 'GONE_ONLY', label: '원본 소멸 확인' }]} />
              <Checkbox checked={filters.inStockOnly} onChange={(event) => set('inStockOnly', event.target.checked)}>DB 재고 수량 1개 이상</Checkbox>
            </div>
          </div>
        </details>
        <div className="pw-filter-footer">
          <span>{filters.sbCodes.length > 0
            ? <Tag closable onClose={() => apply({ ...filters, sbCodes: [] })}>SB코드 {filters.sbCodes.length}개 · 다른 검색 조건과 함께 적용</Tag>
            : '조건을 조합하고 검색을 누르세요.'}</span>
          <Button type="text" onClick={() => apply(EMPTY_PRODUCT_FILTERS)}>조건 초기화</Button>
        </div>
      </form>
      <Modal title="여러 SB코드 붙여넣기" open={codesOpen} onCancel={() => setCodesOpen(false)}
        okText="검색에 적용" cancelText="취소"
        onOk={() => { apply({ ...filters, sbCodes: codes }); setCodesOpen(false); }}>
        <p>쉼표와 줄바꿈을 함께 사용할 수 있습니다. 공백·중복을 정리하고 코드 전체가 일치하는 상품을 찾습니다.</p>
        <Input.TextArea aria-label="검색할 SB코드" value={codesText} onChange={(event) => setCodesText(event.target.value)}
          autoSize={{ minRows: 6, maxRows: 14 }} placeholder={'SB코드1, SB코드2\nSB코드3'} />
        <p>중복 제거 후 {codes.length.toLocaleString()}개 · 소싱처·브랜드 등 다른 조건도 함께 적용합니다.</p>
      </Modal>
    </section>
  );
}
