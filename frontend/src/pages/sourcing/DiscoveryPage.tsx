import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert, Button, Card, Checkbox, Drawer, Empty, Space, Spin, Table, Tag, Tooltip, Typography, message,
} from 'antd';
import {
  AlertTriangle, CheckCircle2, RefreshCw, ShieldAlert, Sparkles,
} from 'lucide-react';
import {
  parseJsonField,
  sourcingDiscoveryApi,
  type Candidate,
  type DiscoveryStatus,
  type ScoreBreakdown,
} from '../../api/sourcingDiscoveryApi';
import ScoreBreakdownPanel from './ScoreBreakdownPanel';

const { Title, Text, Paragraph } = Typography;

/** 발굴이 도는 동안 진행 상태를 물어보는 주기. 크롤이 수 분 걸려 짧게 볼 이유가 없다. */
const POLL_INTERVAL_MS = 10_000;

const CATEGORY_LABELS: Record<string, string> = {
  supplements: '보충제',
  grocery: '식품',
  'sports-nutrition': '스포츠영양',
  'herbs-homeopathy': '허브',
};

const won = (v: number | null | undefined) =>
  v == null ? '-' : `₩${Math.round(v).toLocaleString()}`;

const CustomsBadge = ({ verdict, reason }: { verdict: string | null; reason: string | null }) => {
  if (verdict === 'PASS') {
    return (
      <Tag color="green" icon={<CheckCircle2 size={11} style={{ verticalAlign: -1 }} />}>
        통관 OK
      </Tag>
    );
  }
  if (verdict === 'REVIEW') {
    return (
      <Tooltip title={reason ?? '성분 확인이 필요합니다'}>
        <Tag color="gold" icon={<AlertTriangle size={11} style={{ verticalAlign: -1 }} />}>
          통관 확인필요
        </Tag>
      </Tooltip>
    );
  }
  if (verdict === 'BLOCKED') {
    return (
      <Tooltip title={reason ?? ''}>
        <Tag color="red" icon={<ShieldAlert size={11} style={{ verticalAlign: -1 }} />}>
          통관 불가
        </Tag>
      </Tooltip>
    );
  }
  return <Tag>통관 미판정</Tag>;
};

/**
 * 추천 상품 목록.
 *
 * 통관 REVIEW 후보를 목록에서 빼지 않고 경고 배지로 노출하는 게 핵심이다 —
 * 판정이 애매한 상품을 조용히 감추면 사용자가 기회를 잃고, 왜 사라졌는지도 모른다.
 * 대신 초안 생성 후 검수 화면에서 명시적 승인을 요구한다.
 */
const DiscoveryPage = () => {
  const navigate = useNavigate();
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [blocked, setBlocked] = useState<Candidate[]>([]);
  const [selected, setSelected] = useState<number[]>([]);
  const [status, setStatus] = useState<DiscoveryStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [showBlocked, setShowBlocked] = useState(false);
  const [includeReview, setIncludeReview] = useState(true);
  const pollRef = useRef<number | null>(null);

  const loadCandidates = useCallback(async () => {
    try {
      const res = await sourcingDiscoveryApi.candidates(undefined, includeReview);
      setCandidates(res.data);
    } catch {
      message.error('추천 목록을 불러오지 못했습니다');
    } finally {
      setLoading(false);
    }
  }, [includeReview]);

  const loadStatus = useCallback(async () => {
    try {
      const res = await sourcingDiscoveryApi.discoveryStatus();
      setStatus(res.data);
      return res.data.running;
    } catch {
      return false;
    }
  }, []);

  useEffect(() => {
    void loadCandidates();
    void loadStatus();
  }, [loadCandidates, loadStatus]);

  // 발굴이 도는 동안만 폴링한다. 끝나면 목록을 다시 읽고 폴링을 멈춘다.
  useEffect(() => {
    if (!status?.running) {
      if (pollRef.current) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
      return;
    }
    if (pollRef.current) return;
    pollRef.current = window.setInterval(async () => {
      const stillRunning = await loadStatus();
      if (!stillRunning) {
        await loadCandidates();
        message.success('발굴이 완료되어 추천 목록을 갱신했습니다');
      }
    }, POLL_INTERVAL_MS);
    return () => {
      if (pollRef.current) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [status?.running, loadStatus, loadCandidates]);

  const handleRun = async () => {
    try {
      await sourcingDiscoveryApi.runDiscovery();
      message.info('발굴을 시작했습니다. 수 분 걸립니다.');
      await loadStatus();
    } catch (e) {
      const err = e as { response?: { status?: number; data?: { message?: string } } };
      message.warning(err.response?.data?.message ?? '발굴을 시작하지 못했습니다');
    }
  };

  const handleReject = async (id: number) => {
    await sourcingDiscoveryApi.reject(id);
    setCandidates((prev) => prev.filter((c) => c.id !== id));
    setSelected((prev) => prev.filter((s) => s !== id));
    message.success('거절했습니다. 쿨다운 기간 동안 재추천되지 않습니다.');
  };

  const handleCreateDrafts = async () => {
    if (selected.length === 0) {
      message.warning('상품을 선택하세요');
      return;
    }
    setCreating(true);
    try {
      const res = await sourcingDiscoveryApi.createDrafts(selected);
      const { drafts, failures } = res.data;
      if (failures.length > 0) {
        message.warning(`${drafts.length}건 초안 생성, ${failures.length}건 실패`);
      } else {
        message.success(`${drafts.length}건 초안을 만들었습니다`);
      }
      if (drafts.length > 0) navigate(`/sourcing/drafts/${drafts[0].id}`);
    } catch {
      message.error('초안 생성에 실패했습니다');
    } finally {
      setCreating(false);
    }
  };

  const openBlocked = async () => {
    setShowBlocked(true);
    try {
      const res = await sourcingDiscoveryApi.customsBlocked();
      setBlocked(res.data);
    } catch {
      message.error('통관 차단 목록을 불러오지 못했습니다');
    }
  };

  const lastRun = status?.lastRun && 'crawled' in status.lastRun ? status.lastRun : null;

  const columns = [
    {
      title: '점수',
      dataIndex: 'totalScore',
      width: 72,
      render: (v: number | null) => (
        <Text strong style={{ color: '#00B0A2', fontSize: 15 }}>
          {v == null ? '-' : Math.round(v)}
        </Text>
      ),
    },
    {
      title: '상품',
      render: (_: unknown, c: Candidate) => (
        <Space align="start">
          {c.imageUrl && (
            <img
              src={c.imageUrl}
              alt=""
              width={44}
              height={44}
              style={{ borderRadius: 4, objectFit: 'cover' }}
            />
          )}
          <div style={{ maxWidth: 460 }}>
            <a href={c.sourceUrl} target="_blank" rel="noreferrer">
              {c.nameKo ?? '(이름 없음)'}
            </a>
            <div style={{ marginTop: 2 }}>
              <CustomsBadge verdict={c.customsVerdict} reason={c.customsReason} />
              {c.categorySlug && (
                <Tag>{CATEGORY_LABELS[c.categorySlug] ?? c.categorySlug}</Tag>
              )}
              <Text type="secondary" style={{ fontSize: 12 }}>
                {c.brand}
              </Text>
            </div>
          </div>
        </Space>
      ),
    },
    {
      title: '매입가',
      dataIndex: 'discountPrice',
      width: 100,
      render: (v: number | null) => won(v),
    },
    {
      title: 'iHerb 반응',
      width: 168,
      render: (_: unknown, c: Candidate) => (
        <div style={{ fontSize: 12, lineHeight: 1.6 }}>
          <div>
            ★ {c.rating ?? '-'} ({(c.reviewCount ?? 0).toLocaleString()})
          </div>
          <Text type="secondary">
            {c.sales30d ? `30일 ${c.sales30d.toLocaleString()}개 판매` : '판매량 미노출'}
          </Text>
        </div>
      ),
    },
    {
      title: '국내 시장',
      width: 190,
      render: (_: unknown, c: Candidate) => (
        <div style={{ fontSize: 12, lineHeight: 1.6 }}>
          <div>
            검색 {c.monthlySearchVolume?.toLocaleString() ?? '-'}/월 · 경쟁{' '}
            {c.competitorCount?.toLocaleString() ?? '-'}
          </div>
          <Text type="secondary">
            최저 {won(c.domesticLowPrice)} → 예상 {won(c.estimatedSalePrice)}
          </Text>
        </div>
      ),
    },
    {
      title: '',
      width: 64,
      render: (_: unknown, c: Candidate) => (
        <Button size="small" danger type="text" onClick={() => void handleReject(c.id)}>
          거절
        </Button>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Space
        style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }}
        align="start"
      >
        <div>
          <Title level={3} style={{ marginBottom: 4 }}>
            <Sparkles size={20} style={{ verticalAlign: -3, marginRight: 6 }} />
            추천 상품
          </Title>
          <Text type="secondary">
            iHerb 베스트셀러에서 통관 가능하고 아직 팔지 않는 상품을 골라 점수를 매겼습니다.
          </Text>
        </div>
        <Space>
          <Button onClick={() => void openBlocked()}>통관 차단 목록</Button>
          <Button onClick={() => navigate('/sourcing/settings')}>설정</Button>
          <Button
            type="primary"
            icon={<RefreshCw size={14} style={{ verticalAlign: -2 }} />}
            loading={status?.running}
            onClick={() => void handleRun()}
          >
            {status?.running ? '발굴 중…' : '지금 재수집'}
          </Button>
        </Space>
      </Space>

      {status?.running && (
        <Alert
          type="info"
          showIcon
          icon={<Spin size="small" />}
          style={{ marginBottom: 12 }}
          message="후보를 발굴하고 있습니다"
          description="iHerb 카테고리별 베스트셀러 크롤 → 통관 성분 대조 → 수요 조회 → 채점 순으로 진행됩니다. 수 분 걸립니다."
        />
      )}

      {lastRun && lastRun.warnings.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={`마지막 발굴에서 ${lastRun.warnings.length}건의 문제가 있었습니다`}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {lastRun.warnings.slice(0, 8).map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          }
        />
      )}

      {lastRun && (
        <Card size="small" style={{ marginBottom: 12 }}>
          <Space size="large" wrap>
            <Text type="secondary">수집 {lastRun.crawled}</Text>
            <Text type="secondary">신규 {lastRun.created}</Text>
            <Text type="secondary">추천대상 {lastRun.scored}</Text>
            <Text type="secondary">통관차단 {lastRun.customsBlocked}</Text>
            <Text type="secondary">확인필요 {lastRun.customsReview}</Text>
            <Text type="secondary">제외 {lastRun.excluded}</Text>
            <Text type="secondary">기준 {lastRun.finishedAt?.slice(0, 16).replace('T', ' ')}</Text>
          </Space>
        </Card>
      )}

      <Space style={{ marginBottom: 8 }}>
        <Checkbox checked={includeReview} onChange={(e) => setIncludeReview(e.target.checked)}>
          통관 확인필요 상품 포함
        </Checkbox>
      </Space>

      <Table<Candidate>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={candidates}
        size="small"
        pagination={false}
        rowSelection={{
          selectedRowKeys: selected,
          onChange: (keys) => setSelected(keys as number[]),
        }}
        expandable={{
          expandedRowRender: (c) => {
            const bd = parseJsonField<ScoreBreakdown | null>(c.scoreBreakdown, null);
            return (
              <div>
                {bd ? <ScoreBreakdownPanel breakdown={bd} /> : <Text type="secondary">점수 근거 없음</Text>}
                {c.customsVerdict === 'REVIEW' && (
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginTop: 8 }}
                    message="통관 확인 필요"
                    description={
                      <>
                        <Paragraph style={{ marginBottom: 4 }}>{c.customsReason}</Paragraph>
                        {c.ingredientsRaw && (
                          <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                            성분: {c.ingredientsRaw.slice(0, 400)}
                          </Paragraph>
                        )}
                      </>
                    }
                  />
                )}
              </div>
            );
          },
        }}
        locale={{
          emptyText: (
            <Empty
              description={
                status?.running
                  ? '발굴이 진행 중입니다'
                  : '추천할 상품이 없습니다. 재수집을 실행해 보세요.'
              }
            />
          ),
        }}
      />

      <div
        style={{
          position: 'sticky',
          bottom: 0,
          padding: '12px 0',
          background: 'var(--ant-color-bg-container, #fff)',
          borderTop: '1px solid #f0f0f0',
          marginTop: 12,
        }}
      >
        <Button
          type="primary"
          size="large"
          loading={creating}
          disabled={selected.length === 0}
          onClick={() => void handleCreateDrafts()}
        >
          선택 {selected.length}건 초안 생성 →
        </Button>
      </div>

      <Drawer
        title="통관 차단 상품"
        width={640}
        open={showBlocked}
        onClose={() => setShowBlocked(false)}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="식약처 「해외직구식품 국내 반입차단 대상 원료·성분」에 해당해 추천에서 제외된 상품입니다."
        />
        {blocked.length === 0 ? (
          <Empty description="차단된 상품이 없습니다" />
        ) : (
          blocked.map((c) => (
            <Card key={c.id} size="small" style={{ marginBottom: 8 }}>
              <a href={c.sourceUrl} target="_blank" rel="noreferrer">
                {c.nameKo}
              </a>
              <Paragraph type="danger" style={{ fontSize: 12, marginTop: 4, marginBottom: 0 }}>
                {c.customsReason}
              </Paragraph>
            </Card>
          ))
        )}
      </Drawer>
    </div>
  );
};

export default DiscoveryPage;
