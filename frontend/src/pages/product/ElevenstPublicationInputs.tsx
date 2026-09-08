import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Collapse, Input, Modal, Select, Tag } from 'antd';
import { elevenstPublicationInputsApi, type ElevenstPublicationContext, type ElevenstPublicationReview } from '../../api/elevenstPublicationInputsApi';
import './productPublicationInputs.css';

export function ElevenstPublicationInputs({ productId, onClose }: { productId: number; onClose: () => void }) {
  const metadata = useQuery({ queryKey: ['elevenst-publication-inputs', productId], retry: false, refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => (await elevenstPublicationInputsApi.get(productId, signal)).data.schema });
  const schema = metadata.data;
  const [category, setCategory] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  const [notices, setNotices] = useState<Record<string, string>>({});
  const [review, setReview] = useState<ElevenstPublicationReview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [importText, setImportText] = useState('');
  const [importOpen, setImportOpen] = useState(false);
  const inputVersion = useRef(0);
  useEffect(() => { inputVersion.current++; setReview(null); }, [schema]);
  const invalidate = () => { inputVersion.current++; setReview(null); setError(null); };
  const update = (key: string, value: string | undefined) => { invalidate(); setValues(old => { const next = { ...old }; if (value) next[key] = value; else delete next[key]; return next; }); };
  const context = (): ElevenstPublicationContext => ({ categoryId: category, categoryPath: null, salePrice: null, keywords: [], noticeFields: notices, extraFields: { elevenst: values } });
  const check = async () => {
    if (!schema) return;
    setBusy(true); setError(null); setReview(null);
    const version = inputVersion.current;
    try {
      const result = (await elevenstPublicationInputsApi.review(productId, schema.productRevision, schema.accountReference, context())).data;
      if (version !== inputVersion.current) return;
      if (result.executable !== false || result.stage !== 'INPUT_ONLY' || result.productRevision !== schema.productRevision) throw new Error('invalid review');
      setReview(result);
    } catch { setError('입력 점검에 실패했습니다. 상품 변경·계정·주소 조회 또는 호출 제한 상태를 확인한 뒤 다시 점검하세요.'); }
    finally { setBusy(false); }
  };
  const exportInput = () => {
    if (!schema || !review || !review.inputComplete || review.executable !== false) return;
    const data = { format: 'sbshop-elevenst-input-v1', productId, productRevision: review.productRevision, accountReference: schema.accountReference,
      sbCode: schema.productFields.sbCode, exportedAt: new Date().toISOString(), executable: false, context: review.context };
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a'); link.href = url; link.download = `elevenst-input-${productId}.json`; link.click(); URL.revokeObjectURL(url);
  };
  const importInput = () => {
    try {
      if (!schema || importText.length > 200000) throw new Error('size');
      const parsed = JSON.parse(importText) as Record<string, unknown>;
      if (parsed.format !== 'sbshop-elevenst-input-v1' || parsed.productId !== productId || parsed.sbCode !== schema.productFields.sbCode || parsed.accountReference !== schema.accountReference || parsed.executable !== false) throw new Error('identity');
      const data = parsed.context as ElevenstPublicationContext;
      const validMap = (input: unknown): input is Record<string, string> => !!input && typeof input === 'object' && !Array.isArray(input)
        && Object.entries(input).every(([key, value]) => key.length < 100 && typeof value === 'string' && value.length <= 4000);
      if (!data || typeof data.categoryId !== 'string' || !validMap(data.noticeFields) || !validMap(data.extraFields?.elevenst)) throw new Error('shape');
      const allowed = new Set([...schema.fields.map(f => f.name), 'sellerClassification', 'noticeType']);
      if (Object.keys(data.extraFields.elevenst).some(key => !allowed.has(key))) throw new Error('fields');
      invalidate(); setCategory(data.categoryId); setValues(data.extraFields.elevenst); setNotices(data.noticeFields);
      setImportOpen(false); setImportText('');
    } catch { setError('같은 SB 상품에서 내보낸 11번가 입력 JSON인지 확인하세요. 불러온 값도 현재 상품·계정으로 다시 점검해야 합니다.'); }
  };
  const enabled = schema && !metadata.isFetching && !metadata.isError && !busy;
  const visible = (name: string) => {
    if (name === 'orgnTypDtlsCd') return ['01', '02'].includes(values.orgnTypCd);
    if (name === 'orgnNmVal') return values.orgnTypCd === '03';
    if (name.startsWith('ProductRmaterial.')) return values.rmaterialTypCd === '03';
    if (name === 'certKey') return !!values.certTypeCd && values.certTypeCd !== '131';
    return true;
  };
  return <Modal open title="11번가 등록 필수 입력 준비" width={980} footer={null} onCancel={onClose} maskClosable={!busy} closable={!busy}>
    <section className="ppi" aria-label="11번가 등록 입력 준비">
      <Alert type="info" showIcon message="입력 점검·보관 단계 · 11번가 상품은 등록되지 않습니다."
        description="옵션 없는 새 상품·고정가·업체배송·택배·무료배송부터 준비합니다. 실제 값을 선택하고 점검한 내용을 JSON으로 보관할 수 있습니다." />
      {metadata.isPending && <p role="status">현재 상품과 계정의 출고지·반품지 조회 중…</p>}
      {metadata.isError && <Alert type="error" message="입력 자료·계정 주소를 조회하지 못했습니다." action={<Button onClick={() => { void metadata.refetch(); }}>다시 조회</Button>} />}
      {error && <Alert type="error" message={error} />}
      {schema && !metadata.isError && <>
        <p><strong>{schema.productFields.sbCode}</strong> · {schema.productFields.productName} · 판매용 수량 {schema.productFields.salesQuantity ?? '미확인'}개 <Tag>등록 실행 준비 중</Tag></p>
        <p><Button disabled={!enabled} onClick={() => setImportOpen(true)}>보관한 입력 불러오기</Button> <Button disabled={busy} onClick={() => { invalidate(); void metadata.refetch(); }}>상품·주소 다시 조회</Button></p>
        <fieldset disabled={!enabled}>
          <div className="ppi-fields">
            <label className="ppi-field"><span>11번가 최하위 카테고리 번호</span><Input aria-label="11번가 등록 카테고리 번호" value={category} onChange={e => { invalidate(); setCategory(e.target.value); }} /><small>번호를 입력한 것만으로 유효한 분류라고 확정하지 않습니다.</small></label>
            <label className="ppi-field"><span>Seller Office 가입 유형</span><Select aria-label="11번가 판매자 가입 유형" value={values.sellerClassification} disabled={!enabled} placeholder="실제 가입 유형 선택" options={[{ value: 'DOMESTIC', label: '일반 국내 셀러' }, { value: 'GLOBAL', label: '글로벌 셀러 · 별도 조건 확인 필요' }]} onChange={v => update('sellerClassification', v)} /></label>
          </div>
          <Collapse defaultActiveKey={['판매자·판매 설정', '배송·반품']} items={[...new Set(schema.fields.map(f => f.section))].filter(section => schema.fields.some(f => f.section === section && visible(f.name))).map(section => ({ key: section, label: section, children: <div className="ppi-fields">
            {schema.fields.filter(f => f.section === section && visible(f.name)).map(field => <label key={field.name} className="ppi-field"><span>{field.label}</span>
              {field.name === 'addrSeqOut' || field.name === 'addrSeqIn' ? <Select aria-label={`11번가 ${field.label}`} disabled={!enabled} value={values[field.name]} placeholder="현재 계정 주소에서 선택" options={schema.addresses[field.name].map(a => ({ value: a.code, label: `${a.label} · ${a.address} (코드 ${a.code})` }))} onChange={v => update(field.name, v)} />
                : field.options.length ? <Select aria-label={`11번가 ${field.label}`} disabled={!enabled} value={values[field.name]} allowClear placeholder="직접 선택" options={field.options} onChange={v => update(field.name, v)} />
                  : <Input.TextArea aria-label={`11번가 ${field.label}`} value={values[field.name] ?? ''} autoSize={{ minRows: 1, maxRows: 3 }} maxLength={4000} onChange={e => update(field.name, e.target.value)} />}
              {field.description && <details><summary>입력 조건</summary><small style={{ whiteSpace: 'pre-wrap' }}>{field.description}</small></details>}
            </label>)}
          </div> }))} />
          <div className="ppi-section"><strong>상품고시 · 보관된 2023 항목 기준</strong><Select aria-label="11번가 고시 유형" disabled={!enabled} value={values.noticeType} placeholder="실제 상품고시 유형 선택" options={schema.noticeTypes.map(n => ({ value: n.code, label: n.label }))} onChange={v => { update('noticeType', v); setNotices({}); }} />
            <div className="ppi-fields">{schema.noticeTypes.find(n => n.code === values.noticeType)?.items.map(item => <label key={item.code} className="ppi-field"><span>{item.label}</span><Input.TextArea aria-label={`11번가 상품고시 ${item.label}`} value={notices[item.code] ?? ''} maxLength={4000} autoSize={{ minRows: 1, maxRows: 3 }} onChange={e => { invalidate(); setNotices(old => ({ ...old, [item.code]: e.target.value })); }} /></label>)}</div>
          </div>
        </fieldset>
        <p><Button type="primary" loading={busy} disabled={!enabled} onClick={() => { void check(); }}>현재 입력 점검</Button> <Button disabled={!enabled || !review?.inputComplete} onClick={exportInput}>점검한 입력 JSON 내보내기</Button></p>
        {review && <Alert type={review.inputComplete ? 'info' : 'warning'} message={review.inputComplete ? '입력 항목 점검 완료 · 등록 실행은 아직 준비 중입니다.' : '입력·수정이 필요한 항목'} description={!!review.issues.length && <ul>{review.issues.map((issue, i) => <li key={i}>{issue}</li>)}</ul>} />}
        <details><summary>등록 실행까지 남은 확인</summary><ul>{schema.limitations.map(item => <li key={item}>{item}</li>)}</ul></details>
      </>}
    </section>
    <Modal open={importOpen} title="같은 상품의 입력 JSON 불러오기" onCancel={() => setImportOpen(false)} onOk={importInput} okText="입력값 불러오기" okButtonProps={{ disabled: !enabled || !importText }}><p>불러온 값은 자동 승인되지 않습니다. 현재 계정·상품으로 다시 점검하세요.</p><Input.TextArea aria-label="11번가 입력 JSON" rows={8} maxLength={200000} value={importText} onChange={e => setImportText(e.target.value)} /></Modal>
  </Modal>;
}
