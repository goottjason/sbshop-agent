// Loopback-only metadata fixture; registration/mutation requests are forbidden.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import { ProductPublicationInputs } from '../src/pages/product/ProductPublicationInputs';
import type { PublicationInputContext, PublicationInputSchema } from '../src/api/productPublicationInputsApi';
const checks: string[] = [], calls: (string | undefined)[] = [];
let latest: PublicationInputContext | null = null, fail = true;
const previous: PublicationInputContext = { categoryId: '100', categoryPath: '식품', noticeFields: { 제품명: '이전 등록의 상품명', 제거된고시: '이 값은 새 메타에 없음' },
  extraFields: { noticeCategoryName: '식품', attributes: { 수량: '2개', '개당 용량': '250ml', 오래된옵션: '무효' }, certifications: { NOT_REQUIRED: '' },
    documentUrls: { '판매 허가서': 'https://fixture.example/permit.pdf' }, documentNotApplicable: { '배터리 시험서': 'true' } } };
function schema(categoryId = '100'): PublicationInputSchema {
  return { categoryId, categoryName: categoryId === '100' ? '추천 식품 카테고리' : '새로 선택한 카테고리',
    noticeCategories: [{ name: '식품', fields: [{ name: '제품명', required: true }, { name: '주의 사항', required: false }] }],
    attributes: [{ name: '수량', required: true, group: 'NONE', exclusiveGroup: false, inputType: 'INPUT', dataType: 'NUMBER', units: ['개'], values: [] },
      { name: '개당 용량', required: true, group: 'A', exclusiveGroup: true, inputType: 'INPUT', dataType: 'NUMBER', units: ['ml'], values: [] },
      { name: '총 용량', required: true, group: 'A', exclusiveGroup: true, inputType: 'INPUT', dataType: 'NUMBER', units: ['ml'], values: [] }],
    certifications: [{ type: 'NOT_REQUIRED', name: '인증대상 아님', required: false, dataType: 'NONE' }, { type: 'CODE_TYPE', name: '제품 안전 인증', required: false, dataType: 'CODE' }],
    documents: [{ name: '배터리 시험서', requirement: 'MANDATORY_BATTERY_UN_TEST', canDeclareNotApplicable: true }, { name: '판매 허가서', requirement: 'MANDATORY', canDeclareNotApplicable: false }],
    suggestedContext: { categoryId, categoryPath: null, noticeFields: {}, extraFields: { attributes: { 수량: '2개', '개당 용량': '250ml' } } } };
}
apiClient.defaults.adapter = async config => {
  if (config.method !== 'get' || config.url !== '/api/v1/products/1/publication-inputs' || config.params.market !== 'COUPANG') throw new Error('Unexpected registration or route');
  calls.push(config.params.categoryId); await new Promise(resolve => setTimeout(resolve, 25));
  if (fail) { fail = false; throw new AxiosError('Metadata unavailable', 'ERR_NETWORK', config); }
  const categoryId = config.params.categoryId || '100';
  return { config, status: 200, statusText: 'OK', headers: {}, data: { schema: schema(categoryId), previousEvidence: categoryId === '100' ? [{ id: 'old-1', label: '과거 같은 상품 자료', categoryId: '100', context: previous }] : [] } };
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })}>
  <ConfigProvider theme={{ token: { motion: false } }}><main style={{ maxWidth: 1200, margin: '20px auto' }}><p>로컬 fixture · 실제 등록 없음</p><ProductPublicationInputs productId={1} market="COUPANG" onChange={value => { latest = value; }} /></main></ConfigProvider>
</QueryClientProvider>);
const pause = () => new Promise(resolve => setTimeout(resolve, 25)), content = (text: string) => document.body.innerText.includes(text);
async function wait(check: () => boolean, label: string) { for (let i = 0; i < 160; i++) { if (check()) return; await pause(); } throw new Error(`Timed out ${label}`); }
function button(text: string) { const b = [...document.querySelectorAll<HTMLButtonElement>('button')].find(e => e.textContent?.replace(/\s/g, '') === text.replace(/\s/g, '')); if (!b) throw new Error(`Missing ${text}`); return b; }
function input(label: string, value: string) { const node = document.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[aria-label="${label}"]`)!;
  Object.getOwnPropertyDescriptor(node.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value')!.set!.call(node, value); node.dispatchEvent(new Event('input', { bubbles: true })); }
function checkbox(label: string) { const node = [...document.querySelectorAll('label')].find(e => e.textContent?.includes(label))?.querySelector<HTMLInputElement>('input[type=checkbox]'); if (!node) throw new Error(`Missing checkbox ${label}`); return node; }
async function choose(label: string, title: string) {
  await wait(() => !document.querySelector(`[aria-label="${label}"]`)?.closest('.ant-select')?.classList.contains('ant-select-disabled'), 'select enabled');
  document.querySelector(`[aria-label="${label}"]`)!.closest('.ant-select')!.querySelector('.ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  const selector = `.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${title}"]`;
  await wait(() => !!document.querySelector(selector), 'option'); document.querySelector<HTMLElement>(selector)!.click(); await pause();
}
void (async () => {
  try {
    await wait(() => content('등록 메타 조회에 실패'), 'initial failure'); if (latest) throw new Error('Failure accepted'); button('메타 다시 조회').click();
    await wait(() => content('추천 식품 카테고리'), 'metadata'); if (latest || checkbox('인증대상 아님').checked || checkbox('상품에 맞는 카테고리').checked) throw new Error('Automatic declaration');
    if ((document.querySelector('[aria-label="구매옵션 수량"]') as HTMLInputElement).value !== '2개') throw new Error('DB suggestion missing');
    checks.push('조회 실패 차단·GET 재시도·DB 옵션만 제안');
    await choose('쿠팡 과거 등록 자료', '과거 같은 상품 자료 · 카테고리 100');
    if (latest || content('이전 등록의 상품명') || checkbox('인증대상 아님').checked) throw new Error('Candidate auto applied');
    checks.push('과거 후보 선택만으로 자동 적용하지 않음'); button('선택한 과거 자료 불러오기').click();
    await wait(() => !!document.querySelector('[aria-label="상품고시 제품명"]'), 'prefill');
    if ((document.querySelector('[aria-label="상품고시 제품명"]') as HTMLInputElement).value !== '이전 등록의 상품명' || latest) throw new Error('Prefill or category consent invalid');
    checkbox('상품에 맞는 카테고리').click(); await wait(() => latest !== null, 'valid past');
    if (latest!.noticeFields.제거된고시 || latest!.extraFields.attributes?.오래된옵션 || latest!.extraFields.certifications?.NOT_REQUIRED !== '') throw new Error('Historical schema filtering failed');
    checks.push('과거 자료 명시 불러오기·현 메타 필터·카테고리 명시 선택');
    const documentChecks = document.querySelectorAll('.ppi-document input[type=checkbox]'); if (documentChecks.length !== 1) throw new Error('Illegal document exemption offered');
    (documentChecks[0] as HTMLInputElement).click(); await wait(() => latest === null, 'required battery');
    input('서류 URL 배터리 시험서', 'http://fixture.example/test.pdf'); await pause(); if (latest) throw new Error('Bad document URL accepted');
    input('서류 URL 배터리 시험서', 'https://fixture.example/test.pdf'); await wait(() => latest !== null, 'document URL');
    checks.push('조건부 비해당만 허용·필수 서류·HTTPS URL 검증');
    checkbox('인증대상 아님').click(); await wait(() => latest === null, 'no certificate'); checkbox('제품 안전 인증').click(); await pause(); if (latest) throw new Error('Missing code accepted');
    input('인증 코드 제품 안전 인증', 'CODE-FIXTURE-123'); await wait(() => latest !== null, 'certificate code');
    checks.push('인증 미선택·코드 누락 차단·명시 코드 전달');
    input('구매옵션 총 용량', '500ml'); await wait(() => latest === null, 'exclusive group'); input('구매옵션 개당 용량', ''); await wait(() => latest !== null, 'single member');
    input('구매옵션 총 용량', '500g'); await wait(() => latest === null, 'wrong unit'); input('구매옵션 총 용량', '500ml'); await wait(() => latest !== null, 'correct unit');
    checks.push('배타 옵션 그룹·숫자 허용 단위 검증');
    fail = true; input('쿠팡 등록 카테고리 번호', '200'); await pause(); button('입력한 카테고리 조회').click();
    await wait(() => content('등록 메타 조회에 실패') && latest === null, 'new metadata fail'); button('메타 다시 조회').click();
    await wait(() => content('새로 선택한 카테고리'), 'changed schema');
    if (latest || checkbox('인증대상 아님').checked || checkbox('제품 안전 인증').checked || document.querySelector('[aria-label="상품고시 제품명"]')) throw new Error('Old category values survived');
    checks.push('카테고리 변경·조회 실패 시 이전 입력 무효화');
    button('추천 메타 다시 조회').click(); await wait(() => content('추천 식품 카테고리'), 'restore suggestion');
    await choose('쿠팡 과거 등록 자료', '과거 같은 상품 자료 · 카테고리 100'); button('선택한 과거 자료 불러오기').click();
    await wait(() => !!document.querySelector('[aria-label="상품고시 제품명"]') && !checkbox('상품에 맞는 카테고리').disabled, 'restored prefill'); checkbox('상품에 맞는 카테고리').click(); await wait(() => latest !== null, 'final ready');
    checks.push('GET 메타만 사용·유효한 context만 부모에 전달'); document.body.dataset.browserChecks = 'passed';
  } catch (e) { document.body.dataset.browserChecks = 'failed'; checks.push(String(e)); }
  const result = document.createElement('script'); result.type = 'application/json'; result.id = 'browser-check-result';
  result.textContent = JSON.stringify({ status: document.body.dataset.browserChecks, checks, metadataQueries: calls, context: latest }); document.body.appendChild(result);
})();
