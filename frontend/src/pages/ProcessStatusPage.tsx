import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Input, Button, Table, Card, Typography, Tag, Space, Modal } from 'antd';
import { batchApi } from '../api/batchApi';
import { actionLogApi, type ActionLogItem } from '../api/actionLogApi';
import { formatKst } from '../utils/datetime';
import { SYNC_SOURCE_LABELS, marketLabel } from '../utils/marketLabels';
import { notify } from '../utils/notify';

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

const { Title } = Typography;

const actionStatusColor: Record<string, string> = {
  STARTED: 'blue',
  SUCCESS: 'green',
  FAILED: 'red',
  WARNING: 'orange',
};

const actionTypeLabels: Record<string, string> = {
  COUPANG_SETTLEMENT_SYNC: '쿠팡 정산 동기화',
  CUSTOMS_SYNC: '통관상태 동기화',
  STOCK_SYNC: '재고 동기화',
  ORDER_CONFIRM: '발주확인',
  ORDER_CONFIRM_BATCH: '발주 일괄확인',
  ORDER_CANCEL: '발주취소',
  ORDER_CANCEL_BATCH: '발주 일괄취소',
  ORDER_UPDATE: '주문 정보수정',
  UNIPASS_UPDATE: '유니패스 완료처리',
  PURCHASE_UPDATE: '구매정보 수정',
  SHIPPING_UPDATE: '배송정보 수정',
  PURCHASE_AMOUNT_PARSE: '실구매가 자동인식',
  ORDER_SHIP: '발송 처리',
  ORDER_DELETE: '주문 삭제',
  PRODUCT_PRICE_STOCK_UPDATE: '가격/재고 수정',
  PRODUCT_IMAGE_UPDATE: '이미지 수정',
  SOURCE_IMAGE_CRAWL: '소스이미지 크롤',
  PRODUCT_UPDATE: '상품 정보수정',
  PRODUCT_DELETE: '상품 삭제',
  PRODUCT_SOURCING: '소싱 크롤',
  PRODUCT_BULK_CREATE: '상품 일괄등록',
  PRODUCT_PUBLISH: '마켓 게시',
  BATCH_CRAWL_UPDATE: '배치 크롤 업데이트',
  BATCH_MANUAL_UPDATE: '배치 수동 업데이트',
  BATCH_MANUAL_UPDATE_ALL: '배치 전체필드 업데이트',
  BATCH_BY_SUPPLIER: '소싱업체별 배치',
  CREDENTIAL_SAVE: 'API 키 저장',
  CAFE24_AUTH: 'Cafe24 재인증',
};

const renderMarketType = (v: string | null): string => {
  if (!v) return '-';
  return marketLabel(v);
};

const renderActionType = (v: string): string => {
  if (!v) return '-';
  const explicit = actionTypeLabels[v];
  if (explicit) return explicit;
  if (v.endsWith('_SYNC')) {
    const code = v.slice(0, -'_SYNC'.length);
    const label = SYNC_SOURCE_LABELS[code];
    if (label) return `${label} 동기화`;
  }
  return v;
};

const ProcessStatusPage = () => {
  const [batchId, setBatchId] = useState('');
  const [data, setData] = useState<ProcessStatusItem[]>([]);
  const [loading, setLoading] = useState(false);

  const {
    data: actionLogs = [],
    isFetching: logLoading,
    error: logError,
    refetch: refetchActionLogs,
  } = useQuery<ActionLogItem[]>({
    queryKey: ['action-logs'],
    queryFn: async () => {
      const res = await actionLogApi.getActionLogs(100);
      return res.data || [];
    },
    retry: false,
  });

  const [messageModal, setMessageModal] = useState<{ open: boolean; content: string }>({
    open: false,
    content: '',
  });

  useEffect(() => {
    if (logError) notify.error('활동 로그 조회 실패');
  }, [logError]);

  useEffect(() => {
    const eventSource = new EventSource('/sbshop-agent/api/v1/notifications/subscribe');
    const onBatch = () => { void refetchActionLogs(); };
    eventSource.addEventListener('BATCH_COMPLETED', onBatch);
    eventSource.addEventListener('BATCH_FAILED', onBatch);
    eventSource.onerror = () => {
      if (eventSource.readyState === EventSource.CLOSED) {
        eventSource.close();
      }
    };
    return () => eventSource.close();
  }, [refetchActionLogs]);

  const handleSearch = async () => {
    if (!batchId) {
      notify.warning('batchId를 입력하세요');
      return;
    }
    setLoading(true);
    try {
      const res = await batchApi.getBatchStatus(batchId);
      setData(res.data || []);
    } catch {
      notify.error('조회 실패');
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
          <Button onClick={() => void refetchActionLogs()} loading={logLoading}>새로고침</Button>
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
