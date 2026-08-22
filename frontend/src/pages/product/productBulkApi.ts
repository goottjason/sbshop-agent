import { productApi } from '../../api/productApi';

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
