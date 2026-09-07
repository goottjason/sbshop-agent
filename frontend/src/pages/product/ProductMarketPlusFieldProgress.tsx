import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Select, Tag } from 'antd';
import { marketPlusFieldProgressApi, type MarketPlusFieldStage } from '../../api/marketPlusFieldProgressApi';
import { marketLabel } from '../../utils/marketLabels';
import './productMarketPlusProgress.css';

const labels: Record<string, string> = {
  salePrice: '기준 판매가', costPrice: '소싱 원가', exchangeRate: '환율', deliveryFee: '배송비', minMarginPrice: '최소 마진가',
  marginRate: '마진율', couponRate: '쿠폰율', salesQuantity: '판매용 설정 수량', stock: '기존 DB 재고',
  brand: '브랜드', category: '카테고리', name: '상품명', baseName: '기본명', originalName: '원문명',
  barcode: '바코드', capacity: '용량', measureUnit: '용량 단위', weight: '무게', bundleQuantity: '묶음수량',
  sourceImages: '원본 이미지', hostedImages: '대표·추가 이미지', detailHtml: '상세 HTML', UNREADABLE: '변경 기록 오류',
};
const stageLabels: Record<string, string> = {
  PUBLIC_VALUE_OBSERVED: '공개 표시값 관측', CAFE24_CONFIRMED: '카페24 값 확인', PENDING: '처리·확인 대기', REVIEW_REQUIRED: '조치 필요',
  UNSUPPORTED_FIELD: '필드 확인 경로 필요', UNVERIFIED: '미확인', STALE_EVIDENCE: '과거·불일치 근거',
  CONNECTION_CHANGED: '연결 해제·변경', EVIDENCE_ERROR: '기록 확인 오류', NOT_OBSERVED: '관측 없음',
  CONFLICT_OBSERVED: '성공·실패 충돌', FAILURE_OBSERVED: '전송 실패 관측', SUCCESS_OBSERVED: '전송 성공 관측',
};
const time = (value: string | null) => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '확인 기록 없음';
function Stage({ value }: { value: MarketPlusFieldStage }) {
  const failed = ['REVIEW_REQUIRED', 'EVIDENCE_ERROR', 'CONFLICT_OBSERVED', 'FAILURE_OBSERVED'].includes(value.code);
  return <div className="mpfp-stage"><Tag color={failed ? 'red' : value.code === 'CAFE24_CONFIRMED' ? 'green' : value.code === 'SUCCESS_OBSERVED' ? 'blue' : 'default'}>{stageLabels[value.code] ?? value.code}</Tag>
    <p>{value.detail}</p>
    {(value.expectedValue !== null || value.observedValue !== null) && <div>목표 {value.expectedValue ?? '미확인'} / 관측 {value.observedValue ?? '미확인'}</div>}
    {value.at && <small>{time(value.at)}</small>}
  </div>;
}

export function ProductMarketPlusFieldProgress({ productId }: { productId: number }) {
  const [market, setMarket] = useState('ALL');
  const [field, setField] = useState('ALL');
  const query = useQuery({ queryKey: ['marketplus-field-progress', productId], retry: false,
    queryFn: async ({ signal }) => (await marketPlusFieldProgressApi.get(productId, signal)).data });
  const data = query.data;
  const fields = [...new Set(data?.fields.map(row => row.field) ?? [])];
  const filtered = data?.fields.filter(row => (market === 'ALL' || row.market === market) && (field === 'ALL' || row.field === field)) ?? [];
  return <section className="mpfp" aria-label="G마켓·옥션 필드별 반영 단계">
    <div className="mpfp-heading"><strong>DB 변경 → 카페24 → 마켓플러스 → 최종 마켓</strong>
      <Button size="small" loading={query.isFetching} onClick={() => { void query.refetch(); }}>반영 단계 새로고침</Button></div>
    <p className="mpfp-note">전송 이력의 성공과 현재 상품의 필드 일치를 구분합니다. 마켓별 최종값을 확인해야 반영 완료로 볼 수 있습니다.</p>
    {query.isError && <Alert type="error" showIcon message="필드별 반영 단계를 조회하지 못했습니다. 다시 조회하세요." />}
    {query.isPending && <p role="status">저장 버전과 마켓별 근거 조회 중…</p>}
    {data && <>
      <div className="mpfp-filters"><span>{data.sbCode} · 현재 버전 {data.currentRevision}</span>
        <Select aria-label="반영 단계 마켓" value={market} onChange={setMarket} options={[{ value: 'ALL', label: '두 마켓 모두' }, { value: 'GMARKET', label: 'G마켓' }, { value: 'AUCTION', label: '옥션' }]} />
        <Select aria-label="반영 단계 필드" value={field} onChange={setField} options={[{ value: 'ALL', label: '모든 변경 필드' }, ...fields.map(value => ({ value, label: labels[value] ?? value }))]} />
      </div>
      {data.truncated && <Alert type="warning" showIcon message="표시 한도 240개 필드에 도달했습니다. 최근 변경 이력 기준 일부만 표시합니다." />}
      {!filtered.length ? <p className="mpfp-empty">{data.fields.length ? '선택한 조건의 변경 필드가 없습니다.' : '최근 DB 변경 중 추적 가능한 G마켓·옥션 전송 대상이 없습니다. 기존 상품의 동기화 완료를 뜻하지 않습니다.'}</p>
        : <div className="mpfp-table"><table><thead><tr><th>DB에 저장한 변경</th><th>카페24 본상품</th><th>마켓플러스 전송 관측</th><th>G마켓·옥션 최종값</th></tr></thead>
          <tbody>{filtered.map(row => <tr key={`${row.targetId}:${row.field}`}>
            <td><strong>{marketLabel(row.market)} · {labels[row.field] ?? row.field}</strong>
              <div><Tag>{row.currentRevision ? '현재 DB 버전' : '과거 DB 버전'} {row.revision}</Tag><small>이력 #{row.historyId}</small></div>
              <div className="mpfp-value">{row.beforeValue ?? '(없음)'} → {row.savedValue ?? '(없음)'}</div>
              {row.shortened && <small>긴 값은 앞부분만 표시합니다. 변경 이력에서 전체 내용을 확인하세요.</small>}
              <small>{time(row.savedAt)}</small>
              {!row.currentRevision && <p className="mpfp-historical">이후 DB 변경이 있습니다. 이 행의 확인 결과는 현재 상품의 일치 근거가 아닙니다.</p>}
            </td><td><Stage value={row.cafe24} /></td><td><Stage value={row.transmission} /></td><td><Stage value={row.finalMarket} /></td>
          </tr>)}</tbody></table></div>}
      {!!data.publicValues?.length && <details className="mpfp-notices"><summary>공개 상품 표시값 관측 ({data.publicValues.length}) · 일치 여부 별도 확인</summary>
        {data.publicValues.map(item => <p key={item.id}><strong>{marketLabel(item.market)} · {item.sellerAccount} · {item.externalId}</strong><br />
          {Object.entries(item.values).map(([key, value]) => `${labels[key] ?? key} ${value}`).join(' / ')}<br />
          <small>{time(item.capturedAt)} · DB 버전 {item.productRevision} · {item.currentConnection ? '현재 연결' : '과거 연결'} · {item.currentRevision ? '현재 버전에서 관측' : '이후 DB 변경 있음'}</small></p>)}
      </details>}
      <details className="mpfp-preparation"><summary>재전송 준비 · 계정·상품과 필요한 확인 ({data.preparations.length})</summary>
        {!data.preparations.length && <p>준비할 G마켓·옥션 상품 연결이 없습니다.</p>}
        {data.preparations.map(preparation => <div key={`${preparation.market}:${preparation.externalId}`}>
          <strong>{marketLabel(preparation.market)} · {preparation.sellerAccount ?? '판매 계정 미확인'}</strong>
          <p>카페24 {preparation.cafe24ProductCode ?? '코드 미확인'} / {preparation.cafe24ProductNo ?? '번호 미확인'} → 마켓 {preparation.externalId}</p>
          <Tag>{preparation.connectionState}</Tag>
          <ul>{preparation.blockers.map(reason => <li key={reason}>{reason}</li>)}</ul>
          <div className="mpfp-links"><Button size="small" disabled>자동 재전송 준비 중</Button>
            <a href={preparation.historyUrl} target="_blank" rel="noopener noreferrer">마켓플러스에서 확인</a>
            {preparation.publicProductUrl && <a href={preparation.publicProductUrl} target="_blank" rel="noopener noreferrer">마켓 상품 보기</a>}</div>
        </div>)}
      </details>
      <details className="mpfp-notices"><summary>확인 범위·시각</summary>{data.notices.map(note => <p key={note}>{note}</p>)}<small>{time(data.generatedAt)} 조회</small></details>
    </>}
  </section>;
}
