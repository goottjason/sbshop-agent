import { apiClient } from '../../api/axios';

export type Unit = 'DAY' | 'WEEK' | 'MONTH';
export type Dimension = 'MARKET' | 'STATUS' | 'PRODUCT' | 'VENDOR';

export interface Summary {
  period: { orderCount: number; settlementSum: number; profitSum: number };
  current: { newCount: number; shippingCount: number; customsIssueCount: number };
}
export interface TimeseriesBucket { bucketStart: string; orderCount: number; settlementSum: number; profitSum: number; }
export interface BreakdownItem { key: string; label: string; orderCount: number; settlementSum: number; profitSum: number; }
export interface Attention { customsIssue: number; outOfStock: number; delayed: number; returnCancel: number; }

const iso = (d: string) => d; // 'YYYY-MM-DDTHH:mm:ss' 형태로 이미 조립됨

export const fetchSummary = async (start: string, end: string): Promise<Summary> =>
  (await apiClient.get('/api/v1/dashboard/summary', { params: { start: iso(start), end: iso(end) } })).data;

export const fetchTimeseries = async (start: string, end: string, unit: Unit): Promise<TimeseriesBucket[]> =>
  (await apiClient.get('/api/v1/dashboard/timeseries', { params: { start, end, unit } })).data;

export const fetchBreakdown = async (start: string, end: string, dimension: Dimension, limit = 10): Promise<BreakdownItem[]> =>
  (await apiClient.get('/api/v1/dashboard/breakdown', { params: { start, end, dimension, limit } })).data;

export const fetchAttention = async (): Promise<Attention> =>
  (await apiClient.get('/api/v1/dashboard/attention')).data;
