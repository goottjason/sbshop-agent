import { useState, useEffect, useCallback } from 'react';
import { Input, Button, Table, message, Card, Typography, Tag, Space, Modal } from 'antd';
import { batchApi } from '../api/batchApi';
import { actionLogApi, type ActionLogItem } from '../api/actionLogApi';
import { formatKst } from '../utils/datetime';

const { Title } = Typography;

const actionStatusColor: Record<string, string> = {
  STARTED: 'blue',
  SUCCESS: 'green',
  FAILED: 'red',
};

// D-050: 마켓 코드 → 한글 라벨 (OrderGrid.tsx:416 marketLabels 선례 이식)
const marketTypeLabels: Record<string, string> = {
  COUPANG: '쿠팡',
  SMART_STORE: '스마트스토어',
  ELEVEN_STREET: '11번가',
  GMARKET: 'G마켓/옥션',
  AUCTION: '옥션',
  CAFE24: '카페24',
  EMAIL: '이메일',
  COUPANG_SETTLEMENT: '쿠팡 정산',
};

// D-050: 액션 코드 → 한글 라벨. actionType은 자유문자열(enum 아님)이며 관례상 `{MARKET}_SYNC` 패턴.
// 명시 라벨 우선, `_SYNC` 접미 패턴은 마켓 라벨+동작으로 조합, 그 외는 원문 폴백(미매칭 시 깨지지 않게).
const renderMarketType = (v: string | null): string => {
  if (!v) return '-';
  return marketTypeLabels[v] || v;
};

const renderActionType = (v: string): string => {
  if (!v) return '-';
  if (v.endsWith('_SYNC')) {
    const code = v.slice(0, -'_SYNC'.length);
    const label = marketTypeLabels[code];
    if (label) return `${label} 동기화`;
  }
  return v;
};

interface ProcessStatusItem {
  id: number;
  batchId: string;
  productCode: string;
  jobType: string;
  step: string;
  processStatus: string;
  message: string;
  startedAt: string;
}

const ProcessStatusPage = () => {
  const [batchId, setBatchId] = useState('');
  const [data, setData] = useState<ProcessStatusItem[]>([]);
  const [loading, setLoading] = useState(false);

  // 활동 로그 (D-042)
  const [actionLogs, setActionLogs] = useState<ActionLogItem[]>([]);
  const [logLoading, setLogLoading] = useState(false);

  // D-051: 메시지 전체보기 모달
  const [messageModal, setMessageModal] = useState<{ open: boolean; content: string }>({
    open: false,
    content: '',
  });

  const loadActionLogs = useCallback(async () => {
    setLogLoading(true);
    try {
      const res = await actionLogApi.getActionLogs(100);
      setActionLogs(res.data || []);
    } catch {
      message.error('활동 로그 조회 실패');
    } finally {
      setLogLoading(false);
    }
  }, []);

  useEffect(() => {
    loadActionLogs();
  }, [loadActionLogs]);

  const handleSearch = async () => {
    if (!batchId) {
      message.warning('batchId를 입력하세요');
      return;
    }
    setLoading(true);
    try {
      const res = await batchApi.getBatchStatus(batchId);
      setData(res.data || []);
    } catch {
      message.error('조회 실패');
    } finally {
      setLoading(false);
    }
  };

  const columns = [
    { title: '상품코드', dataIndex: 'productCode', width: 120 },
    { title: '작업유형', dataIndex: 'jobType', width: 150 },
    { title: '단계', dataIndex: 'step', width: 150 },
    { title: '상태', dataIndex: 'processStatus', width: 80,
      render: (v: string) => {
        const color = v === 'SUCCESS' ? 'green' : v === 'FAILED' ? 'red' : 'orange';
        return `<span style="color:${color};font-weight:bold;">${v}</span>`;
      }
    },
    { title: '메시지', dataIndex: 'message', ellipsis: true },
    { title: '시작시간', dataIndex: 'startedAt', width: 180,
      render: (v: string) => formatKst(v) },
  ];

  const actionLogColumns = [
    { title: '시간', dataIndex: 'createdAt', width: 180,
      render: (v: string) => formatKst(v) },
    { title: '액션', dataIndex: 'actionType', width: 180,
      render: (v: string) => renderActionType(v) },
    { title: '마켓', dataIndex: 'marketType', width: 120,
      render: (v: string | null) => renderMarketType(v) },
    { title: '상태', dataIndex: 'actionStatus', width: 100,
      render: (v: string) => <Tag color={actionStatusColor[v] || 'default'}>{v}</Tag> },
    { title: '메시지', dataIndex: 'message', ellipsis: true,
      // D-051: 셀 클릭 시 전체 메시지를 모달로 표시(줄바꿈 보존). 목록 ellipsis는 유지.
      render: (v: string) =>
        v ? (
          <span
            style={{ cursor: 'pointer' }}
            title="클릭하여 전체 메시지 보기"
            onClick={() => setMessageModal({ open: true, content: v })}
          >
            {v}
          </span>
        ) : (
          '-'
        ) },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>배치 진행 현황</Title>
      <Card>
        <Input.Search
          placeholder="batchId 입력"
          value={batchId}
          onChange={(e) => setBatchId(e.target.value)}
          enterButton={<Button type="primary" loading={loading} onClick={handleSearch}>조회</Button>}
          onSearch={handleSearch}
          style={{ maxWidth: 400, marginBottom: 16 }}
        />
        <Table<ProcessStatusItem>
          rowKey="id"
          columns={columns}
          dataSource={data}
          loading={loading}
          pagination={{ pageSize: 50 }}
          size="small"
          scroll={{ y: 500 }}
        />
      </Card>

      <Card style={{ marginTop: 24 }}>
        <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
          <Title level={4} style={{ margin: 0 }}>활동 로그</Title>
          <Button onClick={loadActionLogs} loading={logLoading}>새로고침</Button>
        </Space>
        <Table<ActionLogItem>
          rowKey="id"
          columns={actionLogColumns}
          dataSource={actionLogs}
          loading={logLoading}
          pagination={{ pageSize: 50 }}
          size="small"
          scroll={{ y: 500 }}
        />
      </Card>

      {/* D-051: 메시지 전체보기 모달 */}
      <Modal
        title="메시지 전체보기"
        open={messageModal.open}
        onCancel={() => setMessageModal({ open: false, content: '' })}
        footer={null}
        width={640}
      >
        <pre
          style={{
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            margin: 0,
            fontFamily: 'inherit',
            maxHeight: '60vh',
            overflow: 'auto',
          }}
        >
          {messageModal.content}
        </pre>
      </Modal>
    </div>
  );
};

export default ProcessStatusPage;
