import { useState } from 'react';
import { Input, Button, Table, message, Card, Typography } from 'antd';
import { batchApi } from '../api/batchApi';

const { Title } = Typography;

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
    </div>
  );
};

export default ProcessStatusPage;
