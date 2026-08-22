import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Form, Input, InputNumber, Button, Radio, message, Card, Progress, Space, Typography, Select, ConfigProvider } from 'antd';
import { batchApi } from '../api/batchApi';
import { VENDOR_OPTIONS } from './product/productGridShared';

interface BatchSummary {
  batchId: string;
  total: number;
  success: number;
  failed: number;
  pending: number;
  done: number;
  percent: number;
}

const GREEN = '#166534';

const ACTIVE_BATCH_KEY = 'sbshop.activeBatchId';

const POLL_INTERVAL_MS = 30000;

const SSE_URL = '/sbshop-agent/api/v1/notifications/subscribe';

const BatchUpdatePage = () => {
  const [mode, setMode] = useState<'supplier' | 'manual'>('supplier');
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const [batchId, setBatchId] = useState<string | null>(() => localStorage.getItem(ACTIVE_BATCH_KEY));

  const mountedRef = useRef(true);
  const batchIdRef = useRef<string | null>(batchId);
  useEffect(() => {
    batchIdRef.current = batchId;
  }, [batchId]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const stopTracking = useCallback(() => {
    localStorage.removeItem(ACTIVE_BATCH_KEY);
    if (mountedRef.current) {
      setBatchId(null);
    }
  }, []);

  const { data: summary = null, refetch: refetchSummary } = useQuery<BatchSummary | null>({
    queryKey: ['batch-summary', batchId],
    queryFn: async () => {
      try {
        const res = await batchApi.getBatchSummary(batchId as string);
        return res.data as BatchSummary;
      } catch {
        stopTracking();
        return null;
      }
    },
    enabled: !!batchId,
    gcTime: 0,
    retry: false,
    refetchIntervalInBackground: true,
    refetchInterval: (query) => {
      const data = query.state.data;
      return data && data.total > 0 && data.done >= data.total ? false : POLL_INTERVAL_MS;
    },
  });

  const startTracking = useCallback((id: string) => {
    localStorage.setItem(ACTIVE_BATCH_KEY, id);
    setBatchId(id);
  }, []);

  useEffect(() => {
    const es = new EventSource(SSE_URL);
    const onStarted = (e: Event) => {
      const startedId = (e as MessageEvent).data as string;
      if (startedId && startedId !== batchIdRef.current) {
        startTracking(startedId);
      }
    };
    const onCompleted = (e: Event) => {
      const completedId = String((e as MessageEvent).data).split('|')[0];
      if (completedId && completedId === batchIdRef.current) {
        void refetchSummary();
      }
    };
    es.addEventListener('BATCH_STARTED', onStarted);
    es.addEventListener('BATCH_COMPLETED', onCompleted);
    es.addEventListener('BATCH_FAILED', onCompleted);
    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED) {
        es.close();
      }
    };
    return () => es.close();
  }, [startTracking, refetchSummary]);

  const handleSubmit = async (values: {
    supplierCode?: string;
    productIds?: string;
    marginRate: number;
    couponRate: number;
    minMarginPrice: number;
  }) => {
    setLoading(true);
    try {
      if (mode === 'supplier') {
        const res = await batchApi.updateBySupplier(
          values.supplierCode || 'IHB',
          values.marginRate,
          values.couponRate,
          values.minMarginPrice
        );
        const data = res.data as { batchId?: string; count?: string; message?: string };
        if (data.batchId) {
          message.success(`배치 시작: ${data.count}개 상품 (batchId: ${data.batchId})`);
          startTracking(data.batchId);
        } else {
          message.info(data.message || '해당 소싱업체의 상품이 없습니다.');
        }
      } else {
        const ids = (values.productIds || '').split(',').map((s) => parseInt(s.trim())).filter((n) => !isNaN(n));
        if (ids.length === 0) {
          message.warning('상품 ID를 입력하세요');
          return;
        }
        const res = await batchApi.crawlAndUpdate(ids, values.marginRate, values.couponRate, values.minMarginPrice);
        const startedId = (res.data as { batchId?: string }).batchId;
        message.success(`배치 시작 (batchId: ${startedId})`);
        if (startedId) {
          startTracking(startedId);
        }
      }
    } catch {
      message.error('배치 시작 실패');
    } finally {
      setLoading(false);
    }
  };

  const isComplete = !!summary && summary.total > 0 && summary.done >= summary.total;
  const progressStatus = !summary
    ? 'active'
    : isComplete
      ? (summary.failed === 0 ? 'success' : 'exception')
      : 'active';

  return (
    <ConfigProvider theme={{ token: { colorPrimary: GREEN } }}>
    <div className="product-theme" style={{ padding: 24, background: '#f8f9fa', minHeight: '100%' }}>
      <Card
        title={<span style={{ color: GREEN, fontWeight: 700 }}>배치 가격/재고 일괄 업데이트</span>}
        style={{ borderRadius: 10 }}
      >
        <Radio.Group value={mode} onChange={(e) => setMode(e.target.value)} style={{ marginBottom: 20 }}>
          <Radio.Button value="supplier">소싱업체별</Radio.Button>
          <Radio.Button value="manual">상품ID 지정</Radio.Button>
        </Radio.Group>

        <Form form={form} layout="vertical" onFinish={handleSubmit} initialValues={{ marginRate: 15, couponRate: 20, minMarginPrice: 5000 }}>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-end' }}>
            {mode === 'supplier' ? (
              <Form.Item name="supplierCode" label="소싱업체 코드" initialValue="IHB" style={{ marginBottom: 0 }}>
                <Select style={{ width: 160 }} options={VENDOR_OPTIONS.map((v) => ({ value: v, label: v }))} />
              </Form.Item>
            ) : (
              <Form.Item name="productIds" label="상품 ID (콤마 구분)" style={{ marginBottom: 0 }}>
                <Input placeholder="1, 2, 3" style={{ width: 240 }} />
              </Form.Item>
            )}
            <Form.Item name="marginRate" label="마진율 (%)" style={{ marginBottom: 0 }}>
              <InputNumber min={0} max={100} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="couponRate" label="쿠폰율 (%)" style={{ marginBottom: 0 }}>
              <InputNumber min={0} max={100} style={{ width: 120 }} />
            </Form.Item>
            <Form.Item name="minMarginPrice" label="최소 마진가 (원)" style={{ marginBottom: 0 }}>
              <InputNumber min={0} style={{ width: 150 }} />
            </Form.Item>
            <Form.Item style={{ marginBottom: 0 }}>
              <Button type="primary" htmlType="submit" loading={loading} style={{ fontWeight: 600 }}>배치 실행</Button>
            </Form.Item>
          </div>
        </Form>
      </Card>

      {batchId && (
        <Card
          title={<span style={{ color: GREEN, fontWeight: 700 }}>배치 진행현황</span>}
          style={{ marginTop: 16, borderRadius: 10 }}
          extra={
            <Button size="small" onClick={() => void refetchSummary()}>새로고침</Button>
          }
        >
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <Progress percent={summary?.percent ?? 0} status={progressStatus} />
            {summary ? (
              <>
                <Typography.Text strong>
                  {summary.done} / {summary.total} ({summary.percent}%)
                </Typography.Text>
                <Typography.Text type="secondary">
                  성공 {summary.success} · 실패 {summary.failed} · 대기 {summary.pending}
                </Typography.Text>
                {isComplete && (
                  <Typography.Text type={summary.failed === 0 ? 'success' : 'danger'} strong>
                    완료 (성공 {summary.success} / 실패 {summary.failed})
                  </Typography.Text>
                )}
              </>
            ) : (
              <Typography.Text type="secondary">진행현황 불러오는 중…</Typography.Text>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>batchId: {batchId}</Typography.Text>
          </Space>
        </Card>
      )}
    </div>
    </ConfigProvider>
  );
};

export default BatchUpdatePage;
