import type { ProductContentFreshness } from '../../api/productApi';
import './productFreshness.css';

const time = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR') : '기록 없음';
function label(applied: string | null, collected: string | null) {
  if (!applied) return collected ? '수집됨 · 적용 기록 없음' : '적용 기록 없음';
  return new Date(applied).toLocaleDateString('ko-KR', { year: '2-digit', month: '2-digit', day: '2-digit' })
    + (collected && Date.parse(collected) > Date.parse(applied) ? ' · 새 수집' : '');
}
export function ProductContentFreshnessCell({ value, sbCode, disabled, onOpen }: {
  value: ProductContentFreshness | null | undefined; sbCode: string; disabled: boolean; onOpen: () => void;
}) {
  const title = value ? `이미지 수집 ${time(value.imagesCollectedAt)} / DB 적용 ${time(value.imagesAppliedAt)}\n`
    + `상세 수집 ${time(value.detailHtmlCollectedAt)} / DB 적용 ${time(value.detailHtmlAppliedAt)}\n클릭하여 수집·비교`
    : '콘텐츠 갱신 기록을 확인하지 못했습니다. 클릭하여 이력을 확인하세요.';
  return <button type="button" className="pw-freshness-cell" disabled={disabled} title={title}
    aria-label={`${sbCode} 이미지·상세 갱신 이력`} onClick={onOpen}>
    {value ? <>
      <span><b>이미지</b> {label(value.imagesAppliedAt, value.imagesCollectedAt)}</span>
      <span><b>상세</b> {label(value.detailHtmlAppliedAt, value.detailHtmlCollectedAt)}</span>
    </> : <span>갱신 기록 미확인</span>}
  </button>;
}
