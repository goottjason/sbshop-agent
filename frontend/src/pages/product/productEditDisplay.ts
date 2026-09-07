import { formatNumericPreviewValue } from './productNumericDisplay';

const labels: Record<string, string> = {
  name: '상품명', baseName: '기본명', originalName: '원문명', brand: '브랜드', category: '카테고리',
  costPrice: '원가', salePrice: '기준 판매가', exchangeRate: '환율', deliveryFee: '배송비', marginRate: '마진율',
  couponRate: '쿠폰율', minMarginPrice: '최소마진', salesQuantity: '판매용 설정 수량', stock: 'DB 재고', weight: '무게(kg)', bundleQuantity: '묶음수량',
  capacity: '용량', measureUnit: '단위', barcode: '바코드', memo: '메모', sourceUrl: '소싱 URL', vendor: '소싱처',
  manufacturer: '제조사', origin: '원산지', hsCode: 'HS코드', hostedImages: '게시 이미지', sourceImages: '원본 이미지',
  detailHtml: '상세 HTML', searchKeywords: '검색어',
};
export const editFieldLabel = (field: string) => labels[field] ?? field;
const numericFields = new Set(['salePrice', 'costPrice', 'exchangeRate', 'deliveryFee', 'marginRate', 'couponRate', 'minMarginPrice', 'stock', 'salesQuantity', 'weight', 'bundleQuantity', 'capacity']);
export const editValue = (field: string, value: string | null) => value === null ? '값 없음' : numericFields.has(field) ? formatNumericPreviewValue(value) : value || '(비어 있음)';

