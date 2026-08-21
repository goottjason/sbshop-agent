import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Button, Card, Checkbox, Col, Descriptions, Divider, Input, InputNumber, Result, Row,
  Space, Spin, Tabs, Tag, Typography, message,
} from 'antd';
import { AlertTriangle, CheckCircle2, Save, Upload } from 'lucide-react';
import {
  parseJsonField,
  sourcingDiscoveryApi,
  type Draft,
  type DraftPatch,
  type MarketDraft,
  type PublishResult,
} from '../../api/sourcingDiscoveryApi';

const { Title, Text, Paragraph } = Typography;

const MARKET_LABELS: Record<string, string> = {
  COUPANG: '쿠팡',
  SMART_STORE: '스마트스토어',
  ELEVEN_STREET: '11번가',
  CAFE24: 'Cafe24',
};

const NAME_LIMITS: Record<string, number> = {
  COUPANG: 100,
  SMART_STORE: 100,
  ELEVEN_STREET: 100,
  CAFE24: 250,
};

const won = (v: number | null | undefined) =>
  v == null ? '-' : `₩${Math.round(v).toLocaleString()}`;

const DraftReviewPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const draftId = Number(id);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [result, setResult] = useState<PublishResult | null>(null);

  const [baseNameKo, setBaseNameKo] = useState('');
  const [bundleQty, setBundleQty] = useState(1);
  const [marginRate, setMarginRate] = useState<number | null>(null);
  const [origin, setOrigin] = useState('');
  const [customsAck, setCustomsAck] = useState(false);
  const [marketEdits, setMarketEdits] = useState<Record<string, Partial<MarketDraft>>>({});
  const applyDraft = useCallback((d: Draft) => {
    setDraft(d);
    setBaseNameKo(d.baseNameKo ?? '');
    setBundleQty(d.bundleQty ?? 1);
    setMarginRate(d.marginRate ?? null);
    setOrigin(d.origin ?? '');
    setCustomsAck(d.customsAck);
    setMarketEdits({});
  }, []);
  const load = useCallback(async () => {
    try {
      const res = await sourcingDiscoveryApi.draft(draftId);
      applyDraft(res.data);
    } catch {
      message.error('초안을 불러오지 못했습니다');
    } finally {
      setLoading(false);
    }
  }, [draftId, applyDraft]);
  useEffect(() => {
    if (!Number.isFinite(draftId)) return;
    void load();
  }, [draftId, load]);
  const patch = (): DraftPatch => ({
    baseNameKo,
    bundleQty,
    marginRate,
    origin,
    customsAck,
    marketDrafts: Object.entries(marketEdits).map(([marketType, edit]) => ({
      marketType,
      productName: edit.productName ?? null,
      categoryId: edit.categoryId ?? null,
      salePrice: edit.salePrice ?? null,
      keywords: edit.keywords ? parseJsonField<string[]>(edit.keywords, []) : null,
      enabled: edit.enabled ?? null,
    })),
  });
  const handleSave = async () => {
    setSaving(true);
    try {
      const res = await sourcingDiscoveryApi.updateDraft(draftId, patch());
      applyDraft(res.data);
      message.success('검수 내용을 저장했습니다');
    } catch {
      message.error('저장에 실패했습니다');
    } finally {
      setSaving(false);
    }
  };
  const handlePublish = async () => {
    setPublishing(true);
    try {
      const saved = await sourcingDiscoveryApi.updateDraft(draftId, patch());
      applyDraft(saved.data);
      const res = await sourcingDiscoveryApi.publishDraft(draftId);
      setResult(res.data);
      if (res.data.successCount === res.data.totalCount) {
        message.success(`${res.data.totalCount}개 마켓 등록 완료`);
      } else {
        message.warning(
          `${res.data.successCount}/${res.data.totalCount}개 마켓 등록 — 실패 마켓을 확인하세요`,
        );
      }
    } catch (e) {
      const err = e as { response?: { data?: { message?: string } | string } };
      const reason =
        typeof err.response?.data === 'object'
          ? err.response?.data?.message
          : (err.response?.data as string);
      message.error(reason ?? '등록에 실패했습니다');
    } finally {
      setPublishing(false);
    }
  };
  const marketDrafts = useMemo(() => draft?.marketDrafts ?? [], [draft]);
  const needsCustomsAck = useMemo(
    () => draft != null && !draft.customsAck,
    [draft],
  );
  const publishable = useMemo(() => {
    const enabledValid = marketDrafts.filter((m) => m.enabled && m.valid);
    return enabledValid.length > 0 && customsAck;
  }, [marketDrafts, customsAck]);
  if (loading) {
    return (
      <div style={{ padding: 48, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }
  if (!draft) {
    return <Result status="404" title="초안을 찾을 수 없습니다" />;
  }
  if (result) {
    return (
      <div style={{ padding: 24 }}>
        <Result
          status={result.successCount === result.totalCount ? 'success' : 'warning'}
          title={`마켓 등록 ${result.successCount}/${result.totalCount} 성공`}
          subTitle={`상품코드 ${result.sbCode} (productId ${result.productId})`}
          extra={[
            <Space key="list" direction="vertical" style={{ textAlign: 'left', width: 520 }}>
              {result.outcomes.map((o) => (
                <div key={o.marketType}>
                  <Tag color={o.ok ? 'green' : 'red'}>{o.ok ? '성공' : '실패'}</Tag>
                  {MARKET_LABELS[o.marketType] ?? o.marketType}
                  {o.error ? ` — ${o.error}` : ''}
                </div>
              ))}
            </Space>,
            <Button key="back" type="primary" onClick={() => navigate('/sourcing')}>
              추천 목록으로
            </Button>,
          ]}
        />
      </div>
    );
  }
  const hostedImages = parseJsonField<string[]>(draft.hostedImages, []);
  return (
    <div style={{ padding: 24, paddingBottom: 96 }}>
      <Title level={3} style={{ marginBottom: 4 }}>
        등록 검수
      </Title>
      <Text type="secondary">{draft.originalName}</Text>
      {draft.enrichNote && (
        <Alert
          type="info"
          showIcon
          style={{ marginTop: 12 }}
          message="자동 생성 결과"
          description={draft.enrichNote}
        />
      )}
      {needsCustomsAck && (
        <Alert
          type="warning"
          showIcon
          icon={<AlertTriangle size={16} />}
          style={{ marginTop: 12 }}
          message="통관 확인이 필요한 상품입니다"
          description={
            <>
              <Paragraph style={{ marginBottom: 8 }}>
                성분표를 확인하고 구매대행이 가능한지 직접 판단해 주세요. 승인하지 않으면 등록할 수
                없습니다.
              </Paragraph>
              {draft.ingredientsKo && (
                <Paragraph type="secondary" style={{ fontSize: 12 }}>
                  {draft.ingredientsKo.slice(0, 600)}
                </Paragraph>
              )}
              <Checkbox checked={customsAck} onChange={(e) => setCustomsAck(e.target.checked)}>
                성분을 확인했으며 구매대행이 가능한 상품임을 확인합니다
              </Checkbox>
            </>
          }
        />
      )}
      <Card title="공통 정보" size="small" style={{ marginTop: 16 }}>
        <Row gutter={[16, 12]}>
          <Col span={12}>
            <Text type="secondary">기본 상품명 (마켓별 상품명의 기반)</Text>
            <Input value={baseNameKo} onChange={(e) => setBaseNameKo(e.target.value)} />
          </Col>
          <Col span={4}>
            <Text type="secondary">묶음 수량</Text>
            <InputNumber
              min={1}
              max={20}
              value={bundleQty}
              onChange={(v) => setBundleQty(v ?? 1)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={4}>
            <Text type="secondary">마진율(%)</Text>
            <InputNumber
              min={0}
              max={90}
              value={marginRate ?? undefined}
              onChange={(v) => setMarginRate(v ?? null)}
              style={{ width: '100%' }}
            />
          </Col>
          <Col span={4}>
            <Text type="secondary">원산지</Text>
            <Input value={origin} onChange={(e) => setOrigin(e.target.value)} />
          </Col>
        </Row>
        <Divider style={{ margin: '12px 0' }} />
        <Descriptions size="small" column={4}>
          <Descriptions.Item label="브랜드">{draft.brand ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="단품 매입가">{won(draft.costPrice)}</Descriptions.Item>
          <Descriptions.Item label="총 매입가">
            {won((draft.costPrice ?? 0) * bundleQty)}
          </Descriptions.Item>
          <Descriptions.Item label="바코드">{draft.barcode ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="중량">
            {draft.weightG ? `${draft.weightG}g` : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="수량">{draft.capacity ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="HS코드">{draft.hsCode || '-'}</Descriptions.Item>
          <Descriptions.Item label="이미지">{hostedImages.length}장</Descriptions.Item>
        </Descriptions>
        {hostedImages.length > 0 && (
          <Space wrap style={{ marginTop: 8 }}>
            {hostedImages.slice(0, 6).map((url) => (
              <img
                key={url}
                src={url}
                alt=""
                width={56}
                height={56}
                style={{ borderRadius: 4, objectFit: 'cover' }}
              />
            ))}
          </Space>
        )}
      </Card>
      <Tabs
        style={{ marginTop: 16 }}
        items={marketDrafts.map((md) => {
          const missing = parseJsonField<string[]>(md.missingFields, []);
          const keywords = parseJsonField<string[]>(md.keywords, []);
          const edit = marketEdits[md.marketType] ?? {};
          const name = edit.productName ?? md.productName ?? '';
          const limit = NAME_LIMITS[md.marketType] ?? 100;
          return {
            key: md.marketType,
            label: (
              <Space size={4}>
                {MARKET_LABELS[md.marketType] ?? md.marketType}
                {md.valid ? (
                  <CheckCircle2 size={13} color="#52c41a" style={{ verticalAlign: -2 }} />
                ) : (
                  <AlertTriangle size={13} color="#faad14" style={{ verticalAlign: -2 }} />
                )}
              </Space>
            ),
            children: (
              <Card size="small">
                {missing.length > 0 && (
                  <Alert
                    type="error"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message={`필수필드 ${missing.length}개 미충족 — 이 마켓은 등록할 수 없습니다`}
                    description={
                      <Space wrap>
                        {missing.map((f) => (
                          <Tag key={f} color="red">
                            {f}
                          </Tag>
                        ))}
                      </Space>
                    }
                  />
                )}
                <Row gutter={[16, 12]}>
                  <Col span={16}>
                    <Text type="secondary">
                      상품명 ({name.length}/{limit}자)
                    </Text>
                    <Input
                      value={name}
                      status={name.length > limit ? 'error' : undefined}
                      onChange={(e) =>
                        setMarketEdits((prev) => ({
                          ...prev,
                          [md.marketType]: { ...prev[md.marketType], productName: e.target.value },
                        }))
                      }
                    />
                  </Col>
                  <Col span={4}>
                    <Text type="secondary">판매가</Text>
                    <InputNumber
                      value={edit.salePrice ?? md.salePrice ?? undefined}
                      formatter={(v) => `${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                      parser={(v) => Number((v ?? '').replace(/,/g, ''))}
                      onChange={(v) =>
                        setMarketEdits((prev) => ({
                          ...prev,
                          [md.marketType]: { ...prev[md.marketType], salePrice: v ?? undefined },
                        }))
                      }
                      style={{ width: '100%' }}
                    />
                  </Col>
                  <Col span={4}>
                    <Text type="secondary">수수료율</Text>
                    <div style={{ paddingTop: 5 }}>{md.channelFeeRate ?? '-'}%</div>
                  </Col>
                </Row>
                <Divider style={{ margin: '12px 0' }} />
                <Descriptions size="small" column={2}>
                  <Descriptions.Item label="카테고리">
                    {md.categoryPath ? `${md.categoryPath} (${md.categoryId})` : (md.categoryId ?? '미지정')}
                  </Descriptions.Item>
                  <Descriptions.Item label="등록 대상">
                    <Checkbox
                      checked={edit.enabled ?? md.enabled}
                      onChange={(e) =>
                        setMarketEdits((prev) => ({
                          ...prev,
                          [md.marketType]: { ...prev[md.marketType], enabled: e.target.checked },
                        }))
                      }
                    >
                      이 마켓에 등록
                    </Checkbox>
                  </Descriptions.Item>
                </Descriptions>
                <div style={{ marginTop: 8 }}>
                  <Text type="secondary">검색 키워드 {keywords.length}개</Text>
                  <div style={{ marginTop: 4 }}>
                    {keywords.map((k) => (
                      <Tag key={k}>{k}</Tag>
                    ))}
                  </div>
                </div>
                {md.publishError && (
                  <Alert
                    type="error"
                    showIcon
                    style={{ marginTop: 12 }}
                    message="직전 등록 실패"
                    description={md.publishError}
                  />
                )}
              </Card>
            ),
          };
        })}
      />
      <div
        style={{
          position: 'fixed',
          bottom: 0,
          left: 0,
          right: 0,
          padding: '12px 24px',
          background: '#fff',
          borderTop: '1px solid #f0f0f0',
          textAlign: 'right',
          zIndex: 10,
        }}
      >
        <Space>
          {!publishable && (
            <Text type="secondary">
              {!customsAck
                ? '통관 확인 승인이 필요합니다'
                : '등록 가능한 마켓이 없습니다 (필수필드를 채우세요)'}
            </Text>
          )}
          <Button
            icon={<Save size={14} style={{ verticalAlign: -2 }} />}
            loading={saving}
            onClick={() => void handleSave()}
          >
            저장
          </Button>
          <Button
            type="primary"
            icon={<Upload size={14} style={{ verticalAlign: -2 }} />}
            loading={publishing}
            disabled={!publishable}
            onClick={() => void handlePublish()}
          >
            {marketDrafts.filter((m) => m.enabled && m.valid).length}개 마켓 등록
          </Button>
        </Space>
      </div>
    </div>
  );
};
export default DraftReviewPage;
