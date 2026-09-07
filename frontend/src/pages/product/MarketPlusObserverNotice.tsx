import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Tag } from 'antd';
import { marketPlusTransmissionApi } from '../../api/marketPlusTransmissionApi';

const date = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '이력 없음';
const labels = { NOT_CONFIGURED: '미연결', UNAVAILABLE: '조회 불가', PAUSED: '중지', RUNNING: '확인 중', ATTENTION: '확인 필요', OBSERVED_PARTIAL: '수집 범위 한정', STALE: '동작 미확인' };

export function MarketPlusObserverNotice() {
  const status = useQuery({ queryKey: ['marketplus-observer'], queryFn: async () => (await marketPlusTransmissionApi.observerStatus()).data,
    retry: false, refetchInterval: 60_000, staleTime: 15_000 });
  if (status.isPending) return <p style={{ color: '#64748b', fontSize: 12 }}>마켓플러스 자동 수집 상태 확인 중…</p>;
  if (status.isError || !status.data) return <Alert style={{ margin: '8px 0' }} type="error" showIcon message="마켓플러스 자동 수집 상태를 조회하지 못했습니다."
    action={<Button size="small" loading={status.isFetching} onClick={() => { void status.refetch(); }}>수집 상태 재조회</Button>} />;
  const data = status.data;
  const attention = ['UNAVAILABLE', 'ATTENTION', 'STALE'].includes(data.state);
  return <div style={{ margin: '8px 0', padding: '8px 12px', border: `1px solid ${attention ? '#fbbf24' : '#e2e8f0'}`, borderRadius: 8, fontSize: 12 }}>
    <strong>마켓플러스 자동 수집</strong>{' '}<Tag color={attention ? 'orange' : 'default'}>{labels[data.state]}</Tag>
    <span>수집 {data.collectEnabled ? '사용' : '중지'} · 서버 저장 {data.uploadEnabled ? '사용' : '중지'}</span>{' '}
    <Button size="small" type="link" loading={status.isFetching} onClick={() => { void status.refetch(); }}>수집 상태 재조회</Button>
    <div>{data.message}</div>
    <div style={{ color: '#64748b', marginTop: 4 }}>최근 수집 {date(data.lastCollectedAt)} · 최근 저장 확인 {date(data.lastUploadedAt)}
      {data.nextAttemptAt && <> · 다음 저장 재시도 {date(data.nextAttemptAt)}</>}</div>
    {(data.pendingFiles + data.rejectedFiles + data.blockedFiles > 0) && <div>파일 기준: 저장 대기 {data.pendingFiles} · 일부 제외 {data.rejectedFiles} · 확인 필요 {data.blockedFiles}</div>}
  </div>;
}
