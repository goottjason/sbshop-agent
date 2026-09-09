import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError } from 'axios';
import { BatchProductDelete } from '../src/components/batch/BatchProductDelete';
import { apiClient } from '../src/api/axios';
const calls: string[] = [], checks: string[] = [];
let deletes = 0, completed = 0;
apiClient.defaults.adapter = async config => {
  calls.push(`${config.method} ${config.url}`);
  if (config.method === 'get' && config.url?.endsWith('/markets')) return { data: [{ marketType: 'COUPANG' }, { marketType: 'ELEVEN_STREET' }, { marketType: 'CAFE24', marketIdentifiers: { gmarket_goodsNo: '123', auction_goodsNo: 'A123' } }], status: 200, statusText: 'OK', headers: {}, config };
  if (config.method === 'delete' && config.url === '/api/v1/products/123') {
    deletes++;
    if (deletes === 1) throw new AxiosError('partial deletion', 'ERR_BAD_REQUEST', config, undefined, { status: 409, statusText: 'Conflict', config, headers: {}, data: { disposed: false, deleted: ['COUPANG'], skipped: [], failed: { ELEVEN_STREET: '재조회에서 상품 부재를 확인하지 못했습니다.' }, manual: {} } });
    return { data: { disposed: true, deleted: ['COUPANG', 'ELEVEN_STREET'], skipped: [], failed: {}, manual: {} }, status: 200, statusText: 'OK', headers: {}, config };
  }
  throw new Error('Unexpected fixture request');
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><BatchProductDelete productId={123} sbCode="200901WA005" productName="Nature Made Hair Skin Nails" disabled={false} onBusy={() => {}} onDeleted={() => { completed++; }} /></QueryClientProvider>);
const sleep = () => new Promise(r => setTimeout(r, 40));
const text = (s: string) => document.body.innerText.includes(s);
const button = (s: string) => [...document.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.replaceAll(' ', '').includes(s.replaceAll(' ', '')))!;
async function wait(f: () => unknown) { for (let i = 0; i < 200; i++) { if (f()) return; await sleep(); } throw new Error('Fixture timeout'); }
void (async () => {
  let result;
  try {
    await wait(() => button('상품 삭제')); button('상품 삭제').click();
    await wait(() => text('등록 이력:'));
    if (deletes || !button('마켓 삭제 후 SB 폐기').disabled) throw new Error('Deletion before consent');
    if (!text('G마켓') || !text('옥션') || !text('카페24 삭제와 SB 폐기를 보류')) throw new Error('Hidden child marketplaces');
    checks.push('삭제 대상 마켓 확인 및 명시적 확인 전 삭제 요청 없음');
    document.querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    await wait(() => !button('마켓 삭제 후 SB 폐기').disabled); button('마켓 삭제 후 SB 폐기').click();
    await wait(() => text('일부 마켓 삭제 미완료'));
    if (completed || !text('재조회에서 상품 부재') || !text('삭제 확인: 쿠팡')) throw new Error('False success or hidden reason');
    checks.push('409 부분 실패 시 SB 유지·마켓별 사유 표시·완료 콜백 없음');
    button('남은 마켓 삭제 재시도').click(); await wait(() => text('SB 폐기 완료'));
    if (completed !== 1 || deletes !== 2) throw new Error('Invalid retry/completion');
    checks.push('명시적 재시도 후 disposed=true에서만 폐기 완료 표시');
    result = { status: 'passed', checks, calls, fixtureOnly: true };
  } catch (e) { result = { status: 'failed', error: String(e), calls }; }
  const script = document.createElement('script');script.type='application/json';script.id='browser-check-result';script.textContent=JSON.stringify(result);document.body.appendChild(script);
})();
