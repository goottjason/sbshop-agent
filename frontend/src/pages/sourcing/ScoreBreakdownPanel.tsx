import { Progress, Tag, Typography } from 'antd';
import type { ScoreBreakdown } from '../../api/sourcingDiscoveryApi';

const { Text } = Typography;

/** 서브스코어 키 → 사람이 읽는 이름. 서버 키와 1:1로 맞춰야 한다. */
const LABELS: Record<string, string> = {
  sales30d: '30일 판매량',
  reviewCount: '리뷰 수',
  rating: '평점',
  rank: 'iHerb 랭킹',
  discount: '할인율',
  searchVolume: '국내 검색량',
  competition: '경쟁강도(낮을수록 유리)',
  priceEdge: '가격 경쟁력',
  brandHistory: '자사 브랜드 실적',
  categoryHistory: '자사 카테고리 실적',
};

/** 결측 신호가 왜 비었는지 — 사용자가 조치할 수 있는 것과 아닌 것을 구분해 준다. */
const MISSING_HINTS: Record<string, string> = {
  searchVolume: '네이버 검색광고 API 미설정 또는 조회 실패',
  competition: '네이버 쇼핑검색 API 미설정 또는 조회 실패',
  priceEdge: '국내 최저가를 확인하지 못함',
  sales30d: 'iHerb가 판매량을 노출하지 않음',
  brandHistory: '자사 주문 이력 없음',
  categoryHistory: '자사 주문 이력 없음',
};

interface Props {
  breakdown: ScoreBreakdown;
}

/**
 * 점수 근거 패널.
 *
 * 기여도(contribution)는 이미 "가용 가중치 대비" 로 정규화된 값이라 그대로 더하면 총점이 된다.
 * 결측 항목을 함께 보여주는 게 중요하다 — 점수가 낮은 이유가 "상품이 별로"인지
 * "신호를 못 얻었다"인지 구분되지 않으면 사용자가 잘못된 판단을 한다.
 */
const ScoreBreakdownPanel = ({ breakdown }: Props) => {
  const parts = Object.entries(breakdown.parts ?? {}).sort(
    (a, b) => b[1].contribution - a[1].contribution,
  );

  return (
    <div style={{ padding: '8px 4px' }}>
      <div style={{ display: 'grid', gap: 6 }}>
        {parts.map(([key, part]) => (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Text style={{ width: 170, fontSize: 12 }}>{LABELS[key] ?? key}</Text>
            <Progress
              percent={Math.round(part.score * 100)}
              size="small"
              showInfo={false}
              strokeColor="#00B0A2"
              style={{ flex: 1, marginBottom: 0 }}
            />
            <Text type="secondary" style={{ width: 92, fontSize: 12, textAlign: 'right' }}>
              +{part.contribution.toFixed(1)}점
            </Text>
          </div>
        ))}
      </div>

      {breakdown.missing?.length > 0 && (
        <div style={{ marginTop: 10 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            신호 없음(가중치에서 제외되어 채점됨):{' '}
          </Text>
          {breakdown.missing.map((key) => (
            <Tag key={key} style={{ fontSize: 11 }} title={MISSING_HINTS[key] ?? ''}>
              {LABELS[key] ?? key}
            </Tag>
          ))}
        </div>
      )}

      <div style={{ marginTop: 8, fontSize: 12, color: '#888' }}>
        예상 판매가 {breakdown.estimatedSalePrice?.toLocaleString()}원 · 예상 마진{' '}
        {Math.round(breakdown.estimatedMargin ?? 0).toLocaleString()}원
      </div>
    </div>
  );
};

export default ScoreBreakdownPanel;
