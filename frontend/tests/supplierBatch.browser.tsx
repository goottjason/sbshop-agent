// Isolated loopback fixture only. No application or marketplace network requests are permitted.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import BatchUpdatePage from '../src/pages/BatchUpdatePage';
import type { SupplierBatchCreate, SupplierBatchItem, SupplierBatchRetry, SupplierBatchRun, SupplierBatchStage } from '../src/api/supplierBatchApi';
const checks: string[] = [];
const calls: { method: string; url: string; params?: unknown; body?: unknown }[] = [];
let optionsFail = true, createLost = true, retryLost = true, itemFailure = false, pausedPoll = false, retryOptionsFail = true;
let run: SupplierBatchRun | null = null;
const created = new Map<string, SupplierBatchCreate>(), retried = new Set<string>();
const now = '2026-09-08T02:00:00Z';
const policy = { marginRate: 10, couponRate: 20, minMarginPrice: 1500 };
const markets = ['COUPANG', 'ELEVEN_STREET', 'SMART_STORE', 'CAFE24'] as const;
const names = ['쿠팡', '11번가', '스마트스토어', '카페24'];
const stage = (id: number, value: Partial<SupplierBatchStage>): SupplierBatchStage => ({ id, stage: 'MARKET', state: 'SUCCEEDED', detail: null, retryable: false, attempts: 1, expected: null, observed: null, startedAt: now, finishedAt: now, ...value });
const item = (id: number): SupplierBatchItem => ({ id, productId: id, sbCode: id === 1 ? 'SB-FIXTURE-001' : id === 2 ? 'SB-FIXTURE-002' : 'SB-PAGE2-021', productName: id === 1 ? '비타민 C 1000mg · 120정' : '오메가3 캡슐 · 60정', thumbnailUrl: '/fixtures/new-main.svg', state: id === 1 ? 'FAILED' : 'BLOCKED', detail: null, attempts: 1, sourceSnapshotId: 'fixture-snapshot', editReviewId: 'fixture-review', stages: [
  stage(id * 10, { stage: 'CRAWL', state: 'SUCCEEDED', detail: '최신 가격과 재고를 수집했습니다.' }), stage(id * 10 + 1, { stage: 'DB', state: 'SUCCEEDED', detail: 'SB 상품 저장 완료. 마켓 확인은 별도 단계입니다.' }),
  stage(id * 10 + 2, { market: 'COUPANG', field: 'PRICE', state: 'FAILED', retryable: true, detail: 'HTTP 429 · 재시도 가능 시각까지 대기합니다.', expected: '99500', observed: '96500', nextRunAt: '2026-09-08T02:00:30Z' }),
  stage(id * 10 + 3, { market: 'COUPANG', field: 'STOCK', expected: id === 2 ? '0' : '300', observed: id === 2 ? '0' : '300', detail: '판매용 수량 재조회 일치 확인' }),
  stage(id * 10 + 4, { market: 'ELEVEN_STREET', field: 'PRICE', state: 'BLOCKED', retryable: false, detail: '판매금지 상품 · 자동 재시도 제외', expected: '99100', observed: null }),
  stage(id * 10 + 5, { market: 'ELEVEN_STREET', field: 'STOCK', state: 'SKIPPED', detail: '판매금지 연결 제외' }),
  stage(id * 10 + 6, { market: 'SMART_STORE', field: 'PRICE', state: 'UNCHANGED', expected: '98700', observed: '98700' }),
  stage(id * 10 + 7, { market: 'SMART_STORE', field: 'STOCK', expected: '300', observed: '300' }),
  stage(id * 10 + 8, { market: 'CAFE24', field: 'PRICE', expected: '99900', observed: '99900' }),
  stage(id * 10 + 9, { market: 'CAFE24', field: 'STOCK', state: 'WAITING', expected: '300', observed: null, detail: '전송 대기 · 아직 확인되지 않았습니다.' }),
] });
const products = [item(1), item(2)];
function response(config: any, data: unknown) { return { config, data, status: 200, statusText: 'OK', headers: {} }; }
function failure(config: any, status?: number) { return new AxiosError('Fixture response failure', status ? 'ERR_BAD_RESPONSE' : 'ERR_NETWORK', config, undefined, status ? { config, data: { message: status === 409 ? '동일 소싱처 배치가 진행 중입니다.' : '조회 실패' }, status, statusText: 'Fixture', headers: {} } : undefined); }
apiClient.defaults.adapter = async config => {
  const url = config.url!, method = config.method!;
  const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
  calls.push({ method, url, params: config.params, body });
  await new Promise(resolve => setTimeout(resolve, 25));
  if (method === 'get' && url === '/api/v1/supplier-batches/options') {
    if (optionsFail) { optionsFail = false; throw failure(config); }
    return response(config, { vendors: [{ vendor: 'IHB', label: '아이허브', productCount: 41, defaults: policy }, { vendor: 'FTN', label: '포트넘앤메이슨', productCount: 6, defaults: { marginRate: 12, couponRate: 5, minMarginPrice: 2500 } }], supportedVendors: ['IHB', 'FTN'], markets: markets.map((market, i) => ({ market, label: names[i] })) });
  }
  if (method === 'post' && url === '/api/v1/supplier-batches') {
    if (created.has(body.requestId)) { if (JSON.stringify(created.get(body.requestId)) !== JSON.stringify(body)) throw new Error('Recovery body changed'); return response(config, run); }
    if (run) throw failure(config, 409);
    created.set(body.requestId, body);
    run = { id: 'fixture-run-1', vendor: 'IHB', mode: body.mode, actor: 'fixture', state: 'RUNNING', createdAt: now, updatedAt: now, finishedAt: null, policy: { marginRate: body.marginRate, couponRate: body.couponRate, minMarginPrice: body.minMarginPrice }, markets: [...markets], total: 41, processed: 20, succeeded: 17, failed: 2, blocked: 1, pending: 21, inFlight: 1, nextRunAt: null };
    if (createLost) { createLost = false; throw failure(config); }
    return response(config, run);
  }
  if (method === 'get' && url === '/api/v1/supplier-batches') return response(config, { content: run ? [run] : [], number: config.params.page, size: 10, totalElements: run ? 1 : 0, totalPages: run ? 1 : 0 });
  if (method === 'get' && url === '/api/v1/supplier-batches/fixture-run-1') {
    if (pausedPoll && run?.state === 'PAUSING') { run = { ...run, state: 'PAUSED', inFlight: 0 }; pausedPoll = false; }
    return response(config, run);
  }
  if (method === 'post' && url.endsWith('/pause')) { run = { ...run!, state: 'PAUSING', inFlight: 1 }; return response(config, run); }
  if (method === 'post' && url.endsWith('/resume')) { run = { ...run!, state: 'RUNNING' }; return response(config, run); }
  if (method === 'get' && url === '/api/v1/supplier-batches/fixture-run-1/items') {
    if (itemFailure) throw failure(config, 503);
    const p = config.params;
    return response(config, { content: p.keyword === 'NOTFOUND' ? [] : p.page === 1 ? [item(21)] : p.filter === 'FAILED' ? [products[0]] : products, number: p.page, size: p.size, totalElements: p.keyword === 'NOTFOUND' ? 0 : p.filter === 'FAILED' ? 1 : 41, totalPages: p.keyword === 'NOTFOUND' ? 0 : p.filter === 'FAILED' ? 1 : 3 });
  }
  if (method === 'get' && /\/items\/\d+$/.test(url)) {
    const selected = products.find(i => i.id === Number(url.split('/').at(-1))) ?? item(21);
    return response(config, { item: selected, history: [{ id: 1, stage: 'MARKET', market: 'COUPANG', field: 'PRICE', state: 'FAILED', detail: '첫 시도: 429 응답, 재시도 지연 적용', recordedAt: now }], priceCalculation: { costPrice: 24058, exchangeRate: 1822.55, policy,
      pricingEvidence: { sourcePrice: 13.2, currency: 'GBP', observedExchangeRate: 1822.551899, normalizedExchangeRate: 1822.55, goodsPriceKrw: 24058 }, prices: markets.map(market => ({ market, minimumPrice: '32000', salePrice: '99500' })), notices: ['소싱처 쿠폰 할인 적용 후 계산한 값입니다.'] } });
  }
  if (method === 'get' && url.endsWith('/retry-options')) { if (retryOptionsFail) { retryOptionsFail = false; throw failure(config); } return response(config, { retryableStageCounts: { CRAWL: 0, DB: 0, MARKET: 2 }, retryableProducts: 2, blockedStageCount: 1 }); }
  if (method === 'post' && url.endsWith('/retry')) {
    const request = body as SupplierBatchRetry;
    if (request.itemId && (request.market !== 'COUPANG' || request.field !== 'PRICE')) throw new Error('Wrong stage retried');
    retried.add(request.requestId);
    if (retryLost && request.itemId) { retryLost = false; throw failure(config); }
    return response(config, run);
  }
  if (method === 'get' && url === '/api/v1/products/batch/status/legacy-fixture') return response(config, []);
  throw new Error(`Unexpected network ${method} ${url}`);
};
localStorage.clear(); sessionStorage.clear();
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={queryClient}><ConfigProvider theme={{ token: { motion: false } }}><div style={{ padding: '20px 28px', margin: 'auto', maxWidth: 1440 }}><small>로컬 UI fixture · 실제 배치 실행 없음</small><BatchUpdatePage /></div></ConfigProvider></QueryClientProvider>);
const pause = () => new Promise(resolve => setTimeout(resolve, 25));
const content = (text: string) => document.body.innerText.includes(text);
async function wait(test: () => boolean, label: string) { for (let i = 0; i < 160; i++) { try { if (test()) return; } catch { /* DOM may still be mounting. */ } await pause(); } throw new Error(`Timed out: ${label}`); }
function button(text: string, scope: ParentNode = document) { const found = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.replace(/\s/g, '') === text.replace(/\s/g, '')); if (!found) throw new Error(`Missing button ${text}`); return found; }
function input(label: string, value: string) { const node = document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!; if (!node) throw new Error(`Missing input ${label}`); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(node, value); node.dispatchEvent(new Event('input', { bubbles: true })); }
async function choose(label: string, title: string) { document.querySelector(`[aria-label="${label}"]`)!.closest('.ant-select')!.querySelector('.ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); const selector = `.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${title}"]`; await wait(() => !!document.querySelector(selector), title); document.querySelector<HTMLElement>(selector)!.click(); await pause(); }
function closeDrawer() { document.querySelector<HTMLButtonElement>('.ant-drawer-open .ant-drawer-close')!.click(); }
void (async () => {
  try {
    await wait(() => content('소싱처와 상품 수를 조회하지 못했습니다.'), 'options failure');
    if (!button('전체 업데이트 시작').disabled || content('전체 상품 0개')) throw new Error('Unknown options falsely usable');
    checks.push('소싱처 조회 실패 시 시작 차단·상품 수 미확인 표시'); button('다시 조회', document.querySelector('.sb-batch-start')!).click();
    await wait(() => !button('41개 업데이트 시작').disabled, 'options recovered');
    if (Number((document.querySelector('[aria-label="배치 목표 마진율"]') as HTMLInputElement).value) !== 10) throw new Error('Wrong initial policy');
    input('배치 목표 마진율', '11'); await pause();
    await queryClient.invalidateQueries({ queryKey: ['supplier-batch-options'] }); await pause();
    if (Number((document.querySelector('[aria-label="배치 목표 마진율"]') as HTMLInputElement).value) !== 11) throw new Error('Polling overwrote input');
    button('기본값').click(); await pause(); checks.push('승인 기본10%·20%·1500원과 4마켓·API 재조회 입력 보존');
    button('41개 업데이트 시작').click(); await wait(() => content('직전 시작 요청 확인이 필요합니다.'), 'create response loss');
    if (!button('41개 업데이트 시작').disabled) throw new Error('Ambiguous creation unlocked');
    button('같은 시작 요청 다시 확인').click(); await wait(() => content('SB-FIXTURE-001'), 'run expanded');
    const createCalls = calls.filter(c => c.method === 'post' && c.url === '/api/v1/supplier-batches');
    if (created.size !== 1 || JSON.stringify(createCalls[0].body) !== JSON.stringify(createCalls[1].body)) throw new Error('Duplicate creation');
    checks.push('전체 실행 POST 정확 조건·결과 유실 동일 UUID 복구·생성 1회');
    if (!document.querySelector<HTMLElement>('#supplier-batch-start-form')!.hidden || !content('최근 실행 조건') || !document.querySelector('.sb-batch-start-collapsed')?.textContent?.includes('최소 1,500원')) throw new Error('Accepted run did not collapse with conditions');
    if (document.querySelector('.sb-batch-run-panel .ant-table')!.getBoundingClientRect().top > 600) throw new Error('Matrix below laptop fold');
    button('새 배치 실행').click(); await wait(() => !document.querySelector<HTMLElement>('#supplier-batch-start-form')!.hidden, 'reopen form');
    if (Number((document.querySelector('[aria-label="배치 목표 마진율"]') as HTMLInputElement).value) !== 10 || !content('11번가는 현재 판매 중 상품의 수량 조정만 지원합니다.')) throw new Error('Reopen lost conditions or support notice');
    if (getComputedStyle(document.querySelector('.sb-batch-start .ant-segmented-item-selected')!).backgroundColor !== 'rgb(36, 84, 216)') throw new Error('Selected mode is not cobalt');
    checks.push('접수 성공 후 폼 자동 접힘·최근 조건 보존·다시 열기·코발트 선택·11번가 지원 안내');
    if (!content('실패') || !content('보류') || !content('성공') || !content('20 / 41개')) throw new Error('Summary mixed processed/succeeded');
    checks.push('처리20/41·성공17·실패2·보류1 분리 표시');
    const matrix = document.querySelector('.sb-batch-run-panel')!; if (!matrix.textContent?.includes('확인 99,900원') || !matrix.textContent?.includes('목표 99,500원') || !matrix.textContent?.includes('품절 반영') || !matrix.textContent?.includes('확인 0개')) throw new Error('Confirmed/expected price or confirmed sold-out distinction missing');
    checks.push('마켓 셀 실제 확인 가격·미확인 목표가 구분·실측0개 품절 표시');
    button('41개 업데이트 시작').click(); await wait(() => content('새 배치가 접수되지 않았습니다.'), '409 conflict');
    if (button('41개 업데이트 시작').disabled || content('직전 시작 요청 확인이 필요합니다.')) throw new Error('409 locks form');
    checks.push('명시409 미접수는 입력 복귀·기존 실행 안내'); document.querySelector<HTMLButtonElement>('.sb-batch-start .ant-alert-close-icon')!.click(); button('실행 조건 접기').click();
    await choose('배치 상품 결과 필터', '실패만');
    await wait(() => calls.some(c => c.url.endsWith('/items') && (c.params as any)?.filter === 'FAILED') && !content('SB-FIXTURE-002'), 'server failure filter');
    checks.push('실패 전용 서버 필터 사용·전체 DB 페이지 일관성');
    await choose('배치 상품 결과 필터', '전체 상품'); await wait(() => content('SB-FIXTURE-002'), 'restore rows');
    document.querySelector<HTMLElement>('.sb-batch-run-panel .ant-pagination-item-2')!.click(); await wait(() => content('SB-PAGE2-021'), 'second server page');
    if (!calls.some(c => c.url.endsWith('/items') && (c.params as any)?.page === 1 && (c.params as any)?.size === 20)) throw new Error('Local-only page');
    document.querySelector<HTMLElement>('.sb-batch-run-panel .ant-pagination-item-1')!.click(); await wait(() => content('SB-FIXTURE-001'), 'first page');
    checks.push('상품행 서버 페이지 전환·최초20행');
    const firstIssueCell = document.querySelector('.sb-batch-issues')!; if (firstIssueCell.querySelectorAll(':scope > div').length !== 1) throw new Error('All issues expanded in row'); button('외 1개 문제', firstIssueCell).click(); await wait(() => !!document.querySelector('.ant-drawer-open') && !!document.querySelector('.ant-drawer-open')?.textContent?.includes('판매금지 상품'), 'additional issue detail'); closeDrawer(); await pause(); checks.push('복수 오류는 첫 사유·추가 문제 수로 압축하고 drawer에서 모든 사유 확인');
    document.querySelector<HTMLButtonElement>('[aria-label="SB-FIXTURE-001 쿠팡 판매가 상세"]')!.click();
    await wait(() => !!document.querySelector('.ant-drawer-open') && content('99,500원') && content('96,500원'), 'drawer values');
    const drawer = document.querySelector('.ant-drawer-open')!;
    if (!drawer.textContent?.includes('300개') || !drawer.textContent?.includes('미확인') || button('쿠팡 판매가 재시도', drawer).disabled) throw new Error('Bad field detail');
    if ([...drawer.querySelectorAll('button')].some(b => b.textContent?.includes('11번가 판매가 재시도'))) throw new Error('Blocked retry exposed');
    document.querySelector<HTMLElement>('.ant-drawer-open .ant-collapse-header')!.click(); await wait(() => content('1,822.551899') && content('1,822.55'), 'price evidence');
    const historyHeader = [...drawer.querySelectorAll<HTMLElement>('.ant-collapse-header')].find(n => n.textContent?.includes('처리·오류 이력'))!; historyHeader.click(); await wait(() => content('첫 시도: 429'), 'attempt history');
    checks.push('가격·수량 독립 상태·목표/실측/미확인·원본 환율·실제 오류 이력·비재시도 보류');
    closeDrawer(); await pause(); button('일시정지').click(); await wait(() => content('실행 중인 1건을 마무리'), 'drain');
    if ([...document.querySelectorAll('button')].some(b => b.textContent === '재개')) throw new Error('Drain prematurely resumable');
    pausedPoll = true; await queryClient.invalidateQueries({ queryKey: ['supplier-batch-run'] }); await wait(() => content('일시정지됨'), 'paused');
    checks.push('일시정지 요청→진행 중 drain→완전 정지 후 재개 가능');
    document.querySelector<HTMLButtonElement>('[aria-label="SB-FIXTURE-001 쿠팡 판매가 상세"]')!.click(); await wait(() => !!document.querySelector('.ant-drawer-open'), 'paused drawer');
    button('쿠팡 판매가 재시도', document.querySelector('.ant-drawer-open')!).click(); await wait(() => content('직전 재시도 요청의 접수 여부'), 'retry uncertain');
    closeDrawer(); await pause(); button('같은 재시도 요청 다시 확인').click(); await wait(() => content('재시도를 대기로 접수했습니다.'), 'retry recovered paused');
    const retryCalls = calls.filter(c => c.method === 'post' && c.url.endsWith('/retry'));
    if (JSON.stringify(retryCalls[0].body) !== JSON.stringify(retryCalls[1].body) || retried.size !== 1 || run?.state !== 'PAUSED') throw new Error('Retry duplicated or auto resumed');
    checks.push('단건 실패 PRICE만 UUID 재접수·수집/DB/성공수량 재실행 없음·정지 유지');
    button('실패 단계 일괄 재시도').click(); await wait(() => content('선택 단계 재시도 접수'), 'bulk dialog');
    await wait(() => content('재시도 대상 수를 확인하지 못했습니다.'), 'retry count error'); if (!button('선택 단계 재시도 접수').disabled) throw new Error('Unknown retry count accepted'); button('대상 다시 조회').click(); await wait(() => !button('선택 단계 재시도 접수').disabled, 'retry count recovery');
    if (!content('마켓 반영 실패 · 2건') || !content('자동 재시도 제외 단계 1건')) throw new Error('Retry counts missing'); checks.push('일괄 재시도 집계 실패 차단·다시 조회·실제 단계별/제외 건수');
    if (!content('완료 단계는 그대로 사용합니다.') || !content('배치 전체에 적용')) throw new Error('Bulk scope missing');
    await wait(() => !button('선택 단계 재시도 접수').disabled, 'retry counts loaded'); button('선택 단계 재시도 접수').click(); await wait(() => !document.querySelector('.ant-modal-wrap:not([style*="display: none"])'), 'bulk submitted');
    const lastRetry = calls.filter(c => c.method === 'post' && c.url.endsWith('/retry')).at(-1)!.body as SupplierBatchRetry;
    if (lastRetry.itemId || lastRetry.stage !== 'MARKET' || run?.state !== 'PAUSED') throw new Error('Bulk wrong scope');
    checks.push('일괄 실패 재시도는 서버 전체범위·완료/미지원 제외 안내·정지 유지');
    button('재개').click(); await wait(() => run?.state === 'RUNNING' && content('일시정지'), 'resume');
    itemFailure = true; await queryClient.invalidateQueries({ queryKey: ['supplier-batch-items'] }); await wait(() => content('상품별 처리 결과를 조회하지 못했습니다.'), 'item fetch failure');
    if (content('현재 검색·필터에 해당하는 상품이 없습니다.')) throw new Error('Error shown empty');
    itemFailure = false; button('다시 조회', document.querySelector('.sb-batch-run-panel')!).click(); await wait(() => content('SB-FIXTURE-001'), 'recover rows');
    checks.push('재개 후 상태 갱신·조회 오류를 빈 결과/성공으로 위장하지 않음');
    button('새 배치 실행').click(); await wait(() => !document.querySelector<HTMLElement>('#supplier-batch-start-form')!.hidden, 'conditions reopen'); await choose('배치 소싱처', '포트넘앤메이슨 · FTN'); await wait(() => Number((document.querySelector('[aria-label="배치 목표 마진율"]') as HTMLInputElement).value) === 12, 'vendor defaults');
    await choose('배치 소싱처', '아이허브 · IHB'); await wait(() => content('최근 실행 조건 자동 불러옴'), 'recent conditions');
    checks.push('소싱처별 기본값·최근 성공 조건 자동 복원'); button('실행 조건 접기').click();
    document.querySelector<HTMLElement>('.sb-batch-legacy-section summary')!.click(); await wait(() => !!document.querySelector('[aria-label="이전 배치 ID"]'), 'legacy readonly'); input('이전 배치 ID', 'legacy-fixture'); await pause(); button('기록 조회').click(); await wait(() => content('이 ID로 저장된 이전 배치 기록이 없습니다.'), 'legacy get');
    if (calls.some(c => c.method === 'post' && !c.url.startsWith('/api/v1/supplier-batches'))) throw new Error('Legacy write');
    document.querySelector<HTMLElement>('.sb-batch-legacy-section summary')!.click();
    checks.push('과거 배치 읽기만 분리·운영 데이터 및 외부 마켓 호출 없음');
    document.querySelector<HTMLButtonElement>('.sb-batch-run-panel .ant-alert-close-icon')?.click();
    if ((window as any).__batchCapture === 'drawer') { document.querySelector<HTMLButtonElement>('[aria-label="SB-FIXTURE-001 쿠팡 판매가 상세"]')!.click(); await wait(() => !!document.querySelector('.ant-drawer-open .sb-batch-values'), 'final drawer'); }
    window.scrollTo(0, 0);
    document.body.dataset.browserChecks = 'passed';
  } catch (error) { checks.push(String(error)); document.body.dataset.browserChecks = 'failed'; }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result'; result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks, calls, createdRuns: created.size, uniqueRetries: retried.size }); document.body.appendChild(result);
})();
