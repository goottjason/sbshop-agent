import { useState, useEffect, useCallback } from 'react';
import { Input, Button, Table, message, Card, Typography, Tag, Space } from 'antd';
import { batchApi } from '../api/batchApi';
import { actionLogApi, type ActionLogItem } from '../api/actionLogApi';

const { Title } = Typography;

const actionStatusColor: Record<string, string> = {
  STARTED: 'blue',
  SUCCESS: 'green',
  FAILED: 'red',
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
    { title: '시작시간', dataIndex: 'startedAt', width: 180 },
  ];

  const actionLogColumns = [
    { title: '시간', dataIndex: 'createdAt', width: 180,
      render: (v: string) => (v ? new Date(v).toLocaleString('ko-KR') : '-') },
    { title: '액션', dataIndex: 'actionType', width: 180 },
    { title: '마켓', dataIndex: 'marketType', width: 120,
      render: (v: string | null) => v || '-' },
    { title: '상태', dataIndex: 'actionStatus', width: 100,
      render: (v: string) => <Tag color={actionStatusColor[v] || 'default'}>{v}</Tag> },
    { title: '메시지', dataIndex: 'message', ellipsis: true },
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
    </div>
  );
};

export default ProcessStatusPage;
