// Loopback Chrome fixture. Every application API request is intercepted; no operational writes.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import { ProductInspectionJobs } from '../src/pages/product/ProductInspectionJobs';
const now = new Date().toISOString();
const calls: { method: string; url: string }[] = [];
let failDaily = false;
const checks: string[] = [];
apiClient.defaults.adapter = async config => {
  const url = config.url!; calls.push({ method: config.method!, url });
  await new Promise(resolve => setTimeout(resolve, 25));
  let data: unknown;
  if (url.endsWith('/daily/markets')) {
    if (failDaily) throw new AxiosError('fixture failure', 'ERR_NETWORK', config);
    data = ['SMART_STORE', 'COUPANG', 'CAFE24'].map(market => ({ market, status: { enabled: true, accountVerified: market === 'SMART_STORE', schedule: '매일 03:00 (한국시간)', nextDueAt: null,
      detail: market === 'SMART_STORE' ? '확인한 계정으로 조회합니다.' : '과거 계정 귀속은 미확인입니다. 일반 부재 응답만으로 삭제하지 않습니다.', latest: {
        id: `sweep-${market}`, date: '2026-09-08', state: 'ENQUEUED', enrolled: market === 'COUPANG' ? 321 : 12, batchCount: 1, startedAt: now, finishedAt: null,
        totals: { pending: 11, confirmed: 0, detached: 0, needsAttention: market === 'COUPANG' ? 7 : 1, skipped: 0 },
      } } }));
  } else if (url.endsWith('/availability')) data = { supported: true, accountVerified: config.params?.market === 'SMART_STORE', detail: '명시 상태 조회 · 과거 계정 귀속은 별도 확인' };
  else if (url.endsWith('/batches')) data = [{ id: `batch-${url.includes('COUPANG') ? 'COUPANG' : url.includes('CAFE24') ? 'CAFE24' : 'SMART_STORE'}`, market: url.includes('COUPANG') ? 'COUPANG' : url.includes('CAFE24') ? 'CAFE24' : 'SMART_STORE', actor: 'system', source: 'DAILY', createdAt: now, total: 12, pending: 11, confirmed: 0, detached: 0, needsAttention: 1, skipped: 0, items: [] }];
  else if (url === '/api/v1/products/connection-inspections') data = [];
  else throw new Error(`Unexpected call ${config.method} ${url}`);
  return { config, data, headers: {}, status: 200, statusText: 'OK' };
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })}><ConfigProvider theme={{ token: { motion: false } }}><ProductInspectionJobs productIds={[1]} onClose={() => {}} /></ConfigProvider></QueryClientProvider>);
const pause = () => new Promise(resolve => setTimeout(resolve, 40));
async function wait(check: () => boolean, label: string) { for (let i = 0; i < 120; i++) { if (check()) return; await pause(); } throw new Error(label); }
function click(label: string) { const b = [...document.querySelectorAll<HTMLButtonElement>('button')].find(e => e.textContent?.includes(label)); if (!b) throw new Error(`Button missing ${label}`); b.click(); }
const panel = (market: string) => document.querySelector(`[aria-label="${market} 정기 확인 현황"]`);
(async () => {
  try {
    await wait(() => !!panel('스마트스토어'), 'Smartstore schedule not loaded');
    click('쿠팡 · 확인 필요 7');
    await wait(() => !!panel('쿠팡')?.textContent?.includes('321'), 'Coupang cursor not rendered');
    await wait(() => calls.some(c => c.url.includes('/daily/sweep-COUPANG/batches')), 'Coupang batches not separately queried');
    if (panel('쿠팡')?.textContent?.includes('계정 확인 · 보류')) throw new Error('Unconfirmed history incorrectly prevents safe explicit-state reads');
    if (!panel('쿠팡')?.textContent?.includes('부재 응답만으로 삭제하지 않습니다')) throw new Error('Historical account limitation hidden');
    checks.push('Coupang daily cursor and uncertainty are distinct from Smartstore account confirmation');
    click('카페24 · 확인 필요 1');
    await wait(() => !!panel('카페24'), 'Cafe24 schedule not rendered');
    await wait(() => calls.some(c => c.url.includes('/daily/sweep-CAFE24/batches')), 'Cafe24 batches not separately queried');
    checks.push('Cafe24 opens its own daily batches without mixing marketplace results');
    failDaily = true; click('결과 새로고침');
    await wait(() => !!document.querySelector('.ant-modal-body')?.textContent?.includes('정기 확인 현황 조회 실패.'), 'Schedule failure not shown');
    if (panel('카페24')) throw new Error('Old daily success remained visible after read failure');
    checks.push('Refresh failure removes stale daily details');
    failDaily = false; click('결과 새로고침');
    await wait(() => !!panel('카페24'), 'Daily status did not recover');
    if (calls.some(c => c.method !== 'get')) throw new Error('Opening daily status sent a mutation');
    checks.push('All three statuses and recovery use GET only');
    const out = document.createElement('script'); out.type = 'application/json'; out.id = 'browser-check-result'; out.textContent = JSON.stringify({ status: 'passed', checks, calls }); document.body.append(out);
  } catch (error) { const out = document.createElement('script'); out.type = 'application/json'; out.id = 'browser-check-result'; out.textContent = JSON.stringify({ status: 'failed', error: String(error), checks, calls }); document.body.append(out); }
})();
