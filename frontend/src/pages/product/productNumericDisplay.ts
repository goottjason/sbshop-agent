/** Preserve the API's exact decimal string; formatting must not round the proposed value. */
export function formatNumericPreviewValue(value: string | null): string {
  if (value === null) return '값 없음';
  const match = /^([+-]?)(\d+)(\.\d+)?$/.exec(value);
  if (!match) return value;
  return `${match[1]}${match[2].replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${match[3] ?? ''}`;
}
