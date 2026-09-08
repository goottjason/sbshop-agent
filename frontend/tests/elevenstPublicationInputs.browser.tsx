// Loopback-only input preparation fixture; marketplace product endpoints are forbidden.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import { ElevenstPublicationInputs } from '../src/pages/product/ElevenstPublicationInputs';
import type { ElevenstPublicationContext, ElevenstPublicationSchema } from '../src/api/elevenstPublicationInputsApi';

const checks: string[] = [], calls: string[] = [];
let failGet = true, failReview = false, savedBlob: Blob | null = null, downloaded = 0;
URL.createObjectURL = blob => { savedBlob = blob as Blob; return 'blob:fixture'; };
URL.revokeObjectURL = () => {};
HTMLAnchorElement.prototype.click = function () { if (this.download === 'elevenst-input-1.json') downloaded++; else throw new Error('Unexpected link'); };
const schema: ElevenstPublicationSchema = {
  stage: 'INPUT_ONLY', executable: false, capturedDate: '2026-09-08', productRevision: 8, categoryId: '', accountReference: 'fixture-account',
  limitations: ['대표이미지는 600×600 변환 결과 확인 필요', '등록 실행 계약 준비 중'],
  fields: [
    { name: 'selMthdCd', label: '판매방식', section: '판매자·판매 설정', description: '고정가 판매 범위', options: [{ value: '01', label: '고정가판매' }] },
    { name: 'addrSeqOut', label: '출고지 주소 코드', section: '배송·반품', description: '', options: [] },
    { name: 'addrSeqIn', label: '반품/교환지 주소 코드', section: '배송·반품', description: '', options: [] },
    { name: 'rtngdDlvCst', label: '반품 배송비', section: '배송·반품', description: '10원 단위', options: [] },
  ],
  noticeTypes: [{ code: '891031', label: '가공식품', items: [{ code: '1', label: '제품명' }] }],
  addresses: { addrSeqOut: [{ code: '5', label: '출고지 A', address: 'fixture 출고 주소' }], addrSeqIn: [{ code: '3', label: '반품지 B', address: 'fixture 반품 주소' }] },
  productFields: { sbCode: 'SB123', productName: '점검 상품', brand: '브랜드', referenceSalePrice: 12300, salesQuantity: 300, stockStatus: 'IN_STOCK', images: [] },
};
apiClient.defaults.adapter = async config => {
  calls.push(`${config.method} ${config.url}`);
  await new Promise(resolve => setTimeout(resolve, 15));
  if (config.method === 'get' && config.url === '/api/v1/products/1/publication-inputs' && config.params.market === 'ELEVEN_STREET') {
    if (failGet) { failGet = false; throw new AxiosError('fixture unavailable', 'ERR_NETWORK', config); }
    return { config, status: 200, statusText: 'OK', headers: {}, data: { schema, previousEvidence: [] } };
  }
  if (config.method === 'post' && config.url === '/api/v1/products/1/publication-inputs/elevenst-review') {
    if (failReview) { failReview = false; throw new AxiosError('stale fixture', 'ERR_BAD_REQUEST', config); }
    const body = JSON.parse(config.data), context = body.context as ElevenstPublicationContext, v = context.extraFields.elevenst;
    if (body.productRevision !== 8 || body.accountReference !== 'fixture-account') throw new Error('Wrong revision');
    const issues: string[] = [];
    if (context.categoryId !== '123' || v.sellerClassification !== 'DOMESTIC' || v.selMthdCd !== '01') issues.push('판매자·분류 입력 필요');
    if (v.addrSeqOut !== '5' || v.addrSeqIn !== '3') issues.push('현재 계정 주소 선택 필요');
    if (v.rtngdDlvCst !== '5000') issues.push('반품 배송비 확인 필요');
    if (v.noticeType !== '891031' || context.noticeFields['1'] !== '실제 제품명') issues.push('상품고시 입력 필요');
    return { config, status: 200, statusText: 'OK', headers: {}, data: { stage: 'INPUT_ONLY', executable: false, inputComplete: !issues.length, issues, limitations: schema.limitations, productRevision: 8, context } };
  }
  throw new Error('Forbidden marketplace product request');
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ConfigProvider theme={{ token: { motion: false } }}><ElevenstPublicationInputs productId={1} onClose={() => {}} /></ConfigProvider></QueryClientProvider>);
const pause = () => new Promise(resolve => setTimeout(resolve, 20)), content = (value: string) => document.body.innerText.includes(value);
async function wait(check: () => boolean, label: string) { for (let i = 0; i < 170; i++) { if (check()) return; await pause(); } throw new Error(`Timed out ${label}`); }
function button(text: string) { const node = [...document.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.replace(/\s/g, '') === text.replace(/\s/g, '')); if (!node) throw new Error(`Missing ${text}`); return node; }
function input(label: string, value: string) { const node = document.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[aria-label="${label}"]`); if (!node) throw new Error(`Missing ${label}`); Object.getOwnPropertyDescriptor(node.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value')!.set!.call(node, value); node.dispatchEvent(new Event('input', { bubbles: true })); }
async function choose(label: string, title: string) {
  document.querySelector(`[aria-label="${label}"]`)!.closest('.ant-select')!.querySelector('.ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  const selector = `.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${title}"]`;
  await wait(() => !!document.querySelector(selector), title); document.querySelector<HTMLElement>(selector)!.click(); await pause();
}
void (async () => {
  try {
    await wait(() => content('입력 자료·계정 주소를 조회하지 못했습니다.'), 'initial error'); button('다시 조회').click();
    await wait(() => content('SB123') && !button('현재 입력 점검').disabled, 'schema');
    if (!content('상품은 등록되지 않습니다') || !button('점검한 입력 JSON 내보내기').disabled) throw new Error('Execution status or initial export');
    checks.push('조회 실패 복구·입력 준비와 등록 실행 분리');
    button('현재 입력 점검').click(); await wait(() => content('현재 계정 주소 선택 필요'), 'empty check');
    if (!button('점검한 입력 JSON 내보내기').disabled) throw new Error('Incomplete export'); checks.push('빈 입력에는 임의 주소·고시 기본값 없음');
    input('11번가 등록 카테고리 번호', '123'); await pause();
    await choose('11번가 판매자 가입 유형', '일반 국내 셀러'); await choose('11번가 판매방식', '고정가판매');
    await choose('11번가 출고지 주소 코드', '출고지 A · fixture 출고 주소 (코드 5)');
    await choose('11번가 반품/교환지 주소 코드', '반품지 B · fixture 반품 주소 (코드 3)');
    input('11번가 반품 배송비', '5000'); await pause(); await choose('11번가 고시 유형', '가공식품'); input('11번가 상품고시 제품명', '실제 제품명'); await pause();
    button('현재 입력 점검').click(); await wait(() => !button('점검한 입력 JSON 내보내기').disabled, 'complete');
    if (!content('등록 실행은 아직 준비 중')) throw new Error('False ready'); checks.push('주소 실선택·필수입력 점검 완료 후에도 실행불가 표시');
    button('점검한 입력 JSON 내보내기').click(); await wait(() => downloaded === 1 && !!savedBlob, 'export');
    const exported = JSON.parse(await savedBlob!.text());
    if (exported.executable !== false || exported.productRevision !== 8 || exported.context.extraFields.elevenst.addrSeqOut !== '5' || JSON.stringify(exported).includes('fixture 출고 주소')) throw new Error('Invalid export or address exposure');
    checks.push('현재 revision·입력값 JSON 보관·주소 본문 제외');
    input('11번가 반품 배송비', '6000'); await wait(() => button('점검한 입력 JSON 내보내기').disabled, 'invalidated');
    checks.push('입력 변경 즉시 이전 검토·내보내기 무효화');
    button('보관한 입력 불러오기').click(); await wait(() => !!document.querySelector('[aria-label="11번가 입력 JSON"]'), 'import');
    input('11번가 입력 JSON', JSON.stringify({ ...exported, productId: 2 })); await pause(); button('입력값 불러오기').click(); await wait(() => content('같은 SB 상품에서 내보낸'), 'wrong product');
    input('11번가 입력 JSON', JSON.stringify(exported)); await pause(); button('입력값 불러오기').click(); await wait(() => !document.querySelector('[aria-label="11번가 입력 JSON"]') || !content('같은 상품의 입력 JSON 불러오기'), 'import close');
    if (!button('점검한 입력 JSON 내보내기').disabled) throw new Error('Import auto accepted');
    checks.push('다른 상품 JSON 거절·같은 상품 불러오기 후 재점검 필수');
    failReview = true; button('현재 입력 점검').click(); await wait(() => content('입력 점검에 실패'), 'review fail');
    if (!button('점검한 입력 JSON 내보내기').disabled) throw new Error('Failed export');
    button('현재 입력 점검').click(); await wait(() => !button('점검한 입력 JSON 내보내기').disabled, 'recover');
    checks.push('점검 실패에는 결과 없음·GET과 입력점검 API만 호출');
    document.body.dataset.browserChecks = 'passed';
  } catch (e) { document.body.dataset.browserChecks = 'failed'; checks.push(String(e)); }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result'; result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks, calls, downloaded, marketplaceProductWrites: 0 }); document.body.appendChild(result);
})();
