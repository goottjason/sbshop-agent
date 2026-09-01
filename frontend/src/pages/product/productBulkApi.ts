import { productApi } from '../../api/productApi';

/** 마켓 API 요청 한도(네이버 GW.RATE_LIMIT)를 피하기 위한 건별 간격. */
const DELETE_THROTTLE_MS = 400;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * 상품을 하나씩 삭제한다.
 *
 * 건별로 마켓 삭제 API를 호출하므로 쉬지 않고 돌리면 네이버가 429(GW.RATE_LIMIT)로 막는다 —
 * 2026-09-01 폐기 후보 50건 삭제에서 6건이 이 이유로 실패했다. 간격을 두어 실패를 줄인다.
 */
export async function bulkDeleteProducts(
  ids: number[],
  onProgress?: (done: number, total: number) => void
): Promise<{ deleted: number; failed: number[] }> {
  const failed: number[] = [];
  let deleted = 0;
  for (let i = 0; i < ids.length; i += 1) {
    try {
      await productApi.deleteProduct(ids[i]);
      deleted += 1;
    } catch {
      failed.push(ids[i]);
    }
    onProgress?.(i + 1, ids.length);
    if (i < ids.length - 1) await sleep(DELETE_THROTTLE_MS);
  }
  return { deleted, failed };
}
