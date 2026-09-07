import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Input, Modal, Select, Tag } from 'antd';
import { isAxiosError } from 'axios';
import { productBulkValuesApi, type BulkValueField } from '../../api/productBulkValuesApi';
import type { EditReview } from '../../api/productChangeApi';
import { ProductSaveReview } from './ProductSaveReview';
import { ProductHtmlPreview } from './ProductHtmlPreview';
import { sourceProductUrl } from './productSearch';
import './productWorkspace.css';
import './productBulkValues.css';

interface Draft { field: string; value: string; clear: boolean }
const images = (value: string) => value.split(/\r?\n/).map(url => url.trim()).filter(Boolean);
const validDraft = (draft: Draft, field?: BulkValueField) => {
  if (!field) return false;
  if (draft.clear) return field.clearable;
  if (!draft.value.trim()) return false;
  if (field.kind === 'SELECT') return field.options.some(option => option.value === draft.value);
  if (field.kind === 'IMAGES') {
    const urls = images(draft.value);
    return urls.length > 0 && urls.length <= 100 && urls.every(url => url.length <= field.maxLength && !!sourceProductUrl(url) && !new URL(url).username && !new URL(url).password);
  }
  if (draft.value.length > field.maxLength) return false;
  return field.kind !== 'URL' || (!!sourceProductUrl(draft.value) && !new URL(draft.value).username && !new URL(draft.value).password);
};

export function ProductBulkValuesModal({ productIds, onClose, onSaved }: {
  productIds: number[]; onClose: () => void; onSaved?: () => void;
}) {
  const client = useQueryClient();
  const catalog = useQuery({ queryKey: ['product-bulk-value-fields'], retry: false,
    queryFn: async ({ signal }) => (await productBulkValuesApi.fields(signal)).data });
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [review, setReview] = useState<EditReview | null>(null);
  const [htmlPreviewOpen, setHtmlPreviewOpen] = useState(false);
  const options = catalog.data ?? [];
  const valid = !catalog.isError && drafts.length > 0 && productIds.length > 0 && productIds.length <= 500
    && drafts.every(draft => validDraft(draft, options.find(option => option.field === draft.field)));
  const update = (index: number, patch: Partial<Draft>) => setDrafts(previous => previous.map((draft, i) => i === index ? { ...draft, ...patch } : draft));
  const prepare = async () => {
    if (!valid) return;
    setBusy(true); setError(null);
    const values: Record<string, string | string[]> = {};
    for (const draft of drafts) {
      const field = options.find(option => option.field === draft.field)!;
      values[draft.field] = field.kind === 'IMAGES' ? draft.clear ? [] : images(draft.value) : draft.clear ? '' : draft.value;
    }
    try { setReview((await productBulkValuesApi.preview(productIds, values)).data); }
    catch (cause) { setError(isAxiosError(cause) && typeof cause.response?.data?.message === 'string' ? cause.response.data.message : '변경 검토를 불러오지 못했습니다. 입력값을 확인하고 다시 검토하세요.'); }
    finally { setBusy(false); }
  };
  return <>
    <Modal open title={`필드 일괄 편집 · ${productIds.length}개 상품`} width={1050} maskClosable={!busy} closable={!busy} onCancel={() => { if (!busy) onClose(); }}
      styles={{ body: { maxHeight: '72vh', overflowY: 'auto' } }} footer={<><Button disabled={busy} onClick={onClose}>닫기</Button>
        <Button type="primary" disabled={!valid} loading={busy} onClick={() => { void prepare(); }}>선택 필드·저장값 검토</Button></>}>
      <Alert showIcon type="info" message="추가한 필드만 모든 선택 상품에 같은 값으로 변경합니다."
        description="수정 잠금과 상품명 등 파생 변경을 상품별로 검토한 후 저장합니다. 한 필드라도 제한되면 그 상품의 변경 전체를 제외합니다. DB 저장과 마켓 반영은 별도입니다." />
      <p className="pw-change-note">가격·재고·용량 등 숫자는 기존 숫자 일괄 변경에서 지정·증감·비율로 수정할 수 있습니다.</p>
      {productIds.length > 500 && <Alert type="warning" showIcon message="한 번에 최대 500개 상품까지 검토할 수 있습니다." />}
      {catalog.isError && <Alert type="error" showIcon message="일괄 편집 필드를 불러오지 못했습니다."
        action={<Button onClick={() => { void catalog.refetch(); }}>다시 조회</Button>} />}
      <div className="pbv-add"><Select aria-label="추가할 일괄 편집 필드" placeholder="변경할 필드를 추가하세요" value={null}
        disabled={busy || catalog.isError} loading={catalog.isPending} showSearch optionFilterProp="label"
        options={options.map(field => ({ value: field.field, label: field.label, disabled: drafts.some(draft => draft.field === field.field) }))}
        onChange={(field: string) => setDrafts(previous => previous.some(draft => draft.field === field) ? previous : [...previous, { field, value: '', clear: false }])} />
        <span>{drafts.length}개 필드 선택</span></div>
      {!drafts.length && <p className="pbv-empty">편집할 필드를 추가한 뒤 변경값을 입력하세요. 선택하지 않은 필드는 그대로 보존합니다.</p>}
      {drafts.map((draft, index) => {
        const field = options.find(option => option.field === draft.field);
        if (!field) return null;
        return <section key={draft.field} className="pbv-field">
          <div className="pbv-heading"><strong>{field.label}</strong>{['brand', 'baseName', 'measureUnit'].includes(field.field) && <Tag color="gold">상품명 파생 변경</Tag>}
            {field.clearable && <Checkbox checked={draft.clear} disabled={busy} onChange={event => update(index, { clear: event.target.checked })}>기존 값 비우기</Checkbox>}
            <Button size="small" disabled={busy} aria-label={`${field.label} 편집 제외`} onClick={() => setDrafts(previous => previous.filter((_, i) => i !== index))}>제외</Button></div>
          {draft.clear ? <Alert type="warning" showIcon message={`${field.label}을 비우는 변경을 검토합니다.`} /> : field.kind === 'SELECT' ?
            <Select aria-label={`${field.label} 일괄 변경값`} value={draft.value || undefined} placeholder="변경할 값 선택" disabled={busy} options={field.options}
              onChange={value => update(index, { value })} /> : ['TEXTAREA', 'IMAGES', 'HTML'].includes(field.kind) ?
              <Input.TextArea aria-label={`${field.label} 일괄 변경값`} value={draft.value} disabled={busy} autoSize={{ minRows: field.kind === 'HTML' ? 5 : 2, maxRows: 10 }}
                maxLength={field.kind === 'IMAGES' ? undefined : field.maxLength} placeholder={field.kind === 'IMAGES' ? '이미지 URL을 한 줄에 하나씩 입력 · 전체 목록 교체' : '모든 선택 상품에 적용할 값'}
                onChange={event => update(index, { value: event.target.value })} /> :
              <Input aria-label={`${field.label} 일괄 변경값`} value={draft.value} disabled={busy} maxLength={field.maxLength} placeholder="모든 선택 상품에 적용할 값"
                onChange={event => update(index, { value: event.target.value })} />}
          {!draft.clear && !validDraft(draft, field) && <p className="pbv-hint">{field.kind === 'IMAGES' ? '계정정보 없는 HTTP(S) URL을 한 줄에 하나씩 입력하세요. 최대 100개입니다.' : field.clearable ? '변경값을 입력·선택하세요. 비우려면 ‘기존 값 비우기’를 명시적으로 선택하세요.' : '변경값을 입력·선택하세요. 이 필드는 비울 수 없습니다.'}</p>}
          {field.kind === 'IMAGES' && <p className="pw-change-note">입력한 전체 목록으로 교체합니다. 게시 이미지의 첫 URL이 대표 이미지가 됩니다. 크롤·파일 업로드는 실행하지 않습니다.</p>}
          {field.kind === 'HTML' && !draft.clear && draft.value && <details className="pbv-preview" open={htmlPreviewOpen} onToggle={event => setHtmlPreviewOpen(event.currentTarget.open)}><summary>입력한 상세 HTML 미리보기</summary>{htmlPreviewOpen && <ProductHtmlPreview html={draft.value} label="일괄 변경" />}</details>}
        </section>;
      })}
      {error && <Alert type="error" showIcon message={error} />}
    </Modal>
    {review && <ProductSaveReview review={review} onClose={() => setReview(null)} onSaved={() => {
      void client.invalidateQueries({ queryKey: ['products'] }); onSaved?.();
    }} />}
  </>;
}
