import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Input, Select, Tag } from 'antd';
import { productPublicationInputsApi, type PublicationInputContext, type PublicationInputSchema } from '../../api/productPublicationInputsApi';
import { emptyPublicationInput, filterPublicationInput, publicationInputIssues, requiredPublicationDocument } from './productPublicationInputRules';
import './productPublicationInputs.css';

type Props = { productId: number; market: string; initialContext?: PublicationInputContext | null; disabled?: boolean; onChange: (context: PublicationInputContext | null) => void };
const mapWith = (map: Record<string, string> | undefined, key: string, value: string | undefined) => { const next = { ...map }; if (value === undefined) delete next[key]; else next[key] = value; return next; };
export function ProductPublicationInputs({ productId, market, initialContext, disabled = false, onChange }: Props) {
  const [categoryInput, setCategoryInput] = useState(initialContext?.categoryId ?? '');
  const [queryCategory, setQueryCategory] = useState<string | undefined>(initialContext?.categoryId);
  const [categoryAccepted, setCategoryAccepted] = useState(false);
  const [draft, setDraft] = useState<PublicationInputContext | null>(null);
  const [previousId, setPreviousId] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | null>(null);
  const pendingPrefill = useRef<PublicationInputContext | null>(initialContext ?? null);
  const callback = useRef(onChange); callback.current = onChange;
  const metadata = useQuery({ queryKey: ['publication-inputs', productId, market, queryCategory], enabled: market === 'COUPANG', retry: false, refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => (await productPublicationInputsApi.get(productId, market, queryCategory, signal)).data });
  const schema = metadata.data?.schema;
  useEffect(() => {
    if (!schema || metadata.isError || metadata.isFetching) return;
    const prefill = pendingPrefill.current;
    setDraft(prefill && prefill.categoryId === schema.categoryId ? filterPublicationInput(prefill, schema) : emptyPublicationInput(schema));
    pendingPrefill.current = null; setCategoryAccepted(false);
  }, [schema, metadata.isError, metadata.isFetching]);
  const issues = useMemo(() => !draft || !schema ? ['현재 카테고리 메타를 조회하세요.'] : publicationInputIssues(draft, schema, categoryAccepted), [draft, schema, categoryAccepted]);
  const result = draft && schema && !metadata.isError && !metadata.isFetching && !issues.length ? JSON.stringify(draft) : null;
  useEffect(() => { callback.current(result ? JSON.parse(result) as PublicationInputContext : null); }, [result]);
  const updateExtra = (key: 'attributes' | 'certifications' | 'documentUrls' | 'documentNotApplicable', name: string, value: string | undefined) => {
    setDraft(old => old && ({ ...old, extraFields: { ...old.extraFields, [key]: mapWith(old.extraFields[key], name, value) } }));
  };
  const loadCategory = (id?: string, prefill?: PublicationInputContext) => {
    pendingPrefill.current = prefill ?? null; setDraft(null); setCategoryAccepted(false); setNotice(prefill ? '선택한 과거 자료를 불러왔습니다. 현재 메타에 있는 필드만 표시하며, 값과 인증·서류를 다시 확인하세요.' : null);
    setPreviousId(undefined);
    if (id === queryCategory) void metadata.refetch(); else setQueryCategory(id);
  };
  const usePrevious = () => {
    const row = metadata.data?.previousEvidence.find(item => item.id === previousId); if (!row) return;
    setCategoryInput(row.categoryId); loadCategory(row.categoryId, row.context);
  };
  const renderAttribute = (attr: PublicationInputSchema['attributes'][number]) => {
    const value = draft?.extraFields.attributes?.[attr.name];
    return <label key={attr.name} className="ppi-field"><span>{attr.name} {attr.required && <b className="ppi-required">필수</b>}{attr.exclusiveGroup && <Tag>그룹 {attr.group}에서 하나</Tag>}</span>
      {attr.inputType === 'SELECT' ? <Select aria-label={`구매옵션 ${attr.name}`} value={value} allowClear disabled={disabled} placeholder="허용값 선택"
        options={attr.values.map(v => ({ value: v, label: v }))} onChange={v => updateExtra('attributes', attr.name, v)} />
        : <Input aria-label={`구매옵션 ${attr.name}`} value={value ?? ''} disabled={disabled} maxLength={30} type={attr.dataType === 'DATE' ? 'date' : 'text'}
          placeholder={attr.dataType === 'NUMBER' ? `양수${attr.units.length ? ` + 단위 (${attr.units.join(', ')})` : ''}` : '상품의 실제 값'} onChange={event => updateExtra('attributes', attr.name, event.target.value || undefined)} />}
      {attr.inputType === 'INPUT' && !!attr.units.length && <small>허용 단위: {attr.units.join(', ')} · 숫자 뒤에 단위를 붙여 입력하세요.</small>}
    </label>;
  };
  if (market !== 'COUPANG') return <Alert type="info" message="현재 이 입력 화면은 쿠팡의 상품별 필수 메타를 지원합니다." />;
  return <section className="ppi" aria-label={`상품 ${productId} 쿠팡 등록 필수 입력`}>
    <div className="ppi-title"><strong>쿠팡 등록 필수 정보</strong><Tag color={result ? 'green' : 'orange'}>{result ? '입력 완료 · 등록 내용 준비 가능' : '입력·선택 필요'}</Tag></div>
    <p>상품별 최신 카테고리 메타를 확인합니다. 과거 자료와 인증대상 아님은 직접 선택해야 적용됩니다.</p>
    <div className="ppi-category"><Input aria-label="쿠팡 등록 카테고리 번호" value={categoryInput} disabled={disabled} placeholder="다른 카테고리 번호 입력" onChange={event => setCategoryInput(event.target.value)} />
      <Button disabled={disabled || !/^[1-9][0-9]{0,17}$/.test(categoryInput)} loading={metadata.isFetching} onClick={() => loadCategory(categoryInput)}>입력한 카테고리 조회</Button>
      <Button disabled={disabled} loading={metadata.isFetching} onClick={() => loadCategory()}>추천 메타 다시 조회</Button></div>
    {metadata.isError && <Alert type="error" showIcon message="상품별 등록 메타 조회에 실패했습니다. 현재 입력값으로 등록 내용을 준비할 수 없습니다." action={<Button onClick={() => { void metadata.refetch(); }}>메타 다시 조회</Button>} />}
    {metadata.isPending && <p role="status">쿠팡 카테고리와 필수 메타 조회 중…</p>}
    {!!metadata.data?.previousEvidence.length && <div className="ppi-previous"><Select aria-label="쿠팡 과거 등록 자료" placeholder="과거 자료 후보 · 자동 적용하지 않음" value={previousId} disabled={disabled || metadata.isFetching}
      options={metadata.data.previousEvidence.map(row => ({ value: row.id, label: `${row.label} · 카테고리 ${row.categoryId}` }))} onChange={setPreviousId} />
      <Button disabled={disabled || !previousId || metadata.isFetching} onClick={usePrevious}>선택한 과거 자료 불러오기</Button></div>}
    {notice && <Alert type="info" message={notice} />}
    {schema && draft && !metadata.isError && <fieldset disabled={disabled || metadata.isFetching}>
      <div className="ppi-selected-category"><strong>{schema.categoryName}</strong> · 코드 {schema.categoryId}<br />
        <Checkbox checked={categoryAccepted} disabled={disabled || metadata.isFetching} onChange={event => setCategoryAccepted(event.target.checked)}>상품에 맞는 카테고리임을 확인하고 이 카테고리를 사용합니다.</Checkbox></div>
      <div className="ppi-section"><strong>상품고시</strong><Select aria-label="쿠팡 상품고시 분류" value={draft.extraFields.noticeCategoryName} placeholder="고시 분류를 직접 선택하세요" disabled={disabled}
        options={schema.noticeCategories.map(row => ({ value: row.name, label: row.name }))} onChange={name => setDraft(old => old && ({ ...old, noticeFields: {}, extraFields: { ...old.extraFields, noticeCategoryName: name } }))} />
        <div className="ppi-fields">{schema.noticeCategories.find(row => row.name === draft.extraFields.noticeCategoryName)?.fields.map(field => <label key={field.name} className="ppi-field"><span>{field.name} {field.required && <b className="ppi-required">필수</b>}</span>
          <Input.TextArea aria-label={`상품고시 ${field.name}`} autoSize={{ minRows: 1, maxRows: 4 }} maxLength={4000} value={draft.noticeFields[field.name] ?? ''} disabled={disabled}
            onChange={event => setDraft(old => old && ({ ...old, noticeFields: mapWith(old.noticeFields, field.name, event.target.value || undefined) }))} /></label>)}</div></div>
      <div className="ppi-section"><strong>구매옵션·상품 규격</strong><small>DB에서 확인한 수량·용량만 제안합니다. 같은 배타 그룹은 한 항목만 입력하고 나머지는 비워 두세요.</small>
        {schema.attributes.length ? <div className="ppi-fields">{schema.attributes.map(renderAttribute)}</div> : <p>이 카테고리에 요구된 구매옵션이 없습니다.</p>}</div>
      <div className="ppi-section"><strong>인증</strong><small>필수 인증과 해당하는 인증 구분을 직접 선택하세요.</small>
        {schema.certifications.map(row => { const selected = Object.hasOwn(draft.extraFields.certifications ?? {}, row.type); return <div key={row.type} className="ppi-certification">
          <Checkbox checked={selected} disabled={disabled} onChange={event => updateExtra('certifications', row.type, event.target.checked ? '' : undefined)}>{row.name} {row.required && <b className="ppi-required">필수</b>}</Checkbox>
          {selected && row.dataType === 'CODE' && <Input aria-label={`인증 코드 ${row.name}`} disabled={disabled} value={draft.extraFields.certifications?.[row.type] ?? ''} placeholder="실제 인증 코드" onChange={event => updateExtra('certifications', row.type, event.target.value)} />}
          {selected && row.dataType === 'NONE' && <small>코드 없는 인증 구분을 명시 선택했습니다.</small>}</div>; })}</div>
      <div className="ppi-section"><strong>필수·조건부 서류</strong><small>공개 접근 가능한 HTTPS 파일 URL · 150자 이내 · pdf/hwp/doc/docx/txt/png/jpg/jpeg · 파일 5MB 이하</small>
        {!schema.documents.length && <p>이 카테고리에 요구된 서류가 없습니다.</p>}
        {schema.documents.map(row => { const omitted = draft.extraFields.documentNotApplicable?.[row.name] === 'true'; return <div key={row.name} className="ppi-document"><strong>{row.name}</strong> {requiredPublicationDocument(row.requirement) && <b className="ppi-required">필수·조건 확인</b>}<small>{row.requirement}</small>
          <Input aria-label={`서류 URL ${row.name}`} maxLength={150} disabled={disabled || omitted} value={draft.extraFields.documentUrls?.[row.name] ?? ''} placeholder="https://.../document.pdf" onChange={event => updateExtra('documentUrls', row.name, event.target.value || undefined)} />
          {row.canDeclareNotApplicable && <Checkbox disabled={disabled} checked={omitted} onChange={event => {
            const checked = event.target.checked;
            setDraft(old => old && ({ ...old, extraFields: { ...old.extraFields,
              documentNotApplicable: mapWith(old.extraFields.documentNotApplicable, row.name, checked ? 'true' : undefined),
              documentUrls: checked ? mapWith(old.extraFields.documentUrls, row.name, undefined) : old.extraFields.documentUrls } }));
          }}>이 상품은 해당 서류 조건에 해당하지 않음을 확인했습니다.</Checkbox>}</div>; })}</div>
      {!!issues.length && <details className="ppi-issues" open><summary>등록 내용 준비 전 확인할 항목 ({issues.length})</summary><ul>{issues.map((issue, index) => <li key={`${index}:${issue}`}>{issue}</li>)}</ul></details>}
    </fieldset>}
  </section>;
}
