import test from 'node:test';
import assert from 'node:assert/strict';
import { parseMarketPlusImportFile } from '../src/pages/product/marketPlusImportFile.ts';

const batch = { schemaVersion: 1, source: 'LIVE_CHROME_MARKETPLUS', coverage: 'CURRENT_PAGE', mallId: 'testmall', rows: [{ externalId: '123' }] };
const collection = { schemaVersion: 1, kind: 'MARKETPLUS_COLLECTION', status: 'PARTIAL', pages: [{ page: 1, batch }, { page: 2, batch }] };
test('기존 단일 페이지 수집 파일과 여러 페이지 파일의 범위를 구분한다', () => {
  assert.equal(parseMarketPlusImportFile(batch).count, 1);
  const value = parseMarketPlusImportFile(collection);
  assert.equal(value.count, 2); assert.equal(value.pages.length, 2);
  assert.match(value.coverage, /부분 수집/);
  assert.match(value.coverage, /전체 상품 상태 검증 아님/);
});
test('중간 페이지부터 마지막까지 읽어도 전체 상품 수집 완료로 표시하지 않는다', () => {
  const value = parseMarketPlusImportFile({ ...collection, status: 'REACHED_LAST_PAGE', pages: [{ page: 9, batch }] });
  assert.match(value.coverage, /9~9페이지/);
  assert.match(value.coverage, /선택 조건/);
  assert.match(value.coverage, /전체 상품 상태 검증 아님/);
});
test('빈 파일·잘린 페이지·건너뛴 페이지·잘못된 형식을 가져오지 않는다', () => {
  for (const invalid of [null, {}, { ...collection, pages: [] }, { ...collection, pages: [{ page: 1, batch }, { page: 3, batch }] },
    { ...batch, rows: Array.from({ length: 101 }, () => ({})) }, { ...batch, coverage: 'ALL_PRODUCTS' },
    { ...collection, status: 'SUCCESS' }]) assert.throws(() => parseMarketPlusImportFile(invalid));
});
test('다른 쇼핑몰 파일이 섞이면 업로드 전에 중단한다', () => {
  assert.throws(() => parseMarketPlusImportFile({ ...collection, pages: [{ page: 1, batch }, { page: 2, batch: { ...batch, mallId: 'another' } }] }), /여러 쇼핑몰/);
});
