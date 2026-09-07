import { useQuery } from '@tanstack/react-query';
import { Alert, Button } from 'antd';
import { marketPlusTransmissionApi } from '../../api/marketPlusTransmissionApi';

export function MarketPlusReadinessNotice() {
  const status = useQuery({ queryKey: ['marketplus-readiness'], retry: false, staleTime: 30_000,
    queryFn: async () => (await marketPlusTransmissionApi.readiness()).data });
  if (status.isPending) return null;
  if (!status.isError && status.data?.ready) return null;
  return <Alert style={{ margin: '8px 0' }} type={status.isError ? 'error' : 'warning'} showIcon
    message={status.isError ? '마켓플러스 이력 연결 설정을 조회하지 못했습니다.' : '마켓플러스 이력 연결 설정을 확인해야 합니다.'}
    description={status.isError ? '전송 이슈 조회가 가능한지 확인할 수 없습니다.' : status.data?.reasons.join(' ')}
    action={<Button size="small" loading={status.isFetching} onClick={() => { void status.refetch(); }}>설정 다시 확인</Button>} />;
}
