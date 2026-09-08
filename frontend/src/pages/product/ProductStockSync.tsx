import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Modal, Select, Table, Tag } from 'antd';
import { marketStockSyncApi, type StockSyncReview, type StockSyncItem } from '../../api/marketStockSyncApi';
import { marketLabel } from '../../utils/marketLabels';
const markets = ['COUPANG', 'CAFE24', 'SMART_STORE', 'ELEVEN_STREET'];
const states: Record<string, string> = { READY: '실행 전 확인 대상', SKIPPED: '제외', CHECK: '현재 수량 조회 대기', VERIFY: '실제 반영 재조회 대기', CONFIRMED_QUANTITY: '판매용 수량 일치 확인', BLOCKED: '전송 보류', STALE: '다시 검토 필요', FAILED_MISMATCH: '반영 불일치', UNKNOWN: '결과 미확인' };
const quantity = (value: number | null) => value == null ? '미확인' : `${Number(value).toLocaleString('ko-KR')}개`;
const elevenstSaleStates: Record<string, string> = { '101': '승인대기', '102': '승인전', '103': '판매중', '104': '품절', '105': '전시중지', '106': '판매정상종료', '107': '판매강제종료', '108': '판매금지' };
const elevenstStockStates: Record<string, string> = { '01': '사용', '02': '품절' };
const observedState = (market: string, value: string | null | undefined, kind: 'sale' | 'stock') => {
  if (!value?.trim()) return '미확인';
  if (market !== 'ELEVEN_STREET') return value;
  const label = (kind === 'sale' ? elevenstSaleStates : elevenstStockStates)[value];
  return `${label ?? '확인 필요'} (${value})`;
};
export function ProductStockSync({ productIds, onClose }: { productIds: number[]; onClose: () => void }) {
  const [selected, setSelected] = useState(markets);
  const [review, setReview] = useState<StockSyncReview | null>(null);
  const [watchId, setWatchId] = useState<string | null>(null);
  const [historyId, setHistoryId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const recent = useQuery({ queryKey: ['stock-sync-recent'], queryFn: async ({ signal }) => (await marketStockSyncApi.recent(signal)).data, retry: false });
  const current = useQuery({ queryKey: ['stock-sync-review', watchId], queryFn: async ({ signal }) => (await marketStockSyncApi.get(watchId!, signal)).data, enabled: !!watchId, refetchInterval: query => query.state.data?.items.some(item => ['CHECK', 'VERIFY'].includes(item.state)) ? 5000 : false, retry: false });
  const history = useQuery({ queryKey: ['stock-sync-history', historyId], queryFn: async ({ signal }) => (await marketStockSyncApi.history(historyId!, signal)).data, enabled: !!historyId, retry: false });
  const shown = watchId ? current.isError ? null : current.data : review;
  const preview = async (ids = productIds, targets = selected) => {
    setBusy(true); setError(null); setReview(null); setWatchId(null); setHistoryId(null);
    try { setReview((await marketStockSyncApi.preview(ids, targets)).data); }
    catch { setError('수량 검토 내용을 가져오지 못했습니다. 다시 검토하세요.'); }
    finally { setBusy(false); }
  };
  const commit = async () => {
    if (!review || review.committed) return;
    setBusy(true); setError(null);
    try {
      const saved = (await marketStockSyncApi.commit(review.id)).data;
      queryClient.setQueryData(['stock-sync-review', saved.id], saved); setWatchId(saved.id); setReview(saved);
      await queryClient.invalidateQueries({ queryKey: ['stock-sync-recent'] });
    } catch { setError('접수 결과를 확인하지 못했습니다. 아래 버튼으로 같은 검토를 다시 접수하거나 작업 내역을 조회하세요. 중복 작업은 생성하지 않습니다.'); }
    finally { setBusy(false); }
  };
  const retryItems = shown?.items.filter(i => ['BLOCKED', 'STALE', 'FAILED_MISMATCH', 'UNKNOWN'].includes(i.state)) ?? [];
  return <Modal open title="마켓 판매용 수량 반영" width={1200} onCancel={() => { if (!busy) onClose(); }} footer={null} maskClosable={!busy} closable={!busy}>
    <Alert type="info" showIcon message="판매용 설정 수량을 검토해 반영하고, 마켓에서 재조회한 실제 수량과 필요한 판매 상태로 완료 여부를 확인합니다."
      description="소싱처가 재고 있음이면 판매용 설정 수량(기본 300개), 명시 품절이면 0개가 목표입니다. 스마트스토어 원상품·단일 옵션, 쿠팡 단일 옵션, 카페24 본상품의 확인 가능한 품목을 지원합니다. 11번가는 단일 재고항목의 수량·0개 처리와 허용된 상태의 판매 재개를 지원합니다. 영구 판매금지·강제종료는 재개하지 않습니다. 수집 실패를 품절로 간주하지 않으며, 제외 대상과 실패 사유는 작업에 표시합니다." />
    {selected.includes('ELEVEN_STREET') && <p style={{ fontSize: 12, color: '#667085' }}>11번가는 양수 목표일 때 실제 수량·판매중·재고항목 사용 상태를 확인합니다. 0개 목표는 실제 수량 0개와 구매 불가 상태를 함께 확인하며, 전시중지(105)와 품절(104)을 구분합니다.</p>}
    <p>선택한 상품 {productIds.length.toLocaleString()}개 · 검토당 최대 500개</p>
    <Checkbox.Group value={selected} disabled={busy} options={markets.map(m => ({ label: marketLabel(m), value: m }))} onChange={v => { setSelected(v as string[]); setReview(null); setWatchId(null); setHistoryId(null); }} />
    <p><Button onClick={() => { void preview(); }} loading={busy} disabled={!productIds.length || productIds.length > 500 || !selected.length}>선택 상품 수량 검토</Button></p>
    {recent.isError ? <Alert type="error" message="최근 수량 작업 조회 실패" action={<Button onClick={() => { void recent.refetch(); }}>다시 조회</Button>} /> : <Select style={{ width: '100%' }} placeholder="최근 수량 검토·작업 내역" value={watchId ?? undefined}
      disabled={busy} options={recent.data?.map(r => ({ value: r.id, label: `${new Date(r.createdAt).toLocaleString('ko-KR')} · ${r.committed ? '접수된 작업' : '미접수 검토'} · ${r.total ?? r.items.length}건 · ${r.actor}` }))}
      onChange={id => { setWatchId(id); setReview(null); setError(null); }} />}
    {error && <Alert type="error" message={error} style={{ marginTop: 12 }} />}
    {current.isError && watchId && <Alert type="error" message="작업 결과를 조회하지 못했습니다. 이전 결과를 완료로 표시하지 않습니다." action={<Button onClick={() => { void current.refetch(); }}>다시 조회</Button>} />}
    {shown && <>
      <p>{shown.committed ? '접수 후 작업 기록' : `검토 유효 시간: ${new Date(shown.expiresAt).toLocaleTimeString('ko-KR')}`} · {shown.items.filter(i => i.state === 'CONFIRMED_QUANTITY').length}건 판매용 수량 일치</p>
      <Table<StockSyncItem> size="small" rowKey={r => `${r.productId}:${r.market}`} dataSource={shown.items} pagination={{ pageSize: 20, showSizeChanger: false }} scroll={{ x: 1100, y: 400 }} columns={[
        { title: 'SB코드 / 마켓', key: 'product', width: 190, render: (_, r) => <>{r.sbCode ?? r.productId}<br /><small>{marketLabel(r.market)} · {r.listingId ?? '미등록'}</small></> },
        { title: '반영할 판매용 수량', dataIndex: 'expectedQuantity', width: 125, render: quantity },
        { title: '마켓 재조회 수량', dataIndex: 'observedQuantity', width: 140, render: quantity },
        { title: '재조회 판매·재고 상태', key: 'observedStates', width: 185, render: (_, r) => <><div style={r.market === 'ELEVEN_STREET' && ['107', '108'].includes(r.observedSaleState ?? '') ? { color: '#b42318', fontWeight: 600 } : undefined}>판매: {observedState(r.market, r.observedSaleState, 'sale')}</div><small>재고항목: {observedState(r.market, r.observedStockState, 'stock')}</small></> },
        { title: '상태·사유', key: 'state', render: (_, r) => <><Tag color={r.state === 'CONFIRMED_QUANTITY' ? 'green' : ['CHECK', 'VERIFY', 'READY'].includes(r.state) ? 'blue' : 'orange'}>{states[r.state] ?? r.state}</Tag><div>{r.detail}</div>{r.checkedAt && <small>{new Date(r.checkedAt).toLocaleString('ko-KR')} 확인</small>}</> },
        { title: '전송 이력', key: 'history', width: 90, render: (_, r) => r.id ? <Button size="small" onClick={() => setHistoryId(r.id)}>{r.writes}회 · 보기</Button> : '—' },
      ]} />
      {!shown.committed && shown.actor && <Button type="primary" loading={busy} disabled={!shown.items.some(i => i.state === 'READY')} onClick={() => { if (watchId) { setReview(shown); setWatchId(null); } else void commit(); }}>{watchId ? '이 검토로 돌아가기' : '검토한 수량 반영 접수'}</Button>}
      {!!retryItems.length && <Button disabled={busy} onClick={() => { void preview([...new Set(retryItems.map(i => i.productId))], [...new Set(retryItems.map(i => i.market))]); }}>실패·보류 상품 최신 수량으로 재검토</Button>}
    </>}
    <Modal open={!!historyId} title="판매용 수량 전송·조회 이력" footer={null} onCancel={() => setHistoryId(null)}>
      {history.isError ? <Alert type="error" message="이력 조회 실패" action={<Button onClick={() => { void history.refetch(); }}>다시 조회</Button>} /> : history.data?.map(a => <p key={a.id}><strong>{new Date(a.recordedAt).toLocaleString('ko-KR')}</strong><br />{a.detail}</p>)}
    </Modal>
  </Modal>;
}
