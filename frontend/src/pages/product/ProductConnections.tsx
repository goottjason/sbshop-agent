import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Input, Modal, Tag } from 'antd';
import { marketConnectionApi, type ProductConnection, type ConnectionResult } from '../../api/marketConnectionApi';
import { marketLabel } from '../../utils/marketLabels';

const labels: Record<string, string> = {
  REGISTERED: '재등록 확인', LINKED: '연결 기록 유지', DETACHED_DELETED: '삭제 확인 · 연결 해제', DETACHED_PROHIBITED: '판매금지 · 연결 해제',
  OBSERVED: '조회 기록 저장', DETACHED: '연결 해제 완료', ALREADY_DETACHED: '해제 이력 유지', STALE: '다시 확인 필요',
  PRESENT: '상품 존재', OUT_OF_STOCK: '일시 품절', STOPPED: '판매 중지', PROHIBITED: '판매금지', DELETED: '상품 부재', UNKNOWN: '미확인', ACCOUNT_REVIEW_REQUIRED: '판매 계정 확인 필요',
};
function evidenceText(text: string, source: string) {
  try {
    const value = JSON.parse(text);
    const account = source === 'USER_CONFIRMATION' && value.accountReference ? `판매 계정: ${value.accountReference} · ` : '';
    return `${account}${value.code ?? ''} · ${value.detail ?? ''}`;
  }
  catch { return '근거 내용 조회 실패'; }
}
export function ProductConnections({ productId, onChanged, disabled = false }: { productId: number; onChanged: () => void; disabled?: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ConnectionResult | null>(null);
  const [selected, setSelected] = useState<ProductConnection | null>(null);
  const [account, setAccount] = useState('');
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const client = useQueryClient();
  const rows = useQuery({ queryKey: ['product-connections', productId], enabled: expanded, retry: false,
    queryFn: async ({ signal }) => (await marketConnectionApi.list(productId, signal)).data });
  const history = useQuery({ queryKey: ['product-connection-history', productId], enabled: expanded, retry: false,
    queryFn: async ({ signal }) => (await marketConnectionApi.history(productId, signal)).data });
  const run = async (row: ProductConnection, manual = false) => {
    setBusy(true); setError(null); setResult(null);
    try {
      const response = manual ? await marketConnectionApi.prohibit(productId, row, account.trim(), reason.trim()) : await marketConnectionApi.inspect(productId, row);
      setResult(response.data); setSelected(null);
      await Promise.all([
        client.invalidateQueries({ queryKey: ['product-connections', productId] }),
        client.invalidateQueries({ queryKey: ['product-connection-history', productId] }),
        client.invalidateQueries({ queryKey: ['products'] }),
      ]);
      onChanged();
    } catch { setError('결과를 확인하지 못했습니다. 연결 기록을 다시 조회하세요. 저장 여부를 확인하기 전 완료로 표시하지 않습니다.'); }
    finally { setBusy(false); }
  };
  return <div style={{ margin: '12px 0' }}>
    <Button size="small" onClick={() => setExpanded(!expanded)}>마켓 연결·해제 이력 {expanded ? '접기' : '보기'}</Button>
    {expanded && <>
      {disabled && <Alert type="warning" message="편집 중인 내용을 먼저 저장하거나 상세 화면을 다시 열어 주세요." />}
      <Alert style={{ marginTop: 8 }} type="info" showIcon message="상태 확인에서 삭제·판매금지가 확정되면 해당 연결을 해제합니다."
        description="일시 품절은 연결을 유지합니다. 해제 후 상품번호와 주문 이력을 보존하며, 조회 오류는 삭제로 처리하지 않습니다." />
      {error && <Alert type="error" message={error} action={<Button onClick={() => { void rows.refetch(); void history.refetch(); }}>다시 조회</Button>} />}
      {result && <Alert type={result.result === 'STALE' ? 'warning' : 'info'} message={labels[result.result] ?? result.result} description={result.detail} />}
      {rows.isError ? <Alert type="error" message="연결 기록 조회 실패" action={<Button onClick={() => { void rows.refetch(); }}>재시도</Button>} /> : rows.isPending ? <p>연결 기록 조회 중…</p> : rows.data?.length === 0 ? <p>등록된 연결 기록이 없습니다.</p> : rows.data?.map(row => <div key={`${row.registrationId}:${row.market}`} style={{ borderBottom: '1px solid #e5e7eb', padding: '10px 0' }}>
        <strong>{marketLabel(row.market)}</strong> <Tag color={row.state === 'LINKED' ? 'default' : 'red'}>{labels[row.state]}</Tag>
        <div style={{ fontSize: 12, margin: '4px 0' }}>마켓 상품번호: {row.externalId ?? '미확인'}</div>
        {row.writeBlock && <p style={{ color: '#b45309', fontSize: 12 }}>{row.writeBlock}</p>}
        <Button size="small" disabled={disabled || busy || !row.inspectionSupported || !row.externalId || row.state !== 'LINKED'} onClick={() => { void run(row); }}>현재 상태 확인</Button>{' '}
        <Button size="small" disabled={disabled || busy || !row.externalId || row.state === 'DETACHED_PROHIBITED'} onClick={() => { setSelected(row); setAccount(''); setReason(''); setConfirmed(false); setError(null); }}>판매금지 확인 기록</Button>
        {!row.inspectionSupported && <span style={{ color: '#64748b', fontSize: 12 }}> · 자동 상태 판정 준비 중</span>}
      </div>)}
      <p style={{ fontWeight: 600, marginTop: 12 }}>최근 확인·해제 이력</p>
      {history.isError ? <Alert type="error" message="이력 조회 실패" action={<Button onClick={() => { void history.refetch(); }}>재시도</Button>} /> : history.data?.map(event => <div key={event.id} style={{ fontSize: 12, borderBottom: '1px solid #eee', padding: '6px 0' }}>
        <strong>{marketLabel(event.market)} · {labels[event.result] ?? event.result} · {labels[event.observedState] ?? event.observedState}</strong>
        <div>{event.externalId} · {new Date(event.observedAt).toLocaleString('ko-KR')} · {event.actor}</div>
        <div style={{ overflowWrap: 'anywhere' }}>{evidenceText(event.evidence, event.source)}</div>
      </div>)}
      {history.isSuccess && history.data.length === 0 && <p>새 연결 관리 흐름의 이력이 없습니다.</p>}
    </>}
    <Modal open={selected !== null} title="판매금지 확인 · 연결 해제" onCancel={() => { if (!busy) setSelected(null); }}
      maskClosable={!busy} closable={!busy} confirmLoading={busy} okText="확인 근거 저장·연결 해제"
      okButtonProps={{ disabled: !confirmed || !account.trim() || !reason.trim() }} cancelButtonProps={{ disabled: busy }}
      onOk={() => { if (selected) void run(selected, true); }}>
      <p>{selected && marketLabel(selected.market)} · 마켓 상품번호 {selected?.externalId}</p>
      <p>일시 품절·일반 판매중지가 아닌 영구 판매금지를 확인한 경우에 기록하세요. 해당 마켓 연결만 해제하고 과거 상품번호는 보존합니다.</p>
      <label>확인한 판매 계정<Input aria-label="확인한 판매 계정" maxLength={200} value={account} onChange={e => setAccount(e.target.value)} /></label>
      <label>금지 사유·확인 근거<Input.TextArea aria-label="금지 사유·확인 근거" maxLength={1000} rows={3} value={reason} onChange={e => setReason(e.target.value)} /></label>
      <Checkbox checked={confirmed} onChange={e => setConfirmed(e.target.checked)}>위 계정·상품번호의 영구 판매금지를 확인했습니다.</Checkbox>
      {error && <Alert type="error" message={error} />}
    </Modal>
  </div>;
}
