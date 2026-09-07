import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Modal, Spin, Tag } from 'antd';
import { isAxiosError } from 'axios';
import { marketPlusPublicCheckApi as api, type PublicCheckItem, type PublicCheckRequest } from '../../api/marketPlusPublicCheckApi';
import { createRequestId } from '../../utils/requestId';
import './productMarketPlusPublicRefresh.css';
const labels: Record<string,string> = { QUEUED: '조회 대기', RUNNING: '조회 중', RETRY_WAIT: '재조회 대기', OBSERVED: '공개 표시값 확인', FAILED_UNVERIFIED: '확인 불가', STALE: '요청 후 변경됨', SKIPPED: '대상 제외' };
const marketName = (market: string) => market === 'GMARKET' ? 'G마켓' : '옥션';
const active = (item: PublicCheckItem) => ['QUEUED','RUNNING','RETRY_WAIT'].includes(item.state);
const date = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '아직 없음';
const message = (error: unknown) => isAxiosError(error) && typeof error.response?.data?.message === 'string' ? error.response.data.message : '요청 결과를 확인하지 못했습니다. 다시 확인하세요.';

export function ProductMarketPlusPublicRefresh({ productIds, onClose }: { productIds: number[]; onClose: () => void }) {
  const client = useQueryClient();
  const [markets, setMarkets] = useState<string[]>(['GMARKET','AUCTION']);
  const [id, setId] = useState<string | null>(null);
  const [pending, setPending] = useState<PublicCheckRequest | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recent = useQuery({ queryKey: ['mp-public-checks-recent'], queryFn: ({signal}) => api.recent(signal).then(r => r.data), retry: false });
  const query = useQuery({ queryKey: ['mp-public-checks', id], queryFn: ({signal}) => api.get(id!, signal).then(r => r.data), enabled: !!id, retry: false,
    refetchInterval: q => q.state.data?.items.some(active) ? 5000 : false });
  const collection = query.data;
  async function start(request: PublicCheckRequest) {
    setPending(request); setSending(true); setError(null);
    try {
      const result = (await api.create(request)).data;
      client.setQueryData(['mp-public-checks', result.id], result); setId(result.id); setPending(null);
      void client.invalidateQueries({ queryKey: ['mp-public-checks-recent'] });
    } catch (e) { setError(message(e)); } finally { setSending(false); }
  }
  const valid = productIds.length > 0 && productIds.length <= 50 && markets.length > 0;
  return <Modal open title={`G마켓·옥션 공개가격 조회 · 선택 ${productIds.length}개`} onCancel={onClose} width={1100} footer={<Button onClick={onClose}>닫기</Button>}>
    <div className="mp-public-refresh">
      <p>공개 상품 페이지에서 판매 계정·상품번호와 표시가격을 확인합니다. 마켓 전송, 목표값 일치, 삭제 여부를 확정하는 작업은 아닙니다.</p>
      <div className="mp-public-refresh-controls"><Checkbox.Group value={markets} options={[{label:'G마켓',value:'GMARKET'},{label:'옥션',value:'AUCTION'}]}
        disabled={sending || !!pending} onChange={value => setMarkets(value as string[])} />
        <Button type="primary" loading={sending} disabled={!valid || !!pending} onClick={() => void start({requestId:createRequestId(),productIds,markets})}>선택 상품 공개가격 조회</Button>
      </div>
      {productIds.length > 50 && <Alert type="warning" showIcon message="한 번에 최대 50개 상품을 선택하세요." />}
      {error && <Alert type="error" showIcon message={error} description="접수 응답이 없더라도 작업이 생성됐을 수 있습니다. 같은 요청 확인 또는 최근 요청에서 복구하세요."
        action={<Button disabled={sending || !pending} onClick={() => pending && void start(pending)}>같은 요청 다시 확인</Button>} />}
      <details><summary>최근 조회 요청</summary>
        {recent.isLoading && <Spin />}{recent.isError && <Alert type="error" message="최근 조회 요청을 불러오지 못했습니다." action={<Button onClick={() => void recent.refetch()}>다시 확인</Button>} />}
        {recent.data?.map(row => <Button key={row.id} onClick={() => {setId(row.id);setError(null);setPending(null);}}>{date(row.createdAt)} · {row.items.length}건</Button>)}
      </details>
      {query.isLoading && <Spin />}
      {query.isError ? <Alert type="error" showIcon message="현재 조회 상태를 가져오지 못했습니다. 이전 결과를 최신 상태로 표시하지 않습니다." action={<Button onClick={() => void query.refetch()}>상태 다시 확인</Button>} /> : collection && <>
        <p>요청 {date(collection.createdAt)} · 표시값 확인 {collection.items.filter(item => item.state === 'OBSERVED').length}/{collection.items.length}건
          {collection.items.some(active) && ' · 5초마다 상태 확인'}</p>
        <div className="mp-public-refresh-table"><table><thead><tr><th>상품</th><th>마켓</th><th>처리 상태</th><th>공개 표시값</th><th>확인 시각·사유</th><th>작업</th></tr></thead><tbody>
          {collection.items.map(item => <tr key={item.id}><td>{item.sbCode || `상품 #${item.productId}`}</td><td>{marketName(item.market)}</td>
            <td><Tag color={item.state === 'OBSERVED' ? 'blue' : active(item) ? 'processing' : 'orange'}>{labels[item.state] || item.state}</Tag><small>{item.attempts}회 조회</small></td>
            <td>{item.values.salePrice !== undefined ? `${Number(item.values.salePrice).toLocaleString('ko-KR')}원` : '확인 안 됨'}
              {item.values.salesQuantity !== undefined && <small>무옵션 남은수량 {item.values.salesQuantity}개</small>}</td>
            <td><small>{date(item.checkedAt)}</small><p>{item.reason}</p>{item.nextRunAt && <small>다음 처리: {date(item.nextRunAt)}</small>}
              {item.events.length > 1 && <details><summary>조회 시도 이력</summary>{item.events.map((event,index) => <p key={index}>{event}</p>)}</details>}</td>
            <td>{!active(item) && <Button size="small" disabled={sending || !!pending} onClick={() => void start({requestId:createRequestId(),productIds:[item.productId],markets:[item.market]})}>새로 조회</Button>}</td>
          </tr>)}
        </tbody></table></div>
      </>}
    </div>
  </Modal>;
}
