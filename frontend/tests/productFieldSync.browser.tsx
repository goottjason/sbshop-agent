// Local HTTP fixture. All SB API requests are intercepted, no external writes.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import { ProductFieldSyncModal } from '../src/pages/product/ProductFieldSyncModal';
import type { FieldSyncItem, FieldSyncReview } from '../src/api/marketFieldSyncApi';

const now = new Date().toISOString(), html = '<h2>검토한 상세 내용</h2><script>parent.document.body.dataset.executed="true"</script>';
const checks: string[] = [], calls: { method: string; url: string; body: Record<string, unknown> }[] = [];
let phase = 'PREPARE', latestId = 'fixture', previews = 0, writes = 0;
let failCommit = true, failRead = false, failHistory = true, lostPreview = false;
function item(id: number, productId: number, market: string, state: string): FieldSyncItem {
  return { id, productId, sbCode: `SB-FIXTURE-${productId}`, market, listingId: `LISTING-${id}`, revision: 4, fields: ['detailHtml'],
    expectedValues: state === 'PREPARE' ? {} : { detailHtml: html }, observedValues: state === 'CONFIRMED_FIELDS' ? { detailHtml: html } : {}, state,
    detail: state === 'SKIPPED' ? '카페24 연결이 해제되어 제외됩니다.' : state === 'FAILED_MISMATCH' ? '상세 HTML 재조회 값이 다릅니다.' : '준비·조회 상태입니다.',
    requiresApproval: id === 11, writes, reads: writes ? 2 : 0, nextRunAt: now, checkedAt: writes ? now : null };
}
function review(id = latestId): FieldSyncReview {
  const retry = id !== 'fixture';
  return { id, actor: 'fixture-admin', createdAt: now, expiresAt: new Date(Date.now() + 1800000).toISOString(), committed: !retry && writes > 0,
    preparing: !retry && phase === 'PREPARE', total: retry ? 1 : 3, items: retry ? [item(22, 2, 'CAFE24', 'DRAFT')] :
      [item(11, 1, 'SMART_STORE', phase), item(12, 2, 'CAFE24', phase === 'CONFIRMED_FIELDS' ? 'FAILED_MISMATCH' : phase === 'AWAITING_APPROVAL' ? 'VERIFY' : phase), item(13, 1, 'CAFE24', 'SKIPPED')] };
}
apiClient.defaults.adapter = async config => {
  const url = config.url ?? '', method = config.method ?? '', body = config.data ? JSON.parse(config.data) : {};
  calls.push({ method, url, body }); await new Promise(resolve => setTimeout(resolve, 20)); let data: unknown;
  if (method === 'get' && url === '/api/v1/market-field-sync/reviews') data = previews ? [review(), ...(latestId !== 'fixture' ? [review('fixture')] : [])] : [];
  else if (method === 'post' && url === '/api/v1/market-field-sync/reviews') {
    latestId = ++previews === 1 ? 'fixture' : `retry-${previews}`;
    if (lostPreview) { lostPreview = false; throw new AxiosError('Preview response lost', 'ERR_NETWORK', config); } data = review();
  } else if (method === 'post' && url === '/api/v1/market-field-sync/reviews/fixture/commit') {
    if (body.acceptApproval !== true) throw new Error('Consent absent'); writes = 1; phase = 'CHECK';
    if (failCommit) { failCommit = false; failRead = true; throw new AxiosError('Commit response lost', 'ERR_NETWORK', config); } data = review('fixture');
  } else if (method === 'get' && /^\/api\/v1\/market-field-sync\/reviews\/(fixture|retry-\d+)$/.test(url)) {
    if (failRead) { failRead = false; throw new AxiosError('Read outage', 'ERR_NETWORK', config); } data = review(url.split('/').pop());
  } else if (method === 'get' && url === '/api/v1/market-field-sync/tasks/11/history') {
    if (failHistory) { failHistory = false; throw new AxiosError('History outage', 'ERR_NETWORK', config); }
    data = [{ id: 1, taskId: 11, phase: 'CONFIRMED_FIELDS', detail: '필드와 심사 완료 재조회 확인', recordedAt: now, observedValues: JSON.stringify({ detailHtml: html }) }];
  } else throw new Error(`Unexpected ${method} ${url}`);
  return { config, data, headers: {}, status: 200, statusText: 'OK' };
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })}>
  <ConfigProvider theme={{ token: { motion: false } }}><ProductFieldSyncModal productIds={[1, 2]} onClose={() => undefined} /></ConfigProvider>
</QueryClientProvider>);
const pause = () => new Promise(resolve => setTimeout(resolve, 25)), content = (text: string) => document.body.innerText.includes(text);
async function wait(check: () => boolean, label: string) { for (let i = 0; i < 160; i++) { if (check()) return; await pause(); } throw new Error(`Timed out ${label}`); }
function button(text: string) { const b = [...document.querySelectorAll<HTMLButtonElement>('button')].find(e => e.textContent?.replace(/\s/g, '') === text.replace(/\s/g, '')); if (!b) throw new Error(`Missing ${text}`); return b; }
async function refresh() { button('현재 작업 다시 조회').click(); await pause(); await pause(); }
void (async () => {
  try {
    await wait(() => content('선택 상품 전송값 준비'), 'mount'); button('선택 상품 전송값 준비').click();
    await wait(() => content('마켓별 전송값 준비 중'), 'prepare');
    if (!button('검토한 0건 반영 접수').disabled || calls.some(c => c.url.endsWith('/commit'))) throw new Error('Premature commit');
    checks.push('PREPARE 접수 차단');
    phase = 'DRAFT'; await refresh(); await wait(() => content('수정 심사 요청에 동의합니다'), 'draft');
    if (!button('검토한 2건 반영 접수').disabled || !content('연결이 해제되어')) throw new Error('Consent/skip absent');
    checks.push('전송값·제외 사유·필수 심사 동의');
    const block = document.querySelector<HTMLDetailsElement>('.pfs-html')!; block.open = true; await pause(); const frame = block.querySelector('iframe')!;
    if (frame.getAttribute('sandbox') !== '' || !frame.srcdoc.includes("default-src 'none'") || document.body.dataset.executed) throw new Error('Unsafe HTML');
    checks.push('HTML sandbox/CSP·실행 차단');
    [...document.querySelectorAll('label')].find(e => e.textContent?.includes('수정 심사 요청에 동의합니다'))!.querySelector<HTMLInputElement>('input')!.click(); await pause();
    button('검토한 2건 반영 접수').click(); await wait(() => content('같은 검토 접수 재시도') && content('작업 상태를 조회하지 못했습니다'), 'lost commit');
    if (content('선택 필드 일치 확인')) throw new Error('False success'); button('같은 검토 접수 재시도').click();
    await wait(() => content('접수 후 처리·확인 내역'), 'recovery'); const commits = calls.filter(c => c.url.endsWith('/commit'));
    if (commits.length !== 2 || new Set(commits.map(c => c.url)).size !== 1 || writes !== 1) throw new Error('Not idempotent');
    checks.push('접수 응답 유실·조회 실패·동일 ID 재시도');
    phase = 'AWAITING_APPROVAL'; await refresh(); await wait(() => content('마켓 심사 대기'), 'approval');
    if (calls.filter(c => c.url.endsWith('/commit')).length !== 2) throw new Error('Retransmitted'); checks.push('심사 대기 재전송 없이 조회');
    phase = 'CONFIRMED_FIELDS'; await refresh(); await wait(() => content('선택 필드 일치 1건'), 'partial');
    if (!content('심사 완료 확인') || !content('필드 불일치') || !content('상세 HTML 재조회 값이')) throw new Error('Hidden failure'); checks.push('3건 중 1건만 확인·부분 실패');
    button('이력 · 전송 1회').click(); await wait(() => content('필드 작업 이력 조회 실패'), 'history'); button('다시 조회').click();
    await wait(() => content('필드와 심사 완료 재조회 확인'), 'history retry'); if (document.body.dataset.executed) throw new Error('History executed');
    checks.push('이력 재시도·HTML 원문 안전 표시');
    [...document.querySelectorAll<HTMLButtonElement>('.ant-modal-close')].at(-1)!.click(); await pause();
    const row = [...document.querySelectorAll('tbody tr')].find(e => e.textContent?.includes('필드 불일치'))!;
    [...row.querySelectorAll<HTMLButtonElement>('button')].find(e => e.textContent?.includes('이 상품·마켓 재검토'))!.click();
    await wait(() => latestId === 'retry-2' && content('검토한 1건 반영 접수'), 'retry');
    if (JSON.stringify(calls.filter(c => c.method === 'post' && c.url.endsWith('/reviews')).at(-1)!.body) !== JSON.stringify({ productIds: [2], markets: ['CAFE24'], fields: ['detailHtml'] })) throw new Error('Expanded scope');
    checks.push('재검토는 해당 상품·마켓·필드만');
    await wait(() => !button('선택 상품 전송값 준비').classList.contains('ant-btn-loading'), 'preview ready');
    lostPreview = true; button('선택 상품 전송값 준비').click();
    await wait(() => content('최근 검토를 다시 조회해 생성된 내역을'), 'lost preview');
    if (calls.filter(c => c.method === 'post' && c.url.endsWith('/reviews')).length !== 3) throw new Error('Auto preview retry'); checks.push('검토 응답 유실 복구 안내');
    document.querySelector('[aria-label="최근 필드 검토"]')!.closest('.ant-select')!.querySelector('.ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await wait(() => !!document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option'), 'recent');
    [...document.querySelectorAll<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].find(e => e.textContent?.includes('접수됨'))!.click();
    await wait(() => content('선택 필드 일치 1건'), 'restored'); checks.push('최근 내역에서 기존 작업 복구');
    document.body.dataset.browserChecks = 'passed';
  } catch (e) { document.body.dataset.browserChecks = 'failed'; checks.push(String(e)); }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result';
  result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks, calls }); document.body.appendChild(result);
})();
