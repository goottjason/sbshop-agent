import { isAxiosError } from 'axios';
import type { SupplierBatchMode, SupplierBatchStage, SupplierBatchStageKind } from '../../api/supplierBatchApi';
import { marketLabel } from '../../utils/marketLabels';

export const BATCH_BLUE = '#2454D8';
export const batchMarketLabel = (market: string) => market === 'SMART_STORE' ? '스마트스토어' : marketLabel(market);
export const modeLabel: Record<SupplierBatchMode, string> = { PRICE_STOCK: '가격 + 재고', PRICE: '가격만', STOCK: '재고만' };
export const numberText = (value: number | string | null | undefined) => {
  if (value == null || value === '') return '미확인';
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed.toLocaleString('ko-KR', { maximumFractionDigits: 10 }) : String(value);
};
export const dateText = (value: string | null | undefined) => value ? new Date(value).toLocaleString('ko-KR') : '기록 없음';
export const stageLabel = (stage: SupplierBatchStageKind, market?: string | null, field?: string | null) =>
  `${stage === 'CRAWL' ? '수집' : stage === 'DB' ? 'SB 저장' : batchMarketLabel(market ?? '')}${field === 'PRICE' ? ' 판매가' : field === 'STOCK' ? ' 수량' : ''}`;
export function stageStatus(stage: SupplierBatchStage): { text: string; tone: string } {
  switch (stage.state) {
    case 'SUCCEEDED': return { text: stage.stage === 'CRAWL' ? '수집 완료' : stage.stage === 'DB' ? '저장 완료' : stage.field === 'STOCK' && stage.observed != null && stage.observed.trim() !== '' && Number(stage.observed) === 0 ? '품절 반영' : '반영 확인', tone: 'success' };
    case 'UNCHANGED': return { text: '변경 없음', tone: 'unchanged' };
    case 'FAILED': return { text: '실패', tone: 'failure' };
    case 'BLOCKED': return { text: '보류', tone: 'blocked' };
    case 'SKIPPED': return { text: '제외', tone: 'muted' };
    case 'RUNNING': return { text: stage.detail?.startsWith('전송 후 재조회 중') ? '재조회 중' : stage.detail?.startsWith('전송 작업 접수 완료') ? '전송 대기' : '처리 중', tone: 'running' };
    case 'WAITING': return { text: '대기', tone: 'muted' };
    default: return { text: '상태 확인 필요', tone: 'blocked' };
  }
}
export const mayRetry = (stage: SupplierBatchStage) => stage.retryable && (stage.state === 'FAILED' || stage.state === 'BLOCKED');
export const requestError = (error: unknown, fallback: string) =>
  isAxiosError(error) && typeof error.response?.data?.message === 'string' ? `${fallback} ${error.response.data.message}` : fallback;
