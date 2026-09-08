import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, InputNumber, Segmented, Select } from 'antd';
import { ArrowRightOutlined, ReloadOutlined } from '@ant-design/icons';
import { isAxiosError } from 'axios';
import { supplierBatchApi, type SupplierBatchCreate, type SupplierBatchMarket, type SupplierBatchMode, type SupplierBatchRun } from '../../api/supplierBatchApi';
import { modeLabel, numberText, requestError } from './supplierBatchDisplay';
import { createRequestId } from '../../utils/requestId';

const PENDING_KEY = 'sbshop.supplierBatch.pendingCreate.v1';
const recentKey = (vendor: string) => `sbshop.supplierBatch.recent.${vendor}`;
function recent(vendor: string): SupplierBatchCreate | null {
  try {
    const value = JSON.parse(localStorage.getItem(recentKey(vendor)) ?? 'null');
    return value?.vendor === vendor && ['PRICE_STOCK', 'PRICE', 'STOCK'].includes(value.mode)
      && Array.isArray(value.markets) && ['marginRate', 'couponRate', 'minMarginPrice'].every(key => typeof value[key] === 'number' && Number.isFinite(value[key])) ? value : null;
  } catch { return null; }
}
const initialPending = (): SupplierBatchCreate | null => {
  try {
    const value = JSON.parse(sessionStorage.getItem(PENDING_KEY) ?? 'null');
    return value && typeof value.requestId === 'string' && typeof value.vendor === 'string'
      && ['PRICE_STOCK', 'PRICE', 'STOCK'].includes(value.mode) && Array.isArray(value.markets)
      && ['marginRate', 'couponRate', 'minMarginPrice'].every(key => typeof value[key] === 'number') ? value : null;
  } catch { return null; }
};
const defaultPolicy = (value: { marginRate?: number | null; couponRate?: number | null; minMarginPrice?: number | null }) => ({ marginRate: value.marginRate ?? 10, couponRate: value.couponRate ?? 20, minMarginPrice: value.minMarginPrice ?? 1500 });
const remember = (value: SupplierBatchCreate | null) => {
  try { if (value) sessionStorage.setItem(PENDING_KEY, JSON.stringify(value)); else sessionStorage.removeItem(PENDING_KEY); }
  catch { /* The same request remains recoverable while this page stays open. */ }
};

export function SupplierBatchStart({ onCreated, onCollapse }: { onCreated: (run: SupplierBatchRun) => void; onCollapse?: () => void }) {
  const options = useQuery({ queryKey: ['supplier-batch-options'], queryFn: async ({ signal }) => (await supplierBatchApi.options(signal)).data, retry: false, staleTime: 30_000 });
  const [vendor, setVendor] = useState<string | null>(null);
  const [mode, setMode] = useState<SupplierBatchMode>('PRICE_STOCK');
  const [margin, setMargin] = useState<number | null>(10);
  const [coupon, setCoupon] = useState<number | null>(20);
  const [minimum, setMinimum] = useState<number | null>(1500);
  const [markets, setMarkets] = useState<SupplierBatchMarket[]>(['COUPANG', 'ELEVEN_STREET', 'SMART_STORE', 'CAFE24']);
  const [pending, setPending] = useState<SupplierBatchCreate | null>(initialPending);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conditionSource, setConditionSource] = useState('기본 조건');
  const initializedVendor = useRef<string | null>(null);
  const chosenVendor = vendor ?? options.data?.vendors.find(item => item.vendor === 'IHB')?.vendor ?? options.data?.vendors[0]?.vendor;
  const chosen = options.data?.vendors.find(item => item.vendor === chosenVendor);
  const activeMarkets = markets.filter(market => options.data?.markets.some(item => item.market === market));
  const supported = !!chosen && options.data?.supportedVendors.includes(chosen.vendor);
  const validPolicy = margin != null && margin >= 0 && margin < 100 && coupon != null && coupon >= 0 && coupon <= 100 && minimum != null && minimum >= 0;
  const locked = busy || !!pending;
  const loadConditions = (value: Pick<SupplierBatchCreate, 'mode' | 'markets' | 'marginRate' | 'couponRate' | 'minMarginPrice'>, label: string) => {
    setMode(value.mode); setMarkets(value.markets); setMargin(value.marginRate); setCoupon(value.couponRate); setMinimum(value.minMarginPrice); setConditionSource(label);
  };
  useEffect(() => {
    if (!chosen || initializedVendor.current === chosen.vendor || pending || busy) return;
    initializedVendor.current = chosen.vendor;
    const saved = recent(chosen.vendor);
    loadConditions(saved ?? { ...defaultPolicy(chosen.defaults), mode: 'PRICE_STOCK', markets: options.data?.markets.map(item => item.market) ?? [] }, saved ? '최근 실행 조건 자동 불러옴' : '소싱처 기본 조건');
  }, [chosen, options.data, pending, busy]);

  const submit = async (request: SupplierBatchCreate) => {
    setBusy(true); setError(null); setPending(request); remember(request);
    try {
      const run = (await supplierBatchApi.create(request)).data;
      try { localStorage.setItem(recentKey(request.vendor), JSON.stringify(request)); } catch { /* Saving preferences is optional. */ }
      setPending(null); remember(null); onCreated(run);
      void options.refetch();
    } catch (failure) {
      if (isAxiosError(failure) && [400, 409, 422].includes(failure.response?.status ?? 0)) {
        setPending(null); remember(null);
        setError(requestError(failure, failure.response?.status === 409 ? '새 배치가 접수되지 않았습니다. 아래 실행 기록에서 진행 중인 배치를 확인하거나 조건을 변경하세요.' : '실행 조건을 확인하고 다시 시작하세요.'));
      } else setError(requestError(failure, '배치 시작 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인하면 중복 배치를 만들지 않습니다.'));
    } finally { setBusy(false); }
  };

  return <section className="sb-batch-start" aria-labelledby="supplier-batch-start-title">
    <div className="sb-batch-section-title"><h2 id="supplier-batch-start-title">새 배치 실행</h2><span>조건을 정하면 수집부터 마켓 반영까지 자동으로 진행합니다.</span>{onCollapse && <Button size="small" disabled={locked} onClick={onCollapse} aria-expanded={true} aria-controls="supplier-batch-start-form">실행 조건 접기</Button>}</div>
    {options.isError && <Alert type="error" showIcon message="소싱처와 상품 수를 조회하지 못했습니다."
      action={<Button size="small" icon={<ReloadOutlined />} onClick={() => { void options.refetch(); }}>다시 조회</Button>} />}
    <div className="sb-batch-start-primary">
      <label className="sb-batch-field"><span>소싱처</span><Select aria-label="배치 소싱처" value={chosenVendor} disabled={locked || options.isError} loading={options.isPending}
        placeholder={options.isPending ? '소싱처 조회 중…' : '소싱처 선택'} options={options.data?.vendors.map(item => ({ value: item.vendor, label: `${item.label} · ${item.vendor}` }))} onChange={setVendor} /></label>
      <div className="sb-batch-field"><span>대상</span><div className="sb-batch-target" role="status">{options.isError ? '대상 확인 불가' : chosen ? <>전체 상품 <strong>{numberText(chosen.productCount)}개</strong></> : options.isPending ? '상품 수 조회 중…' : '소싱처를 선택하세요'}</div></div>
      <div className="sb-batch-field"><span id="supplier-batch-mode-label">업데이트 항목</span><Segmented block aria-labelledby="supplier-batch-mode-label" value={mode} disabled={locked}
        options={Object.entries(modeLabel).map(([value, label]) => ({ value, label }))} onChange={value => { setMode(value as SupplierBatchMode); setConditionSource('직접 조정한 조건'); }} /></div>
    </div>
    <div className={`sb-batch-policy ${mode === 'STOCK' ? 'sb-batch-policy-idle' : ''}`}>
      <label className="sb-batch-field"><span>목표 마진율</span><InputNumber aria-label="배치 목표 마진율" value={margin} min={0} max={99.99} precision={2} addonAfter="%" disabled={locked || mode === 'STOCK'} onChange={value => { setMargin(value); setConditionSource('직접 조정한 조건'); }} /></label>
      <label className="sb-batch-field"><span>소싱처 쿠폰 할인율</span><InputNumber aria-label="배치 소싱처 쿠폰 할인율" value={coupon} min={0} max={100} precision={2} addonAfter="%" disabled={locked || mode === 'STOCK'} onChange={value => { setCoupon(value); setConditionSource('직접 조정한 조건'); }} /><small>소싱처에서 구매할 때 받는 할인</small></label>
      <label className="sb-batch-field"><span>최소 마진</span><InputNumber aria-label="배치 최소 마진" value={minimum} min={0} precision={0} step={100} addonAfter="원" disabled={locked || mode === 'STOCK'} onChange={value => { setMinimum(value); setConditionSource('직접 조정한 조건'); }} /></label>
    </div>
    <div className="sb-batch-condition-source"><span>{conditionSource}</span><Button type="link" size="small" disabled={locked || !chosen || !recent(chosen.vendor)} onClick={() => { const saved = chosen && recent(chosen.vendor); if (saved) loadConditions(saved, '최근 실행 조건 불러옴'); }}>최근 조건 불러오기</Button><Button type="link" size="small" disabled={locked || !chosen} onClick={() => { if (chosen) loadConditions({ ...defaultPolicy(chosen.defaults), mode: 'PRICE_STOCK', markets: options.data?.markets.map(item => item.market) ?? [] }, '소싱처 기본 조건'); }}>기본값</Button></div>
    <div className="sb-batch-calculation-flow">{mode === 'STOCK' ? <span>재고만 선택하면 가격 조건은 적용하지 않습니다.</span> : <><span>구매가에 쿠폰 할인 적용</span><ArrowRightOutlined /><span>마켓별 수수료 반영</span><ArrowRightOutlined /><span>판매가 계산</span></>}</div>
    <p className="sb-batch-scope">영구 판매금지는 재개하지 않으며, 확인되지 않은 판매 재개는 보류합니다. 마켓별 지원 조건과 실제 재조회 결과를 표시합니다.</p>
    {mode === 'PRICE_STOCK' && <p className="sb-batch-scope">가격·재고가 모두 수집되어야 저장합니다. 하나라도 실패하면 해당 상품 전체를 실패 처리하고, 재시도할 때 함께 다시 수집합니다.</p>}
    <div className="sb-batch-market-select"><strong>연결된 마켓</strong><Checkbox.Group aria-label="배치 반영 마켓" value={activeMarkets} disabled={locked || options.isError}
      options={options.data?.markets.map(item => ({ value: item.market, label: item.label }))} onChange={value => { setMarkets(value as SupplierBatchMarket[]); setConditionSource('직접 조정한 조건'); }} /></div>
    {chosen?.vendor === 'IHB' && <p className="sb-batch-help" role="note">아이허브 할인 제외 상품은 입력 쿠폰율 대신 0%로 계산합니다. 상품 상세에서 실제 적용률과 사유를 확인할 수 있습니다.</p>}
    {activeMarkets.includes('ELEVEN_STREET') && <p className="sb-batch-market-note" role="note">11번가는 지원 조건을 충족한 상품의 가격·수량을 반영하고, 재조회로 확인해야 성공으로 표시합니다.{mode !== 'STOCK' && <> 가격 인상 시 11번가 쿠폰·수수료 혜택이 종료될 수 있으며, 설정 판매가로 자동 반영합니다. 소싱처 쿠폰과는 별개입니다.</>}</p>}
    {chosen && !supported && !options.isError && <Alert type="warning" message="이 소싱처의 자동 배치 수집은 아직 지원하지 않습니다." />}
    {error && <Alert type="error" showIcon message={error} closable onClose={() => setError(null)} />}
    {pending && !busy && <Alert type="warning" showIcon message="직전 시작 요청 확인이 필요합니다."
      description={`${pending.vendor} · ${modeLabel[pending.mode]} · 마진 ${pending.marginRate}% · 쿠폰 ${pending.couponRate}% · 최소 ${numberText(pending.minMarginPrice)}원`}
      action={<Button onClick={() => { void submit(pending); }}>같은 시작 요청 다시 확인</Button>} />}
    <div className="sb-batch-start-footer"><small>선택한 소싱처의 전체 활성 상품이 대상입니다. 미등록 마켓은 건너뜁니다.</small>
      <Button type="primary" size="large" loading={busy} disabled={locked || !validPolicy || !supported || options.isError || !chosen?.productCount || !activeMarkets.length}
        onClick={() => { if (chosen && margin != null && coupon != null && minimum != null) void submit({ requestId: createRequestId(), vendor: chosen.vendor, mode, marginRate: margin, couponRate: coupon, minMarginPrice: minimum, markets: activeMarkets }); }}>
        {chosen ? `${numberText(chosen.productCount)}개 업데이트 시작` : '전체 업데이트 시작'}
      </Button></div>
  </section>;
}
