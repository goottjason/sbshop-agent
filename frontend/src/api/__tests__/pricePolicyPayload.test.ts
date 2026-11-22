import { expect, it } from 'vitest';
import { toPricePolicyPayload } from '../pricePolicyApi';

it('공통 가격 정책: 빈 칸은 null, 0은 0으로 보낸다 — 마진 하한율·하한액 포함', () => {
  expect(toPricePolicyPayload({
    marginRate: '15', couponRate: '0', minMarginPrice: '', marginFloorRate: '5', marginFloorAmount: ' 5000 ',
  })).toEqual({ marginRate: 15, couponRate: 0, minMarginPrice: null, marginFloorRate: 5, marginFloorAmount: 5000 });
});

it('마진 하한을 둘 다 비우면 null로 보내 하한을 쓰지 않는다', () => {
  expect(toPricePolicyPayload({
    marginRate: '15', couponRate: '20', minMarginPrice: '5000', marginFloorRate: '', marginFloorAmount: '',
  })).toEqual({ marginRate: 15, couponRate: 20, minMarginPrice: 5000, marginFloorRate: null, marginFloorAmount: null });
});

it('마진 하한 0은 0으로 보낸다(빈 값과 다르다)', () => {
  expect(toPricePolicyPayload({
    marginRate: '', couponRate: '', minMarginPrice: '', marginFloorRate: '0', marginFloorAmount: '0',
  })).toMatchObject({ marginFloorRate: 0, marginFloorAmount: 0 });
});
