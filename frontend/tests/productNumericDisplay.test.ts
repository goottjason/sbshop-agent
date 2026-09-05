import test from 'node:test';
import assert from 'node:assert/strict';
import { formatNumericPreviewValue } from '../src/pages/product/productNumericDisplay.ts';

test('금액 표시에서 큰 수와 긴 소수의 원래 정밀도를 유지한다', () => {
  assert.equal(formatNumericPreviewValue('999999999999999.99'), '999,999,999,999,999.99');
  assert.equal(formatNumericPreviewValue('12345.678901234567890123'), '12,345.678901234567890123');
  assert.equal(formatNumericPreviewValue('-12345.05'), '-12,345.05');
});

test('0·누락·작은 중량을 구별하고 표시 과정에서 반올림하지 않는다', () => {
  assert.equal(formatNumericPreviewValue('0'), '0');
  assert.equal(formatNumericPreviewValue(null), '값 없음');
  assert.equal(formatNumericPreviewValue('0.05'), '0.05');
  assert.equal(formatNumericPreviewValue('4.5'), '4.5');
});
