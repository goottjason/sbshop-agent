import type { PublicationInputContext, PublicationInputSchema } from '../../api/productPublicationInputsApi';
export const emptyPublicationInput = (schema: PublicationInputSchema): PublicationInputContext => ({ categoryId: schema.categoryId, categoryPath: schema.categoryName,
  noticeFields: {}, extraFields: { attributes: { ...schema.suggestedContext.extraFields.attributes } } });
const pick = (values: Record<string, string> | undefined, allowed: string[]) => Object.fromEntries(Object.entries(values ?? {}).filter(([key]) => allowed.includes(key)));
export function filterPublicationInput(value: PublicationInputContext, schema: PublicationInputSchema): PublicationInputContext {
  const notice = schema.noticeCategories.find(row => row.name === value.extraFields.noticeCategoryName);
  return { categoryId: schema.categoryId, categoryPath: schema.categoryName, noticeFields: pick(value.noticeFields, notice?.fields.map(row => row.name) ?? []),
    extraFields: { ...(notice ? { noticeCategoryName: notice.name } : {}), attributes: pick(value.extraFields.attributes, schema.attributes.map(row => row.name)),
      certifications: pick(value.extraFields.certifications, schema.certifications.map(row => row.type)),
      documentUrls: pick(value.extraFields.documentUrls, schema.documents.map(row => row.name)),
      documentNotApplicable: pick(value.extraFields.documentNotApplicable, schema.documents.filter(row => row.canDeclareNotApplicable).map(row => row.name)) } };
}
const nonempty = (value: string | undefined) => !!value?.trim();
export const requiredPublicationDocument = (requirement: string) => requirement.startsWith('MANDATORY') && requirement !== 'MANDATORY_PARALLEL_IMPORTED';
export function publicationInputIssues(value: PublicationInputContext, schema: PublicationInputSchema, categoryAccepted: boolean): string[] {
  const issues: string[] = [];
  if (!categoryAccepted || value.categoryId !== schema.categoryId) issues.push('현재 카테고리를 명시 선택하세요.');
  const notice = schema.noticeCategories.find(row => row.name === value.extraFields.noticeCategoryName);
  if (!notice) issues.push('상품고시 분류를 선택하세요.');
  for (const field of notice?.fields ?? []) if (field.required && !nonempty(value.noticeFields[field.name])) issues.push(`상품고시: ${field.name}`);
  const attrs = value.extraFields.attributes ?? {};
  const groups = new Map<string, PublicationInputSchema['attributes']>();
  for (const attr of schema.attributes) {
    if (attr.exclusiveGroup) groups.set(attr.group, [...(groups.get(attr.group) ?? []), attr]);
    else if (attr.required && !nonempty(attrs[attr.name])) issues.push(`필수 옵션: ${attr.name}`);
    const entered = attrs[attr.name]; if (!nonempty(entered)) continue;
    if (entered.length > 30) issues.push(`${attr.name}: 30자 이내로 입력하세요.`);
    if (attr.inputType === 'SELECT') { if (!attr.values.includes(entered)) issues.push(`${attr.name}: 허용된 선택값이 아닙니다.`); }
    else if (attr.inputType !== 'INPUT') issues.push(`${attr.name}: 지원 여부를 확인할 입력 형식입니다 (${attr.inputType}).`);
    else if (attr.dataType === 'NUMBER') {
      const match = entered.match(/^([0-9]+(?:\.[0-9]+)?)(.*)$/);
      if (!match || Number(match[1]) <= 0 || !Number.isFinite(Number(match[1])) || !(attr.units.length ? attr.units.includes(match[2]) : match[2] === '')) issues.push(`${attr.name}: 양수와 허용 단위를 입력하세요.`);
    } else if (attr.dataType === 'DATE') {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(entered) || !Number.isFinite(Date.parse(entered)) || new Date(entered).toISOString().slice(0, 10) !== entered) issues.push(`${attr.name}: 유효한 날짜를 입력하세요.`);
    } else if (attr.dataType !== 'STRING') issues.push(`${attr.name}: 지원 여부를 확인할 데이터 형식입니다 (${attr.dataType}).`);
  }
  for (const [group, members] of groups) {
    const count = members.filter(attr => nonempty(attrs[attr.name])).length;
    if (count > 1 || members.some(attr => attr.required) && count !== 1) issues.push(`옵션 그룹 ${group}: ${members.map(attr => attr.name).join(' / ')} 중 한 항목을 입력하세요.`);
  }
  const certificates = value.extraFields.certifications ?? {};
  if (!Object.keys(certificates).length) issues.push('인증 구분을 직접 선택하세요. 인증대상 아님도 직접 확인해야 합니다.');
  for (const row of schema.certifications) {
    const selected = Object.hasOwn(certificates, row.type);
    if (row.required && !selected) issues.push(`필수 인증: ${row.name}`);
    if (selected && row.dataType === 'CODE' && !nonempty(certificates[row.type])) issues.push(`인증 코드: ${row.name}`);
    if (selected && !['CODE', 'NONE'].includes(row.dataType)) issues.push(`${row.name}: 인증 입력 형식 확인이 필요합니다 (${row.dataType}).`);
  }
  for (const row of schema.documents) {
    const omitted = value.extraFields.documentNotApplicable?.[row.name] === 'true';
    const url = value.extraFields.documentUrls?.[row.name];
    if (omitted && !row.canDeclareNotApplicable) issues.push(`${row.name}: 비해당 생략을 허용하지 않습니다.`);
    if (requiredPublicationDocument(row.requirement) && !omitted && !nonempty(url)) issues.push(`필수 서류: ${row.name}`);
    if (url) { try { const parsed = new URL(url); if (parsed.protocol !== 'https:' || parsed.username || parsed.password || url.length > 150 || !/\.(pdf|hwp|doc|docx|txt|png|jpg|jpeg)$/i.test(parsed.pathname)) throw new Error(); }
      catch { issues.push(`${row.name}: 150자 이내 HTTPS 문서 URL과 허용 확장자를 확인하세요.`); } }
  }
  return issues;
}
