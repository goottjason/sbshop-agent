import test from 'node:test';
import assert from 'node:assert/strict';
import { parseSbCodes, sourceProductUrl } from '../src/pages/product/productSearch.ts';

test('SB코드 붙여넣기: 쉼표·CRLF·줄바꿈·중복·대소문자·앞자리 0', () => {
  assert.deepEqual(parseSbCodes(' sb0001, SB0002\r\nSB0003\n sb0001 ,, '), ['SB0001', 'SB0002', 'SB0003']);
  assert.deepEqual(parseSbCodes(' , \r\n'), []);
  assert.equal(parseSbCodes(Array.from({ length: 3000 }, (_, i) => `SB${i}`).join('\n')).length, 3000);
});

test('소싱처 상품 링크는 절대 HTTP(S) URL만 새 탭 대상이 된다', () => {
  assert.equal(sourceProductUrl(' https://example.com/pr/product/001?ref=shop '), 'https://example.com/pr/product/001?ref=shop');
  assert.equal(sourceProductUrl('http://example.com/item'), 'http://example.com/item');
  for (const invalid of [null, undefined, '', ' ', '/products/1', '//example.com', 'javascript:alert(1)', 'data:text/html,test', 'https://']) {
    assert.equal(sourceProductUrl(invalid), undefined);
  }
});
