// Read-only local fixture: every API call is intercepted and all mutations are rejected.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { apiClient } from '../src/api/axios';
import { ProductStockSync } from '../src/pages/product/ProductStockSync';
import type { StockSyncItem, StockSyncReview } from '../src/api/marketStockSyncApi';
const checks: string[] = [], calls: string[] = [];
const now = new Date().toISOString();
const item = (id: number, expected: number, observed: number | null, sale: string | null, stock: string | null, state: string): StockSyncItem => ({ id, productId: id, sbCode: `SB-STOCK-${id}`, market: 'ELEVEN_STREET', listingId: String(1000 + id), revision: 0, expectedQuantity: expected, observedQuantity: observed, observedSaleState: sale, observedStockState: stock, state,
  detail: state === 'FAILED_MISMATCH' ? '실제 수량이 목표 수량과 다릅니다.' : state === 'BLOCKED' ? '판매금지·강제종료 재개 금지' : state === 'UNKNOWN' ? '판매 상태와 수량을 확인하지 못했습니다.' : '재조회한 수량과 필요한 판매 상태를 확인했습니다.', writes: 0, reads: 1, checkedAt: now, nextRunAt: null });
const review: StockSyncReview = { id: 'stock-state-fixture', actor: 'fixture', createdAt: now, expiresAt: now, committed: true, items: [
  item(1, 0, 997, '105', '01', 'FAILED_MISMATCH'),
  item(2, 0, 0, '104', '02', 'CONFIRMED_QUANTITY'),
  item(3, 0, 0, '105', '01', 'CONFIRMED_QUANTITY'),
  item(4, 300, 300, '103', '01', 'CONFIRMED_QUANTITY'),
  item(5, 300, 300, '107', '01', 'BLOCKED'),
  item(6, 300, 300, '108', '01', 'BLOCKED'),
  item(7, 0, null, null, null, 'UNKNOWN'),
] };
apiClient.defaults.adapter = async config => {
  if (config.method !== 'get') throw new Error('Fixture forbids mutation');
  calls.push(config.url!);
  if (config.url !== '/api/v1/market-stock-sync/reviews' && config.url !== '/api/v1/market-stock-sync/reviews/stock-state-fixture') throw new Error('Unexpected read');
  return { config, status: 200, statusText: 'OK', headers: {}, data: config.url.endsWith('stock-state-fixture') ? review : [review] };
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })}><ConfigProvider theme={{ token: { motion: false } }}><ProductStockSync productIds={[1]} onClose={() => {}} /></ConfigProvider></QueryClientProvider>);
const pause = () => new Promise(resolve => setTimeout(resolve, 25));
async function wait(check: () => boolean, label: string) { for (let i = 0; i < 120; i++) { if (check()) return; await pause(); } throw new Error(`Timed out ${label}`); }
const row = (id: number) => document.querySelector<HTMLTableRowElement>(`tr[data-row-key="${id}:ELEVEN_STREET"]`)!;
void (async () => {
  try {
    await wait(() => !!document.querySelector('.ant-select-selector'), 'history select');
    document.querySelector('.ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await wait(() => !!document.querySelector('.ant-select-item-option'), 'history option'); document.querySelector<HTMLElement>('.ant-select-item-option')!.click();
    await wait(() => !!row(7), 'all observed rows');
    if (row(1).cells[2].textContent !== '997개' || row(1).cells[3].textContent?.includes('품절') || !row(1).cells[3].textContent?.includes('전시중지 (105)')) throw new Error('Display stop changed raw quantity or sold-out meaning');
    checks.push('전시중지105·실제997개를 품절0개로 변환하지 않음');
    if (row(2).cells[2].textContent !== '0개' || !row(2).cells[3].textContent?.includes('품절 (104)') || !row(2).cells[3].textContent?.includes('품절 (02)')) throw new Error('Actual zero/sold-out states missing');
    checks.push('실제0개·판매품절104·재고품절02 원값 표시');
    if (row(3).cells[2].textContent !== '0개' || row(3).cells[3].textContent?.includes('품절') || !row(3).cells[3].textContent?.includes('전시중지 (105)')) throw new Error('105 relabelled 104');
    checks.push('동일0개라도 전시중지105와 품절104 별도 표시');
    if (row(4).cells[2].textContent !== '300개' || !row(4).cells[3].textContent?.includes('판매중 (103)') || !row(4).cells[3].textContent?.includes('사용 (01)')) throw new Error('Positive readback missing');
    checks.push('양수300개·판매중103·사용01 재조회 값 표시');
    if (!row(5).cells[3].textContent?.includes('판매강제종료 (107)') || !row(6).cells[3].textContent?.includes('판매금지 (108)') || !row(5).textContent?.includes('전송 보류') || !row(6).textContent?.includes('전송 보류')) throw new Error('Permanent prohibition obscured');
    checks.push('107강제종료·108판매금지와 전송보류 구분');
    if (row(7).cells[2].textContent !== '미확인' || row(7).cells[3].textContent !== '판매: 미확인재고항목: 미확인') throw new Error('Unknown evidence inferred');
    checks.push('미관측 수량·판매·재고 상태를 각각 미확인 유지');
    if (!document.body.innerText.includes('영구 판매금지·강제종료는 재개하지 않습니다.') || !document.body.innerText.includes('실제 수량 0개와 구매 불가 상태')) throw new Error('Support limits missing');
    checks.push('지원 확대·재조회 성공 조건 안내·GET 전용 검증');
    document.body.dataset.browserChecks = 'passed';
  } catch (error) { checks.push(String(error)); document.body.dataset.browserChecks = 'failed'; }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result'; result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks, calls, fixtureOnly: true, mutations: 0 }); document.body.appendChild(result);
})();
