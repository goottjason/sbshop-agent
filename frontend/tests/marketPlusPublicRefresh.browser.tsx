// Local fixture: all API calls are intercepted and unknown mutations are rejected.
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider } from 'antd';
import { AxiosError } from 'axios';
import { apiClient } from '../src/api/axios';
import { ProductMarketPlusPublicRefresh } from '../src/pages/product/ProductMarketPlusPublicRefresh';
import type { PublicCheckCollection, PublicCheckRequest } from '../src/api/marketPlusPublicCheckApi';
const now=new Date().toISOString(), checks:string[]=[], requests:PublicCheckRequest[]=[];
const base='/api/v1/products/marketplus-public-checks/collections';
let lose=true, readFail=false;
const result:PublicCheckCollection={id:'fixture-collection',requestId:'fixture',createdAt:now,items:[
 {id:1,productId:11,sbCode:'SB-정상관측',market:'AUCTION',state:'OBSERVED',reason:'공개 표시값 확인. 목표값 일치·전송 완료·판매 상태 확정은 별도입니다.',attempts:1,nextRunAt:null,checkedAt:now,observationId:31,values:{salePrice:'95500',salesQuantity:'500'},events:['1회 · 표시값 확인']},
 {id:2,productId:11,sbCode:'SB-정상관측',market:'GMARKET',state:'FAILED_UNVERIFIED',reason:'오류 페이지로 판매 계정·가격을 확인할 수 없습니다. 삭제로 판단하지 않습니다.',attempts:3,nextRunAt:null,checkedAt:now,observationId:null,values:{},events:['1회 · 확인 불가','2회 · 확인 불가','3회 · 확인 불가']},
 {id:3,productId:12,sbCode:'SB-429대기',market:'AUCTION',state:'RETRY_WAIT',reason:'실제 HTTP 429 응답: 마켓 공통 대기 후 재조회합니다.',attempts:1,nextRunAt:new Date(Date.now()+60000).toISOString(),checkedAt:now,observationId:null,values:{},events:['1회 · HTTP 429']},
 {id:4,productId:12,sbCode:'SB-429대기',market:'GMARKET',state:'SKIPPED',reason:'연결된 해당 마켓 상품이 없습니다.',attempts:0,nextRunAt:null,checkedAt:null,observationId:null,values:{},events:[]},
]};
apiClient.defaults.adapter=async config=>{
 let data:unknown;await new Promise(resolve=>setTimeout(resolve,25));
 if(config.method==='post'&&config.url===base){requests.push(JSON.parse(String(config.data)) as PublicCheckRequest);if(lose){lose=false;throw new AxiosError('lost response','ERR_NETWORK',config);}data=result;}
 else if(config.method==='get'&&config.url===base)data=[result];
 else if(config.method==='get'&&config.url===base+'/'+result.id){if(readFail){throw new AxiosError('read failed','ERR_NETWORK',config);}data=result;}
 else throw new Error('Unexpected API or marketplace write: '+config.method+' '+config.url);
 return {config,data,headers:{},status:200,statusText:'OK'};
};
const client=new QueryClient({defaultOptions:{queries:{retry:false,refetchOnWindowFocus:false}}});
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><ConfigProvider theme={{token:{motion:false}}}><ProductMarketPlusPublicRefresh productIds={[11,12]} onClose={()=>{}} /></ConfigProvider></QueryClientProvider>);
const pause=()=>new Promise(resolve=>setTimeout(resolve,30));
async function wait(check:()=>boolean){for(let n=0;n<230;n++){if(check())return;await pause();}throw new Error('Timeout: '+document.body.innerText.slice(-300));}
function button(text:string){const b=[...document.querySelectorAll<HTMLButtonElement>('button')].find(e=>e.innerText.replaceAll(' ','')===text.replaceAll(' ',''));if(!b)throw new Error('Missing '+text);return b;}
void(async()=>{try{
 await wait(()=>!!document.querySelector('.mp-public-refresh-controls'));button('선택 상품 공개가격 조회').click();await wait(()=>document.body.innerText.includes('접수 응답이 없더라도'));
 if(!button('선택 상품 공개가격 조회').disabled)throw new Error('New request allowed after unknown response');
 button('같은 요청 다시 확인').click();await wait(()=>document.querySelectorAll('tbody tr').length===4);
 if(requests.length!==2||requests[0].requestId!==requests[1].requestId)throw new Error('Lost response did not reuse request');checks.push('접수 응답 유실 후 같은 requestId로 복구');
 if(!document.body.innerText.includes('95,500원')||!document.body.innerText.includes('무옵션 남은수량 500개')||!document.body.innerText.includes('목표값 일치·전송 완료'))throw new Error('Typed observation or scope missing');checks.push('공개 표시가격·옥션 남은수량과 마켓 전송 완료 분리');
 if(!document.body.innerText.includes('삭제로 판단하지')||!document.body.innerText.includes('실제 HTTP 429')||!document.body.innerText.includes('연결된 해당 마켓'))throw new Error('Partial failures hidden');checks.push('오류페이지 확인 불가·실제429 대기·연결 없는 대상 제외 표시');
 const history=[...document.querySelectorAll<HTMLDetailsElement>('details')].find(e=>e.querySelector('summary')?.innerText==='조회 시도 이력')!;history.open=true;
 if(!document.body.innerText.includes('2회 · 확인 불가'))throw new Error('Attempt reasons missing');checks.push('작업별 과거 실패 사유 유지');
 await wait(()=>client.getQueryState(['mp-public-checks',result.id])?.fetchStatus==='idle');
 readFail=true;await client.invalidateQueries({queryKey:['mp-public-checks',result.id]});await wait(()=>document.body.innerText.includes('이전 결과를 최신 상태로 표시하지 않습니다'));
 if(document.querySelector('tbody'))throw new Error('Stale success shown after failed current read');
 readFail=false;button('상태 다시 확인').click();await wait(()=>document.querySelectorAll('tbody tr').length===4);checks.push('상태 조회 실패 시 이전 성공 숨김과 안전한 GET 복구');
 document.querySelectorAll('tbody tr')[1].querySelector<HTMLButtonElement>('button')!.click();await wait(()=>requests.length===3);
 if(JSON.stringify(requests[2].productIds)!=='[11]'||JSON.stringify(requests[2].markets)!=='["GMARKET"]'||requests[2].requestId===requests[1].requestId)throw new Error('Retry scope expanded');checks.push('실패한 한 상품·마켓만 새 요청으로 재조회');
 await wait(()=>!button('선택 상품 공개가격 조회').disabled);document.body.dataset.browserChecks='passed';
}catch(e){document.body.dataset.browserChecks='failed';checks.push(String(e));}
const el=document.createElement('script');el.type='application/json';el.id='browser-check-result';el.textContent=JSON.stringify({status:document.body.dataset.browserChecks,checks});document.body.appendChild(el);
})();
