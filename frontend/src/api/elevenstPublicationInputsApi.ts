import { apiClient } from './axios';

export interface ElevenstPublicationContext {
  categoryId: string; categoryPath: null; salePrice: null; keywords: string[];
  noticeFields: Record<string, string>; extraFields: { elevenst: Record<string, string> };
}
export interface ElevenstPublicationSchema {
  stage: 'INPUT_ONLY'; executable: false; capturedDate: string; productRevision: number; categoryId: string; accountReference: string;
  limitations: string[];
  fields: { name: string; label: string; section: string; description: string; options: { value: string; label: string }[] }[];
  noticeTypes: { code: string; label: string; items: { code: string; label: string }[] }[];
  addresses: Record<'addrSeqOut' | 'addrSeqIn', { code: string; label: string; address: string }[]>;
  productFields: { sbCode: string; productName: string; brand: string; referenceSalePrice: number | null; salesQuantity: number | null; stockStatus: string; images: string[] };
}
export interface ElevenstPublicationReview {
  stage: 'INPUT_ONLY'; executable: false; inputComplete: boolean; issues: string[]; limitations: string[];
  productRevision: number; context: ElevenstPublicationContext;
}
export const elevenstPublicationInputsApi = {
  get: (id: number, signal?: AbortSignal) => apiClient.get<{ schema: ElevenstPublicationSchema; previousEvidence: [] }>(
    `/api/v1/products/${id}/publication-inputs`, { params: { market: 'ELEVEN_STREET' }, signal }),
  review: (id: number, productRevision: number, accountReference: string, context: ElevenstPublicationContext) => apiClient.post<ElevenstPublicationReview>(
    `/api/v1/products/${id}/publication-inputs/elevenst-review`, { productRevision, accountReference, context }),
};
