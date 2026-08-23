import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Descriptions, Drawer, Space, Spin, Tag, Typography } from 'antd';
import {
  parseJsonField,
  sourcingDiscoveryApi,
  type Candidate,
  type ScoreBreakdown,
} from '../../api/sourcingDiscoveryApi';
import ScoreBreakdownPanel from './ScoreBreakdownPanel';
import { notify } from '../../utils/notify';

const { Text, Paragraph } = Typography;

const won = (v: number | null | undefined) =>
  v == null ? '-' : `₩${Math.round(v).toLocaleString()}`;

const num = (v: number | null | undefined) => (v == null ? '-' : v.toLocaleString());

const when = (v: string | null | undefined) =>
  v == null ? '-' : v.slice(0, 16).replace('T', ' ');

const VERDICT_TAGS: Record<string, { color: string; label: string }> = {
  PASS: { color: 'green', label: '통관 OK' },
  REVIEW: { color: 'gold', label: '통관 확인필요' },
  BLOCKED: { color: 'red', label: '통관 불가' },
  UNKNOWN: { color: 'default', label: '통관 미판정' },
};

interface Props {
  candidateId: number | null;
  fallback?: Candidate | null;
  onClose: () => void;
  onRejected: (id: number) => void;
}

const CandidateDetailDrawer = ({ candidateId, fallback, onClose, onRejected }: Props) => {
  const {
    data: candidate,
    isLoading,
    error,
  } = useQuery<Candidate>({
    queryKey: ['sourcing-candidate', candidateId],
    queryFn: async () => (await sourcingDiscoveryApi.candidate(candidateId as number)).data,
    enabled: candidateId != null,
    retry: false,
  });

  const shown = candidate ?? (candidateId != null && fallback?.id === candidateId ? fallback : null);
  const breakdown = parseJsonField<ScoreBreakdown | null>(shown?.scoreBreakdown, null);
  const verdict = VERDICT_TAGS[shown?.customsVerdict ?? 'UNKNOWN'] ?? VERDICT_TAGS.UNKNOWN;

  const handleReject = async () => {
    if (!shown) return;
    try {
      await sourcingDiscoveryApi.reject(shown.id);
      onRejected(shown.id);
      notify.success('거절했습니다. 쿨다운 기간 동안 재추천되지 않습니다.');
      onClose();
    } catch {
      notify.error('거절하지 못했습니다');
    }
  };

  return (
    <Drawer
      title={shown?.nameKo ?? '후보 상세'}
      width={720}
      open={candidateId != null}
      onClose={onClose}
      extra={
        shown && (
          <Space>
            <Button danger onClick={() => void handleReject()}>
              거절
            </Button>
          </Space>
        )
      }
    >
      {isLoading && !shown ? (
        <Spin />
      ) : error && !shown ? (
        <Alert type="error" showIcon message="후보 정보를 불러오지 못했습니다" />
      ) : shown ? (
        <>
          <Space align="start" style={{ marginBottom: 16 }}>
            {shown.imageUrl && (
              <img
                src={shown.imageUrl}
                alt=""
                width={96}
                height={96}
                style={{ borderRadius: 6, objectFit: 'cover' }}
              />
            )}
            <div>
              <div>
                <Tag color={verdict.color}>{verdict.label}</Tag>
                {shown.candidateStatus && <Tag>{shown.candidateStatus}</Tag>}
              </div>
              <Text type="secondary">{shown.brand ?? '브랜드 미상'}</Text>
              <div style={{ marginTop: 4 }}>
                <a href={shown.sourceUrl} target="_blank" rel="noreferrer">
                  소싱처 상품 페이지 열기
                </a>
              </div>
            </div>
          </Space>

          <Descriptions bordered size="small" column={2} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="소싱처">
              {shown.vendor} · {shown.externalId}
            </Descriptions.Item>
            <Descriptions.Item label="베스트셀러 순위">
              {shown.rankPosition == null ? '-' : `${shown.rankPosition}위`}
            </Descriptions.Item>
            <Descriptions.Item label="정가">{won(shown.listPrice)}</Descriptions.Item>
            <Descriptions.Item label="매입가(할인)">
              {won(shown.discountPrice)}
              {shown.discountPct != null && ` (-${shown.discountPct}%)`}
            </Descriptions.Item>
            <Descriptions.Item label="예상 판매가">{won(shown.estimatedSalePrice)}</Descriptions.Item>
            <Descriptions.Item label="예상 마진율">
              {shown.estimatedMarginRate == null ? '-' : `${shown.estimatedMarginRate}%`}
            </Descriptions.Item>
            <Descriptions.Item label="국내 최저가">{won(shown.domesticLowPrice)}</Descriptions.Item>
            <Descriptions.Item label="국내 시세(중앙값)">{won(shown.domesticMedianPrice)}</Descriptions.Item>
            <Descriptions.Item label="수요 조회 키워드">{shown.demandKeyword ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="월 검색량 · 경쟁상품">
              {num(shown.monthlySearchVolume)} · {num(shown.competitorCount)}
            </Descriptions.Item>
            <Descriptions.Item label="발견 시각">{when(shown.discoveredAt)}</Descriptions.Item>
            <Descriptions.Item label="최근 확인">{when(shown.lastSeenAt)}</Descriptions.Item>
            {shown.excludeReason && (
              <Descriptions.Item label="제외 사유" span={2}>
                <Text type="danger">{shown.excludeReason}</Text>
              </Descriptions.Item>
            )}
          </Descriptions>

          {shown.customsReason && (
            <Alert
              type={shown.customsVerdict === 'BLOCKED' ? 'error' : 'warning'}
              showIcon
              style={{ marginBottom: 16 }}
              message="통관 판정 사유"
              description={shown.customsReason}
            />
          )}

          {breakdown ? (
            <div style={{ marginBottom: 16 }}>
              <ScoreBreakdownPanel breakdown={breakdown} />
            </div>
          ) : (
            <Paragraph type="secondary">점수 근거 없음</Paragraph>
          )}

          <Text strong>성분 원문</Text>
          <Paragraph
            type="secondary"
            style={{
              fontSize: 12, marginTop: 6, maxHeight: 200, overflowY: 'auto',
              background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 6, padding: 10,
            }}
          >
            {shown.ingredientsRaw ?? '수집된 성분 정보가 없습니다.'}
          </Paragraph>
        </>
      ) : null}
    </Drawer>
  );
};

export default CandidateDetailDrawer;
