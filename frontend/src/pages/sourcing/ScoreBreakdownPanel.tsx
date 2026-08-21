import { Progress, Tag, Typography } from 'antd';
import type { ScoreBreakdown } from '../../api/sourcingDiscoveryApi';

interface Props {
  breakdown: ScoreBreakdown;
}

const { Text } = Typography;

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

const MISSING_HINTS: Record<string, string> = {
  searchVolume: '네이버 검색광고 API 미설정 또는 조회 실패',
  competition: '네이버 쇼핑검색 API 미설정 또는 조회 실패',
  priceEdge: '국내 최저가를 확인하지 못함',
  sales30d: 'iHerb가 판매량을 노출하지 않음',
  brandHistory: '자사 주문 이력 없음',
  categoryHistory: '자사 주문 이력 없음',
};

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
