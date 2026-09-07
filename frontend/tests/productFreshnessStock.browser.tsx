// Local HTTP Chrome fixture. Every API call is intercepted; no operating-market write is made.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App, ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import ProductGrid from '../src/pages/product/ProductGrid';

const ago = (days: number) => new Date(Date.now() - days * 86400000).toISOString();
const now = ago(0);
const calls: { url: string; method: string; body: any; params: any }[] = [];
const checks: string[] = [];
let commitLost = true;
let readLost = true;
let committed = false;
let reviewCount = 0;
const rows = ['old-main', 'old-detail', 'new-main', 'new-detail'].map((image, i) => ({
  id: i + 1, sbCode: `SB-WORK-${i + 1}`, brand: '검증 브랜드', productName: `갱신 검증 상품 ${i + 1}`,
  originalName: '', baseName: '검증 상품', vendor: 'IHB', salePrice: 12300, stock: 12, stockStatus: 'IN_STOCK',
  repImageUrl: `${location.origin}/fixtures/${image}.svg`, category: 'SUPPLEMENT', memo: '', hostedImages: [], marketRegistrations: {},
  sourcingUrl: `${location.origin}/product/${i + 1}`, bundleQuantity: 1,
  contentFreshness: { imagesAppliedAt: i === 2 ? null : ago(i === 0 ? 120 : 10),
    detailHtmlAppliedAt: i === 2 ? null : ago(i === 3 ? 120 : 10), imagesCollectedAt: now, detailHtmlCollectedAt: now },
}));
const item = (market: string) => ({ id: market === 'COUPANG' ? 41 : 42, productId: 1, sbCode: 'SB-WORK-1', market,
  listingId: 'fixture-listing', revision: 9, expectedQuantity: 300, observedQuantity: market === 'COUPANG' ? 300 : 12,
  state: !committed ? 'READY' : market === 'COUPANG' ? 'CONFIRMED_QUANTITY' : 'BLOCKED',
  detail: market === 'COUPANG' ? '마켓에서 재조회한 수량이 300개입니다.' : '마켓플러스 자동 전달 범위 확인이 필요합니다.',
  writes: 0, reads: committed ? 1 : 0, nextRunAt: null, checkedAt: committed ? now : null });
const review = () => ({ id: 'stock-fixture', actor: 'fixture', createdAt: now, expiresAt: new Date(Date.now() + 1800000).toISOString(),
  committed, total: 2, items: ['COUPANG', 'CAFE24'].map(item) });
apiClient.defaults.adapter = async config => {
  const url = config.url!; const body = config.data ? JSON.parse(config.data) : null;
  calls.push({ url, method: config.method!, body, params: config.params });
  await new Promise(resolve => setTimeout(resolve, 20));
  let data: unknown;
  if (url === '/api/v1/products/search') {
    const selected = body.contentAgeDays ? rows.filter(row => row.id !== 2) : rows;
    data = { content: selected, totalElements: selected.length, totalPages: 1, number: 0 };
  } else if (url === '/api/v1/products/brands') data = ['검증 브랜드'];
  else if (url === '/api/v1/products/categories') data = ['SUPPLEMENT'];
  else if (url === '/api/v1/marketplus/transmissions/readiness') data = { ready: true, reasons: [] };
  else if (url === '/api/v1/marketplus/observer/status') data = { state: 'OBSERVED_PARTIAL', message: 'fixture',
    collectEnabled: true, uploadEnabled: true, heartbeatAt: now, lastCollectedAt: now, lastUploadedAt: now, pendingFiles: 0 };
  else if (url === '/api/v1/market-stock-sync/reviews' && config.method === 'get') data = reviewCount ? [review()] : [];
  else if (url === '/api/v1/market-stock-sync/reviews' && config.method === 'post') { reviewCount++; data = review(); }
  else if (url === '/api/v1/market-stock-sync/reviews/stock-fixture/commit') {
    committed = true;
    if (commitLost) { commitLost = false; throw new AxiosError('Lost commit response', 'ERR_NETWORK', config); }
    data = review();
  } else if (url === '/api/v1/market-stock-sync/reviews/stock-fixture') {
    if (readLost) { readLost = false; throw new AxiosError('Lost status response', 'ERR_NETWORK', config); }
    data = review();
  } else if (url === '/api/v1/market-stock-sync/tasks/41/history') data = [{ id: 1, phase: 'READ', detail: '실제 수량 300개 일치, 전송 없음', recordedAt: now }];
  else throw new Error(`Unexpected request: ${config.method} ${url}`);
  return { config, data, headers: {}, status: 200, statusText: 'OK' };
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><ConfigProvider theme={{ token: { motion: false } }}><App>
  <main style={{ height: '100vh' }}><ProductGrid /></main>
</App></ConfigProvider></QueryClientProvider>);
const pause = () => new Promise(resolve => setTimeout(resolve, 30));
async function wait(check: () => boolean, reason: string) {
  for (let i = 0; i < 180; i++) { if (check()) return; await pause(); }
  throw new Error(`Timed out: ${reason}`);
}
const button = (text: string) => [...document.querySelectorAll<HTMLButtonElement>('button')].find(e => e.textContent?.trim() === text);
async function click(text: string) { await wait(() => !!button(text) && !button(text)!.disabled, text); button(text)!.click(); await pause(); }
async function run() {
  try {
    await wait(() => document.querySelectorAll('tbody tr').length === 4, 'grid rows');
    await wait(() => [...document.querySelectorAll<HTMLImageElement>('tbody img')].every(e => e.complete && e.naturalWidth > 0), 'real images loaded');
    if (calls.find(c => c.url.endsWith('/search'))?.params?.sort !== 'workspacePriority,asc') throw new Error('Default sort absent from request');
    checks.push('기본 정렬을 서버 전체 검색에 전달');
    const freshness = document.querySelector<HTMLButtonElement>('[aria-label="SB-WORK-1 이미지·상세 갱신 이력"]');
    if (!freshness?.title.includes('이미지 수집') || !freshness.title.includes('DB 적용') || !freshness.textContent?.includes('새 수집')) throw new Error('Collection and application times not separated');
    checks.push('수집 성공과 DB 적용 시각을 구분해 표시');
    document.querySelector<HTMLInputElement>('[aria-label="SB-WORK-1 선택"]')!.click();
    await click('이미지·상세 90일 경과 / 적용 기록 없음');
    await wait(() => document.querySelectorAll('tbody tr').length === 3, 'aged filter response');
    const ageRequest = calls.filter(c => c.url.endsWith('/search')).at(-1)!;
    if (ageRequest.body.contentAgeDays !== 90 || ageRequest.body.contentAgeField !== 'ANY') throw new Error('Age filter not sent');
    if (document.querySelector<HTMLInputElement>('[aria-label="SB-WORK-1 선택"]')!.checked) throw new Error('Selection survived changed search');
    checks.push('90일·미적용 빠른 조건 전달 및 이전 선택 초기화');
    const sort = document.querySelector<HTMLInputElement>('input[aria-label="상품 정렬"]')!;
    sort.parentElement!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); await pause();
    await wait(() => [...document.querySelectorAll('.ant-select-item-option')].some(e => e.textContent === '판매가 낮은 순'), 'sort menu');
    ([...document.querySelectorAll('.ant-select-item-option')].find(e => e.textContent === '판매가 낮은 순') as HTMLElement).click();
    await wait(() => calls.filter(c => c.url.endsWith('/search')).at(-1)?.params?.sort === 'priceInfo.salePrice,asc', 'price sort request');
    checks.push('판매가 정렬 전환과 기존 필터 동시 전달');
    document.querySelector<HTMLInputElement>('[aria-label="SB-WORK-1 선택"]')!.click();
    await click('마켓 반영·작업 ▾');
    await wait(() => !![...document.querySelectorAll('[role=menuitem]')].find(e => e.textContent === '판매용 수량 반영·작업'), 'stock menu');
    ([...document.querySelectorAll('[role=menuitem]')].find(e => e.textContent === '판매용 수량 반영·작업') as HTMLElement).click();
    await click('선택 상품 수량 검토');
    await wait(() => !!button('검토한 수량 반영 접수'), 'quantity review');
    const preview = calls.find(c => c.url === '/api/v1/market-stock-sync/reviews' && c.method === 'post')!;
    if (JSON.stringify(preview.body.productIds) !== '[1]' || preview.body.markets.length !== 2) throw new Error('Stock review target mismatch');
    if (!document.body.innerText.includes('300개')) throw new Error('Configured sales quantity not shown');
    checks.push('선택 상품·마켓 수량 검토와 판매용 300개 표시');
    await click('검토한 수량 반영 접수');
    await wait(() => document.body.innerText.includes('접수 결과를 확인하지 못했습니다'), 'lost commit');
    await click('검토한 수량 반영 접수');
    await wait(() => document.body.innerText.includes('작업 결과를 조회하지 못했습니다'), 'lost readback');
    if (document.body.innerText.includes('판매용 수량 일치 확인')) throw new Error('Read failure displayed as confirmed');
    await click('다시 조회');
    await wait(() => document.body.innerText.includes('판매용 수량 일치 확인') && document.body.innerText.includes('전송 보류'), 'partial results');
    if (calls.filter(c => c.url.endsWith('/commit')).length !== 2 || reviewCount !== 1) throw new Error('Retry created another review');
    checks.push('접수 응답 유실 시 같은 검토 재시도·조회 실패에 성공 숨김');
    checks.push('실제 수량 일치와 마켓 전달 보류 사유를 따로 표시');
    await click('0회 · 보기');
    await wait(() => document.body.innerText.includes('실제 수량 300개 일치, 전송 없음'), 'attempt history');
    checks.push('수량 작업 조회·전송 이력 열람');
    const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result';
    result.textContent = JSON.stringify({ status: 'passed', checks }); document.body.appendChild(result);
  } catch (error) {
    const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result';
    result.textContent = JSON.stringify({ status: 'failed', checks, error: String(error) }); document.body.appendChild(result);
  }
}
void run();
