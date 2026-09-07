// Local intercepted fixture: no operational API, product registration or marketplace writes.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { apiClient } from '../src/api/axios';
import { ProductRegistrationJobs } from '../src/pages/product/ProductRegistrationJobs';
const checks: string[] = [], calls: {url: string; method: string; data: unknown}[] = [];
const now = new Date().toISOString();
const task = (market: string) => ({ id: `review-${market}`, productId: 1, sbCode: 'SB-FIXTURE', market, actor: 'admin', state: 'PREVIEW', detail: '등록 검토', name: '검토 상품', categoryId: '100', categoryPath: '식품', price: 12300, quantity: 300, image: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=', listingId: null, expiresAt: now, createdAt: now, checkedAt: null, committed: false, shippingSummary: market === 'CAFE24' ? { '판매 시작': '판매 중지로 생성 → 판매용 수량 확인 → 새 상품 판매·진열 시작' } : { 반품비: '3,000원' } });
const previous = { categoryId: '100', categoryPath: '식품', noticeFields: { 제품명: '검토 상품' }, extraFields: { noticeCategoryName: '식품', attributes: {}, certifications: { NOT_REQUIRED: '' }, documentUrls: {}, documentNotApplicable: {} } };
let recent = [{ ...task('CAFE24'), id: 'uncertain-cafe', state: 'UNKNOWN_CREATE', committed: true, detail: '생성 여부 미확인' }];
apiClient.defaults.adapter = async config => {
  const url = config.url!, data = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
  calls.push({ url, method: config.method!, data }); await new Promise(r => setTimeout(r, 25));
  let result: unknown;
  if (url === '/api/v1/products/registrations') result = recent;
  else if (url.endsWith('/candidates')) result = ['COUPANG', 'CAFE24'].map(market => ({ productId: 1, sbCode: 'SB-FIXTURE', market, oldListingId: '123', connectionState: 'DETACHED_DELETED', reason: '삭제 확인됨. 사유를 확인하세요.', selectable: true }));
  else if (url.endsWith('/publication-inputs')) result = { schema: { categoryId: '100', categoryName: '검토 식품 카테고리', noticeCategories: [{ name: '식품', fields: [{ name: '제품명', required: true }] }], attributes: [], documents: [], certifications: [{ type: 'NOT_REQUIRED', name: '인증대상 아님', required: false, dataType: 'NONE' }], suggestedContext: { categoryId: '100', noticeFields: {}, extraFields: {} } }, previousEvidence: [{ id: 'old-1', label: '같은 상품의 과거 정보', categoryId: '100', context: previous }] };
  else if (url.endsWith('/reviews')) { if (!data.selected.find((p: {market: string; context?: unknown}) => p.market === 'COUPANG')?.context) throw new Error('Coupang context was omitted'); result = { prepared: [task('COUPANG'), task('CAFE24')], excluded: [] }; }
  else if (url.endsWith('/commit')) { const market = url.includes('CAFE24') ? 'CAFE24' : 'COUPANG'; result = { ...task(market), state: 'QUEUED', committed: true }; recent = [...recent, result as typeof recent[number]]; }
  else if (url.endsWith('/recheck')) result = { ...recent[0], state: 'VERIFY', listingId: data.listingId };
  else throw new Error(`Unexpected call ${url}`);
  return { config, status: 200, statusText: 'OK', headers: {}, data: result };
};
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false,refetchOnWindowFocus:false}}})}><ConfigProvider theme={{token:{motion:false}}}><ProductRegistrationJobs productIds={[1]} onClose={() => {}} /></ConfigProvider></QueryClientProvider>);
const pause = () => new Promise(r => setTimeout(r, 30));
async function wait(fn: () => boolean, label: string) {for(let i=0;i<160;i++){if(fn())return;await pause();}throw new Error(label);}
const text = () => [...document.querySelectorAll('.ant-modal-body')].map(n => n.textContent).join(' ');
function button(label: string) {const node=[...document.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent?.includes(label));if(!node)throw new Error(`Button missing ${label}`);return node;}
function checkbox(label: string) {const node=[...document.querySelectorAll('label')].find(n=>n.textContent?.includes(label))?.querySelector<HTMLInputElement>('input[type=checkbox]');if(!node)throw new Error(`Checkbox missing ${label}`);return node;}
async function choose(label: string, title: string) {document.querySelector(`[aria-label="${label}"]`)!.closest('.ant-select')!.querySelector('.ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));const sel=`.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="${title}"]`;await wait(()=>!!document.querySelector(sel),'choice');document.querySelector<HTMLElement>(sel)!.click();await pause();}
void (async()=>{try{
  await wait(()=>text().includes('생성 여부 미확인'),'recent loaded');button('등록 후보 조회').click();await wait(()=>!!document.querySelector('tr[data-row-key="1:COUPANG"]'),'candidates');
  for(const market of ['COUPANG','CAFE24'])document.querySelector<HTMLInputElement>(`tr[data-row-key="1:${market}"] input[type=checkbox]`)!.click();
  await wait(()=>text().includes('검토 식품 카테고리'),'metadata');if(!button('등록 내용 준비').disabled)throw new Error('Missing inputs accepted');if(calls.some(c=>c.url.endsWith('/reviews')))throw new Error('Preparation sent without consent');checks.push('쿠팡 필수 입력 이전 등록 준비 차단');
  await choose('쿠팡 과거 등록 자료','같은 상품의 과거 정보 · 카테고리 100');button('선택한 과거 자료 불러오기').click();await wait(()=>!!document.querySelector('[aria-label="상품고시 제품명"]'),'past applied');checkbox('상품에 맞는 카테고리').click();await wait(()=>!button('등록 내용 준비').disabled,'valid context');button('등록 내용 준비').click();await wait(()=>text().includes('실제로 전송할 등록 내용'),'prepared');
  const request = calls.find(c=>c.url.endsWith('/reviews'))?.data as {selected: {market: string; context?: typeof previous}[]};if(request.selected.find(c=>c.market==='COUPANG')?.context?.noticeFields.제품명!=='검토 상품')throw new Error('Context not transported');if(request.selected.find(c=>c.market==='CAFE24')?.context)throw new Error('CP context leaked to Cafe24');checks.push('명시 선택한 상품별 context만 준비 API 전달');
  if(!text().includes('판매 중지로 생성 → 판매용 수량 확인 → 새 상품 판매·진열 시작'))throw new Error('Cafe activation plan missing');if(calls.some(c=>c.url.endsWith('/commit')))throw new Error('Preparation committed itself');checks.push('카페24 수량·판매 시작 계획과 마켓별 검토 표시');
  await wait(()=>!document.querySelector<HTMLInputElement>('tr[data-row-key="review-CAFE24"] input[type=checkbox]')!.disabled && !checkbox('삭제 사유와 새 상품명').disabled,'preview ready');document.querySelector<HTMLInputElement>('tr[data-row-key="review-CAFE24"] input[type=checkbox]')!.click();checkbox('삭제 사유와 새 상품명').click();await wait(()=>!button('건 등록 접수').disabled,'commit selected');button('건 등록 접수').click();await wait(()=>calls.some(c=>c.url.endsWith('/review-CAFE24/commit')),'cafe commit');if(calls.some(c=>c.url.endsWith('/review-COUPANG/commit')))throw new Error('Unselected CP committed');checks.push('최종 동의 후 선택 마켓만 접수');
  await wait(()=>!button('원상품 번호로 재조회').disabled,'recheck ready');button('원상품 번호로 재조회').click();await wait(()=>!!document.querySelector('[aria-label="생성된 원상품 번호"]'),'recheck opened');const input=document.querySelector<HTMLInputElement>('[aria-label="생성된 원상품 번호"]')!;Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,'789');input.dispatchEvent(new Event('input',{bubbles:true}));await wait(()=>!button('원상품 재조회 접수').disabled,'valid id');button('원상품 재조회 접수').click();await wait(()=>calls.some(c=>c.url.endsWith('/uncertain-cafe/recheck')),'recheck');if(calls.filter(c=>c.url.endsWith('/reviews')).length!==1)throw new Error('Unknown result re-created');checks.push('생성 불명 결과는 상품번호 재조회만 접수');
  const out=document.createElement('script');out.type='application/json';out.id='browser-check-result';out.textContent=JSON.stringify({status:'passed',checks,calls});document.body.append(out);
}catch(error){const out=document.createElement('script');out.type='application/json';out.id='browser-check-result';out.textContent=JSON.stringify({status:'failed',error:String(error),checks,calls});document.body.append(out);}})();
