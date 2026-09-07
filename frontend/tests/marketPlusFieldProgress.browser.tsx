// Loopback fixture only. All application API calls are intercepted; HTML is displayed as text.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import { ProductMarketPlusFieldProgress } from '../src/pages/product/ProductMarketPlusFieldProgress';
import type { MarketPlusFieldProgress, MarketPlusFieldStage } from '../src/api/marketPlusFieldProgressApi';

const now = new Date().toISOString();
const checks: string[] = [];
const calls: string[] = [];
let fail = true;
const stage = (code: string, detail: string): MarketPlusFieldStage => ({ code, detail, at: now, expectedValue: null, observedValue: null });
const data: MarketPlusFieldProgress = {
  productId: 1, sbCode: 'SB-검증상품', currentRevision: 9, generatedAt: now, truncated: false,
  fields: [
    { historyId: 12, targetId: 21, revision: 9, currentRevision: true, savedAt: now, market: 'GMARKET', field: 'salePrice',
      beforeValue: '20000', savedValue: '18000', shortened: false,
      cafe24: { ...stage('CAFE24_CONFIRMED', '카페24 본상품 재조회 일치'), expectedValue: '18000', observedValue: '18000' },
      transmission: stage('SUCCESS_OBSERVED', '전송 성공 관측 · 이 DB 버전·필드와 연결 미확인'), finalMarket: stage('UNVERIFIED', '최종 마켓 값을 직접 조회한 근거 없음') },
    { historyId: 12, targetId: 22, revision: 9, currentRevision: true, savedAt: now, market: 'AUCTION', field: 'salePrice',
      beforeValue: '20000', savedValue: '18000', shortened: false, cafe24: stage('CAFE24_CONFIRMED', '카페24 본상품 재조회 일치'),
      transmission: stage('FAILURE_OBSERVED', '전송 실패 · 진열/판매 상태 확인 필요'), finalMarket: stage('UNVERIFIED', '최종 마켓 값 미확인') },
    { historyId: 11, targetId: 20, revision: 8, currentRevision: false, savedAt: now, market: 'AUCTION', field: 'detailHtml',
      beforeValue: '기존 상세', savedValue: '<script>document.body.dataset.executed="true"</script>', shortened: false,
      cafe24: stage('UNSUPPORTED_FIELD', '해당 필드 재조회 기록 없음'), transmission: stage('CONFLICT_OBSERVED', '같은 분에 성공·실패 관측'),
      finalMarket: stage('UNVERIFIED', '현재 버전의 확인 근거로 사용하지 않음') },
  ],
  publicValues: [], preparations: [{ market: 'AUCTION', sellerAccount: 'fixture-seller', cafe24ProductNo: '10186', cafe24ProductCode: 'P0000PBU',
    externalId: 'D000000001', connectionState: 'LINKED', automaticRetryAllowed: false,
    blockers: ['선택 필드·계정·상품 범위를 확인해야 합니다.'], historyUrl: 'https://mp.cafe24.com/mp/queue/productList', publicProductUrl: null }],
  notices: ['전송 이력 성공을 최종값 일치로 사용하지 않습니다.'],
};
apiClient.defaults.adapter = async config => {
  calls.push(`${config.method} ${config.url}`);
  if (config.method !== 'get' || config.url !== '/api/v1/products/1/marketplus-field-progress') throw new Error('Unexpected mutation or route');
  await new Promise(resolve => setTimeout(resolve, 30));
  if (fail) { fail = false; throw new AxiosError('fixture read failed', 'ERR_NETWORK', config); }
  return { config, data, headers: {}, status: 200, statusText: 'OK' };
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })}>
  <ConfigProvider theme={{ token: { motion: false } }}><main style={{ maxWidth: 1280, margin: '20px auto' }}><p>로컬 fixture · 운영 API/마켓 쓰기 없음</p><ProductMarketPlusFieldProgress productId={1} /></main></ConfigProvider>
</QueryClientProvider>);
const pause = () => new Promise(resolve => setTimeout(resolve, 30));
async function wait(check: () => boolean) { for (let i = 0; i < 180; i++) { if (check()) return; await pause(); } throw new Error('Timed out'); }
async function select(label: string, choice: string) {
  const input = document.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  input.closest('.ant-select')!.querySelector('.ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  const selector = `.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${choice}"]`;
  await wait(() => !!document.querySelector(selector));
  document.querySelector<HTMLElement>(selector)!.click(); await pause();
}
void (async () => {
  try {
    await wait(() => document.body.innerText.includes('단계를 조회하지 못했습니다'));
    [...document.querySelectorAll<HTMLButtonElement>('button')].find(e => e.textContent === '반영 단계 새로고침')!.click();
    await wait(() => document.querySelectorAll('tbody tr').length === 3);
    checks.push('조회 실패 표시와 안전한 GET 재시도');
    if (!document.body.innerText.includes('전송 성공 관측') || !document.body.innerText.includes('최종 마켓 값을 직접 조회한 근거 없음')) throw new Error('Transfer and final values conflated');
    if (!document.body.innerText.includes('진열/판매 상태 확인 필요') || !document.body.innerText.includes('같은 분에 성공·실패')) throw new Error('Failure details hidden');
    checks.push('카페24 확인·MP 성공/실패/충돌·최종 미확인 분리');
    if (!document.body.innerText.includes('과거 DB 버전') || document.body.dataset.executed || document.querySelector('iframe')) throw new Error('Historical or HTML safety failed');
    checks.push('과거 revision 경고와 HTML 원문 실행 방지');
    await select('반영 단계 마켓', '옥션');
    await wait(() => document.querySelectorAll('tbody tr').length === 2);
    await select('반영 단계 필드', '상세 HTML');
    await wait(() => document.querySelectorAll('tbody tr').length === 1);
    checks.push('마켓·변경 필드 조합 필터');
    await select('반영 단계 마켓', '두 마켓 모두'); await select('반영 단계 필드', '모든 변경 필드');
    document.querySelector<HTMLDetailsElement>('.mpfp-preparation')!.open = true;
    const retry = [...document.querySelectorAll<HTMLButtonElement>('button')].find(e => e.textContent === '자동 재전송 준비 중');
    if (!retry?.disabled || calls.some(call => !call.startsWith('get '))) throw new Error('Unverified retry was allowed');
    checks.push('계정·상품 준비 정보와 미검증 자동 재전송 차단');
    document.body.dataset.browserChecks = 'passed';
  } catch (e) { document.body.dataset.browserChecks = 'failed'; checks.push(String(e)); }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result';
  result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks }); document.body.appendChild(result);
})();
