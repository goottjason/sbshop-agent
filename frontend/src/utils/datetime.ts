// 백엔드는 모든 시간 필드를 zone 정보 없는 LocalDateTime(UTC 벽시계값)으로 직렬화한다
// (컨테이너 TZ=UTC, Jackson time-zone은 LocalDateTime 직렬화에 영향 없음).
// 따라서 프론트에서 naive 문자열을 UTC로 간주해 KST(Asia/Seoul)로 변환·표시한다.
// 이 방식은 기존 데이터·신규 데이터를 모두 일관되게 교정하며, 브라우저 로컬 존과 무관하다.

const HAS_ZONE = /([zZ])|([+-]\d{2}:?\d{2})$/;

/** naive(존 없는) 문자열을 UTC로 간주해 Date로 파싱. 존 정보가 이미 있으면 그대로 사용. */
export function toKstDate(value?: string | null): Date | null {
  if (!value) return null;
  const normalized = HAS_ZONE.test(value) ? value : `${value}Z`;
  const d = new Date(normalized);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** KST "YYYY-MM-DD HH:mm" (분 단위). */
export function formatKstDateTime(value?: string | null): string {
  const d = toKstDate(value);
  if (!d) return '-';
  // sv-SE 로케일은 "YYYY-MM-DD HH:mm:ss" 형태를 반환 → 앞 16자(분까지)만 사용
  return d.toLocaleString('sv-SE', { timeZone: 'Asia/Seoul' }).slice(0, 16);
}

/** KST 전체 표시(초 포함, ko-KR). */
export function formatKst(value?: string | null): string {
  const d = toKstDate(value);
  if (!d) return '-';
  return d.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
}
