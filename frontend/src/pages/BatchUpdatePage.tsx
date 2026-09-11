import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, ConfigProvider, Empty, Modal, Pagination, Progress, Spin } from 'antd';
import { CaretDownOutlined, CaretRightOutlined, DeleteOutlined, PauseOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { supplierBatchApi, type SupplierBatchRun } from '../api/supplierBatchApi';
import { SupplierBatchStart } from '../components/batch/SupplierBatchStart';
import { SupplierBatchRunPanel } from '../components/batch/SupplierBatchRunPanel';
import { LegacyBatchHistory } from '../components/batch/LegacyBatchHistory';
import { BATCH_BLUE, dateText, modeLabel, numberText, requestError } from '../components/batch/supplierBatchDisplay';
import '../components/batch/supplierBatch.css';

const stateText: Record<SupplierBatchRun['state'], string> = { RUNNING: '진행 중', PAUSING: '일시정지 중', PAUSED: '일시정지됨', COMPLETED: '처리 완료' };

export default function BatchUpdatePage() {
  const cache = useQueryClient();
  const [page, setPage] = useState(0);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [createdRun, setCreatedRun] = useState<SupplierBatchRun | null>(null);
  const [startCollapsed, setStartCollapsed] = useState(false);
  const [lastStartedRun, setLastStartedRun] = useState<SupplierBatchRun | null>(null);
  const [controlBusy, setControlBusy] = useState<string | null>(null);
  const [controlError, setControlError] = useState<string | null>(null);
  const [deleteBusy, setDeleteBusy] = useState<string | null>(null);
  const [legacyOpen, setLegacyOpen] = useState(false);
  const runs = useQuery({ queryKey: ['supplier-batch-runs', page], queryFn: async ({ signal }) => (await supplierBatchApi.runs(page, 10, signal)).data, retry: false, refetchInterval: 5000 });
  const selected = useQuery({ queryKey: ['supplier-batch-run', expandedId], queryFn: async ({ signal }) => (await supplierBatchApi.run(expandedId!, signal)).data, enabled: !!expandedId, retry: false,
    refetchInterval: 5000 });
  const changed = (run: SupplierBatchRun) => {
    cache.setQueryData(['supplier-batch-run', run.id], run);
    if (createdRun?.id === run.id) setCreatedRun(run);
    void cache.invalidateQueries({ queryKey: ['supplier-batch-runs'] });
  };
  const control = async (run: SupplierBatchRun) => {
    setControlBusy(run.id); setControlError(null);
    try { changed((await (run.state === 'PAUSED' ? supplierBatchApi.resume(run.id) : supplierBatchApi.pause(run.id))).data); }
    catch (error) {
      setControlError(requestError(error, '배치 제어 요청 결과를 확인하지 못했습니다. 최신 상태를 다시 조회하세요.'));
      void cache.invalidateQueries({ queryKey: ['supplier-batch-run', run.id] });
      void cache.invalidateQueries({ queryKey: ['supplier-batch-runs'] });
    } finally { setControlBusy(null); }
  };
  const deleteRun = (run: SupplierBatchRun) => {
    Modal.confirm({
      title: '배치 기록을 영구 삭제할까요?',
      content: `${run.vendor} ${dateText(run.createdAt)} 배치의 상품·단계·시도·재시도 기록을 모두 삭제합니다. 상품 DB와 마켓 상품에는 영향을 주지 않습니다. 삭제 후 복구할 수 없습니다.`,
      okText: '영구 삭제', cancelText: '취소', okButtonProps: { danger: true },
      onOk: async () => {
        setDeleteBusy(run.id); setControlError(null);
        try {
          await supplierBatchApi.delete(run.id);
          if (expandedId === run.id) setExpandedId(null);
          if (createdRun?.id === run.id) setCreatedRun(null);
          await cache.invalidateQueries({ queryKey: ['supplier-batch-runs'] });
        } catch (error) {
          setControlError(requestError(error, '배치 기록을 삭제하지 못했습니다. 진행 중인 작업인지 확인하세요.'));
          throw error;
        } finally { setDeleteBusy(null); }
      },
    });
  };
  const visibleRuns = runs.data?.content ?? [];
  const newRunOutsidePage = createdRun && !visibleRuns.some(run => run.id === createdRun.id);
  const renderRun = (listRun: SupplierBatchRun) => {
    const expanded = expandedId === listRun.id;
    const run = expanded && selected.data?.id === listRun.id ? selected.data : listRun;
    const hasIssues = run.failed + run.blocked > 0;
    const percent = run.total ? Math.min(100, Math.max(0, run.processed * 100 / run.total)) : 0;
    return <article key={run.id} className={`sb-batch-run ${expanded ? 'sb-batch-run-open' : ''}`}>
      <div className="sb-batch-run-header"><button className="sb-batch-run-toggle" type="button" aria-expanded={expanded} aria-controls={`run-${run.id}`} onClick={() => setExpandedId(expanded ? null : run.id)}>
        {expanded ? <CaretDownOutlined /> : <CaretRightOutlined />}<span className="sb-batch-run-identity"><strong>{run.vendor} 전체 업데이트</strong><span>{modeLabel[run.mode]} · {dateText(run.createdAt)}</span><span>마진 {run.policy.marginRate}% · 쿠폰 {run.policy.couponRate}% · 최소 {numberText(run.policy.minMarginPrice)}원</span></span>
        <span className={`sb-batch-badge ${run.state === 'PAUSED' || hasIssues && run.state === 'COMPLETED' ? 'sb-batch-blocked' : run.state === 'COMPLETED' ? 'sb-batch-success' : 'sb-batch-running'}`}>{stateText[run.state] ?? '상태 확인 필요'}</span>
        <span className="sb-batch-run-progress"><span>최종 처리 {numberText(run.processed)} / {numberText(run.total)}개</span><Progress percent={percent} showInfo={false} size="small" strokeColor={BATCH_BLUE} status="normal" /></span>
        <span className="sb-batch-run-counts"><span>마켓 반영 완료 <b>{numberText(run.succeeded)}</b></span><span>마켓 대상 없음 <b>{numberText(run.dbOnly ?? 0)}</b></span><span className={run.failed ? 'sb-batch-error-text' : ''}>실패 <b>{numberText(run.failed)}</b></span><span>보류 <b>{numberText(run.blocked)}</b></span><span>대기·처리 <b>{numberText(run.pending)}</b></span></span>
      </button><div className="sb-batch-run-control">{run.state === 'RUNNING' ? <Button size="small" icon={<PauseOutlined />} loading={controlBusy === run.id} disabled={!!controlBusy || !!deleteBusy} onClick={() => { void control(run); }}>일시정지</Button> : run.state === 'PAUSED' ? <Button size="small" icon={<PlayCircleOutlined />} loading={controlBusy === run.id} disabled={!!controlBusy || !!deleteBusy} onClick={() => { void control(run); }}>재개</Button> : run.state === 'PAUSING' ? <span className="sb-batch-help">진행 중 {numberText(run.inFlight)}건 마무리</span> : <span className="sb-batch-help">{hasIssues ? '문제 확인 필요' : '처리 종료'}</span>}{run.state === 'COMPLETED' && <Button danger type="text" size="small" icon={<DeleteOutlined />} loading={deleteBusy === run.id} disabled={!!controlBusy || !!deleteBusy} onClick={() => deleteRun(run)} aria-label="배치 기록 영구 삭제">기록 삭제</Button>}</div></div>
      {run.stageProgress && <p className="sb-batch-help" style={{ padding: '0 20px' }}>수집 완료 {numberText((run.stageProgress.CRAWL_SUCCEEDED ?? 0) + (run.stageProgress.CRAWL_UNCHANGED ?? 0))}개 · SB 저장 완료 {numberText((run.stageProgress.DB_SUCCEEDED ?? 0) + (run.stageProgress.DB_UNCHANGED ?? 0))}개 · 마켓 반영 확인 {numberText(run.stageProgress.MARKET_SUCCEEDED ?? 0)}건 · 마켓 대기 {numberText(run.stageProgress.MARKET_WAITING ?? 0)}건 · 마켓 처리 중 {numberText(run.stageProgress.MARKET_RUNNING ?? 0)}건 (마켓 건수는 가격·재고 각각 집계)</p>}
      {expanded && <div id={`run-${run.id}`}>{selected.isError ? <Alert type="error" showIcon message="배치의 최신 상태를 조회하지 못했습니다." action={<Button onClick={() => { void selected.refetch(); }}>다시 조회</Button>} /> : selected.isPending ? <div className="sb-batch-loading"><Spin /> 배치 상태 조회 중…</div> : <SupplierBatchRunPanel key={run.id} run={run} onChanged={changed} />}</div>}
    </article>;
  };
  return <ConfigProvider theme={{ token: { colorPrimary: BATCH_BLUE, borderRadius: 8 } }}><main className="sb-batch-page">
    <header className="sb-batch-page-heading"><div><h1>배치 업데이트</h1><p>소싱처 전체 상품의 가격과 재고를 한 번에 갱신합니다.</p></div><span className="sb-batch-live-indicator">5초 간격 상태 조회</span></header>
    {startCollapsed && lastStartedRun && <section className="sb-batch-start-collapsed" aria-label="최근 배치 실행 조건"><div><strong>최근 실행 조건</strong><span>{lastStartedRun.vendor} 전체 {numberText(lastStartedRun.total)}개 · {modeLabel[lastStartedRun.mode]} · 마진 {lastStartedRun.policy.marginRate}% · 쿠폰 {lastStartedRun.policy.couponRate}% · 최소 {numberText(lastStartedRun.policy.minMarginPrice)}원</span></div><Button type="primary" onClick={() => setStartCollapsed(false)} aria-expanded={false} aria-controls="supplier-batch-start-form">새 배치 실행</Button></section>}
    <div id="supplier-batch-start-form" hidden={startCollapsed}><SupplierBatchStart onCollapse={lastStartedRun ? () => setStartCollapsed(true) : undefined} onCreated={run => { setCreatedRun(run); setLastStartedRun(run); setStartCollapsed(true); setExpandedId(run.id); setPage(0); changed(run); }} /></div>
    <section className="sb-batch-history" aria-labelledby="supplier-batch-history-title"><div className="sb-batch-section-title"><h2 id="supplier-batch-history-title">배치 실행 기록</h2><Button icon={<ReloadOutlined />} onClick={() => { void runs.refetch(); if (expandedId) void selected.refetch(); }}>새로고침</Button></div>
      <p className="sb-batch-help">행을 펼치면 상품별 수집 → SB 저장 → 마켓 반영 결과를 확인할 수 있습니다. 처리 완료에도 실패·보류 상품이 남을 수 있습니다.</p>
      {controlError && <Alert type="error" showIcon message={controlError} closable onClose={() => setControlError(null)} />}
      {runs.isError ? <Alert type="error" showIcon message="배치 실행 기록을 조회하지 못했습니다." action={<Button onClick={() => { void runs.refetch(); }}>다시 조회</Button>} /> : runs.isPending ? <div className="sb-batch-loading"><Spin /> 배치 실행 기록 조회 중…</div> : !visibleRuns.length && !createdRun ? <Empty description="아직 실행한 새 배치가 없습니다." /> : <div className="sb-batch-run-list">{newRunOutsidePage && <><p className="sb-batch-help">방금 접수한 배치</p>{renderRun(createdRun)}</>}{visibleRuns.map(renderRun)}</div>}
      {!runs.isError && !!runs.data?.totalElements && <Pagination className="sb-batch-history-pagination" current={page + 1} pageSize={10} total={runs.data.totalElements} showSizeChanger={false} showTotal={total => `전체 ${numberText(total)}회`} onChange={next => { setPage(next - 1); setCreatedRun(null); setExpandedId(null); }} />}
    </section>
    <details className="sb-batch-legacy-section" onToggle={event => setLegacyOpen(event.currentTarget.open)}><summary>이전 방식으로 실행한 배치 기록 조회</summary>{legacyOpen && <LegacyBatchHistory />}</details>
  </main></ConfigProvider>;
}
