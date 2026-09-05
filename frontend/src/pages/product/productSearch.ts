import type { ProductFilters } from './ProductFilterPanel';

export const EMPTY_PRODUCT_FILTERS: ProductFilters = {
  keyword: '', sbCodes: [], brands: [], categories: [], includeUncategorized: false,
  markets: [], vendors: [], stockStatuses: [], inStockOnly: false, sourceGone: 'ALL',
};

export function parseSbCodes(text: string): string[] {
  return [...new Set(text.split(/[,\r\n]+/).map((code) => code.trim().toUpperCase()).filter(Boolean))];
}

/** Stored source URLs are data: only open absolute HTTP(S) links. */
export function sourceProductUrl(value: string | null | undefined): string | undefined {
  if (!value?.trim()) return undefined;
  try {
    const url = new URL(value.trim());
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : undefined;
  } catch {
    return undefined;
  }
}
