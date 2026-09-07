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
  const attention = ['NOT_CONFIGURED', 'UNAVAILABLE', 'PAUSED', 'ATTENTION', 'STALE'].includes(data.state)
    || !data.collectEnabled || !data.uploadEnabled || data.rejectedFiles + data.blockedFiles > 0;
  const details = <>
    <div>{data.message}</div>
    <div className="pw-observer-times">최근 수집 {date(data.lastCollectedAt)} · 최근 저장 확인 {date(data.lastUploadedAt)}
      {data.nextAttemptAt && <> · 다음 저장 재시도 {date(data.nextAttemptAt)}</>}</div>
    {(data.pendingFiles + data.rejectedFiles + data.blockedFiles > 0) && <div>파일 기준: 저장 대기 {data.pendingFiles} · 일부 제외 {data.rejectedFiles} · 확인 필요 {data.blockedFiles}</div>}
  </>;
  return <div className={`pw-observer${attention ? ' pw-observer-attention' : ''}`}>
    <div className="pw-observer-heading">
      <strong>마켓플러스 자동 수집</strong><Tag color={attention ? 'orange' : 'default'}>{labels[data.state]}</Tag>
      <span>수집 {data.collectEnabled ? '사용' : '중지'} · 서버 저장 {data.uploadEnabled ? '사용' : '중지'}</span>
      {!attention && <span className="pw-observer-times">최근 수집 {date(data.lastCollectedAt)}</span>}
      <Button size="small" type="link" loading={status.isFetching} onClick={() => { void status.refetch(); }}>재조회</Button>
    </div>
    {attention ? <div role="status">{details}</div> : <details className="pw-observer-details"><summary>수집·저장 상세</summary>{details}</details>}
  </div>;
}
