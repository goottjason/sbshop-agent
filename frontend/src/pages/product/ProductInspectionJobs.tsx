import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Modal, Progress, Select, Space, Table, Tag } from 'antd';
import { inspectionRequestId, marketInspectionApi, type InspectionItem, type InspectionBatch } from '../../api/marketInspectionApi';

const labels: Record<string, string> = {
  QUEUED: '대기', RUNNING: '조회 중', RETRY_WAIT: '재시도 대기', CONFIRMED: '상태 확인', DETACHED: '연결 해제',
  UNKNOWN: '미확인', FAILED: '실행 실패', STALE: '연결 변경 · 재확인', SKIPPED: '제외', ACCOUNT_REVIEW_REQUIRED: '판매 계정 확인 필요',
  ENROLLING: '상품 접수 중', ENQUEUED: '상태 확인 중', FINISHED: '처리 종료',
  PRESENT: '상품 존재', OUT_OF_STOCK: '일시 품절', STOPPED: '판매 중지', PROHIBITED: '영구 판매금지', DELETED: '상품 부재',
};
const marketNames: Record<string, string> = { SMART_STORE: '스마트스토어', COUPANG: '쿠팡', ELEVEN_STREET: '11번가', CAFE24: '카페24' };
export function ProductInspectionJobs({ productIds, onClose }: { productIds: number[]; onClose: () => void }) {
  const [selection] = useState(() => [...productIds]);
  const [batchId, setBatchId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [acceptedMarkets, setAcceptedMarkets] = useState<string[]>([]);
  const [market, setMarket] = useState('SMART_STORE');
  const selectionAccepted = acceptedMarkets.includes(market);
  const [error, setError] = useState<string | null>(null);
  const [attentionOnly, setAttentionOnly] = useState(false);
  const requests = useRef(new Map<string, string>());
  const client = useQueryClient();
  const availability = useQuery({ queryKey: ['inspection-availability', market], retry: false,
    queryFn: async ({ signal }) => (await marketInspectionApi.availability(signal, market)).data });
  const daily = useQuery({ queryKey: ['inspection-daily'], retry: false, refetchInterval: 5000,
    queryFn: async ({ signal }) => (await marketInspectionApi.dailyMarkets(signal)).data });
  const schedule = daily.isError ? undefined : daily.data?.find(row => row.market === market)?.status;
  const sweep = schedule?.latest;
  const dailyBatches = useQuery({ queryKey: ['inspection-daily-batches', sweep?.id], enabled: !!sweep,
    retry: false, refetchInterval: sweep?.state === 'FINISHED' ? false : 5000,
    queryFn: async ({ signal }) => (await marketInspectionApi.dailyBatches(sweep!.id, signal)).data });
  const recent = useQuery({ queryKey: ['inspection-jobs'], retry: false, refetchInterval: 5000,
    queryFn: async ({ signal }) => (await marketInspectionApi.recent(signal)).data });
  const current = useQuery<InspectionBatch>({ queryKey: ['inspection-job', batchId], enabled: batchId !== null, retry: false,
    refetchInterval: q => q.state.data?.pending === 0 ? false : 3000,
    queryFn: async ({ signal }) => (await marketInspectionApi.get(batchId!, signal)).data });
  const run = async (retry = false) => {
    const key = retry ? `retry:${batchId}` : `selection:${market}`;
    const requestId = requests.current.get(key) ?? inspectionRequestId();
    requests.current.set(key, requestId);
    setBusy(true); setError(null);
    try {
      const response = retry ? await marketInspectionApi.retry(batchId!, requestId) : await marketInspectionApi.create(selection, requestId, market);
      if (!retry) setAcceptedMarkets(previous => [...previous, market]);
      setBatchId(response.data.id);
      client.setQueryData(['inspection-job', response.data.id], response.data);
      await client.invalidateQueries({ queryKey: ['inspection-jobs'] });
      void client.invalidateQueries({ queryKey: ['inspection-daily'] });
      void client.invalidateQueries({ queryKey: ['inspection-daily-batches'] });
    } catch {
      setError('작업 접수 결과를 확인하지 못했습니다. 같은 버튼으로 재시도하면 같은 요청 ID를 사용합니다. 최근 작업에서도 접수 여부를 확인할 수 있습니다.');
    } finally { setBusy(false); }
  };
  const batch = current.isError ? undefined : current.data;
  const chooseBatch = (id: string) => { setBatchId(id); setAttentionOnly(false); setError(null); };
  const jobLabel = (job: InspectionBatch) => `${marketNames[job.market ?? 'SMART_STORE'] ?? job.market} · ${job.source === 'DAILY' ? '정기' : job.source.startsWith('RETRY:') ? '재확인' : '선택'} · ${new Date(job.createdAt).toLocaleString('ko-KR')} · ${job.total}건 · 대기 ${job.pending} · 확인 필요 ${job.needsAttention}`;
  const refresh = () => {
    void recent.refetch(); void availability.refetch(); void daily.refetch();
    if (sweep) void dailyBatches.refetch();
    if (batchId) void current.refetch();
  };
  const needsAttention = (row: InspectionItem) => ['UNKNOWN', 'FAILED', 'STALE', 'ACCOUNT_REVIEW_REQUIRED'].includes(row.state);
  return <Modal open width={1100} title="마켓 상태 일괄 확인" onCancel={onClose} footer={<Button onClick={() => {
    void client.invalidateQueries({ queryKey: ['products'] }); onClose();
  }}>닫기</Button>}>
    <Alert type="info" showIcon message="마켓별 상품번호로 상태 확인"
      description="스마트스토어·쿠팡·11번가·카페24를 선택해 조회합니다. G마켓·옥션은 별도 API 자료 확인 중입니다. 품절은 연결을 유지하며, 삭제·영구 판매금지가 확정되면 근거를 보존하고 연결을 해제합니다. 정보 동기화 완료를 뜻하지 않습니다. 창을 닫아도 접수된 작업은 계속됩니다." />
    {availability.isError ? <Alert type="error" message="조회 지원 정보 확인 실패" action={<Button onClick={() => { void availability.refetch(); }}>재시도</Button>} />
      : availability.data && !availability.data.accountVerified && <Alert type="warning" message={availability.data.detail} />}
    {!daily.isError && daily.data && <Space wrap style={{ marginTop: 12 }} aria-label="마켓별 정기 확인 선택">
      {daily.data.map(row => <Button key={row.market} size="small" type={market === row.market ? 'primary' : 'default'} disabled={busy}
        onClick={() => { setMarket(row.market); setError(null); }}>
        {marketNames[row.market] ?? row.market} · {row.status.latest ? `확인 필요 ${row.status.latest.totals.needsAttention}` : '접수 대기'}
      </Button>)}
    </Space>}
    {daily.isError ? <Alert style={{ marginTop: 12 }} type="error" message="정기 확인 현황 조회 실패. 일정과 처리 결과를 확인할 수 없습니다."
      action={<Button onClick={() => { void daily.refetch(); }}>재시도</Button>} /> : schedule && <div aria-label={`${marketNames[market]} 정기 확인 현황`} style={{ marginTop: 12, padding: 14, border: '1px solid #dbe3eb', borderRadius: 8 }}>
      <Space wrap><strong>{marketNames[market]} 정기 확인</strong><Tag color={schedule.enabled && (schedule.accountVerified || market !== 'SMART_STORE') ? 'blue' : 'orange'}>
        {!schedule.enabled ? '사용 안 함' : market !== 'SMART_STORE' || schedule.accountVerified ? schedule.schedule : '계정 확인 · 보류'}
      </Tag></Space>
      <div style={{ color: '#475569', margin: '6px 0' }}>{schedule.detail}</div>
      {schedule.nextDueAt && <div>확인 예정: {new Date(schedule.nextDueAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} (한국시간)</div>}
      {sweep ? <>
        <div style={{ margin: '8px 0' }}>{sweep.date} · {labels[sweep.state] ?? sweep.state} · 접수 {sweep.enrolled.toLocaleString()}건
          {sweep.state === 'FINISHED' && sweep.totals.needsAttention > 0 && ' · 미확인 결과가 남아 있습니다.'}</div>
        <Space wrap style={{ marginBottom: 8 }}>
          <Tag>대기·진행 {sweep.totals.pending}</Tag><Tag color="blue">상태 확인 {sweep.totals.confirmed}</Tag>
          <Tag color="red">연결 해제 {sweep.totals.detached}</Tag><Tag color="orange">확인 필요 {sweep.totals.needsAttention}</Tag><Tag>제외 {sweep.totals.skipped}</Tag>
        </Space>
        <div><Select aria-label="정기 확인 세부 작업" placeholder={`정기 확인 결과 열기 · ${sweep.batchCount}개 작업`}
          style={{ width: '100%', maxWidth: 650 }} loading={dailyBatches.isFetching} disabled={dailyBatches.isError}
          value={!dailyBatches.isError && dailyBatches.data?.some(b => b.id === batchId) ? batchId : undefined}
          onChange={chooseBatch} options={dailyBatches.isError ? [] : dailyBatches.data?.map((job, index) => ({ value: job.id, label: `${index + 1}. ${jobLabel(job)}` }))} /></div>
        {dailyBatches.isError && <Alert type="error" message="정기 확인 세부 작업 조회 실패" action={<Button onClick={() => { void dailyBatches.refetch(); }}>재시도</Button>} />}
      </> : <div>아직 접수된 정기 확인 작업이 없습니다.</div>}
    </div>}
    <Space wrap style={{ margin: '12px 0' }}>
      <Select aria-label="상태 확인할 마켓" value={market} disabled={busy} style={{ width: 145 }}
        options={Object.entries(marketNames).map(([value, label]) => ({ value, label }))}
        onChange={value => { setMarket(value); setError(null); }} />
      <Button type="primary" loading={busy && !batchId} disabled={busy || selectionAccepted || !selection.length || !availability.data?.supported || availability.isError} onClick={() => { void run(); }}>
        {selectionAccepted ? '선택 상품 접수 완료' : '선택 상품 상태 확인'} ({selection.length})
      </Button>
      <Select aria-label="최근 상태 확인 작업" placeholder="최근 상태 확인 작업" style={{ width: 390 }} value={!recent.isError && recent.data?.some(b => b.id === batchId) ? batchId : undefined}
        onChange={chooseBatch} disabled={recent.isError}
        options={recent.isError ? [] : recent.data?.map(job => ({ value: job.id, label: jobLabel(job) }))} />
      <Button onClick={refresh}>결과 새로고침</Button>
    </Space>
    {error && <Alert type="error" message={error} />}
    {recent.isError && <Alert type="error" message="최근 작업 조회 실패. 접수된 작업이 없다는 뜻은 아닙니다." />}
    {current.isError && <Alert type="error" message="진행 결과 조회 실패. 완료 여부를 확인할 수 없습니다." />}
    {batch && <>
      <div style={{ marginTop: 12 }}>작업자 {batch.source === 'DAILY' ? '정기 확인' : batch.actor} · {new Date(batch.createdAt).toLocaleString('ko-KR')}</div>
      <Progress percent={batch.total ? Math.floor((batch.total - batch.pending) / batch.total * 100) : 0}
        status={batch.pending ? 'active' : batch.needsAttention ? 'exception' : 'normal'}
        format={() => `${batch.total - batch.pending}/${batch.total} 처리`} />
      <Space wrap style={{ marginBottom: 12 }}>
        <Tag>대기·진행 {batch.pending}</Tag><Tag color="blue">상태 확인 {batch.confirmed}</Tag>
        <Tag color="red">연결 해제 {batch.detached}</Tag><Tag color="orange">확인 필요 {batch.needsAttention}</Tag><Tag>제외 {batch.skipped}</Tag>
        <Button size="small" onClick={() => setAttentionOnly(!attentionOnly)}>{attentionOnly ? '전체 결과 보기' : '확인 필요만 보기'}</Button>
        <Button size="small" disabled={busy || !batch.needsAttention} onClick={() => { void run(true); }}>실패·미확인 다시 확인</Button>
      </Space>
      <Table<InspectionItem> size="small" rowKey="id" scroll={{ x: 900 }} pagination={{ pageSize: 50, showSizeChanger: false }}
        dataSource={attentionOnly ? batch.items.filter(needsAttention) : batch.items}
        columns={[
          { title: 'SB코드', dataIndex: 'sbCode', width: 145, render: (code, row) => code ?? `상품 ${row.productId}` },
          { title: '마켓 상품번호', dataIndex: 'externalId', width: 140 },
          { title: '처리 상태', dataIndex: 'state', width: 150, render: value => labels[value] ?? value },
          { title: '관측 상태', dataIndex: 'observedState', width: 120, render: value => labels[value] ?? value ?? '—' },
          { title: '시도', dataIndex: 'attempts', width: 55 },
          { title: '사유·다음 확인', render: (_, row) => <div style={{ overflowWrap: 'anywhere' }}>
            {row.detail ?? '순서대로 확인합니다.'}{row.code && <div style={{ color: '#64748b' }}>{row.code}</div>}
            {row.state === 'RETRY_WAIT' && <div>재시도 예정: {new Date(row.nextRunAt).toLocaleString('ko-KR')}</div>}
          </div> },
        ]} />
    </>}
  </Modal>;
}
