import { useState } from 'react';
import { Button, Checkbox, Input, Modal, Select, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { MARKET_FILTER_OPTIONS, VENDOR_OPTIONS, STOCK_STATUS_OPTIONS } from './productGridShared';
import { EMPTY_PRODUCT_FILTERS, parseSbCodes } from './productSearch';
import './productWorkspace.css';
import type { MarketPlusIssueFilter } from '../../api/marketPlusTransmissionApi';

const MARKETPLUS_ISSUE_OPTIONS: { value: MarketPlusIssueFilter; label: string }[] = [
  { value: 'ALL', label: '전체' }, { value: 'ANY_ISSUE', label: '실패 또는 결과 충돌' },
  { value: 'FAILURE', label: '전송 실패' }, { value: 'CONFLICT', label: '성공·실패 결과 충돌' },
];

export interface ProductFilters {
  keyword: string;
  sbCodes: string[];
  brands: string[];
  categories: string[];
  includeUncategorized: boolean;
  markets: string[];
  registeredMarkets: string[];
  missingMarkets: string[];
  pendingChangesOnly: boolean;
  marketPlusIssue: MarketPlusIssueFilter;
  vendors: string[];
  stockStatuses: string[];
  inStockOnly: boolean;
  sourceGone: 'ALL' | 'GONE_ONLY' | 'ALIVE_ONLY';
  contentAgeDays: number | null;
  contentAgeField: 'ANY' | 'IMAGES' | 'DETAIL_HTML';
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

  const setMarkets = (key: 'registeredMarkets' | 'missingMarkets', value: string[]) => {
    const opposite = key === 'registeredMarkets' ? 'missingMarkets' : 'registeredMarkets';
    setFilters((previous) => ({ ...previous, [key]: value, [opposite]: previous[opposite].filter((market) => !value.includes(market)) }));
  };
  const marketLabel = (market: string) => MARKET_FILTER_OPTIONS.find((option) => option.id === market)?.label ?? market;

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
        <div className="pw-quick-filters" aria-label="빠른 검색 조건">
          <span>빠른 조건</span>
          <Button size="small" onClick={() => apply({ ...filters, vendors: ['IHB'], stockStatuses: ['IN_STOCK'], markets: [],
            registeredMarkets: ['COUPANG'], missingMarkets: ['ELEVEN_STREET'] })}>IHB · 재고 있음 · 쿠팡 등록 · 11번가 미등록</Button>
          <Checkbox checked={filters.pendingChangesOnly} onChange={(event) => set('pendingChangesOnly', event.target.checked)}>미반영 DB 변경 있음</Checkbox>
          <Button size="small" onClick={() => apply({ ...filters, marketPlusIssue: 'ANY_ISSUE' })}>G마켓·옥션 전송 이슈</Button>
          <Button size="small" onClick={() => apply({ ...filters, contentAgeDays: 90, contentAgeField: 'ANY' })}>이미지·상세 90일 경과 / 적용 기록 없음</Button>
        </div>
        <details className="pw-more-filters">
          <summary>카테고리 · 마켓 연결 · 전송 이슈 · 콘텐츠 갱신 · 원본 상태</summary>
          <div className="pw-filter-grid">
            <div className="pw-filter">
              <label htmlFor="pw-content-age">콘텐츠 DB 적용 시각</label>
              <Select id="pw-content-age" value={filters.contentAgeDays ?? 0} onChange={value => set('contentAgeDays', value || null)}
                options={[{ value: 0, label: '전체' }, ...[30, 60, 90, 180, 365].map(value => ({ value, label: `${value}일 경과 또는 적용 기록 없음` }))]} />
              <Select aria-label="갱신 시각 비교 항목" value={filters.contentAgeField} onChange={value => set('contentAgeField', value)}
                options={[{ value: 'ANY', label: '이미지 또는 상세 HTML' }, { value: 'IMAGES', label: '이미지' }, { value: 'DETAIL_HTML', label: '상세 HTML' }]} />
              <small>새 내용을 수집만 한 경우 DB 적용 시각은 바뀌지 않습니다.</small>
            </div>
            <div className="pw-filter">
              <label htmlFor="pw-marketplus-issue">G마켓·옥션 전송 결과</label>
              <Select id="pw-marketplus-issue" value={filters.marketPlusIssue} onChange={value => set('marketPlusIssue', value)} options={MARKETPLUS_ISSUE_OPTIONS} />
              <small>현재 연결의 최근 수집 이력 기준입니다. 미수집 이력은 포함되지 않습니다.</small>
            </div>
            <div className="pw-filter">
              <label htmlFor="pw-categories">카테고리</label>
              <Select id="pw-categories" mode="multiple" allowClear placeholder="모든 카테고리"
                value={filters.categories} onChange={(value) => set('categories', value)}
                options={categoryOptions.map((value) => ({ value, label: value }))} />
              <Checkbox checked={filters.includeUncategorized} onChange={(event) => set('includeUncategorized', event.target.checked)}>미분류 포함</Checkbox>
            </div>
            <div className="pw-filter">
              <label htmlFor="pw-registered-markets">등록된 마켓 · 모두 충족</label>
              <Select id="pw-registered-markets" mode="multiple" allowClear placeholder="등록 마켓 선택"
                value={filters.registeredMarkets} onChange={(value) => setMarkets('registeredMarkets', value)}
                options={MARKET_FILTER_OPTIONS.map((option) => ({ value: option.id, label: option.label }))} />
              <small>DB 연결 기준. 일시 품절도 포함하며 실제 상태는 마켓 상태 확인에서 조회합니다.</small>
            </div>
            <div className="pw-filter">
              <label htmlFor="pw-missing-markets">미등록·연결 해제 마켓 · 모두 충족</label>
              <Select id="pw-missing-markets" mode="multiple" allowClear placeholder="미등록 마켓 선택"
                value={filters.missingMarkets} onChange={(value) => setMarkets('missingMarkets', value)}
                options={MARKET_FILTER_OPTIONS.map((option) => ({ value: option.id, label: option.label }))} />
              <small>삭제·영구 금지로 해제된 연결도 포함합니다. 실제 등록 가능 여부는 등록 검토에서 확인합니다.</small>
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
          <span className="pw-filter-tags">
            {filters.registeredMarkets.map((market) => <Tag key={'registered-' + market} color="green" closable
              onClose={() => apply({ ...filters, registeredMarkets: filters.registeredMarkets.filter((item) => item !== market) })}>{marketLabel(market)} 등록</Tag>)}
            {filters.missingMarkets.map((market) => <Tag key={'missing-' + market} color="orange" closable
              onClose={() => apply({ ...filters, missingMarkets: filters.missingMarkets.filter((item) => item !== market) })}>{marketLabel(market)} 미등록·해제</Tag>)}
            {filters.pendingChangesOnly && <Tag color="gold" closable onClose={() => apply({ ...filters, pendingChangesOnly: false })}>미반영 DB 변경</Tag>}
            {filters.contentAgeDays != null && <Tag color="gold" closable onClose={() => apply({ ...filters, contentAgeDays: null })}>
              {filters.contentAgeField === 'IMAGES' ? '이미지' : filters.contentAgeField === 'DETAIL_HTML' ? '상세 HTML' : '이미지·상세'} {filters.contentAgeDays}일 경과 / 적용 기록 없음
            </Tag>}
            {filters.marketPlusIssue !== 'ALL' && <Tag color="red" closable onClose={() => apply({ ...filters, marketPlusIssue: 'ALL' })}>G마켓·옥션 {MARKETPLUS_ISSUE_OPTIONS.find(o => o.value === filters.marketPlusIssue)?.label}</Tag>}
            {filters.sbCodes.length > 0
            ? <Tag closable onClose={() => apply({ ...filters, sbCodes: [] })}>SB코드 {filters.sbCodes.length}개 · 다른 검색 조건과 함께 적용</Tag>
            : '선택한 조건을 모두 만족하는 상품을 검색합니다.'}</span>
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
