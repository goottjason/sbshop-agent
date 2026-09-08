import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Input, Modal, Select, Table, Tooltip } from 'antd';
import { isAxiosError } from 'axios';
import { supplierBatchApi, type SupplierBatchItem, type SupplierBatchItemFilter, type SupplierBatchMarket, type SupplierBatchRetry, type SupplierBatchRun, type SupplierBatchStage, type SupplierBatchStageKind } from '../../api/supplierBatchApi';
import { createRequestId } from '../../utils/requestId';
import { batchMarketLabel, dateText, mayRetry, numberText, requestError, stageLabel } from './supplierBatchDisplay';
import { BatchStageBadge, SupplierBatchDrawer } from './SupplierBatchDrawer';

const markets: SupplierBatchMarket[] = ['COUPANG', 'ELEVEN_STREET', 'SMART_STORE', 'CAFE24'];
type RetryQueue = { requests: SupplierBatchRetry[]; next: number };
const retryKey = (id: string) => `sbshop.supplierBatch.pendingRetry.${id}`;
function loadRetry(id: string): RetryQueue | null {
  try { const value = JSON.parse(sessionStorage.getItem(retryKey(id)) ?? 'null'); return value && Array.isArray(value.requests) && value.requests.length <= 3 && value.requests.every((r: SupplierBatchRetry) => typeof r.requestId === 'string') && Number.isInteger(value.next) ? value : null; } catch { return null; }
}
function saveRetry(id: string, value: RetryQueue | null) { try { if (value) sessionStorage.setItem(retryKey(id), JSON.stringify(value)); else sessionStorage.removeItem(retryKey(id)); } catch { /* In-memory recovery remains available. */ } }

export function SupplierBatchRunPanel({ run, onChanged }: { run: SupplierBatchRun; onChanged: (run: SupplierBatchRun) => void }) {
  const cache = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState<SupplierBatchItemFilter>('ALL');
  const [itemId, setItemId] = useState<number | null>(null);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkStages, setBulkStages] = useState<SupplierBatchStageKind[]>(['CRAWL', 'DB', 'MARKET']);
  const [retryQueue, setRetryQueue] = useState<RetryQueue | null>(() => loadRetry(run.id));
  const [retryBusy, setRetryBusy] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);
  const [retryNotice, setRetryNotice] = useState<string | null>(null);
  const retryOptions = useQuery({ queryKey: ['supplier-batch-retry-options', run.id], enabled: bulkOpen, retry: false,
    queryFn: async ({ signal }) => (await supplierBatchApi.retryOptions(run.id, signal)).data });
  const selectedBulkStages = bulkStages.filter(stage => (retryOptions.data?.retryableStageCounts[stage] ?? 0) > 0);
  const items = useQuery({ queryKey: ['supplier-batch-items', run.id, page, size, keyword, filter], queryFn: async ({ signal }) => (await supplierBatchApi.items(run.id, page, size, keyword, filter, signal)).data,
    retry: false, refetchInterval: run.state === 'RUNNING' || run.state === 'PAUSING' ? 5000 : false });
  const changeRetry = (value: RetryQueue | null) => { setRetryQueue(value); saveRetry(run.id, value); };
  const executeRetry = async (queue: RetryQueue) => {
    setRetryBusy(true); setRetryError(null); setRetryNotice(null); changeRetry(queue);
    try {
      for (let index = queue.next; index < queue.requests.length; index++) {
        const updated = (await supplierBatchApi.retry(run.id, queue.requests[index])).data;
        onChanged(updated);
        changeRetry({ ...queue, next: index + 1 });
      }
      changeRetry(null); setBulkOpen(false);
      setRetryNotice('재시도를 접수했습니다. 각 단계의 처리 결과를 계속 확인합니다.');
    } catch (error) {
      if (isAxiosError(error) && [400, 422].includes(error.response?.status ?? 0)) changeRetry(null);
      setRetryError(requestError(error, '재시도 접수 결과를 확인하지 못했습니다.'));
    } finally {
      setRetryBusy(false);
      void cache.invalidateQueries({ queryKey: ['supplier-batch-items', run.id] });
      void cache.invalidateQueries({ queryKey: ['supplier-batch-detail', run.id] });
    }
  };
  const retryStage = (targetItemId: number, stage: SupplierBatchStage) => {
    if (retryQueue || retryBusy || !mayRetry(stage)) return;
    void executeRetry({ next: 0, requests: [{ requestId: createRequestId(), itemId: targetItemId, stage: stage.stage, ...(stage.market ? { market: stage.market } : {}), ...(stage.field ? { field: stage.field } : {}) }] });
  };
  const statusCell = (item: SupplierBatchItem, kind: SupplierBatchStageKind, market?: SupplierBatchMarket) => {
    const stages = item.stages.filter(stage => stage.stage === kind && (!market || stage.market === market));
    return stages.length ? <div className="sb-batch-matrix-stages">{stages.map(stage => <Tooltip key={stage.id} title={stage.detail ?? undefined}><button type="button" className="sb-batch-stage-button" onClick={() => setItemId(item.id)} aria-label={`${item.sbCode} ${stageLabel(stage.stage, stage.market, stage.field)} 상세`}>
      <span className="sb-batch-cell-status">{stage.field && <small>{stage.field === 'PRICE' ? '가격' : '수량'}</small>}<BatchStageBadge stage={stage} /></span>
      {stage.field && <span className="sb-batch-cell-value">{(stage.state === 'SUCCEEDED' || stage.state === 'UNCHANGED') && stage.observed != null ? <>확인 <strong>{numberText(stage.observed)}{stage.field === 'PRICE' ? '원' : '개'}</strong></> : stage.expected != null ? <>목표 {numberText(stage.expected)}{stage.field === 'PRICE' ? '원' : '개'}</> : '값 미확인'}</span>}
    </button></Tooltip>)}</div> : <span className="sb-batch-help">{market && !run.markets.includes(market) ? '선택 안 함' : '단계 준비 전'}</span>;
  };
  const retryFeedback = <>
    {retryError && <Alert type="error" showIcon message={retryError} />}
    {retryQueue && !retryBusy && <Alert type="warning" showIcon message="직전 재시도 요청의 접수 여부를 확인해야 합니다."
      action={<Button onClick={() => { void executeRetry(retryQueue); }}>같은 재시도 요청 다시 확인</Button>} />}
    {retryBusy && <Alert type="info" message="실패 단계 재시도를 접수하고 있습니다…" />}
    {retryNotice && <Alert type="info" showIcon closable onClose={() => setRetryNotice(null)} message={run.state === 'PAUSED' ? '재시도를 대기로 접수했습니다. 일시정지를 해제하면 처리합니다.' : retryNotice} />}
  </>;
  return <div className="sb-batch-run-panel">
    <div className="sb-batch-run-policy">반영 마켓: {run.markets.map(batchMarketLabel).join(' · ')} · 미등록 마켓 제외</div>
    {run.state === 'PAUSING' && <Alert type="info" showIcon message={`새 작업 접수를 멈추고, 실행 중인 ${numberText(run.inFlight)}건을 마무리하고 있습니다.`} description="진행 중인 외부 요청을 강제로 중단하지 않습니다. 처리가 끝나면 일시정지됨으로 바뀝니다." />}
    {run.state === 'PAUSED' && <Alert type="warning" showIcon message="일시정지됨 · 재개를 누르면 남은 상품부터 처리합니다." description="실패 단계의 재시도를 접수해도 자동으로 재개하지 않습니다." />}
    {run.nextRunAt && <p className="sb-batch-help">다음 처리 가능 시각: {dateText(run.nextRunAt)}</p>}
    <div className="sb-batch-matrix-toolbar"><div><Input.Search aria-label="배치 상품 검색" placeholder="SB코드 또는 상품명" allowClear onSearch={value => { setKeyword(value.trim()); setPage(0); }} />
      <Select aria-label="배치 상품 결과 필터" value={filter} onChange={value => { setFilter(value); setPage(0); }} options={[
        { value: 'ALL', label: '전체 상품' }, { value: 'FAILED', label: '실패만' }, { value: 'BLOCKED', label: '보류만' }, { value: 'PENDING', label: '대기·처리 중' }, { value: 'SUCCEEDED', label: '마켓 반영 완료' }, { value: 'DB_ONLY', label: '마켓 대상 없음' },
      ]} /></div><div><Button onClick={() => { void items.refetch(); }}>새로고침</Button><Button disabled={!!retryQueue || retryBusy || run.failed + run.blocked === 0} onClick={() => setBulkOpen(true)}>실패 단계 일괄 재시도</Button></div></div>
    {retryFeedback}
    {items.isError ? <Alert type="error" showIcon message="상품별 처리 결과를 조회하지 못했습니다."
      action={<Button onClick={() => { void items.refetch(); }}>다시 조회</Button>} /> : <Table<SupplierBatchItem> size="small" rowKey="id" loading={items.isPending} dataSource={items.data?.content ?? []} scroll={{ x: 1170 }}
      locale={{ emptyText: items.isPending ? '상품별 처리 기록 조회 중…' : keyword || filter !== 'ALL' ? '현재 검색·필터에 해당하는 상품이 없습니다.' : '배치의 상품 기록을 아직 준비 중이거나 대상 상품이 없습니다.' }}
      pagination={{ current: page + 1, pageSize: size, total: items.data?.totalElements ?? 0, showSizeChanger: true, pageSizeOptions: [20, 50, 100], showTotal: total => `${numberText(total)}개`, onChange: (next, nextSize) => { setPage(nextSize !== size ? 0 : next - 1); setSize(nextSize); } }} columns={[
        { title: '상품', key: 'product', width: 250, fixed: 'left', render: (_, item) => <button className="sb-batch-product-button" type="button" onClick={() => setItemId(item.id)}>{item.thumbnailUrl && <img src={item.thumbnailUrl} alt="" referrerPolicy="no-referrer" />}<span><strong>{item.sbCode}</strong><span>{item.productName}</span></span></button> },
        { title: '수집', key: 'crawl', width: 105, render: (_, item) => statusCell(item, 'CRAWL') },
        { title: 'SB 저장', key: 'db', width: 105, render: (_, item) => statusCell(item, 'DB') },
        ...markets.map(market => ({ title: batchMarketLabel(market), key: market, width: 122, render: (_: unknown, item: SupplierBatchItem) => statusCell(item, 'MARKET', market) })),
        { title: '문제 · 조치', key: 'issues', width: 230, render: (_, item) => {
          const issues = item.stages.filter(stage => stage.state === 'FAILED' || stage.state === 'BLOCKED');
          return <div className="sb-batch-issues">{issues.length ? issues.slice(0, 1).map(stage => <div key={stage.id}><strong>{stageLabel(stage.stage, stage.market, stage.field)} · {stage.state === 'FAILED' ? '실패' : '보류'}</strong><Tooltip title={stage.detail ?? undefined}><span className="sb-batch-primary-issue-detail">{stage.detail ?? '상세 사유가 제공되지 않았습니다.'}</span></Tooltip>{mayRetry(stage) && <Button size="small" disabled={retryBusy || !!retryQueue} onClick={() => retryStage(item.id, stage)}>해당 단계 재시도</Button>}</div>) : <span className="sb-batch-help">{item.detail ?? '기록된 문제 없음'}</span>}<Button type="link" size="small" onClick={() => setItemId(item.id)}>{issues.length > 1 ? `외 ${issues.length - 1}개 문제` : '처리 상세'}</Button></div>;
        } },
      ]} />}
    <Modal title="실패 단계 일괄 재시도" open={bulkOpen} onCancel={() => setBulkOpen(false)} confirmLoading={retryBusy} okText="선택 단계 재시도 접수" okButtonProps={{ disabled: !selectedBulkStages.length || !!retryQueue || retryBusy || retryOptions.isFetching || retryOptions.isError }}
      onOk={() => { const requests: SupplierBatchRetry[] = selectedBulkStages.length === 3 ? [{ requestId: createRequestId() }] : selectedBulkStages.map(stage => ({ requestId: createRequestId(), stage })); void executeRetry({ requests, next: 0 }); }}>
      <p>이 배치 전체 상품 중 선택 단계의 실패·보류이며 재시도가 허용된 작업을 다시 접수합니다.</p>
      {retryFeedback}
      {retryOptions.isError ? <Alert type="error" message="재시도 대상 수를 확인하지 못했습니다." action={<Button onClick={() => { void retryOptions.refetch(); }}>대상 다시 조회</Button>} /> : retryOptions.isPending ? <p>재시도 대상 조회 중…</p> : <>
        <Checkbox.Group className="sb-batch-retry-options" value={selectedBulkStages} disabled={retryBusy || !!retryQueue} onChange={value => setBulkStages(value as SupplierBatchStageKind[])} options={[
          { value: 'CRAWL', label: `소싱처 수집 실패 · ${numberText(retryOptions.data.retryableStageCounts.CRAWL)}건`, disabled: !retryOptions.data.retryableStageCounts.CRAWL },
          { value: 'DB', label: `SB 저장 실패 · ${numberText(retryOptions.data.retryableStageCounts.DB)}건`, disabled: !retryOptions.data.retryableStageCounts.DB },
          { value: 'MARKET', label: `마켓 반영 실패 · ${numberText(retryOptions.data.retryableStageCounts.MARKET)}건`, disabled: !retryOptions.data.retryableStageCounts.MARKET },
        ]} />
        <p>재시도 가능한 상품 {numberText(retryOptions.data.retryableProducts)}개 · 자동 재시도 제외 단계 {numberText(retryOptions.data.blockedStageCount)}건</p>
      </>}
      <Alert type="info" showIcon message="완료 단계는 그대로 사용합니다." description="판매금지, 미지원, 추가 확인이 필요한 단계는 자동으로 제외합니다. 현재 화면의 검색·필터 범위와 관계없이 이 배치 전체에 적용합니다." />
      <p>현재 실패 상품 {numberText(run.failed)}개 · 보류 상품 {numberText(run.blocked)}개. 실제 재접수 수는 각 단계의 재시도 가능 여부에 따라 달라집니다.</p>
      {run.state === 'PAUSED' && <p>일시정지 상태를 유지하며, 재개 후 처리합니다.</p>}
    </Modal>
    {itemId != null && <SupplierBatchDrawer run={run} itemId={itemId} onClose={() => setItemId(null)} onRetry={retryStage} retryBusy={retryBusy || !!retryQueue} retryFeedback={retryFeedback} />}
  </div>;
}
