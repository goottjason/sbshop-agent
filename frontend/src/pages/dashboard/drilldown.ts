// 대시보드 요소 → 통합 주문 관리(/orders) 드릴다운 URL 빌더. OrderGrid의 URL 파라미터 파싱과 계약 일치.
export interface DrilldownFilters {
  markets?: string[];
  statuses?: string[];
  customsStatuses?: string[];
  stockStatuses?: string[];
  vendors?: string[];
  keyword?: string;
  startDate?: string; // 'YYYY-MM-DD'
  endDate?: string;   // 'YYYY-MM-DD'
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
