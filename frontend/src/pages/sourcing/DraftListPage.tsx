import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Empty, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { ClipboardList, RefreshCw, Sparkles } from 'lucide-react';
import { sourcingDiscoveryApi, type Draft } from '../../api/sourcingDiscoveryApi';

const { Title, Text } = Typography;

const STATUS_LABELS: Record<string, string> = {
  ENRICHING: '보완 중',
  READY: '검수 대기',
  PUBLISHING: '등록 중',
  PUBLISHED: '등록 완료',
  FAILED: '등록 실패',
};

const STATUS_COLORS: Record<string, string> = {
  ENRICHING: 'default',
  READY: 'blue',
  PUBLISHING: 'gold',
  PUBLISHED: 'green',
  FAILED: 'red',
};

const STATUS_OPTIONS = Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }));

const won = (v: number | null | undefined) =>
  v == null ? '-' : `₩${Math.round(v).toLocaleString()}`;

const DraftListPage = () => {
  const navigate = useNavigate();
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [statusFilter, setStatusFilter] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await sourcingDiscoveryApi.drafts(
        statusFilter.length > 0 ? statusFilter : undefined,
      );
      setDrafts([...res.data].sort((a, b) => b.id - a.id));
    } catch {
      message.error('초안 목록을 불러오지 못했습니다');
    } finally {
      setLoading(false);
    }
  }, [statusFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  const columns = [
    {
      title: '번호',
      dataIndex: 'id',
      width: 76,
      render: (v: number) => <Text type="secondary">#{v}</Text>,
    },
    {
      title: '상품',
      render: (_: unknown, d: Draft) => (
        <div style={{ maxWidth: 520 }}>
          <div>{d.baseNameKo ?? d.originalName ?? '(이름 없음)'}</div>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {[d.brand, d.originalName].filter(Boolean).join(' · ')}
          </Text>
        </div>
      ),
    },
    {
      title: '상태',
      dataIndex: 'draftStatus',
      width: 120,
      render: (v: string | null, d: Draft) => {
        const tag = (
          <Tag color={STATUS_COLORS[v ?? ''] ?? 'default'}>
            {STATUS_LABELS[v ?? ''] ?? v ?? '-'}
          </Tag>
        );
        return d.enrichNote ? <Tooltip title={d.enrichNote}>{tag}</Tooltip> : tag;
      },
    },
    {
      title: '마켓 준비',
      width: 110,
      render: (_: unknown, d: Draft) => {
        const enabled = d.marketDrafts.filter((m) => m.enabled);
        const ready = enabled.filter((m) => m.valid);
        if (enabled.length === 0) return <Text type="secondary">-</Text>;
        return (
          <Text type={ready.length === enabled.length ? 'success' : 'warning'}>
            {ready.length}/{enabled.length}
          </Text>
        );
      },
    },
    {
      title: '매입가',
      width: 130,
      render: (_: unknown, d: Draft) => (
        <div style={{ fontSize: 12, lineHeight: 1.6 }}>
          <div>{won(d.costPrice)}</div>
          <Text type="secondary">{d.bundleQty ? `묶음 ${d.bundleQty}개` : '묶음 미설정'}</Text>
        </div>
      ),
    },
    {
      title: '마진율',
      dataIndex: 'marginRate',
      width: 84,
      render: (v: number | null) => (v == null ? '-' : `${v}%`),
    },
    {
      title: '등록 결과',
      width: 120,
      render: (_: unknown, d: Draft) =>
        d.productId ? <Tag color="green">상품 #{d.productId}</Tag> : <Text type="secondary">-</Text>,
    },
    {
      title: '',
      width: 84,
      render: (_: unknown, d: Draft) => (
        <Button size="small" type="link" onClick={() => navigate(`/sourcing/drafts/${d.id}`)}>
          검수하기
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
            <ClipboardList size={20} style={{ verticalAlign: -3, marginRight: 6 }} />
            등록 초안
          </Title>
          <Text type="secondary">
            추천 상품에서 만든 초안 목록입니다. 행을 눌러 검수·등록을 이어서 진행하세요.
          </Text>
        </div>
        <Space>
          <Button
            icon={<Sparkles size={14} style={{ verticalAlign: -2 }} />}
            onClick={() => navigate('/sourcing')}
          >
            추천 상품
          </Button>
          <Button
            icon={<RefreshCw size={14} style={{ verticalAlign: -2 }} />}
            onClick={() => void load()}
          >
            새로고침
          </Button>
        </Space>
      </Space>

      <Space style={{ marginBottom: 8 }}>
        <Select
          mode="multiple"
          allowClear
          placeholder="상태 전체"
          options={STATUS_OPTIONS}
          value={statusFilter}
          onChange={setStatusFilter}
          style={{ minWidth: 280 }}
        />
      </Space>

      <Table<Draft>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={drafts}
        size="small"
        pagination={{ pageSize: 20, showSizeChanger: false, hideOnSinglePage: true }}
        onRow={(d) => ({
          onClick: () => navigate(`/sourcing/drafts/${d.id}`),
          style: { cursor: 'pointer' },
        })}
        locale={{
          emptyText: (
            <Empty
              description={
                statusFilter.length > 0
                  ? '해당 상태의 초안이 없습니다'
                  : '초안이 없습니다. 추천 상품에서 초안을 만들어 보세요.'
              }
            />
          ),
        }}
      />
    </div>
  );
};

export default DraftListPage;
