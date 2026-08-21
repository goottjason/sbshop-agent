export interface DrilldownFilters {
  markets?: string[];
  statuses?: string[];
  customsStatuses?: string[];
  stockStatuses?: string[];
  vendors?: string[];
  keyword?: string;
  startDate?: string;
  endDate?: string;
}

export function buildOrderGridUrl(f: DrilldownFilters): string {
  const p = new URLSearchParams();
  f.markets?.forEach((m) => p.append('markets', m));
  f.statuses?.forEach((s) => p.append('statuses', s));
  f.customsStatuses?.forEach((c) => p.append('customsStatuses', c));
  f.stockStatuses?.forEach((s) => p.append('stockStatuses', s));
  f.vendors?.forEach((v) => p.append('vendors', v));
  if (f.keyword) p.set('keyword', f.keyword);
  if (f.startDate) p.set('startDate', f.startDate);
  if (f.endDate) p.set('endDate', f.endDate);
  const qs = p.toString();
  return qs ? `/orders?${qs}` : '/orders';
}
