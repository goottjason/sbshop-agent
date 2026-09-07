export interface ImportPage { page: number; payload: unknown; count: number }
export interface ImportFile { pages: ImportPage[]; count: number; coverage: string }
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('수집 파일 형식을 확인하세요.');
  return value as Record<string, unknown>;
}
function batch(value: unknown, page: number): ImportPage {
  const payload = object(value);
  if (payload.schemaVersion !== 1 || payload.source !== 'LIVE_CHROME_MARKETPLUS' || payload.coverage !== 'CURRENT_PAGE'
    || !Array.isArray(payload.rows) || payload.rows.length < 1 || payload.rows.length > 100) throw new Error(`${page}페이지: 수집 형식과 행 수(1~100)를 확인하세요.`);
  return { page, payload, count: payload.rows.length };
}
export function parseMarketPlusImportFile(value: unknown): ImportFile {
  const file = object(value);
  if (file.kind !== 'MARKETPLUS_COLLECTION') {
    const page = batch(file, 1);
    return { pages: [page], count: page.count, coverage: '현재 페이지의 수집 이력' };
  }
  if (file.schemaVersion !== 1 || !['RUNNING', 'PARTIAL', 'REACHED_LAST_PAGE'].includes(String(file.status))
    || !Array.isArray(file.pages) || file.pages.length < 1 || file.pages.length > 2000) throw new Error('저장된 수집 페이지가 없거나 파일 형식이 올바르지 않습니다.');
  let previous = 0;
  let mall: unknown;
  const pages = file.pages.map(value => {
    const entry = object(value);
    if (!Number.isSafeInteger(entry.page) || Number(entry.page) < 1 || (previous > 0 && entry.page !== previous + 1)) throw new Error('수집 페이지가 연속되지 않습니다.');
    previous = Number(entry.page);
    const result = batch(entry.batch, previous);
    const currentMall = object(result.payload).mallId;
    if (mall !== undefined && mall !== currentMall) throw new Error('여러 쇼핑몰의 파일을 한 번에 가져올 수 없습니다.');
    mall = currentMall;
    return result;
  });
  return { pages, count: pages.reduce((n, p) => n + p.count, 0), coverage:
    `${pages[0].page}~${pages[pages.length - 1].page}페이지 · ${file.status === 'REACHED_LAST_PAGE' ? '선택 조건의 마지막 페이지까지 읽음' : '부분 수집'} (전체 상품 상태 검증 아님)` };
}
