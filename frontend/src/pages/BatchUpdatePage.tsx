import { useCallback, useEffect, useRef, useState } from 'react';
import { Form, Input, InputNumber, Button, Radio, message, Card, Progress, Space, Typography, Select, ConfigProvider } from 'antd';
import { batchApi } from '../api/batchApi';
import { VENDOR_OPTIONS } from './product/productGridShared';

// 그린 테마(상품/주문 페이지와 통일). antd 전역 primary는 검정이라 이 페이지만 ConfigProvider로 그린 적용.
const GREEN = '#166534';

const ACTIVE_BATCH_KEY = 'sbshop.activeBatchId';
const POLL_INTERVAL_MS = 30000;
// D-089: 배치 진행바를 전 클라이언트에 공유하기 위한 SSE 구독 주소(다른 페이지와 동일 경로)
const SSE_URL = '/sbshop-agent/api/v1/notifications/subscribe';

interface BatchSummary {
  batchId: string;
  total: number;
  success: number;
  failed: number;
  pending: number;
  done: number;
  percent: number;
}

const BatchUpdatePage = () => {
  const [mode, setMode] = useState<'supplier' | 'manual'>('supplier');
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const [batchId, setBatchId] = useState<string | null>(null);
  const [summary, setSummary] = useState<BatchSummary | null>(null);

  // setState-after-unmount 가드 + 인터벌 핸들
  const mountedRef = useRef(true);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // SSE 콜백이 최신 batchId를 참조하도록(자기 배치 중복 startTracking 방지)
  const batchIdRef = useRef<string | null>(null);
  useEffect(() => {
    batchIdRef.current = batchId;
  }, [batchId]);

  const clearPoll = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  const stopTracking = useCallback(() => {
    clearPoll();
    localStorage.removeItem(ACTIVE_BATCH_KEY);
    if (mountedRef.current) {
      setBatchId(null);
      setSummary(null);
    }
  }, [clearPoll]);

  // 단발 요약 조회. 완료면 폴링 중단. 404/에러는 조용히 추적 해제(폴링마다 토스트 스팸 방지).
  const fetchSummary = useCallback(async (id: string) => {
    try {
      const res = await batchApi.getBatchSummary(id);
      const data = res.data as BatchSummary;
      if (!mountedRef.current) return;
      setSummary(data);
      if (data.total > 0 && data.done >= data.total) {
        clearPoll();
      }
    } catch {
      // 알 수 없는/정리된 batchId 등 → 조용히 카드 숨김
      stopTracking();
    }
  }, [clearPoll, stopTracking]);

  // batchId 추적 시작: localStorage 저장 + 즉시 조회 + 폴링(30초)
  const startTracking = useCallback((id: string) => {
    localStorage.setItem(ACTIVE_BATCH_KEY, id);
    setBatchId(id);
    setSummary(null);
    clearPoll();
    void fetchSummary(id);
    intervalRef.current = setInterval(() => {
      void fetchSummary(id);
    }, POLL_INTERVAL_MS);
  }, [clearPoll, fetchSummary]);

  // 새로고침 복원: 마운트 시 저장된 batchId가 있으면 즉시 조회 후 폴링 재개
  useEffect(() => {
    mountedRef.current = true;
    const saved = localStorage.getItem(ACTIVE_BATCH_KEY);
    if (saved) {
      startTracking(saved);
    }
    return () => {
      mountedRef.current = false;
      clearPoll();
    };
    // 마운트 시 1회만 실행
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // D-089: SSE 구독 — 다른 클라이언트(동업자)가 시작한 배치도 진행바에 공유한다.
  // BATCH_STARTED로 batchId를 받으면 추적 시작, 완료 이벤트는 즉시 요약 갱신.
  useEffect(() => {
    const es = new EventSource(SSE_URL);
    const onStarted = (e: Event) => {
      const startedId = (e as MessageEvent).data as string;
      // 자기 자신이 방금 시작한 배치면 중복 startTracking(요약 리셋) 회피
      if (startedId && startedId !== batchIdRef.current) {
        startTracking(startedId);
      }
    };
    const onCompleted = (e: Event) => {
      // payload: "batchId|success" — 추적 중인 배치면 즉시 갱신(폴링 주기 대기 없이 완료 반영)
      const completedId = String((e as MessageEvent).data).split('|')[0];
      if (completedId && completedId === batchIdRef.current) {
        void fetchSummary(completedId);
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
  }, [startTracking, fetchSummary]);

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
        // D-038: 대상 상품이 없으면 백엔드는 {message}만 반환(batchId·count 없음) → undefined 방지 분기
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
          {/* 입력을 한 줄로: 라벨은 위, 필드는 나란히, 실행 버튼은 줄 끝 정렬 */}
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
            <Button size="small" onClick={() => void fetchSummary(batchId)}>새로고침</Button>
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
