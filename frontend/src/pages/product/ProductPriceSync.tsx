import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Modal, Select, Table, Tag } from 'antd';
import { marketPriceSyncApi, type PriceSyncReview, type PriceSyncItem } from '../../api/marketPriceSyncApi';
import { marketLabel } from '../../utils/marketLabels';
const markets = ['SMART_STORE', 'COUPANG', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'];
const states: Record<string, string> = { READY: '반영 가능', SKIPPED: '제외', CHECK: '현재 가격 조회 대기', VERIFY: '실제 반영 재조회 대기', CONFIRMED_PRICE: '판매가 일치 확인', BLOCKED: '전송 보류', STALE: '다시 검토 필요', FAILED_MISMATCH: '반영 불일치', UNKNOWN: '결과 미확인' };
const money = (value: number | null) => value == null ? '미확인' : `${Number(value).toLocaleString('ko-KR')}원`;
export function ProductPriceSync({ productIds, onClose }: { productIds: number[]; onClose: () => void }) {
  const [selected, setSelected] = useState(markets);
  const [review, setReview] = useState<PriceSyncReview | null>(null);
  const [watchId, setWatchId] = useState<string | null>(null);
  const [historyId, setHistoryId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const recent = useQuery({ queryKey: ['price-sync-recent'], queryFn: async ({ signal }) => (await marketPriceSyncApi.recent(signal)).data, retry: false });
  const current = useQuery({ queryKey: ['price-sync-review', watchId], queryFn: async ({ signal }) => (await marketPriceSyncApi.get(watchId!, signal)).data, enabled: !!watchId, refetchInterval: 5000, retry: false });
  const history = useQuery({ queryKey: ['price-sync-history', historyId], queryFn: async ({ signal }) => (await marketPriceSyncApi.history(historyId!, signal)).data, enabled: !!historyId, retry: false });
  const shown = watchId ? current.isError ? null : current.data : review;
  const preview = async (ids = productIds, targets = selected) => {
    setBusy(true); setError(null); setReview(null); setWatchId(null); setHistoryId(null);
    try { setReview((await marketPriceSyncApi.preview(ids, targets)).data); }
    catch { setError('가격 검토 내용을 가져오지 못했습니다. 다시 검토하세요.'); }
    finally { setBusy(false); }
  };
  const commit = async () => {
    if (!review || review.committed) return;
    setBusy(true); setError(null);
    try {
      const saved = (await marketPriceSyncApi.commit(review.id)).data;
      queryClient.setQueryData(['price-sync-review', saved.id], saved); setWatchId(saved.id); setReview(saved);
      await queryClient.invalidateQueries({ queryKey: ['price-sync-recent'] });
    } catch { setError('접수 결과를 확인하지 못했습니다. 아래 버튼으로 같은 검토를 다시 접수하거나 작업 내역을 조회하세요. 중복 작업은 생성하지 않습니다.'); }
    finally { setBusy(false); }
  };
  const retryItems = shown?.items.filter(i => ['BLOCKED', 'STALE', 'FAILED_MISMATCH', 'UNKNOWN'].includes(i.state)) ?? [];
  return <Modal open title="마켓 판매가 반영" width={1120} onCancel={() => { if (!busy) onClose(); }} footer={null} maskClosable={!busy} closable={!busy}>
    <Alert type="info" showIcon message="마켓별 계산 가격을 검토한 뒤 반영합니다. 실제 판매가를 재조회해 완료 여부를 확인합니다."
      description="현재 스마트스토어·쿠팡·카페24 가격 단독 반영을 지원합니다. 미지원 마켓, 판매 중지, 연결 해제, 옵션 연결이나 마켓플러스 전달 범위가 불명확한 상품은 사유와 함께 보류합니다." />
    <p>선택한 상품 {productIds.length.toLocaleString()}개 · 검토당 최대 500개</p>
    <Checkbox.Group value={selected} disabled={busy} options={markets.map(m => ({ label: marketLabel(m), value: m }))} onChange={v => setSelected(v as string[])} />
    <p><Button onClick={() => { void preview(); }} loading={busy} disabled={!productIds.length || productIds.length > 500 || !selected.length}>선택 상품 가격 검토</Button></p>
    {recent.isError ? <Alert type="error" message="최근 가격 작업 조회 실패" action={<Button onClick={() => { void recent.refetch(); }}>다시 조회</Button>} /> : <Select style={{ width: '100%' }} placeholder="최근 가격 검토·작업 내역" value={watchId ?? undefined}
      disabled={busy} options={recent.data?.map(r => ({ value: r.id, label: `${new Date(r.createdAt).toLocaleString('ko-KR')} · ${r.committed ? '접수된 작업' : '미접수 검토'} · ${r.total ?? r.items.length}건 · ${r.actor}` }))}
      onChange={id => { setWatchId(id); setReview(null); setError(null); }} />}
    {error && <Alert type="error" message={error} style={{ marginTop: 12 }} />}
    {current.isError && watchId && <Alert type="error" message="작업 결과를 조회하지 못했습니다. 이전 결과를 완료로 표시하지 않습니다." action={<Button onClick={() => { void current.refetch(); }}>다시 조회</Button>} />}
    {shown && <>
      <p>{shown.committed ? '접수 후 작업 기록' : `검토 유효 시간: ${new Date(shown.expiresAt).toLocaleTimeString('ko-KR')}`} · {shown.items.filter(i => i.state === 'CONFIRMED_PRICE').length}건 판매가 일치</p>
      <Table<PriceSyncItem> size="small" rowKey={r => `${r.productId}:${r.market}`} dataSource={shown.items} pagination={{ pageSize: 20, showSizeChanger: false }} scroll={{ x: 900, y: 400 }} columns={[
        { title: 'SB코드 / 마켓', key: 'product', width: 190, render: (_, r) => <>{r.sbCode ?? r.productId}<br /><small>{marketLabel(r.market)} · {r.listingId ?? '미등록'}</small></> },
        { title: '반영할 판매가', dataIndex: 'expectedPrice', width: 125, render: money },
        { title: '마켓 재조회 가격', dataIndex: 'observedPrice', width: 140, render: money },
        { title: '상태·사유', key: 'state', render: (_, r) => <><Tag color={r.state === 'CONFIRMED_PRICE' ? 'green' : ['CHECK', 'VERIFY', 'READY'].includes(r.state) ? 'blue' : 'orange'}>{states[r.state] ?? r.state}</Tag><div>{r.detail}</div>{r.checkedAt && <small>{new Date(r.checkedAt).toLocaleString('ko-KR')} 확인</small>}</> },
        { title: '전송 이력', key: 'history', width: 90, render: (_, r) => r.id ? <Button size="small" onClick={() => setHistoryId(r.id)}>{r.writes}회 · 보기</Button> : '—' },
      ]} />
      {!shown.committed && shown.actor && <Button type="primary" loading={busy} disabled={!shown.items.some(i => i.state === 'READY')} onClick={() => { if (watchId) { setReview(shown); setWatchId(null); } else void commit(); }}>{watchId ? '이 검토로 돌아가기' : '검토한 가격 반영 접수'}</Button>}
      {!!retryItems.length && <Button disabled={busy} onClick={() => { void preview([...new Set(retryItems.map(i => i.productId))], [...new Set(retryItems.map(i => i.market))]); }}>실패·보류 상품 최신 가격으로 재검토</Button>}
    </>}
    <Modal open={!!historyId} title="판매가 전송·조회 이력" footer={null} onCancel={() => setHistoryId(null)}>
      {history.isError ? <Alert type="error" message="이력 조회 실패" action={<Button onClick={() => { void history.refetch(); }}>다시 조회</Button>} /> : history.data?.map(a => <p key={a.id}><strong>{new Date(a.recordedAt).toLocaleString('ko-KR')}</strong><br />{a.detail}</p>)}
    </Modal>
  </Modal>;
}
