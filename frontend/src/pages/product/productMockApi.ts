import { productApi, type ProductEditFields } from '../../api/productApi';

let warnedUpdate = false;

// MOCK: 다음 세션 백엔드 구현 (PATCH /api/v1/products/{id})
// 현재는 서버 반영 없이 지연 후 성공만 반환. 호출부는 낙관적 로컬 반영으로 UX 완성.
export function updateProductFields(id: number, fields: Partial<ProductEditFields>): Promise<{ ok: true }> {
  if (!warnedUpdate) {
    console.warn('[MOCK] updateProductFields: 백엔드 PATCH 미구현 — 로컬 반영만. 다음 세션 구현 예정.');
    warnedUpdate = true;
  }
  console.warn('[MOCK] updateProductFields', id, fields);
  return new Promise((resolve) => setTimeout(() => resolve({ ok: true }), 300));
}

// 일괄 삭제: 신규 bulk 엔드포인트 대신 기존 단건 DELETE를 순차 호출(실동작).
// 다음 세션에 POST /api/v1/products/bulk-delete로 교체(인터페이스 유지).
export async function bulkDeleteProducts(ids: number[]): Promise<{ deleted: number; failed: number[] }> {
  const failed: number[] = [];
  let deleted = 0;
  for (const id of ids) {
    try {
      await productApi.deleteProduct(id);
      deleted += 1;
    } catch {
      failed.push(id);
    }
  }
  return { deleted, failed };
}
