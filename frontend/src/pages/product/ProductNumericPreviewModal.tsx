import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Input, Modal, Select, Tag } from 'antd';
import { productChangeApi, type ChangeOperation, type NumericField, type NumericPreviewRequest } from '../../api/productChangeApi';
import { formatNumericPreviewValue } from './productNumericDisplay';

const operationLabels: Record<ChangeOperation, string> = { SET: '값 지정', ADD: '증감', PERCENT: '비율 변경 (%)' };
const statusLabels = { VALID: '수치 검사 통과', UNCHANGED: '변경 없음', INVALID: '계산 제외', NOT_FOUND: '상품 없음' };
const marketLabels: Record<string, string> = {
  COUPANG: '쿠팡', SMART_STORE: '스마트스토어', ELEVEN_STREET: '11번가', CAFE24: '카페24', GMARKET: 'G마켓', AUCTION: '옥션', UNKNOWN: '마켓 미확인',
};
interface Props { productIds: number[]; onClose: () => void }

export function ProductNumericPreviewModal({ productIds, onClose }: Props) {
  const [changes, setChanges] = useState<NumericPreviewRequest['changes']>([{ field: 'SALE_PRICE', operation: 'SET', value: '' }]);
  const fields = useQuery({ queryKey: ['numeric-edit-fields'], queryFn: async ({ signal }) => (await productChangeApi.fields(signal)).data });
  const options = fields.data ?? [];
  const request: NumericPreviewRequest = { productIds, changes, fractionPolicy: 'APPLY_FIELD_RULES' };
  const preview = useQuery({
    queryKey: ['numeric-change-preview', request], enabled: false, gcTime: 0, retry: false,
    queryFn: async ({ signal }) => (await productChangeApi.preview(request, signal)).data,
  });
  const validInput = fields.isSuccess && changes.length > 0 && productIds.length > 0 && productIds.length <= 5000
    && changes.every((change) => /^[+-]?\d+(\.\d+)?$/.test(change.value) && change.value.length <= 30
      && options.find((option) => option.field === change.field)?.operations.includes(change.operation));
  const change = (index: number, patch: Partial<NumericPreviewRequest['changes'][number]>) =>
    setChanges((current) => current.map((value, i) => i === index ? { ...value, ...patch } : value));
  const used = changes.map((entry) => entry.field);
  const available = options.filter((option) => !used.includes(option.field));
  const result = preview.data;

  return <Modal open title={`일괄 변경 미리보기 · ${productIds.length}개 상품`} width={1050} onCancel={onClose}
    footer={<><Button onClick={onClose}>닫기</Button><Button type="primary" disabled={!validInput} loading={preview.isFetching}
      onClick={() => { void preview.refetch(); }}>변경 전후 계산</Button></>}>
    <Alert type="info" showIcon message="변경 전후 값을 검토하는 화면입니다."
      description="현재 단계에서는 계산 결과를 확인할 수 있습니다. 저장·마켓 반영은 별도 적용 단계에서 제공합니다." />
    {productIds.length > 5000 && <Alert type="warning" message="한 번에 5,000개까지 미리볼 수 있습니다. 선택 범위를 줄여 주세요." />}
    {fields.isError && <Alert type="error" message="편집 필드 목록을 불러오지 못했습니다."
      action={<Button onClick={() => { void fields.refetch(); }}>재시도</Button>} />}
    <div className="pw-change-inputs">
      {changes.map((entry, index) => {
        const option = options.find((item) => item.field === entry.field);
        return <div key={index} className="pw-change-row">
          <Select aria-label={`${index + 1}번째 변경 필드`} loading={fields.isLoading} value={entry.field}
            options={options.map((item) => ({ value: item.field, label: item.label, disabled: used.includes(item.field) && item.field !== entry.field }))}
            onChange={(field: NumericField) => change(index, { field, operation: 'SET', value: '' })} />
          <Select aria-label={`${index + 1}번째 변경 방식`} value={entry.operation}
            options={(option?.operations ?? ['SET']).map((value) => ({ value, label: operationLabels[value] }))}
            onChange={(operation: ChangeOperation) => change(index, { operation })} />
          <Input aria-label={`${index + 1}번째 변경값`} value={entry.value} inputMode="decimal"
            placeholder={entry.operation === 'SET' ? '변경할 값' : '음수는 차감·인하'}
            onChange={(event) => change(index, { value: event.target.value.trim() })}
            suffix={entry.operation === 'PERCENT' ? '%' : option?.unit} />
          <Button aria-label={`${index + 1}번째 변경 필드 제거`} disabled={changes.length === 1}
            onClick={() => setChanges((current) => current.filter((_, i) => i !== index))}>제거</Button>
        </div>;
      })}
      <Button disabled={!available.length} onClick={() => setChanges((current) => [...current, { field: available[0].field, operation: 'SET', value: '' }])}>변경 필드 추가</Button>
      <p className="pw-change-note">판매가는 계산 후 100원 단위로 반올림합니다. 재고·묶음수량의 비율 계산은 소수 부분을 버립니다. 수량 직접 지정·증감은 정수로 입력하세요. 마진율·쿠폰율의 증감은 %p 기준입니다.</p>
    </div>
    {preview.isError && <Alert type="error" showIcon message="미리보기를 계산하지 못했습니다."
      description="입력값과 연결 상태를 확인한 뒤 다시 계산해 주세요." />}
    {preview.isFetching && <p role="status">선택 상품의 현재 값으로 계산 중…</p>}
    {result && !preview.isFetching && !preview.isError && <>
      <div className="pw-preview-summary" role="status">
        <Tag>수치 검사 통과 {result.valid}</Tag><Tag>변경 없음 {result.unchanged}</Tag>
        <Tag color={result.invalid + result.notFound > 0 ? 'orange' : undefined}>계산 제외·상품 없음 {result.invalid + result.notFound}</Tag>
        <span>{new Date(result.generatedAt).toLocaleString('ko-KR')} 기준</span>
      </div>
      <div className="pw-preview-results">
        <table><thead><tr><th>상품</th><th>변경 전 → 변경 예정값</th><th>검사 결과·검토 사항</th></tr></thead>
          <tbody>{result.items.map((item) => <tr key={item.productId}>
            <td>{item.sbCode ?? `상품 ID ${item.productId}`}</td>
            <td>{item.fields.map((field) => <div key={field.field} className="pw-preview-field">
              <strong>{options.find((option) => option.field === field.field)?.label ?? field.field}</strong>
              <span>{formatNumericPreviewValue(field.before)} → {field.after !== null
                ? formatNumericPreviewValue(field.after) : '변경 예정값 없음'}</span>
              {field.status === 'INVALID' && <span className="pw-change-note">{field.calculated !== null
                ? `계산 원값 ${formatNumericPreviewValue(field.calculated)}` : '계산 불가'}</span>}
              {field.rounded && <span className="pw-change-note">계산 원값 {formatNumericPreviewValue(field.calculated)}
                {' → '}반올림·버림 후 {formatNumericPreviewValue(field.after)}</span>}
              {field.reason && <small>{field.reason}</small>}
            </div>)}</td>
            <td><Tag color={item.status === 'INVALID' || item.status === 'NOT_FOUND' ? 'orange' : undefined}>{statusLabels[item.status]}</Tag>
              {item.marketCheck === 'REQUIRED' && <div><Tag color="gold">마켓 수정 제한 확인 필요</Tag><small>{item.markets.map((market) => marketLabels[market] ?? market).join(' · ')}</small></div>}
              {item.notes.map((note) => <p key={note} className="pw-change-note">{note}</p>)}
            </td>
          </tr>)}</tbody>
        </table>
      </div>
    </>}
  </Modal>;
}
