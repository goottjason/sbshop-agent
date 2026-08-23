import { useEffect, useState } from 'react';
import {
  Alert, Button, Card, Checkbox, Col, Divider, Input, InputNumber, Row, Slider, Space, Spin,
  Typography,
} from 'antd';
import { RefreshCw, Save } from 'lucide-react';
import { sourcingDiscoveryApi, type SourcingConfig } from '../../api/sourcingDiscoveryApi';
import { notify } from '../../utils/notify';

const { Title, Text, Paragraph } = Typography;

const ALL_CATEGORIES: { slug: string; label: string; hint: string }[] = [
  { slug: 'supplements', label: '보충제', hint: '비타민·미네랄·유산균·오메가 등' },
  { slug: 'grocery', label: '식품', hint: '커피·차·스낵. 통관 리스크가 낮다' },
  { slug: 'sports-nutrition', label: '스포츠영양', hint: '단백질·프로테인. 객단가가 높다' },
  { slug: 'herbs-homeopathy', label: '허브', hint: '허브·동종요법. 반입차단 성분이 상대적으로 많다' },
];

const WEIGHT_FIELDS: { key: string; label: string; group: string }[] = [
  { key: 'sales30d', label: '30일 판매량', group: 'iHerb 신호' },
  { key: 'reviewCount', label: '리뷰 수', group: 'iHerb 신호' },
  { key: 'rating', label: '평점', group: 'iHerb 신호' },
  { key: 'rank', label: '랭킹', group: 'iHerb 신호' },
  { key: 'discount', label: '할인율', group: 'iHerb 신호' },
  { key: 'searchVolume', label: '국내 검색량', group: '국내 수요' },
  { key: 'competition', label: '경쟁강도(역방향)', group: '국내 수요' },
  { key: 'priceEdge', label: '가격 경쟁력', group: '국내 수요' },
  { key: 'brandHistory', label: '브랜드 실적', group: '자사 이력' },
  { key: 'categoryHistory', label: '카테고리 실적', group: '자사 이력' },
];

const SourcingSettingsPage = () => {
  const [config, setConfig] = useState<SourcingConfig | null>(null);
  const [weights, setWeights] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [syncing, setSyncing] = useState(false);

  useEffect(() => {
    void (async () => {
      try {
        const res = await sourcingDiscoveryApi.config();
        setConfig(res.data);
        try {
          setWeights(JSON.parse(res.data.scoreWeights || '{}'));
        } catch {
          setWeights({});
        }
      } catch {
        notify.error('설정을 불러오지 못했습니다');
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  const set = <K extends keyof SourcingConfig>(key: K, value: SourcingConfig[K]) =>
    setConfig((prev) => (prev ? { ...prev, [key]: value } : prev));

  const toggleCategory = (slug: string, checked: boolean) => {
    if (!config) return;
    const current = config.categories.split(',').map((s) => s.trim()).filter(Boolean);
    const next = checked ? [...new Set([...current, slug])] : current.filter((c) => c !== slug);
    set('categories', next.join(','));
  };

  const handleSave = async () => {
    if (!config) return;
    setSaving(true);
    try {
      const res = await sourcingDiscoveryApi.updateConfig({
        ...config,
        scoreWeights: JSON.stringify(weights),
      });
      setConfig(res.data);
      notify.success('설정을 저장했습니다');
    } catch {
      notify.error('저장에 실패했습니다');
    } finally {
      setSaving(false);
    }
  };

  const handleSyncBanned = async () => {
    setSyncing(true);
    try {
      const res = await sourcingDiscoveryApi.syncBannedIngredients();
      if (res.data.ok) {
        notify.success(
          `반입차단 성분 동기화 완료 — 신규 ${res.data.created} · 갱신 ${res.data.updated} · 현재 차단중 ${res.data.activeCount}건`,
        );
      } else {
        notify.error(`동기화 실패(기존 ${res.data.activeCount}건 유지): ${res.data.error}`);
      }
    } catch {
      notify.error('동기화 요청에 실패했습니다');
    } finally {
      setSyncing(false);
    }
  };

  if (loading || !config) {
    return (
      <div style={{ padding: 48, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  const selectedCategories = config.categories.split(',').map((s) => s.trim());
  const weightTotal = Object.values(weights).reduce((a, b) => a + b, 0);

  return (
    <div style={{ padding: 24, maxWidth: 1000 }}>
      <Title level={3}>소싱 자동화 설정</Title>

      <Card title="추천 범위" size="small" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 12]}>
          <Col span={6}>
            <Text type="secondary">한 번에 추천할 개수</Text>
            <InputNumber
              min={5}
              max={100}
              value={config.recommendCount}
              onChange={(v) => set('recommendCount', v ?? 20)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={6}>
            <Text type="secondary">카테고리당 크롤 페이지</Text>
            <InputNumber
              min={1}
              max={10}
              value={config.pagesPerCategory}
              onChange={(v) => set('pagesPerCategory', v ?? 3)}
              style={{ width: '100%' }}
            />
            <Text type="secondary" style={{ fontSize: 11 }}>
              1페이지 ≈ 48개, 렌더에 ~45초
            </Text>
          </Col>
          <Col span={12}>
            <Text type="secondary">크롤 대상 카테고리</Text>
            <div>
              {ALL_CATEGORIES.map((c) => (
                <div key={c.slug}>
                  <Checkbox
                    checked={selectedCategories.includes(c.slug)}
                    onChange={(e) => toggleCategory(c.slug, e.target.checked)}
                  >
                    {c.label}{' '}
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      — {c.hint}
                    </Text>
                  </Checkbox>
                </div>
              ))}
            </div>
          </Col>
        </Row>
      </Card>

      <Card title="필터" size="small" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 12]}>
          <Col span={6}>
            <Text type="secondary">최소 리뷰 수</Text>
            <InputNumber
              min={0}
              value={config.minReviewCount}
              onChange={(v) => set('minReviewCount', v ?? 0)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={6}>
            <Text type="secondary">최소 평점</Text>
            <InputNumber
              min={0}
              max={5}
              step={0.1}
              value={config.minRating}
              onChange={(v) => set('minRating', v ?? 0)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={6}>
            <Text type="secondary">거절 쿨다운(일)</Text>
            <InputNumber
              min={0}
              value={config.rejectCooldownDays}
              onChange={(v) => set('rejectCooldownDays', v ?? 90)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={6} style={{ paddingTop: 20 }}>
            <Checkbox
              checked={config.excludeSponsored}
              onChange={(e) => set('excludeSponsored', e.target.checked)}
            >
              광고 노출 상품 제외
            </Checkbox>
          </Col>
        </Row>
      </Card>

      <Card title="수익성 가드" size="small" style={{ marginBottom: 16 }}>
        <Paragraph type="secondary" style={{ fontSize: 12 }}>
          점수와 무관하게 <b>탈락시키는 조건</b>입니다. 인기가 많아도 팔면 손해인 상품은 추천하지 않습니다.
        </Paragraph>
        <Row gutter={[16, 12]}>
          <Col span={6} style={{ paddingTop: 20 }}>
            <Checkbox
              checked={config.profitGuardEnabled}
              onChange={(e) => set('profitGuardEnabled', e.target.checked)}
            >
              수익성 가드 사용
            </Checkbox>
          </Col>
          <Col span={6}>
            <Text type="secondary">목표 마진율(%)</Text>
            <InputNumber
              min={0}
              max={90}
              value={config.targetMarginRate}
              onChange={(v) => set('targetMarginRate', v ?? 20)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={6}>
            <Text type="secondary">최소 마진(원)</Text>
            <InputNumber
              min={0}
              step={500}
              value={config.minMarginPrice}
              onChange={(v) => set('minMarginPrice', v ?? 3000)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={6}>
            <Text type="secondary">국내 최저가 대비 상한(배)</Text>
            <InputNumber
              min={1}
              max={3}
              step={0.05}
              value={config.maxPriceRatio}
              onChange={(v) => set('maxPriceRatio', v ?? 1.3)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={6}>
            <Text type="secondary">매입 쿠폰 할인율(%)</Text>
            <InputNumber
              min={0}
              max={90}
              value={config.couponRate}
              onChange={(v) => set('couponRate', v ?? 0)}
              style={{ width: '100%' }}
            />
          </Col>
        </Row>
      </Card>

      <Card title="점수 가중치" size="small" style={{ marginBottom: 16 }}>
        <Paragraph type="secondary" style={{ fontSize: 12 }}>
          신호가 없는 항목(예: 네이버 API 미설정)은 0점이 아니라 <b>가중치에서 제외</b>되어 채점됩니다.
          현재 합계 {weightTotal}.
        </Paragraph>
        {['iHerb 신호', '국내 수요', '자사 이력'].map((group) => (
          <div key={group} style={{ marginBottom: 8 }}>
            <Text strong style={{ fontSize: 12 }}>
              {group}
            </Text>
            {WEIGHT_FIELDS.filter((f) => f.group === group).map((f) => (
              <Row key={f.key} align="middle" gutter={8}>
                <Col span={6}>
                  <Text style={{ fontSize: 12 }}>{f.label}</Text>
                </Col>
                <Col span={14}>
                  <Slider
                    min={0}
                    max={40}
                    value={weights[f.key] ?? 0}
                    onChange={(v) => setWeights((prev) => ({ ...prev, [f.key]: v }))}
                  />
                </Col>
                <Col span={2}>
                  <Text type="secondary">{weights[f.key] ?? 0}</Text>
                </Col>
              </Row>
            ))}
          </div>
        ))}
      </Card>

      <Card title="스케줄 · 통관 성분 DB" size="small" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 12]} align="middle">
          <Col span={6}>
            <Checkbox
              checked={config.scheduleEnabled}
              onChange={(e) => set('scheduleEnabled', e.target.checked)}
            >
              정기 자동 발굴 사용
            </Checkbox>
          </Col>
          <Col span={8}>
            <Text type="secondary">크론(표시용 — 실제 시각은 서버 설정)</Text>
            <Input
              value={config.scheduleCron}
              onChange={(e) => set('scheduleCron', e.target.value)}
            />
          </Col>
          <Col span={10}>
            <Button
              icon={<RefreshCw size={14} style={{ verticalAlign: -2 }} />}
              loading={syncing}
              onClick={() => void handleSyncBanned()}
            >
              반입차단 성분 목록 지금 동기화
            </Button>
          </Col>
        </Row>
        <Divider style={{ margin: '12px 0' }} />
        <Alert
          type="info"
          showIcon
          message="통관 게이트는 식약처 「해외직구식품 국내 반입차단 대상 원료·성분」 목록을 기준으로 1차 스크리닝만 합니다."
          description="확인필요(REVIEW) 판정은 반드시 사람이 성분을 보고 승인해야 등록됩니다. 최종 통관 책임은 판매자에게 있습니다."
        />
      </Card>

      <Space>
        <Button
          type="primary"
          icon={<Save size={14} style={{ verticalAlign: -2 }} />}
          loading={saving}
          onClick={() => void handleSave()}
        >
          설정 저장
        </Button>
      </Space>
    </div>
  );
};

export default SourcingSettingsPage;
