import { Fragment } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Tag } from 'antd';
import { productChangeApi } from '../../api/productChangeApi';
import { marketLabel } from '../../utils/marketLabels';
import { formatNumericPreviewValue } from './productNumericDisplay';

export function ProductPricePreview({ productId }: { productId: number }) {
  const preview = useQuery({
    queryKey: ['product-price-preview', productId], enabled: false, retry: false, gcTime: 0,
    queryFn: async ({ signal }) => (await productChangeApi.pricePreview(productId, signal)).data,
  });
  const money = (value: string | null) => value === null ? '—' : `${formatNumericPreviewValue(value)}원`;
  const result = preview.data;
  return <div style={{ marginTop: 12 }}>
    <Button size="small" loading={preview.isFetching} onClick={() => { void preview.refetch(); }}>마켓별 가격 계산 근거</Button>
    <p className="pw-change-note">저장된 상품과 현재 정책으로 계산합니다. 편집 중인 값은 저장 후 반영됩니다. 실제 마켓 판매가는 별도로 조회하세요.</p>
    {preview.isError && <Alert type="error" showIcon message="가격 계산 정보를 불러오지 못했습니다. 다시 계산해 주세요." />}
    {result && !preview.isError && !preview.isFetching && <div className="pw-price-preview">
      <p className="pw-change-note">{new Date(result.generatedAt).toLocaleString('ko-KR')} 기준 · 계산 결과이며 저장·마켓 전송은 실행하지 않습니다.</p>
      {result.items.length === 0 ? <p>가격 계산을 지원하는 마켓이 없습니다.</p> : <table>
        <thead><tr><th>마켓</th><th>100원 반올림가</th><th>최소마진 하한</th><th>최종가</th></tr></thead>
        <tbody>{result.items.map((item) => <Fragment key={item.market}>
          <tr><td>{marketLabel(item.market)}</td><td>{money(item.roundedPrice)}</td>
            <td>{money(item.minimumPrice)}</td><td><strong>{money(item.salePrice)}</strong></td></tr>
          <tr><td colSpan={4}>
            {item.minimumAdjusted && <Tag color="gold">최소마진 보정</Tag>}
            {item.status === 'FALLBACK' && <Tag color="orange">최소마진 미검증</Tag>}
            {item.status === 'FAILED' && <Tag color="red">계산 실패</Tag>}
            <span>{item.reason}</span>
          </td></tr>
        </Fragment>)}</tbody>
      </table>}
    </div>}
  </div>;
}
