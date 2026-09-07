import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Modal, Select, Table, Tag } from 'antd';
import { isAxiosError } from 'axios';
import { marketFieldSyncApi, type FieldSyncItem, type FieldSyncReview } from '../../api/marketFieldSyncApi';
import { marketLabel } from '../../utils/marketLabels';
import { editFieldLabel } from './productEditDisplay';
import { ProductHtmlPreview } from './ProductHtmlPreview';
import './productFieldSync.css';

const markets = ['SMART_STORE', 'COUPANG', 'CAFE24', 'ELEVEN_STREET', 'GMARKET', 'AUCTION'];
const fields = ['detailHtml', 'hostedImages', 'name', 'brand', 'manufacturer', 'barcode', 'weight', 'category', 'baseName', 'originalName', 'capacity', 'measureUnit', 'bundleQuantity', 'origin', 'hsCode', 'searchKeywords', 'sourceImages'];
const running = new Set(['PREPARE', 'CHECK', 'VERIFY', 'AWAITING_APPROVAL']);
const retryable = new Set(['SKIPPED', 'BLOCKED', 'STALE', 'EXPIRED', 'FAILED_MISMATCH', 'UNKNOWN']);
const states: Record<string, string> = { PREPARE: '전송값 준비 중', DRAFT: '검토·동의 대기', CHECK: '마켓 현재값 조회 대기', VERIFY: '전송 후 재조회 대기', AWAITING_APPROVAL: '마켓 심사 대기', CONFIRMED_FIELDS: '선택 필드 일치 확인', SKIPPED: '제외·지원 확인 필요', BLOCKED: '전송 보류', STALE: '새 검토 필요', EXPIRED: '검토 만료', FAILED_MISMATCH: '필드 불일치', UNKNOWN: '결과 미확인' };
const time = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR') : '없음';
const errorMessage = (error: unknown, fallback: string) => isAxiosError(error) && typeof error.response?.data?.message === 'string' ? `${fallback} ${error.response.data.message}` : fallback;
function Value({ field, value, label }: { field: string; value: string | undefined; label: string }) {
  if (value === undefined) return <span className="pfs-muted">미확인·준비 전</span>;
  if (field === 'detailHtml') return <details className="pfs-html"><summary>{value.length.toLocaleString()}자 · 상세 HTML 보기</summary><ProductHtmlPreview html={value} label={label} /></details>;
  return <div className="pfs-value">{value || '(비어 있음)'}</div>;
}
export function ProductFieldSyncModal({ productIds, onClose }: { productIds: number[]; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [selectedMarkets, setSelectedMarkets] = useState(['SMART_STORE', 'CAFE24']);
  const [selectedFields, setSelectedFields] = useState(['detailHtml']);
  const [watchId, setWatchId] = useState<string | null>(null);
  const [historyItem, setHistoryItem] = useState<FieldSyncItem | null>(null);
  const [acceptApproval, setAcceptApproval] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uncertain, setUncertain] = useState<{ id: string; acceptApproval: boolean } | null>(null);
  const recent = useQuery({ queryKey: ['field-sync-recent'], queryFn: async ({ signal }) => (await marketFieldSyncApi.recent(signal)).data, retry: false });
  const current = useQuery({ queryKey: ['field-sync-review', watchId], enabled: !!watchId, retry: false,
    queryFn: async ({ signal }) => (await marketFieldSyncApi.get(watchId!, signal)).data,
    refetchInterval: query => query.state.status === 'error' ? false : query.state.data?.items.some(item => running.has(item.state)) ? 5000 : false });
  const history = useQuery({ queryKey: ['field-sync-history', historyItem?.id], enabled: !!historyItem?.id, retry: false,
    queryFn: async ({ signal }) => (await marketFieldSyncApi.history(historyItem!.id!, signal)).data });
  const shown = current.isError ? undefined : current.data;
  useEffect(() => { if (current.data?.committed && uncertain?.id === current.data.id) { setUncertain(null); setError(null); } }, [current.data, uncertain]);
  const install = (value: FieldSyncReview) => { queryClient.setQueryData(['field-sync-review', value.id], value); setWatchId(value.id); };
  const preview = async (ids = productIds, targetMarkets = selectedMarkets, targetFields = selectedFields) => {
    setBusy(true); setError(null); setAcceptApproval(false); setHistoryItem(null); setUncertain(null);
    try { install((await marketFieldSyncApi.preview(ids, targetMarkets, targetFields)).data); await recent.refetch(); }
    catch (e) { setError(errorMessage(e, '검토 생성 결과를 확인하지 못했습니다. 최근 검토를 다시 조회해 생성된 내역을 먼저 확인하세요.')); void recent.refetch(); }
    finally { setBusy(false); }
  };
  const commit = async (id: string, consent: boolean) => {
    setBusy(true); setError(null);
    try { install((await marketFieldSyncApi.commit(id, consent)).data); setUncertain(null); await recent.refetch(); }
    catch (e) { setUncertain({ id, acceptApproval: consent }); setError(errorMessage(e, '접수 결과를 확인하지 못했습니다. 같은 검토 ID로 다시 조회하거나 접수 재시도할 수 있습니다.')); void current.refetch(); }
    finally { setBusy(false); }
  };
  const drafts = shown?.items.filter(item => item.state === 'DRAFT') ?? [];
  const approvalNeeded = drafts.some(item => item.requiresApproval);
  const expired = !!shown && Date.parse(shown.expiresAt) <= Date.now();
  const reset = () => { setWatchId(null); setAcceptApproval(false); setError(null); setUncertain(null); setHistoryItem(null); };
  return <Modal open title="상품 필드 마켓 반영" width={1240} footer={null} onCancel={() => { if (!busy) onClose(); }} maskClosable={!busy} closable={!busy} className="pfs-modal">
    <Alert type="info" showIcon message="DB에 저장된 값 → 마켓별 전송값 검토 → 반영 접수 → 실제 값·심사 결과 재조회"
      description="선택한 필드만 확인합니다. 가격과 판매용 수량은 각각의 반영 메뉴를 사용하세요. G마켓·옥션의 마켓플러스 전송·최종값은 별도 확인합니다." />
    <div className="pfs-selection"><strong>상품 {productIds.length.toLocaleString()}개 · 검토당 최대 500개</strong>
      <Checkbox.Group aria-label="필드 반영 마켓" value={selectedMarkets} disabled={busy} options={markets.map(value => ({ value, label: marketLabel(value) }))}
        onChange={value => { setSelectedMarkets(value as string[]); reset(); }} />
      <Select mode="multiple" aria-label="마켓에 반영할 필드" value={selectedFields} disabled={busy} placeholder="반영할 필드 선택"
        options={fields.map(value => ({ value, label: editFieldLabel(value) }))} onChange={value => { setSelectedFields(value); reset(); }} />
      <small>지원하지 않는 필드·잠긴 상품·해제된 연결은 준비 결과에 사유가 표시됩니다. 원본 이미지 등 내부 값이 마켓 필드로 연결되는지는 서버에서 검증합니다.</small>
      <Button type="primary" loading={busy} disabled={!productIds.length || productIds.length > 500 || !selectedMarkets.length || !selectedFields.length}
        onClick={() => { void preview(); }}>선택 상품 전송값 준비</Button>
    </div>
    <div className="pfs-recent"><Select aria-label="최근 필드 검토" value={watchId ?? undefined} placeholder="최근 20개 검토·접수 내역에서 복구" disabled={busy}
      options={recent.data?.map(row => ({ value: row.id, label: `${time(row.createdAt)} · ${row.committed ? '접수됨' : row.preparing ? '준비 중' : '미접수 검토'} · ${row.total}건` }))}
      onChange={id => { reset(); setWatchId(id); }} />
      <Button size="small" loading={recent.isFetching} onClick={() => { void recent.refetch(); }}>최근 검토 다시 조회</Button>
      {watchId && <Button size="small" loading={current.isFetching} onClick={() => { void current.refetch(); }}>현재 작업 다시 조회</Button>}</div>
    {recent.isError && <Alert type="error" message="최근 필드 작업 조회 실패. 다시 조회하여 응답을 받지 못한 검토를 복구하세요." />}
    {error && <Alert type="error" message={error} />}
    {uncertain && !current.data?.committed && <Button disabled={busy} onClick={() => { void commit(uncertain.id, uncertain.acceptApproval); }}>같은 검토 접수 재시도</Button>}
    {watchId && current.isPending && <p role="status">마켓별 검토 상태 조회 중…</p>}
    {watchId && current.isError && <Alert type="error" message="작업 상태를 조회하지 못했습니다. 이전 조회 결과를 완료로 표시하지 않습니다." />}
    {shown && <>
      <div className="pfs-summary"><strong>{shown.preparing ? '마켓별 전송값 준비 중' : shown.committed ? '접수 후 처리·확인 내역' : '전송 예정값을 확인하세요'}</strong>
        <span>전체 {shown.total}건 중 선택 필드 일치 {shown.items.filter(item => item.state === 'CONFIRMED_FIELDS').length}건 · 대기 {shown.items.filter(item => running.has(item.state)).length}건 · 제외·조치 필요 {shown.items.filter(item => retryable.has(item.state)).length}건</span>
        {!shown.committed && <small>검토 유효 기한 {time(shown.expiresAt)}{expired && ' · 만료됨'}</small>}</div>
      <Table<FieldSyncItem> size="small" rowKey={item => item.id ?? `${item.productId}:${item.market}`} dataSource={shown.items}
        pagination={{ pageSize: 15, showSizeChanger: false }} scroll={{ x: 1030, y: 420 }} columns={[
          { title: '상품 / 마켓', key: 'product', width: 170, render: (_, item) => <><strong>{item.sbCode ?? `상품 #${item.productId}`}</strong><div>{marketLabel(item.market)}</div><small>{item.listingId ?? '등록번호 없음'} · DB 버전 {item.revision}</small></> },
          { title: '필드별 전송 예정값 / 마켓 재조회값', key: 'values', width: 490, render: (_, item) => <div className="pfs-field-values">{item.fields.map(field => <div key={field}><strong>{editFieldLabel(field)}</strong><div className="pfs-pair"><div><small>전송 예정</small><Value field={field} value={item.expectedValues[field]} label={`${item.id} ${field} 전송 예정`} /></div><div><small>마켓 재조회</small><Value field={field} value={item.observedValues[field]} label={`${item.id} ${field} 마켓 재조회`} /></div></div></div>)}</div> },
          { title: '상태·사유', key: 'state', render: (_, item) => <><Tag color={item.state === 'CONFIRMED_FIELDS' ? 'green' : retryable.has(item.state) ? 'red' : 'blue'}>{states[item.state] ?? item.state}</Tag>
            {item.requiresApproval && <Tag color={item.state === 'CONFIRMED_FIELDS' ? 'green' : 'orange'}>{item.state === 'CONFIRMED_FIELDS' ? '심사 완료 확인' : '마켓 심사 필요'}</Tag>}<div className="pfs-detail">{item.detail}</div>
            {item.checkedAt && <small>{time(item.checkedAt)} 확인</small>}{item.nextRunAt && running.has(item.state) && <div><small>다음 조회 예정 {time(item.nextRunAt)}</small></div>}
            <div className="pfs-actions">{item.id && <Button size="small" onClick={() => setHistoryItem(item)}>이력 · 전송 {item.writes}회</Button>}
              {retryable.has(item.state) && <Button size="small" disabled={busy} onClick={() => { void preview([item.productId], [item.market], item.fields); }}>이 상품·마켓 재검토</Button>}</div></> },
        ]} />
      {!shown.committed && <div className="pfs-commit">
        {shown.preparing && <Alert type="info" message="전송값 준비가 끝나면 지원 여부와 목표값을 검토할 수 있습니다. 아직 접수할 수 없습니다." />}
        {approvalNeeded && <Checkbox checked={acceptApproval} disabled={busy} onChange={event => setAcceptApproval(event.target.checked)}>전송 예정값을 확인했으며, 심사가 필요한 마켓의 수정 심사 요청에 동의합니다.</Checkbox>}
        <Button type="primary" loading={busy} disabled={shown.preparing || !drafts.length || expired || approvalNeeded && !acceptApproval}
          onClick={() => { void commit(shown.id, acceptApproval); }}>검토한 {drafts.length}건 반영 접수</Button>
        <small>접수는 완료가 아닙니다. 필요한 심사와 선택한 모든 필드의 재조회가 일치해야 해당 상품·마켓이 확인 완료로 표시됩니다.</small>
      </div>}
    </>}
    <Modal open={!!historyItem} title={`${historyItem?.sbCode ?? ''} · ${marketLabel(historyItem?.market ?? '')} 필드 전송·조회 이력`} footer={null} width={850} onCancel={() => setHistoryItem(null)}>
      {history.isError ? <Alert type="error" message="필드 작업 이력 조회 실패" action={<Button onClick={() => { void history.refetch(); }}>다시 조회</Button>} />
        : history.isPending ? <p>이력 조회 중…</p> : history.data?.length ? history.data.map(attempt => <div className="pfs-attempt" key={attempt.id}><strong>{time(attempt.recordedAt)} · {states[attempt.phase] ?? attempt.phase}</strong><p>{attempt.detail}</p>
          {attempt.observedValues && <details><summary>이때의 관측값 원문</summary><pre>{attempt.observedValues}</pre></details>}</div>) : <p>아직 전송·조회 이력이 없습니다.</p>}
    </Modal>
  </Modal>;
}
