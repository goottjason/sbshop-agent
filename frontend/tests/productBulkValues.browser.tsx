// Browser fixture only: HTTP is loopback; every application API call is intercepted below.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App, ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import ProductGrid from '../src/pages/product/ProductGrid';

const now = new Date().toISOString();
const token = '00000000-0000-4000-8000-000000000001';
const asset = (name: string) => `${location.origin}/fixtures/${name}.svg`;
const calls: { url: string; method: string; data: unknown }[] = [];
let lostCommit = true, saved = false;
const checks: string[] = [];
const fields = [
  { field: 'brand', label: '브랜드', kind: 'TEXT', maxLength: 100, clearable: true, options: [] },
  { field: 'memo', label: '메모', kind: 'TEXTAREA', maxLength: 2000, clearable: true, options: [] },
  { field: 'barcode', label: '바코드', kind: 'TEXT', maxLength: 100, clearable: true, options: [] },
  { field: 'detailHtml', label: '상세 HTML', kind: 'HTML', maxLength: 1000000, clearable: true, options: [] },
  { field: 'hostedImages', label: '대표·추가 이미지 URL 목록', kind: 'IMAGES', maxLength: 1000, clearable: true, options: [] },
];
const changes = () => [
  { field: 'brand', before: '이전 브랜드', after: '새 브랜드', derived: false },
  { field: 'name', before: '이전 브랜드 검증상품, 25.5그램, 3개', after: '새 브랜드 검증상품, 25.5그램, 3개', derived: true },
  { field: 'memo', before: '이전 메모', after: '검토할 공통 메모', derived: false },
  { field: 'hostedImages', before: JSON.stringify([asset('old-main'), asset('old-detail')]), after: JSON.stringify([asset('new-main'), asset('new-detail')]), derived: false },
  { field: 'detailHtml', before: '<p>기존 설명</p>', after: '<h2>새 상품 설명</h2>', derived: false },
];
apiClient.defaults.adapter = async config => {
  const url = config.url!, data = config.data ? JSON.parse(config.data) : null;
  calls.push({ url, method: config.method!, data });
  await new Promise(resolve => setTimeout(resolve, 30));
  let result: unknown;
  if (url === '/api/v1/products/search') {
    if (saved) document.body.dataset.saved = 'true';
    result = { content: [1, 2].map(id => ({ id, sbCode: `SB-일괄${id}`, brand: id === 1 && saved ? '새 브랜드' : '이전 브랜드', productName: '일괄 편집 검증 상품',
      vendor: 'IHB', salePrice: 12300, stock: 12, bundleQuantity: 3, repImageUrl: asset(id === 1 && saved ? 'new-main' : 'old-main'), hostedImages: [],
      sourcingUrl: `${location.origin}/source-product`, memo: '이전 메모', marketRegistrations: {}, category: 'FOOD', stockStatus: 'IN_STOCK' })), totalElements: 2, totalPages: 1, number: 0 };
  } else if (url === '/api/v1/products/categories') result = ['FOOD'];
  else if (url === '/api/v1/products/brands') result = ['이전 브랜드'];
  else if (url === '/api/v1/marketplus/transmissions/readiness') result = { ready: true, reasons: [] };
  else if (url === '/api/v1/marketplus/observer/status') result = { state: 'OBSERVED_PARTIAL', message: '로컬 fixture', collectEnabled: true, uploadEnabled: true,
    heartbeatAt: now, lastCollectedAt: now, lastUploadedAt: now, nextAttemptAt: null, pendingFiles: 0, rejectedFiles: 0, blockedFiles: 0 };
  else if (url === '/api/v1/products/changes/values-preview/fields') result = fields;
  else if (url === '/api/v1/products/changes/values-preview') {
    const clear = Object.keys(data.values).length === 1 && data.values.memo === '';
    if (!clear && (Object.keys(data.values).sort().join(',') !== 'brand,detailHtml,hostedImages,memo' || data.values.brand !== '새 브랜드'
      || data.values.memo !== '검토할 공통 메모' || JSON.stringify(data.values.hostedImages) !== JSON.stringify([asset('new-main'), asset('new-detail')]))) throw new Error('Unselected or cleared fields leaked into payload');
    result = { reviewId: clear ? '00000000-0000-4000-8000-000000000002' : token, expiresAt: new Date(Date.now() + 1800000).toISOString(), items: [1, 2].map(id => ({
      productId: id, sbCode: `SB-일괄${id}`, revision: 7, state: clear || id === 1 ? 'READY' : 'EXCLUDED',
      changes: clear ? [{ field: 'memo', before: '이전 메모', after: '', derived: false }] : changes(),
      connections: id === 2 ? [{ registrationId: 1, market: 'COUPANG', externalId: 'fixture', state: 'RECORDED', reason: '연결 기록' }] : [],
      prices: [], reasons: clear || id === 1 ? [] : ['연결 마켓의 브랜드·이미지 수정 조건 확인 필요'], notices: [],
    })) };
  } else if (url === '/api/v1/products/changes/commit') {
    if (data.reviewId !== token || Object.keys(data).length !== 1) throw new Error('Not committing the fixed server review');
    if (lostCommit) { lostCommit = false; throw new AxiosError('Fixture lost response', 'ERR_NETWORK', config); }
    saved = true;
    result = { reviewId: token, items: [{ productId: 1, sbCode: 'SB-일괄1', state: 'SAVED', historyId: 10, reason: 'DB 저장 완료 · 마켓 반영 별도' },
      { productId: 2, sbCode: 'SB-일괄2', state: 'EXCLUDED', historyId: null, reason: '연결 마켓의 수정 조건 확인 필요' }] };
  } else throw new Error(`Unexpected API request: ${config.method} ${url}`);
  return { config, data: result, headers: {}, status: 200, statusText: 'OK' };
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })}><ConfigProvider theme={{ token: { motion: false } }}><App>
  <aside style={{ position: 'fixed', top: 10, right: 14, zIndex: 5000, padding: '5px 9px', fontSize: 11, color: 'white', background: '#334155' }}>로컬 fixture · 운영 데이터/마켓 요청 없음</aside>
  <main style={{ height: '100vh' }}><ProductGrid /></main>
</App></ConfigProvider></QueryClientProvider>);
const pause = (ms = 40) => new Promise(resolve => setTimeout(resolve, ms));
async function wait(check: () => boolean, label: string) { for (let i = 0; i < 200; i++) { if (check()) return; await pause(); } throw new Error(`Timed out: ${label}`); }
const button = (label: string) => [...document.querySelectorAll<HTMLButtonElement>('button')].find(node => node.textContent?.trim() === label);
async function click(label: string) { await wait(() => !!button(label) && !button(label)!.disabled, label); button(label)!.click(); await pause(); }
function input(label: string, value: string) {
  const node = document.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[aria-label="${label}"]`)!;
  const proto = node instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(node, value); node.dispatchEvent(new Event('input', { bubbles: true }));
}
async function add(label: string) {
  document.querySelector('.pbv-add .ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  await wait(() => !!document.querySelector(`.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${label}"]`), `add ${label}`);
  (document.querySelector(`.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${label}"]`) as HTMLElement).click();
  await wait(() => [...document.querySelectorAll('.pbv-heading strong')].some(node => node.textContent === label), `field ${label}`);
}
function closeReview() {
  const dialog = [...document.querySelectorAll<HTMLElement>('.ant-modal')].find(node => node.querySelector('.ant-modal-title')?.textContent?.includes('변경 내용 확인'))!;
  dialog.querySelector<HTMLButtonElement>('.ant-modal-close')!.click();
}
(async () => {
  try {
    await wait(() => document.querySelectorAll('tbody tr').length === 2, 'grid');
    document.querySelector<HTMLInputElement>('input[aria-label="현재 페이지 상품 선택"]')!.click();
    await click('일괄 편집 (2) ▾');
    await wait(() => !![...document.querySelectorAll('[role="menuitem"]')].find(node => node.textContent?.includes('기본정보·이미지·상세 편집')), 'bulk menu');
    ([...document.querySelectorAll<HTMLElement>('[role="menuitem"]')].find(node => node.textContent?.includes('기본정보·이미지·상세 편집'))!).click();
    await wait(() => !!document.querySelector('.pbv-add .ant-select-selector'), 'bulk editor');
    checks.push('실제 그리드 선택 → 일괄 편집 메뉴 → 비숫자 편집 진입');
    await add('메모'); await add('바코드');
    if (!button('선택 필드·저장값 검토')?.disabled) throw new Error('Empty selected values were accepted');
    document.querySelector<HTMLButtonElement>('[aria-label="바코드 편집 제외"]')!.click();
    document.querySelector<HTMLInputElement>('.pbv-heading input[type="checkbox"]')!.click();
    await click('선택 필드·저장값 검토');
    await wait(() => !!button('검토한 2개 상품 저장'), 'clear review');
    const clear = calls.find(call => call.url.endsWith('/values-preview'))!.data.values;
    if (Object.keys(clear).join(',') !== 'memo' || clear.memo !== '') throw new Error('Explicit clear not isolated');
    checks.push('빈 입력 검토 차단·명시한 메모만 비우기·선택 제외 필드 보존');
    closeReview(); await wait(() => !button('검토한 2개 상품 저장'), 'close clear review');
    document.querySelector<HTMLInputElement>('.pbv-heading input[type="checkbox"]')!.click(); input('메모 일괄 변경값', '검토할 공통 메모');
    await add('브랜드'); input('브랜드 일괄 변경값', '새 브랜드');
    await add('대표·추가 이미지 URL 목록'); input('대표·추가 이미지 URL 목록 일괄 변경값', `${asset('new-main')}\n${asset('new-detail')}`);
    await add('상세 HTML'); input('상세 HTML 일괄 변경값', `<h2>새 상품 설명</h2><img src="${asset('new-main')}"><script>parent.document.body.dataset.unsafeBulk='executed'</script>`);
    await wait(() => !!document.querySelector('.pbv-preview summary'), 'HTML preview control');
    if (document.querySelector('iframe')) throw new Error('Closed HTML preview loaded iframe');
    (document.querySelector('.pbv-preview summary') as HTMLElement).click();
    await wait(() => !!document.querySelector('iframe'), 'HTML preview');
    const frame = document.querySelector('iframe')!;
    if (frame.getAttribute('sandbox') !== '' || !frame.srcdoc.includes('Content-Security-Policy')) throw new Error('HTML sandbox missing');
    await pause(150); if (document.body.dataset.unsafeBulk) throw new Error('Unsafe HTML executed');
    checks.push('상세 HTML 명시적 미리보기·sandbox/CSP 실행 차단');
    await click('선택 필드·저장값 검토');
    await wait(() => !!button('검토한 1개 상품 저장'), 'mixed review');
    if (!document.body.innerText.includes('파생 변경') || !document.body.innerText.includes('저장 제외')) throw new Error('Derived name or locked product missing');
    checks.push('명시한 4개 필드만 전송·상품명 파생 변경·잠긴 상품 전체 제외 표시');
    await click('검토한 1개 상품 저장'); await click('같은 변경으로 저장 재시도');
    await wait(() => document.body.dataset.saved === 'true', 'grid refresh');
    const commits = calls.filter(call => call.url.endsWith('/changes/commit'));
    if (commits.length !== 2 || commits.some(call => call.data.reviewId !== token)) throw new Error('Review ID changed during retry');
    if (!document.body.innerText.includes('DB 저장 완료 · 마켓 반영 별도')) throw new Error('False market success');
    checks.push('저장 응답 유실 시 동일 검토 재시도·상품별 결과·그리드 갱신');
    if (calls.some(call => /publish|field-sync|crawl|price-stock/.test(call.url))) throw new Error('Unexpected legacy or market write');
    checks.push('직접 DB 수정·마켓 전송 우회 요청 없음');
    document.body.dataset.browserChecks = 'passed';
  } catch (cause) { document.body.dataset.browserChecks = 'failed'; checks.push(String(cause)); }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result';
  result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks }); document.body.appendChild(result);
})();
