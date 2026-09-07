import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Tag } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { marketPlusTransmissionApi, type MarketPlusImportResult } from '../../api/marketPlusTransmissionApi';
import { marketLabel } from '../../utils/marketLabels';
import { parseMarketPlusImportFile, type ImportFile } from './marketPlusImportFile';
import { MarketPlusReadinessNotice } from './MarketPlusReadinessNotice';

const time = (value: string) => new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });

export function ProductMarketPlusHistory({ productId, initialExpanded = false }: { productId: number; initialExpanded?: boolean }) {
  const [expanded, setExpanded] = useState(initialExpanded);
  const [pending, setPending] = useState<(ImportFile & { filename: string }) | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<MarketPlusImportResult | null>(null);
  const [progress, setProgress] = useState('');
  const stop = useRef(false);
  useEffect(() => () => { stop.current = true; }, []);
  const input = useRef<HTMLInputElement>(null);
  const client = useQueryClient();
  const history = useQuery({ queryKey: ['marketplus-transmissions', productId], enabled: expanded, retry: false,
    queryFn: async ({ signal }) => (await marketPlusTransmissionApi.history(productId, signal)).data });

  const readFile = async (file: File) => {
    setError(null); setResult(null);
    try {
      if (file.size > 20_000_000) throw new Error('파일은 20MB 이하여야 합니다.');
      const payload: unknown = JSON.parse(await file.text());
      setPending({ ...parseMarketPlusImportFile(payload), filename: file.name });
    } catch (e) { setError(e instanceof Error ? e.message : '수집 파일을 읽지 못했습니다.'); }
  };
  const ingest = async () => {
    if (!pending) return;
    setBusy(true); setError(null); stop.current = false;
    const total: MarketPlusImportResult = { saved: 0, duplicate: 0, rejected: 0, items: [] };
    try {
      for (let i = 0; i < pending.pages.length; i++) {
        if (stop.current) { setError('가져오기를 중단했습니다. 남은 페이지는 저장하지 않았습니다. 같은 파일로 재시도할 수 있습니다.'); return; }
        const page = pending.pages[i];
        setProgress(`${i + 1}/${pending.pages.length}페이지 저장 중…`);
        const response = await marketPlusTransmissionApi.import(page.payload);
        total.saved += response.data.saved; total.duplicate += response.data.duplicate; total.rejected += response.data.rejected;
        total.items.push(...response.data.items.filter(item => item.result === 'REJECTED').map(item => ({ ...item, page: page.page, detail: `${page.page}페이지 · ${item.detail}` })));
        setResult({ ...total, items: [...total.items] });
      }
      setPending(null);
    } catch (e) {
      setError(isAxiosError(e) && e.response?.status === 400 ? (e.response.data?.message ?? '수집 파일과 판매 계정을 확인하세요.')
        : '저장 결과를 확인하지 못했습니다. 이력을 다시 조회하거나 같은 파일로 재시도하세요. 중복 저장은 방지됩니다.');
    } finally {
      setBusy(false); setProgress('');
      await Promise.all([client.invalidateQueries({ queryKey: ['marketplus-transmissions'] }), client.invalidateQueries({ queryKey: ['products'] })]);
    }
  };

  return <div style={{ margin: '12px 0' }}>
    <Button size="small" onClick={() => setExpanded(!expanded)}>지마켓·옥션 전송 이력 {expanded ? '접기' : '보기'}</Button>
    {expanded && <>
      <MarketPlusReadinessNotice />
      <Alert style={{ marginTop: 8 }} type="info" showIcon message="마켓플러스에서 수집한 전송 결과입니다."
        description="전송 성공은 현재 판매 상태나 모든 항목의 일치를 보장하지 않습니다. 수집하지 않은 기간·페이지의 이력은 표시되지 않습니다." />
      <div style={{ margin: '8px 0', display: 'flex', gap: 8 }}>
        <Button size="small" loading={history.isFetching} onClick={() => { void history.refetch(); }}>저장 이력 새로고침</Button>
        <Button size="small" disabled={busy} onClick={() => input.current?.click()}>수집 파일 가져오기</Button>
        <input ref={input} type="file" accept=".json,application/json" hidden onChange={e => {
          const file = e.target.files?.[0]; e.target.value = ''; if (file) void readFile(file);
        }} />
      </div>
      {error && <Alert type="error" message={error} />}
      {result && <Alert type={result.rejected > 0 ? 'warning' : 'success'} message={`이력 저장 ${result.saved}건 · 기존 이력 ${result.duplicate}건 · 제외/미확인 ${result.rejected}건`}
        description={result.items.filter(i => i.result === 'REJECTED').map(i => <div key={`${i.page ?? 1}:${i.row}`}>{i.row}행 · {i.externalId}: {i.detail}</div>)} />}
      {history.isError ? <Alert type="error" message="저장된 전송 이력을 조회하지 못했습니다."
        description={isAxiosError(history.error) && history.error.response?.status === 400 && typeof history.error.response.data?.message === 'string' ? history.error.response.data.message : undefined} />
        : history.isPending ? <p>전송 이력 조회 중…</p>
        : history.data?.length === 0 ? <p>이 상품의 수집 이력이 없습니다. 전송 성공·실패를 아직 판단할 수 없습니다.</p>
        : history.data?.map(({ observation: event, currentConnection }) => <div key={event.id} style={{ padding: '10px 0', borderBottom: '1px solid #e5e7eb', fontSize: 12 }}>
          <strong>{marketLabel(event.market)}</strong>{' '}
          <Tag color={event.outcome === 'FAILURE' ? 'red' : 'blue'}>{event.outcome === 'FAILURE' ? '전송 실패' : '전송 완료 · 판매 상태 미확인'}</Tag>
          {!currentConnection && <Tag>과거 연결 이력</Tag>}
          <div>{event.sellerAccount} · {event.externalId} · {event.transferType}</div>
          <div style={{ overflowWrap: 'anywhere', margin: '4px 0' }}>{event.detail}</div>
          {event.reasonCode === 'SOURCE_STATE_REVIEW_REQUIRED' && <div style={{ color: '#b45309' }}>카페24 진열·판매 상태와 전송 작업을 확인하세요. 일시 품절 상품을 임의로 판매 재개하지 않습니다.</div>}
          {event.outcome === 'FAILURE' && <a href="https://mp.cafe24.com/mp/queue/productList" target="_blank" rel="noopener noreferrer">마켓플러스에서 원인 확인·재전송</a>}
          <div style={{ color: '#64748b', marginTop: 4 }}>요청 {time(event.requestedAt)} · 완료 {time(event.completedAt)}<br />수집 {time(event.capturedAt)} · {event.actor}</div>
        </div>)}
      {history.isSuccess && history.data.length > 0 && <p style={{ color: '#64748b', fontSize: 12 }}>최근 관측 이력 최대 100건 · 화면의 시각은 한국 시간입니다. 같은 분에 발생한 동일 내용은 하나의 관측으로 표시됩니다.</p>}
    </>}
    <Modal open={pending !== null} title="전송 이력 가져오기" okText="이력 저장" onOk={() => { void ingest(); }}
      onCancel={() => { if (!busy) setPending(null); }} confirmLoading={busy} closable={!busy} maskClosable={!busy} cancelButtonProps={{ disabled: busy }}>
      <p>{pending?.filename} · {pending?.count}행</p>
      <p>{pending?.coverage}</p>
      {busy && <p role="status">{progress} <Button size="small" onClick={() => { stop.current = true; }}>현재 페이지 처리 후 중단</Button></p>}
      <p>파일에 포함된 여러 상품의 이력을 저장합니다. 각 상품의 판매 계정·카페24 번호·마켓 상품번호가 시스템 연결과 일치하는 행만 저장됩니다.</p>
      <p>외부 상품을 전송하거나 판매 상태를 변경하는 작업은 아닙니다.</p>
      {error && <Alert type="error" message={error} />}
    </Modal>
  </div>;
}
