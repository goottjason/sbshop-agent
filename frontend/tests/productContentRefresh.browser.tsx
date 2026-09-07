// Local Chrome fixture: no production or external-market requests are made.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App, ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import type { ProductContentSnapshot } from '../src/api/productContentApi';
import ProductGrid from '../src/pages/product/ProductGrid';

const calls: { url: string; method: string; data: unknown }[] = [];
const now = new Date().toISOString();
const later = new Date(Date.now() + 60 * 60 * 1000).toISOString();
const asset = (name: string) => `${location.origin}/fixtures/${name}.svg`;
const content = {
  sourceImages: [asset('old-main'), asset('old-detail')], hostedImages: [asset('old-main'), asset('old-detail')],
  detailHtml: `<h2 style="color:#475569">기존 설명 · 구형 라벨</h2><img src="${asset('old-main')}" width="80" height="100"><p>기존 이미지와 상품 설명으로 생성한 상세정보입니다.</p>`,
};
const snapshot = (id: number): ProductContentSnapshot => ({
  id: `snapshot-${id}`, productId: id, sbCode: `SB-검증${id}`, revision: 7, sourceUrl: `${location.origin}/source-product`, vendor: 'IHB',
  state: 'READY', reason: '콘텐츠 수집 완료', requestedAt: now, collectedAt: now, expiresAt: later, appliedAt: null,
  current: content, proposed: {
    sourceImages: [asset('new-main'), asset('new-detail')], hostedImages: [asset('new-main'), asset('new-detail')],
    detailHtml: `<h2 style="color:#166534">최신 설명 · 2개 묶음</h2><img src="${asset('new-main')}" width="80" height="100"><img src="${asset('new-main')}" width="80" height="100"><p>최신 라벨 이미지와 현재 묶음수량을 반영한 자동 생성 상세정보입니다.</p><script>parent.document.body.dataset.unsafeHtml="executed"</script>`,
  },
  fields: ['IMAGES', 'DETAIL_HTML'].map(field => ({ field: field as 'IMAGES' | 'DETAIL_HTML', available: true, editable: true, reason: '연결 없는 상품', collectedAt: now, appliedAt: null })), notices: [],
});
const items = [1, 2, 3, 4, 5].map(snapshot);
items[0].notices = ['수집 설명의 실행 코드와 외부 링크를 제거합니다.', '상품명과 묶음수량은 수집 요청 당시 DB 값을 사용합니다.',
  '대표·추가 이미지도 바꾸려면 이미지 항목을 함께 선택하세요.', '수집 완료 후 DB 적용과 외부 마켓 반영을 별도로 확인하세요.'];
items[1].fields.forEach(field => { field.editable = false; field.reason = '현재 마켓 연결로 수정 잠금'; });
items[2].state = 'PARTIAL'; items[2].reason = '이미지 호스팅 실패 · HTML만 수집'; items[2].fields[0].available = false; items[2].fields[0].collectedAt = null;
items[2].notices = ['추가 이미지 2번 다운로드 실패: HTTP 503 응답'];
items[3].state = 'UNSUPPORTED'; items[3].vendor = 'AMZ'; items[3].reason = '현재 지원하지 않는 소싱처'; items[3].collectedAt = null; items[3].proposed = null;
items[3].expiresAt = null;
items[3].fields.forEach(field => { field.available = false; field.collectedAt = null; });
items[4].expiresAt = new Date(Date.now() - 1000).toISOString();
let lostCollection = true;
let lostCommit = true;
let requestId: string | null = null;
let savedCount = 0;
const reviewId = 'review-fixture';
const checks: string[] = [];
const gridReadPaths = new Set(['/api/v1/products/search', '/api/v1/products/brands', '/api/v1/products/categories',
  '/api/v1/marketplus/transmissions/readiness', '/api/v1/marketplus/observer/status']);
apiClient.defaults.adapter = async config => {
  const url = config.url!;
  const data = config.data ? JSON.parse(config.data) : null;
  calls.push({ url, method: config.method!, data });
  await new Promise(resolve => setTimeout(resolve, 30));
  let response: unknown;
  if (url === '/api/v1/products/search') {
    if (savedCount > 0) document.body.dataset.saved = 'true';
    response = { content: items.map(item => ({
      id: item.productId, sbCode: item.sbCode, brand: '검증 브랜드', productName: `콘텐츠 비교 검증 상품 ${item.productId}`,
      baseName: '검증 상품', originalName: 'Content fixture', vendor: item.vendor, salePrice: 12300, stock: 12, bundleQuantity: 2,
      repImageUrl: item.fields[0].appliedAt ? asset('new-main') : asset('old-main'),
      hostedImages: item.fields[0].appliedAt ? item.proposed?.hostedImages : content.hostedImages, sourcingUrl: item.sourceUrl, memo: '', marketRegistrations: {},
      category: 'SUPPLEMENT', stockStatus: 'IN_STOCK',
    })), totalElements: items.length, totalPages: 1, number: 0 };
  } else if (url === '/api/v1/products/brands') response = ['검증 브랜드'];
  else if (url === '/api/v1/products/categories') response = ['SUPPLEMENT'];
  else if (url === '/api/v1/marketplus/transmissions/readiness') response = { ready: true, reasons: [] };
  else if (url === '/api/v1/marketplus/observer/status') response = {
    state: 'OBSERVED_PARTIAL', message: '로컬 fixture · 운영 연결 없음', collectEnabled: true, uploadEnabled: true,
    heartbeatAt: now, lastCollectedAt: now, lastUploadedAt: now, nextAttemptAt: null, pendingFiles: 0, rejectedFiles: 0, blockedFiles: 0,
  };
  else if (url === '/api/v1/products/content/collections') {
    if (requestId && requestId !== data.requestId) throw new Error('Collection retry changed request ID');
    requestId = data.requestId;
    if (lostCollection) { lostCollection = false; throw new AxiosError('Lost collection response', 'ERR_NETWORK', config); }
    response = { id: 'collection-fixture', createdAt: now, items };
  } else if (url === '/api/v1/products/content/collections/collection-fixture') response = { id: 'collection-fixture', createdAt: now, items };
  else if (url === '/api/v1/products/content/reviews') {
    const chosen = data.items;
    if (JSON.stringify(chosen) !== JSON.stringify([{ snapshotId: 'snapshot-1', fields: ['IMAGES', 'DETAIL_HTML'] }, { snapshotId: 'snapshot-3', fields: ['DETAIL_HTML'] }])) throw new Error('Locked, unavailable or expired field included in review');
    response = { reviewId, expiresAt: later, items: [1, 3].map(id => ({ productId: id, sbCode: `SB-검증${id}`, revision: 7, state: 'READY',
      changes: [{ field: 'detailHtml', before: '<p>기존</p>', after: '<p>새 설명</p>', derived: false }], connections: [], prices: [], reasons: [], notices: [] })) };
  } else if (url === '/api/v1/products/content/commit') {
    if (data.reviewId !== reviewId) throw new Error('Commit retry changed review ID');
    if (lostCommit) { lostCommit = false; throw new AxiosError('Lost save response', 'ERR_NETWORK', config); }
    savedCount += 1;
    for (const id of [1, 3]) {
      items[id - 1].appliedAt = now;
      items[id - 1].fields.filter(field => id === 1 || field.field === 'DETAIL_HTML').forEach(field => { field.appliedAt = now; field.editable = false; });
    }
    response = { reviewId, items: [1, 3].map(id => ({ productId: id, sbCode: `SB-검증${id}`, state: 'SAVED', historyId: id, reason: 'DB 저장 완료 · 마켓 반영 별도' })) };
  } else throw new Error(`Unexpected request: ${config.method} ${url}`);
  return { config, data: response, headers: {}, status: 200, statusText: 'OK' };
};
sessionStorage.removeItem('sbshop.productContent.collection');
// Match the HTTP production origin, which does not provide randomUUID.
Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true });
const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><ConfigProvider theme={{ token: { motion: false } }}><App>
  <aside aria-label="검증 환경" style={{ position: 'fixed', top: 10, right: 14, zIndex: 5000, padding: '5px 9px', borderRadius: 5, fontSize: 11, color: '#fff', background: '#334155' }}>로컬 fixture · 운영 데이터/마켓 요청 없음</aside>
  <main style={{ height: '100vh' }}><ProductGrid /></main>
</App></ConfigProvider></QueryClientProvider>);
const pause = (ms = 40) => new Promise(resolve => setTimeout(resolve, ms));
const wait = async (check: () => boolean, label: string) => {
  for (let i = 0; i < 200; i++) { if (check()) return; await pause(); }
  throw new Error(`Timed out: ${label}`);
};
const button = (label: string) => [...document.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === label);
const click = async (label: string) => {
  await wait(() => !!button(label) && !button(label)!.disabled, `button ${label}`); button(label)!.click(); await pause();
};
async function run() {
  try {
    await wait(() => document.querySelectorAll('tbody input[aria-label$=" 선택"]').length === 5, 'real grid fixture rows');
    const pageSelection = document.querySelector<HTMLInputElement>('input[aria-label="현재 페이지 상품 선택"]')!;
    pageSelection.click();
    await click('이미지·상세 갱신 (5)');
    await wait(() => !!button('선택 5개 상품 최신 내용 수집'), 'grid opens content modal');
    checks.push('실제 ProductGrid 5개 체크박스 선택 → 콘텐츠 갱신 모달 진입');
    await click('선택 5개 상품 최신 내용 수집');
    await wait(() => document.body.innerText.includes('접수 여부가 불확실'), 'lost collection response');
    await click('같은 수집 요청 확인');
    await wait(() => document.querySelectorAll('.pw-content-card').length === 5, 'collected snapshots');
    const collectionCalls = calls.filter(call => call.url.endsWith('/collections'));
    if (collectionCalls.length !== 2 || new Set(collectionCalls.map(call => (call.data as { requestId: string }).requestId)).size !== 1) throw new Error('Request ID not preserved');
    if (!/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(requestId!)) throw new Error('Invalid HTTP-compatible request ID');
    checks.push('수집 응답 유실 시 같은 요청 ID로 복구');
    if (document.querySelectorAll('iframe').length) throw new Error('Closed comparisons loaded HTML');
    checks.push('다건 비교는 펼친 상품만 HTML을 로딩');
    const notes = document.querySelector<HTMLDetailsElement>('.pw-content-card > .pw-content-notices');
    if (!notes || notes.open || !notes.querySelector('summary')?.textContent?.includes('(4)')) throw new Error('Collection notices not compact by default');
    notes.querySelector('summary')!.click();
    await wait(() => notes.open && notes.querySelectorAll('p').length === 4, 'collection notices expandable');
    notes.querySelector('summary')!.click();
    await wait(() => !notes.open, 'collection notices collapsed');
    const failedNotes = document.querySelectorAll('.pw-content-card')[2].querySelector<HTMLDetailsElement>('.pw-content-notices');
    if (!failedNotes?.open || !document.body.innerText.includes('추가 이미지 2번 다운로드 실패: HTTP 503 응답')) throw new Error('Partial collection failure details hidden');
    if (!document.body.innerText.includes('수정 잠금·확인 필요 항목 있음') || !document.body.innerText.includes('이미지 호스팅 실패') || !document.body.innerText.includes('소싱처 미지원')) throw new Error('Incomplete state labels');
    if (document.querySelectorAll('.pw-content-card')[3].textContent?.includes('유효 시간이 지났습니다')) throw new Error('Null expiry mislabelled as expired');
    checks.push('잠금·부분 수집 실패·미지원·만료 상태 구분');
    const first = document.querySelector('.pw-content-card > .pw-content-comparison') as HTMLDetailsElement;
    first.querySelector('summary')!.click();
    await wait(() => document.querySelectorAll('iframe').length === 2, 'comparison HTML');
    await wait(() => [...document.querySelectorAll<HTMLImageElement>('.pw-content-images img')].length === 4
      && [...document.querySelectorAll<HTMLImageElement>('.pw-content-images img')].every(image => image.complete && image.naturalWidth > 0), 'four local comparison images decoded');
    const loadedImages = [...document.querySelectorAll<HTMLImageElement>('.pw-content-images img')].map(image => image.currentSrc);
    if (new Set(loadedImages).size !== 4 || loadedImages.some(url => !url.startsWith(`${location.origin}/fixtures/`))) throw new Error('Missing distinct local images');
    checks.push('로컬 HTTP 구형·최신 대표/추가 이미지 4개 실제 디코딩 확인');
    if ([...document.querySelectorAll('iframe')].some(frame => frame.getAttribute('sandbox') !== '' || !frame.srcdoc.includes('Content-Security-Policy'))) throw new Error('Unsafe preview permissions');
    await pause(100);
    if (document.body.dataset.unsafeHtml) throw new Error('Collected HTML script executed');
    checks.push('수집 HTML 스크립트 차단 및 CSP 확인');
    await click('적용 가능한 이미지'); await click('적용 가능한 상세 HTML');
    await wait(() => document.body.innerText.includes('선택 2개 상품 · 3개 항목'), 'valid selection count');
    checks.push('잠김·미수집·만료 필드 일괄 선택 제외');
    await click('선택 내용 적용 검토'); await click('검토한 2개 상품 저장');
    await wait(() => !!button('같은 변경으로 저장 재시도'), 'lost commit response');
    await click('같은 변경으로 저장 재시도');
    await wait(() => document.body.dataset.saved === 'true', 'save callback');
    if (calls.filter(call => call.url === '/api/v1/products/search').length < 2) throw new Error('Grid not refreshed after content apply');
    await wait(() => !!document.querySelector<HTMLImageElement>('tbody img[src$="new-main.svg"]')?.naturalWidth, 'grid thumbnail reflects saved image');
    checks.push('콘텐츠 DB 적용 결과 후 실제 ProductGrid 조회 갱신');
    if (savedCount !== 1 || calls.filter(call => call.url.endsWith('/content/commit')).length !== 2) throw new Error('Save retry mismatch');
    checks.push('콘텐츠 저장 응답 유실 시 같은 검토 ID로 재시도');
    if (calls.some(call => !call.url.startsWith('/api/v1/products/content/') && !gridReadPaths.has(call.url))) throw new Error('Legacy edit or marketplace endpoint used');
    if (!document.body.innerText.includes('마켓 반영 별도')) throw new Error('Database save confused with market sync');
    checks.push('DB 적용과 외부 마켓 반영을 구분');
    const recovery = JSON.parse(sessionStorage.getItem('sbshop.productContent.collection')!);
    if (recovery.collectionId !== 'collection-fixture' || recovery.request.requestId !== requestId) throw new Error('No recovery state');
    checks.push('창을 다시 열 때 사용할 수집 복구 정보 저장');
    const reviewDialog = [...document.querySelectorAll<HTMLElement>('.ant-modal')].find(dialog => dialog.querySelector('.ant-modal-title')?.textContent?.includes('변경 내용 확인'))!;
    reviewDialog.querySelector<HTMLButtonElement>('.ant-modal-close')!.click();
    await wait(() => !button('검토한 2개 상품 저장'), 'review closed');
    await pause(200);
    const body = document.querySelector<HTMLElement>('.ant-modal-body');
    if (body) body.scrollTop = 340;
    document.body.dataset.browserChecks = 'passed';
  } catch (cause) { document.body.dataset.browserChecks = 'failed'; checks.push(String(cause)); }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result';
  result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks }); document.body.appendChild(result);
}
void run();
