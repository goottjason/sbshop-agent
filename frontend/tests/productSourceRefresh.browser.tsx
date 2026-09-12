import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App, ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import type { ProductSourceSnapshot } from '../src/api/productSourceApi';
import { ProductSourceRefreshModal } from '../src/pages/product/ProductSourceRefreshModal';

const calls: { url: string; data: unknown }[] = [];
const now = new Date().toISOString(); const later = new Date(Date.now() + 3600000).toISOString();
const snapshots: ProductSourceSnapshot[] = [1, 2, 3, 4].map(id => ({ id: `s-${id}`, productId: id, sbCode: `SB-${id}`,
  revision: 7, sourceUrl: null, vendor: 'IHB', state: 'READY', reason: '수집 완료 · DB 저장 전', requestedAt: now,
  collectedAt: now, expiresAt: later, appliedAt: null, current: { costPrice: 10000, exchangeRate: 1, stock: 77, stockStatus: 'IN_STOCK' },
  proposed: { costPrice: 12000, exchangeRate: 1, stock: null, stockStatus: 'OUT_OF_STOCK' },
  fields: [{ field: 'PRICE', available: true, editable: true, reason: '', collectedAt: now, appliedAt: null },
    { field: 'STOCK', available: true, editable: true, reason: '', collectedAt: now, appliedAt: null }], notices: [] }));
snapshots[1].state = 'PARTIAL'; snapshots[1].fields[0].available = false; snapshots[1].fields[0].collectedAt = null;
snapshots[1].proposed!.costPrice = null; snapshots[1].notices = ['환율을 확인하지 못했습니다. 가격은 유지합니다.'];
snapshots[2].state = 'UNSUPPORTED'; snapshots[2].vendor = 'AMZ'; snapshots[2].proposed = null; snapshots[2].collectedAt = null;
snapshots[2].expiresAt = null; snapshots[2].fields.forEach(field => { field.available = false; field.collectedAt = null; });
snapshots[3].expiresAt = new Date(Date.now() - 1000).toISOString();
let lostCollection = true, lostCommit = true, saved = false; let requestId: string | undefined;
const checks: string[] = [];
apiClient.defaults.adapter = async config => {
  const url = config.url!; const data = config.data ? JSON.parse(config.data) : null; calls.push({ url, data });
  await new Promise(resolve => setTimeout(resolve, 20)); let response: unknown;
  if (url.endsWith('/source-refresh/collections')) {
    if (requestId && requestId !== data.requestId) throw new Error('Different request ID on retry'); requestId = data.requestId;
    if (lostCollection) { lostCollection = false; throw new AxiosError('lost', 'ERR_NETWORK', config); }
    response = { id: 'collection', createdAt: now, items: snapshots };
  } else if (url.endsWith('/source-refresh/collections/collection')) response = { id: 'collection', createdAt: now, items: snapshots };
  else if (url.endsWith('/source-refresh/reviews')) {
    if (JSON.stringify(data.items) !== JSON.stringify([{ snapshotId: 's-1', fields: ['PRICE', 'STOCK'] }, { snapshotId: 's-2', fields: ['STOCK'] }]))
      throw new Error('Unavailable or expired field entered the review');
    response = { reviewId: 'review', expiresAt: later, items: [1, 2].map(id => ({ productId: id, sbCode: `SB-${id}`, revision: 7,
      state: 'READY', changes: [{ field: 'stockStatus', before: 'IN_STOCK', after: 'OUT_OF_STOCK', derived: false }], connections: [], prices: [], reasons: [], notices: [] })) };
  } else if (url.endsWith('/source-refresh/commit')) {
    if (data.reviewId !== 'review') throw new Error('Different review ID');
    if (lostCommit) { lostCommit = false; throw new AxiosError('lost', 'ERR_NETWORK', config); }
    saved = true; response = { reviewId: 'review', items: [1, 2].map(id => ({ productId: id, sbCode: `SB-${id}`, state: 'SAVED', historyId: id, reason: 'DB 저장 완료 · 마켓 반영 별도' })) };
  } else throw new Error(`Unexpected API: ${url}`);
  return { config, data: response, headers: {}, status: 200, statusText: 'OK' };
};
sessionStorage.removeItem('sbshop.productSource.collection');
const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><ConfigProvider theme={{ token: { motion: false } }}><App>
  <ProductSourceRefreshModal productIds={[1, 2, 3, 4]} onClose={() => {}} onSaved={() => { document.body.dataset.saved = 'true'; }} />
</App></ConfigProvider></QueryClientProvider>);
const pause = (ms = 40) => new Promise(resolve => setTimeout(resolve, ms));
const wait = async (check: () => boolean) => { for (let i = 0; i < 200; i++) { if (check()) return; await pause(); } throw new Error('Timed out'); };
const button = (label: string) => [...document.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.trim() === label);
const click = async (label: string) => { await wait(() => !!button(label) && !button(label)!.disabled); button(label)!.click(); await pause(); };
async function run() {
  try {
    await click('선택 4개 상품 최신 내용 수집'); await wait(() => document.body.innerText.includes('접수 여부가 불확실'));
    await click('같은 수집 요청 확인'); await wait(() => document.querySelectorAll('.pw-content-card').length === 4);
    if (new Set(calls.filter(c => c.url.endsWith('/collections')).map(c => c.data.requestId)).size !== 1) throw new Error('Not idempotent');
    checks.push('수집 응답 유실 후 동일 requestId로 복구');
    const details = document.querySelector<HTMLDetailsElement>('.pw-content-card > .pw-content-comparison')!;
    details.querySelector('summary')!.click(); await pause();
    if (!document.body.innerText.includes('10,000') || !document.body.innerText.includes('12,000') || !document.body.innerText.includes('관측 없음 · 기존값 유지')) throw new Error('No before/after observation');
    checks.push('수집 전후 가격·재고 비교 및 누락 실재고 유지 표시');
    if (!document.body.innerText.includes('환율을 확인하지 못했습니다') || !document.body.innerText.includes('소싱처 미지원')) throw new Error('Missing failure explanation');
    checks.push('부분 실패와 미지원 소싱처 구분');
    await click('적용 가능한 가격'); await click('적용 가능한 재고 상태');
    await wait(() => document.body.innerText.includes('선택 2개 상품 · 3개 항목')); checks.push('미수집·미지원·만료 필드 선택 제외');
    await click('선택 내용 적용 검토'); await click('검토한 2개 상품 저장');
    await wait(() => !!button('같은 변경으로 저장 재시도')); await click('같은 변경으로 저장 재시도');
    await wait(() => document.body.dataset.saved === 'true' && saved); checks.push('공통 검토 UI 저장과 동일 reviewId 재시도');
    if (calls.some(c => !c.url.startsWith('/api/v1/products/source-refresh/')) || !document.body.innerText.includes('마켓 반영 별도')) throw new Error('Legacy path or false market success');
    checks.push('즉시 배치·외부 마켓 API 호출 없이 DB 저장과 마켓 반영 분리');
    document.body.dataset.browserChecks = 'passed';
  } catch (error) { document.body.dataset.browserChecks = 'failed'; checks.push(String(error)); }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result';
  result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks }); document.body.appendChild(result);
}
void run();
