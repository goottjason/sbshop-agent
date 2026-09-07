import { apiClient } from './axios';
export interface PublicationInputContext {
  categoryId: string; categoryPath: string | null; salePrice?: number | null; keywords?: string[];
  noticeFields: Record<string, string>;
  extraFields: { noticeCategoryName?: string; attributes?: Record<string, string>; certifications?: Record<string, string>;
    documentUrls?: Record<string, string>; documentNotApplicable?: Record<string, string> };
}
export interface PublicationInputSchema {
  categoryId: string; categoryName: string;
  noticeCategories: { name: string; fields: { name: string; required: boolean }[] }[];
  attributes: { name: string; required: boolean; group: string; exclusiveGroup: boolean; inputType: string; dataType: string; units: string[]; values: string[] }[];
  documents: { name: string; requirement: string; canDeclareNotApplicable: boolean }[];
  certifications: { type: string; name: string; required: boolean; dataType: string }[];
  suggestedContext: PublicationInputContext;
}
export interface PublicationInputs {
  schema: PublicationInputSchema;
  previousEvidence: { id: string; label: string; categoryId: string; context: PublicationInputContext }[];
}
export const productPublicationInputsApi = {
  get: (productId: number, market: string, categoryId?: string, signal?: AbortSignal) => apiClient.get<PublicationInputs>(
    `/api/v1/products/${productId}/publication-inputs`, { params: { market, ...(categoryId ? { categoryId } : {}) }, signal }),
};
