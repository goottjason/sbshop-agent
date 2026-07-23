// 전화번호를 하이픈 형식(010-0000-0000)으로 정규화한다.
// 쿠팡·스마트스토어 주문은 하이픈 없이(예: 01000000000) 저장되는 경우가 있어
// 표시 시점에 숫자만 추출해 마켓 무관하게 일관된 형식으로 보정한다.
// 이미 하이픈이 포함된 값도 숫자만 뽑아 동일 규칙으로 다시 포맷하므로 표시가 통일된다.
export function formatPhone(raw?: string | null): string {
  if (!raw) return '';
  const digits = raw.replace(/\D/g, '');
  if (!digits) return raw;
  // 휴대폰 11자리: 010-0000-0000
  if (digits.length === 11) return `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7)}`;
  // 10자리: 서울 국번(02) → 2-4-4, 그 외 → 3-3-4
  if (digits.length === 10) {
    return digits.startsWith('02')
      ? `${digits.slice(0, 2)}-${digits.slice(2, 6)}-${digits.slice(6)}`
      : `${digits.slice(0, 3)}-${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  // 서울 국번(02) 9자리: 02-000-0000
  if (digits.length === 9 && digits.startsWith('02')) {
    return `${digits.slice(0, 2)}-${digits.slice(2, 5)}-${digits.slice(5)}`;
  }
  // 규칙에 맞지 않는 길이는 원본을 유지(잘못된 포맷 전파 방지).
  return raw;
}
