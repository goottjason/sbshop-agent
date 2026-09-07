import { apiClient } from './axios';
import type { EditReview } from './productChangeApi';

export interface BulkValueField {
  field: string; label: string; kind: 'TEXT' | 'TEXTAREA' | 'SELECT' | 'URL' | 'IMAGES' | 'HTML';
  maxLength: number; clearable: boolean; options: { value: string; label: string }[];
}
export const productBulkValuesApi = {
  fields: (signal?: AbortSignal) => apiClient.get<BulkValueField[]>('/api/v1/products/changes/values-preview/fields', { signal }),
  preview: (productIds: number[], values: Record<string, string | string[]>) =>
    apiClient.post<EditReview>('/api/v1/products/changes/values-preview', { productIds, values }),
};
