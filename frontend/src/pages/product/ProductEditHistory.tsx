import { Alert, Button, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { productEditApi } from '../../api/productChangeApi';
import { marketLabel } from '../../utils/marketLabels';
import { editFieldLabel, editValue } from './productEditDisplay';

export function ProductEditHistory({ productId }: { productId: number }) {
  const history = useQuery({ queryKey: ['product-edit-history', productId], enabled: false, retry: false,
    queryFn: async ({ signal }) => (await productEditApi.history(productId, signal)).data });
  return <div style={{ marginTop: 12 }}><Button size="small" loading={history.isFetching} onClick={() => { void history.refetch(); }}>변경 이력 보기</Button>
    <p className="pw-change-note">새 편집 흐름에서 저장한 최근 50건을 표시합니다. 기존 변경 이력을 소급 생성하지 않습니다.</p>
    {history.isError && <Alert type="error" message="변경 이력을 조회하지 못했습니다. 다시 조회하세요." />}
    {history.data && !history.isError && !history.isFetching && (history.data.length === 0 ? <p>기록된 변경이 없습니다.</p>
      : history.data.map(item => <div key={item.id} style={{ borderTop: '1px solid #e2e8f0', padding: '10px 0' }}>
        <strong>{new Date(item.createdAt).toLocaleString('ko-KR')} · {item.actor}</strong>
        {item.changes.map(c => <p key={c.field} className="pw-change-note" style={{ overflowWrap: 'anywhere', maxHeight: 100, overflow: 'auto' }}>{editFieldLabel(c.field)}: {editValue(c.field, c.before)} → {editValue(c.field, c.after)}</p>)}
        {item.targets.map(t => <Tag key={t.id} color={['CONFIRMED_PRICE', 'CONFIRMED_QUANTITY', 'CONFIRMED_FIELDS', 'SUPERSEDED_BY_CURRENT'].includes(t.state) ? 'green' : 'orange'}>{marketLabel(t.market)} · {({ PENDING_DISPATCH: '반영 대기', DISPATCHED: '전송·재조회 진행', ACTION_REQUIRED: '반영 확인·조치 필요', AWAITING_REVIEW: '전송 검토·심사 동의 대기', CONFIRMED_FIELDS: '검토 필드 일치 확인', CONFIRMED_PRICE: '판매가 일치 확인', CONFIRMED_QUANTITY: '판매용 수량 일치 확인', SUPERSEDED_BY_CURRENT: '최신 변경 반영으로 대체', CANCELLED_DETACHED: '연결 해제로 반영 취소' } as Record<string, string>)[t.state] ?? t.state}</Tag>)}
      </div>))}
  </div>;
}
