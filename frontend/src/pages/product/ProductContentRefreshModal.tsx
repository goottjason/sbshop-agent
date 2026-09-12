import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Image, Modal, Spin, Tag } from 'antd';
import { isAxiosError } from 'axios';
import {
  productContentApi, type ProductContentCollectionRequest, type ProductContentField,
  type ProductContentSnapshot, type ProductContentValues,
} from '../../api/productContentApi';
import type { EditReview } from '../../api/productChangeApi';
import { ProductSaveReview } from './ProductSaveReview';
import { ProductHtmlPreview } from './ProductHtmlPreview';
import { sourceProductUrl } from './productSearch';
import { createRequestId } from '../../utils/requestId';
import './productWorkspace.css';

const RECOVERY_KEY = 'sbshop.productContent.collection';
const fieldLabels: Record<ProductContentField, string> = { IMAGES: '대표·추가 이미지', DETAIL_HTML: '상세 HTML' };
const stateLabels: Record<ProductContentSnapshot['state'], string> = {
  QUEUED: '수집 대기', COLLECTING: '수집 중', READY: '수집 완료 · 적용 전', PARTIAL: '일부 수집 · 확인 필요',
  FAILED: '수집 실패', UNSUPPORTED: '소싱처 미지원',
};
const date = (value: string | null | undefined) => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '이력 없음';
const collecting = (item: ProductContentSnapshot) => ['QUEUED', 'COLLECTING'].includes(item.state);

interface Recovery { request: ProductContentCollectionRequest; collectionId?: string }
function readRecovery(): Recovery | null {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(RECOVERY_KEY) ?? 'null') as Recovery | null;
    if (!parsed?.request || typeof parsed.request.requestId !== 'string' || !Array.isArray(parsed.request.productIds)
      || !parsed.request.productIds.every(id => Number.isSafeInteger(id) && id > 0)
      || (parsed.collectionId !== undefined && typeof parsed.collectionId !== 'string')) return null;
    return parsed;
  } catch { return null; }
}
function persistRecovery(value: Recovery) {
  try { sessionStorage.setItem(RECOVERY_KEY, JSON.stringify(value)); } catch { /* 현재 모달의 상태는 계속 유지한다. */ }
}
function errorMessage(error: unknown): string {
  if (isAxiosError(error) && typeof error.response?.data?.message === 'string') return error.response.data.message;
  return '요청 결과를 확인하지 못했습니다. 연결을 확인하고 다시 시도하세요.';
}
function canSelect(snapshot: ProductContentSnapshot, field: ProductContentField, now: number): boolean {
  const rule = snapshot.fields.find(item => item.field === field);
  return !!snapshot.proposed && ['READY', 'PARTIAL'].includes(snapshot.state)
    && !!snapshot.expiresAt && new Date(snapshot.expiresAt).getTime() > now
    && !!rule?.available && !!rule.editable && !rule.appliedAt;
}

function ImageSet({ content, label }: { content: ProductContentValues | null; label: string }) {
  const urls = content ? content.hostedImages.length ? content.hostedImages : content.sourceImages : [];
  if (!urls.length) return <p className="pw-content-empty">이미지 없음</p>;
  return <Image.PreviewGroup><div className="pw-content-images">{urls.map((url, index) => {
    const safeUrl = sourceProductUrl(url);
    return <figure key={`${index}:${url}`}>
      {safeUrl ? <Image src={safeUrl} alt={`${label} ${index === 0 ? '대표' : `추가 ${index}`} 이미지`} width={94} height={94}
        style={{ objectFit: 'contain' }} /> : <span className="pw-content-empty">이미지 URL 확인 필요</span>}
      <figcaption>{index === 0 ? '대표 이미지' : `추가 ${index}`}</figcaption>
    </figure>;
  })}</div></Image.PreviewGroup>;
}

function SnapshotCard({ snapshot, selected, disabled, initiallyExpanded, onSelection }: {
  snapshot: ProductContentSnapshot; selected: ProductContentField[]; disabled: boolean;
  initiallyExpanded: boolean;
  onSelection: (field: ProductContentField, checked: boolean) => void;
}) {
  const [expanded, setExpanded] = useState(initiallyExpanded);
  const [now] = useState(() => Date.now());
  const sourceUrl = sourceProductUrl(snapshot.sourceUrl);
  const hasFailure = ['FAILED', 'UNSUPPORTED', 'PARTIAL'].includes(snapshot.state);
  const expired = !collecting(snapshot) && !!snapshot.expiresAt && new Date(snapshot.expiresAt).getTime() <= now;
  return <article className="pw-content-card" aria-label={`${snapshot.sbCode ?? snapshot.productId} 수집 비교`}>
    <div className="pw-content-card-title"><strong>{snapshot.sbCode ?? snapshot.productId}</strong>
      <span>{snapshot.vendor ?? '소싱처 없음'}</span>
      <Tag color={hasFailure ? 'orange' : collecting(snapshot) ? 'blue' : 'default'}>
        {snapshot.appliedAt ? 'DB 적용 이력 있음' : stateLabels[snapshot.state]}
      </Tag>
      {snapshot.fields.some(rule => rule.available && !rule.editable && !rule.appliedAt) && <Tag color="orange">수정 잠금·확인 필요 항목 있음</Tag>}
      {sourceUrl && <a href={sourceUrl} target="_blank" rel="noopener noreferrer">소싱처 열기 ↗</a>}
    </div>
    <p className="pw-content-times">수집 요청 {date(snapshot.requestedAt)} · 수집 성공 {date(snapshot.collectedAt)} · DB 적용 {date(snapshot.appliedAt)}</p>
    {snapshot.reason && <p className={hasFailure ? 'pw-content-warning' : 'pw-change-note'}>{snapshot.reason}</p>}
    {expired && <Alert type="warning" showIcon message="검토 유효 시간이 지났습니다. 최신 내용을 다시 수집하세요." />}
    {snapshot.notices.length > 0 && <details className="pw-content-notices" open={hasFailure || undefined}>
      <summary>수집 참고 사항 ({snapshot.notices.length})</summary>
      {snapshot.notices.map(notice => <p className={hasFailure ? 'pw-content-warning' : undefined} key={notice}>{notice}</p>)}
    </details>}
    {collecting(snapshot) ? <p><Spin size="small" /> 수집 작업을 기다리는 중입니다. 창을 닫아도 서버에서 계속 진행합니다.</p> :
      <details className="pw-content-comparison" open={expanded} onToggle={event => setExpanded(event.currentTarget.open)}><summary>비교 및 적용 항목 선택</summary>{expanded && snapshot.fields.map(rule => <section key={rule.field} className="pw-content-field">
        <div className="pw-content-field-title"><Checkbox checked={selected.includes(rule.field)}
          disabled={disabled || !canSelect(snapshot, rule.field, now)} onChange={event => onSelection(rule.field, event.target.checked)}>
          {fieldLabels[rule.field]} 적용 검토에 포함
        </Checkbox>
          {!rule.available ? <Tag color="orange">수집 결과 없음</Tag> : rule.appliedAt ? <Tag color="green">DB 적용 이력</Tag> :
            !rule.editable ? <Tag color="orange">수정 잠금·확인 필요</Tag> : <Tag>선택 가능</Tag>}
        </div>
        <p className="pw-content-times">수집 성공 {date(rule.collectedAt)} · DB 적용 {date(rule.appliedAt)}{rule.reason && <> · {rule.reason}</>}</p>
        <details className="pw-content-comparison" open={rule.available || undefined}>
          <summary>{fieldLabels[rule.field]} 기존·수집 내용 비교</summary>
          <div className="pw-content-compare-grid">
            <div><h4>수집 요청 당시 DB</h4>{rule.field === 'IMAGES'
              ? <ImageSet content={snapshot.current} label="기존" />
              : <ProductHtmlPreview html={snapshot.current.detailHtml} label="기존" />}</div>
            <div><h4>이번에 수집한 내용{!rule.available && ' · 적용 불가'}</h4>{rule.field === 'IMAGES'
              ? <ImageSet content={snapshot.proposed} label="수집" />
              : <ProductHtmlPreview html={snapshot.proposed?.detailHtml} label="수집" />}</div>
          </div>
        </details>
      </section>)}</details>}
  </article>;
}

export function ProductContentRefreshModal({ productIds, onClose, onSaved }: {
  productIds: number[]; onClose: () => void; onSaved: () => void;
}) {
  const queryClient = useQueryClient();
  const [recovery, setRecovery] = useState<Recovery | null>(readRecovery);
  const [collectionId, setCollectionId] = useState<string | null>(() => productIds.length === 0 ? readRecovery()?.collectionId ?? null : null);
  const [historical, setHistorical] = useState<ProductContentSnapshot | null>(null);
  const [selection, setSelection] = useState<Record<string, ProductContentField[]>>({});
  const [now] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unknownRequest, setUnknownRequest] = useState<ProductContentCollectionRequest | null>(() => {
    const previous = readRecovery();
    return previous && !previous.collectionId ? previous.request : null;
  });
  const [review, setReview] = useState<EditReview | null>(null);
  const collection = useQuery({ queryKey: ['product-content-collection', collectionId], enabled: !!collectionId,
    queryFn: async ({ signal }) => (await productContentApi.collection(collectionId!, signal)).data,
    retry: false, refetchInterval: query => query.state.data?.items.some(collecting) ? 5000 : false });
  const history = useQuery({ queryKey: ['product-content-history', productIds[0]], enabled: productIds.length === 1,
    queryFn: async ({ signal }) => (await productContentApi.history(productIds[0], signal)).data, retry: false });
  const snapshots = historical ? [historical] : collection.data?.items ?? [];
  const selected = snapshots.map(snapshot => ({ snapshotId: snapshot.id,
    fields: (selection[snapshot.id] ?? []).filter(field => canSelect(snapshot, field, now)) })).filter(item => item.fields.length > 0);
  const inProgress = snapshots.filter(collecting).length;

  const collect = async (retry?: ProductContentCollectionRequest) => {
    const request = retry ?? { requestId: createRequestId(), productIds: [...productIds] };
    setBusy(true); setError(null); setUnknownRequest(request);
    const pending = { request }; setRecovery(pending); persistRecovery(pending);
    try {
      const result = (await productContentApi.collect(request)).data;
      const saved = { request, collectionId: result.id }; persistRecovery(saved); setRecovery(saved);
      queryClient.setQueryData(['product-content-collection', result.id], result);
      setCollectionId(result.id); setHistorical(null); setSelection({}); setUnknownRequest(null);
      if (productIds.length === 1) void history.refetch();
    } catch (cause) {
      const rejected = isAxiosError(cause) && [400, 401, 403, 404, 413, 422].includes(cause.response?.status ?? 0);
      if (rejected) {
        setUnknownRequest(null);
        // 명확히 거절된 요청은 이전의 정상 수집을 덮어쓰지 않는다.
        const previous = recovery?.collectionId ? recovery : null;
        setRecovery(previous);
        if (previous) persistRecovery(previous);
        else try { sessionStorage.removeItem(RECOVERY_KEY); } catch { /* 복구 저장소 사용 불가 */ }
      }
      setError(`${errorMessage(cause)}${rejected ? ' 수집 요청이 거절되었습니다.' : ' 접수 여부가 불확실하면 같은 수집 요청으로 확인하세요.'}`);
    } finally { setBusy(false); }
  };
  const prepare = async () => {
    setBusy(true); setError(null);
    try { setReview((await productContentApi.review(selected)).data); }
    catch (cause) { setError(`적용 검토 실패: ${errorMessage(cause)}`); }
    finally { setBusy(false); }
  };
  const selectField = (field: ProductContentField) => setSelection(previous => {
    const next = { ...previous };
    for (const snapshot of snapshots) if (canSelect(snapshot, field, now)) next[snapshot.id] = Array.from(new Set([...(next[snapshot.id] ?? []), field]));
    return next;
  });
  const saved = () => {
    onSaved(); setSelection({});
    if (collectionId) void collection.refetch();
    if (productIds.length === 1) void history.refetch().then(result => {
      if (historical) setHistorical(result.data?.find(item => item.id === historical.id) ?? null);
    });
  };

  return <>
    <Modal open title="소싱 이미지·상세정보 갱신" width={1180} onCancel={() => { if (!busy) onClose(); }} maskClosable={!busy} closable={!busy}
      styles={{ body: { maxHeight: '75vh', overflowY: 'auto' } }} footer={<div className="pw-content-footer">
        <span>선택 {selected.length}개 상품 · {selected.reduce((count, item) => count + item.fields.length, 0)}개 항목</span>
        <Button disabled={busy} onClick={onClose}>닫기</Button>
        <Button type="primary" loading={busy} disabled={!selected.length || collection.isError} onClick={() => { void prepare(); }}>선택 내용 적용 검토</Button>
      </div>}>
      <Alert type="info" showIcon message="수집 내용을 비교해 선택한 항목만 DB에 적용합니다. 마켓 반영은 별도입니다." />
      <details className="pw-content-notices">
        <summary>수집·적용 안내</summary>
        <p>현재 iHerb·Vitabiotics·포트넘앤메이슨·코스트코 UK·Ocado 상품을 지원합니다. Ocado는 브라우저 접근이 차단되면 수집 실패로 남기고 기존 값을 유지합니다. VTB의 다중 규격 상품은 이미지·설명 전체의 규격 대응 확인이 필요할 수 있습니다. 상세 HTML은 현재 상품명·묶음수량 등을 사용한 자동 생성 규칙을 따릅니다. 함께 갱신할 항목은 한 번에 선택하세요. 일부를 적용하면 남은 항목은 다시 수집해야 합니다. 연결에 따른 필드 잠금은 유지됩니다.</p>
      </details>
      <div className="pw-content-toolbar">
        <Button loading={busy} disabled={!productIds.length || productIds.length > 50 || !!unknownRequest || inProgress > 0}
          onClick={() => { void collect(); }}>{collectionId || historical ? '선택 상품 다시 수집' : `선택 ${productIds.length}개 상품 최신 내용 수집`}</Button>
        {recovery?.collectionId && recovery.collectionId !== collectionId && <Button disabled={busy}
          onClick={() => { setCollectionId(recovery.collectionId!); setHistorical(null); setSelection({}); setError(null); }}>최근 수집 이어보기</Button>}
        {collectionId && <Button loading={collection.isFetching} disabled={busy} onClick={() => { void collection.refetch(); }}>수집 결과 재조회</Button>}
        {inProgress > 0 && <span role="status">{inProgress}개 수집 대기·진행 중 / {snapshots.length}개</span>}
      </div>
      {productIds.length > 50 && <Alert type="warning" showIcon message="한 번에 최대 50개 상품을 수집합니다. 선택 수를 줄여 주세요." />}
      {!productIds.length && !collectionId && !unknownRequest && <p className="pw-change-note">목록에서 상품을 선택하면 새 수집을 시작할 수 있습니다.</p>}
      {unknownRequest && <Alert type="warning" showIcon message={`이전 수집 요청 ${unknownRequest.productIds.length}개의 접수 결과를 확인해야 합니다.`}
        description="같은 요청을 보내 이미 접수된 작업을 확인합니다. 중복 수집을 새로 시작하지 않습니다."
        action={<Button loading={busy} onClick={() => { void collect(unknownRequest); }}>같은 수집 요청 확인</Button>} />}
      {error && <Alert type="error" showIcon message={error} />}
      {collection.isError && <Alert type="error" showIcon message="수집 결과 조회 실패. 표시된 이전 결과의 변경 여부를 확인할 수 없습니다."
        action={<Button loading={collection.isFetching} onClick={() => { void collection.refetch(); }}>다시 조회</Button>} />}
      {collectionId && collection.isPending && <p><Spin size="small" /> 수집 결과 조회 중…</p>}
      {snapshots.length > 0 && <>
        <div className="pw-content-toolbar"><span>일괄 선택</span>
          <Button size="small" disabled={busy || collection.isError} onClick={() => selectField('IMAGES')}>적용 가능한 이미지</Button>
          <Button size="small" disabled={busy || collection.isError} onClick={() => selectField('DETAIL_HTML')}>적용 가능한 상세 HTML</Button>
          <Button size="small" disabled={busy} onClick={() => setSelection({})}>선택 해제</Button>
        </div>
        {snapshots.map(snapshot => <SnapshotCard key={snapshot.id} snapshot={snapshot} selected={selection[snapshot.id] ?? []}
          initiallyExpanded={snapshots.length === 1}
          disabled={busy || collection.isError} onSelection={(field, checked) => setSelection(previous => ({ ...previous,
            [snapshot.id]: checked ? Array.from(new Set([...(previous[snapshot.id] ?? []), field])) : (previous[snapshot.id] ?? []).filter(item => item !== field),
          }))} />)}
      </>}
      {productIds.length === 1 && <details className="pw-content-history"><summary>이 상품의 최근 수집·적용 이력</summary>
        {history.isError ? <Alert type="error" message="수집 이력 조회 실패" action={<Button size="small" onClick={() => { void history.refetch(); }}>다시 조회</Button>} /> :
          history.isPending ? <Spin size="small" /> : !history.data?.length ? <p className="pw-change-note">수집 이력이 없습니다.</p> :
            <ul>{history.data.map(item => <li key={item.id}><span>{date(item.requestedAt)} · {stateLabels[item.state]} · DB 적용 {date(item.appliedAt)}</span>
              <Button size="small" disabled={busy} onClick={() => { setHistorical(item); setSelection({}); }}>내용 비교</Button></li>)}</ul>}
      </details>}
    </Modal>
    {review && <ProductSaveReview review={review} commit={productContentApi.commit} onClose={() => setReview(null)} onSaved={saved} />}
  </>;
}
