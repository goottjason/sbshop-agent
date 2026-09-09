import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Modal, Progress, Table } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { productApi, type ProductDeleteResult } from '../../api/productApi';
import { supplierBatchApi, type SupplierBatchItem } from '../../api/supplierBatchApi';
import { batchMarketLabel } from './supplierBatchDisplay';

type Entry = Pick<SupplierBatchItem, 'id' | 'productId' | 'sbCode' | 'productName'> & {
  state: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'UNKNOWN'; detail?: string;
};
const activeRuns = new Set<string>();
const key = (runId: string) => `sbshop.bulkDelete.${runId}`;
function restore(runId: string): Entry[] {
  try {
    const rows: Entry[] = JSON.parse(sessionStorage.getItem(key(runId)) ?? '[]');
    return Array.isArray(rows) && rows.every(r => Number.isSafeInteger(r.productId) && Number.isSafeInteger(r.id) && typeof r.sbCode === 'string' && ['PENDING', 'RUNNING', 'DONE', 'FAILED', 'UNKNOWN'].includes(r.state))
      ? rows.map(r => r.state === 'RUNNING' ? { ...r, state: 'UNKNOWN', detail: '화면이 종료되어 결과 확인이 필요합니다. 상품 상세에서 확인해 주세요.' } : r) : [];
  } catch { return []; }
}
function describe(result: ProductDeleteResult) {
  return [result.disposed ? 'SB 소프트 삭제 완료' : 'SB 유지',
    ...Object.entries(result.failed).map(([m, why]) => `${batchMarketLabel(m)} 실패: ${why}`),
    ...Object.keys(result.manual).map(m => `${batchMarketLabel(m)} 미삭제 이력 보존`)].join(' · ');
}
export function BatchBulkDelete({ runId, selected, disabled, onBusy, onDone }: {
  runId: string; selected: SupplierBatchItem[]; disabled: boolean; onBusy: (busy: boolean) => void; onDone: () => void;
}) {
  const cache = useQueryClient();
  const [rows, setRows] = useState<Entry[]>(() => restore(runId));
  const [open, setOpen] = useState(false);
  const [checked, setChecked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const stop = useRef(false);
  useEffect(() => () => { stop.current = true; }, []);
  useEffect(() => {
    if (!busy) return;
    const prevent = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', prevent);
    return () => window.removeEventListener('beforeunload', prevent);
  }, [busy]);
  const save = (next: Entry[]) => {
    // Save before sending any destructive request. Failure to checkpoint prevents sending.
    sessionStorage.setItem(key(runId), JSON.stringify(next));
    setRows([...next]);
  };
  const execute = async (retryFailed = false) => {
    if (busy || disabled || !checked) return;
    if (activeRuns.has(runId)) { setError('이전 삭제 요청의 처리가 끝난 후 기록을 다시 열어 주세요.'); return; }
    activeRuns.add(runId);
    const targets = new Set(rows.filter(r => r.state === (retryFailed ? 'FAILED' : 'PENDING')).map(r => r.productId));
    const next: Entry[] = rows.map(r => retryFailed && r.state === 'FAILED' ? { ...r, state: 'PENDING' } : { ...r });
    stop.current = false; setBusy(true); onBusy(true); setError(null);
    try {
      save(next);
      for (let index = 0; index < next.length; index++) {
        if (stop.current) break;
        if (next[index].state !== 'PENDING' || !targets.has(next[index].productId)) continue;
        const currentRun = (await supplierBatchApi.run(runId)).data;
        if (['RUNNING', 'PAUSING'].includes(currentRun.state)) throw new Error('배치를 일시정지하거나 완료한 후 삭제해 주세요.');
        const entry = next[index];
        const detail = (await supplierBatchApi.detail(runId, entry.id)).data;
        if (detail.item.productId !== entry.productId || detail.item.sbCode !== entry.sbCode) throw new Error('선택 상품 정보가 달라 중단했습니다. 대상을 다시 확인해 주세요.');
        if (stop.current) break;
        if (detail.productDeleted) {
          next[index] = { ...entry, state: 'DONE', detail: '이미 SB 소프트 삭제됨 · 기존 이력 보존' }; save(next); continue;
        }
        next[index] = { ...entry, state: 'RUNNING', detail: '마켓 삭제·재조회 중' }; save(next);
        try {
          const result = (await productApi.deleteProduct(entry.productId)).data;
          if (typeof result?.disposed !== 'boolean' || !result.failed || !result.manual) throw new Error('응답 확인 필요');
          next[index] = { ...entry, state: result.disposed ? 'DONE' : 'FAILED', detail: describe(result) };
        } catch (failure) {
          if (isAxiosError(failure) && failure.response?.status === 409 && failure.response.data?.disposed === false && failure.response.data?.failed && failure.response.data?.manual) {
            next[index] = { ...entry, state: 'FAILED', detail: describe(failure.response.data as ProductDeleteResult) };
          } else {
            next[index] = { ...entry, state: 'UNKNOWN', detail: '삭제 요청의 결과를 확인하지 못했습니다. 상품 상세에서 확인 후 재시도해 주세요.' };
            stop.current = true;
          }
        }
        save(next);
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '작업을 중단했습니다. 저장된 결과를 확인해 주세요.');
    } finally {
      activeRuns.delete(runId);
      setBusy(false); onBusy(false); onDone();
      void cache.invalidateQueries({ queryKey: ['products'] });
      void cache.invalidateQueries({ queryKey: ['supplier-batch-detail', runId] });
    }
  };
  const labels = { PENDING: '대기', RUNNING: '삭제 중', DONE: 'SB 삭제 완료', FAILED: '실패 · SB 유지', UNKNOWN: '결과 확인 필요' };
  const completed = rows.filter(r => r.state === 'DONE').length;
  const showNew = () => {
    if (activeRuns.has(runId)) { setError('이전 삭제 요청이 처리 중입니다. 완료 후 다시 열어 주세요.'); setOpen(true); return; }
    try {
      save([...new Map(selected.map(r => [r.productId, { id: r.id, productId: r.productId, sbCode: r.sbCode, productName: r.productName, state: 'PENDING' as const }])).values()]);
      setChecked(false); setError(null); setOpen(true);
    } catch { setError('작업 기록을 저장할 수 없습니다. 브라우저 저장 공간을 확인해 주세요.'); setOpen(true); }
  };
  return <>
    <Button danger disabled={disabled || busy || !selected.length} onClick={showNew}>선택 {selected.length}개 마켓 삭제 후 SB 폐기</Button>
    {!!rows.length && <Button disabled={busy} onClick={() => { setRows(restore(runId)); setOpen(true); setChecked(false); }}>일괄 삭제 기록 ({completed}/{rows.length})</Button>}
    <Modal title={`선택 상품 일괄 삭제 · ${rows.length}개`} open={open} width={900} closable={!busy} maskClosable={!busy} keyboard={!busy}
      onCancel={() => setOpen(false)} footer={<>
        {busy ? <Button onClick={() => { stop.current = true; }}>현재 상품 처리 후 중단</Button> : <Button onClick={() => setOpen(false)}>닫기</Button>}
        <Button danger type="primary" loading={busy} disabled={busy || disabled || !checked || !rows.some(r => r.state === 'PENDING')} onClick={() => { void execute(); }}>대기 상품 삭제 실행</Button>
        {rows.some(r => r.state === 'FAILED') && <Button danger disabled={busy || disabled || !checked} onClick={() => { void execute(true); }}>실패 상품 재시도</Button>}
      </>}>
      <Alert type="warning" showIcon message="선택한 상품을 한 개씩 순서대로 처리합니다." description="삭제 가능한 마켓을 처리한 후 SB에서 소프트 삭제합니다. 11번가와 카페24 연동 G마켓·옥션은 외부 미삭제 상품번호를 이력에 보존합니다. 다른 마켓의 삭제 실패는 SB를 유지합니다." />
      <p>전체 배치가 아닌 아래 선택 상품만 처리합니다. 실행 중에는 이 화면을 유지해 주세요. 페이지를 닫으면 남은 상품은 자동 실행하지 않습니다.</p>
      <Checkbox checked={checked} disabled={busy} onChange={e => setChecked(e.target.checked)}>아래 상품들의 삭제 사유와 선택 대상을 확인했습니다.</Checkbox>
      {error && <Alert type="error" message={error} />}
      <Progress percent={rows.length ? Math.round(rows.filter(r => !['PENDING', 'RUNNING'].includes(r.state)).length / rows.length * 100) : 0} status={rows.some(r => ['FAILED', 'UNKNOWN'].includes(r.state)) ? 'exception' : busy ? 'active' : 'normal'} />
      <p>SB 삭제 완료 {completed} · 실패 {rows.filter(r => r.state === 'FAILED').length} · 결과 확인 필요 {rows.filter(r => r.state === 'UNKNOWN').length} · 대기 {rows.filter(r => r.state === 'PENDING').length}</p>
      <Table<Entry> rowKey="productId" size="small" dataSource={rows} pagination={{ pageSize: 10 }} columns={[
        { title: 'SB코드', dataIndex: 'sbCode', width: 135 }, { title: '상품명', dataIndex: 'productName' },
        { title: '상태', width: 135, render: (_, row) => labels[row.state] }, { title: '처리 결과·사유', dataIndex: 'detail' },
      ]} />
    </Modal>
  </>;
}
