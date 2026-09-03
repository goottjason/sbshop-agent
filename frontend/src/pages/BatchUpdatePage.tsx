import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Form, Input, InputNumber, Button, Radio, Card, Progress, Space, Typography, Select,
  ConfigProvider, Alert, App as AntApp,
} from 'antd';
import { batchApi } from '../api/batchApi';
import { notify } from '../utils/notify';
import { fetchVendorPricePolicies, type VendorPricePolicy } from '../api/vendorPricePolicyApi';
import BatchResultTable from '../components/batch/BatchResultTable';

interface BatchSummary {
  batchId: string;
  total: number;
  success: number;
  failed: number;
  partial: number;
  pending: number;
  done: number;
  percent: number;
}

interface ManualItem {
  productId: number;
  price: number | null;
  stock: number | null;
}

type BatchMode = 'supplier' | 'crawl' | 'direct';

interface CrawlParams {
  marginRate: number;
  couponRate: number;
  minMarginPrice: number;
}

const RETRY_PARAMS_KEY = 'sbshop.batch.retryParams';

const readRetryParams = (): CrawlParams | null => {
  try {
    const raw = localStorage.getItem(RETRY_PARAMS_KEY);
    return raw ? (JSON.parse(raw) as CrawlParams) : null;
  } catch {
    return null;
  }
};

const crawlParams = (values: { marginRate: number; couponRate: number; minMarginPrice: number }): CrawlParams => ({
  marginRate: values.marginRate,
  couponRate: values.couponRate,
  minMarginPrice: values.minMarginPrice,
});

const GREEN = '#166534';

const ACTIVE_BATCH_KEY = 'sbshop.activeBatchId';

const POLL_INTERVAL_MS = 30000;

const SSE_URL = '/sbshop-agent/api/v1/notifications/subscribe';

const MANUAL_PLACEHOLDER = `1024, 32900, 12
1025, , 0
1026, 18500,`;

const PREVIEW_LIMIT = 20;

const BLANK_TOKENS = ['', '-'];

const MAX_PRICE = 100_000_000;

const MAX_STOCK = 1_000_000;

const THOUSANDS_COMMA = /\d,\d{3}(?!\d)/;

const parseCell = (cell: string | undefined) => (cell ?? '').trim();

const isBlank = (cell: string) => BLANK_TOKENS.includes(cell);

const parseManualItems = (raw: string): { items: ManualItem[]; error: string | null } => {
  const lines = raw
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  if (lines.length === 0) {
    return { items: [], error: '적용할 값을 한 줄에 한 상품씩 입력하세요.' };
  }
  const items: ManualItem[] = [];
  const seen = new Set<number>();
  for (let i = 0; i < lines.length; i += 1) {
    const lineNo = i + 1;
    const fail = (reason: string) => ({ items: [], error: `${lineNo}번째 줄 — ${reason}: "${lines[i]}"` });
    if (THOUSANDS_COMMA.test(lines[i])) {
      return fail('천 단위 콤마로 읽힐 수 있는 숫자가 있습니다 — 콤마를 빼고 "1024, 32900, 12"처럼 띄어 쓰세요');
    }
    const cells = lines[i].split(/[,\t]/).map(parseCell);
    if (cells.length > 3) {
      return fail('항목이 3개(상품ID, 판매가, 재고)를 넘습니다');
    }
    const idCell = cells[0];
    const productId = Number(idCell);
    if (isBlank(idCell) || !Number.isInteger(productId) || productId <= 0) {
      return fail('상품 ID가 올바르지 않습니다');
    }
    if (seen.has(productId)) {
      return fail(`상품 ID ${productId}가 중복됩니다`);
    }
    seen.add(productId);

    const priceCell = cells[1] ?? '';
    let price: number | null = null;
    if (!isBlank(priceCell)) {
      const parsed = Number(priceCell);
      if (!Number.isFinite(parsed) || parsed <= 0 || parsed > MAX_PRICE) {
        return fail(`판매가는 0보다 크고 ${MAX_PRICE.toLocaleString()}원 이하여야 합니다`);
      }
      price = Math.round(parsed);
    }

    const stockCell = cells[2] ?? '';
    let stock: number | null = null;
    if (!isBlank(stockCell)) {
      const parsed = Number(stockCell);
      if (!Number.isInteger(parsed) || parsed < 0 || parsed > MAX_STOCK) {
        return fail(`재고는 0 이상 ${MAX_STOCK.toLocaleString()} 이하 정수여야 합니다`);
      }
      stock = parsed;
    }

    if (price === null && stock === null) {
      return fail('판매가와 재고 중 최소 하나는 입력해야 합니다');
    }
    items.push({ productId, price, stock });
  }
  return { items, error: null };
};

const describeItem = (item: ManualItem) => {
  const parts: string[] = [];
  parts.push(item.price === null ? '판매가 유지' : `판매가 ${item.price.toLocaleString()}원`);
  if (item.stock === null) {
    parts.push('판매상태 유지');
  } else {
    parts.push(`재고 ${item.stock} → ${item.stock <= 0 ? '품절' : '판매중'}`);
  }
  return parts.join(' · ');
};

const BatchUpdatePage = () => {
  const { modal } = AntApp.useApp();
  const [mode, setMode] = useState<BatchMode>('supplier');
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const { data: vendorPolicies = [] } = useQuery<VendorPricePolicy[]>({
    queryKey: ['vendorPricePolicies'],
    queryFn: fetchVendorPricePolicies,
    staleTime: 5 * 60 * 1000,
  });

  const [supplierCode, setSupplierCode] = useState('IHB');
  const activePolicy = vendorPolicies.find((p) => p.vendor === supplierCode) ?? null;

  useEffect(() => {
    if (!activePolicy) return;
    form.setFieldsValue({
      marginRate: activePolicy.marginRate ?? undefined,
      couponRate: activePolicy.couponRate ?? undefined,
      minMarginPrice: activePolicy.minMarginPrice ?? undefined,
    });
  }, [activePolicy, form]);

  const [batchId, setBatchId] = useState<string | null>(() => localStorage.getItem(ACTIVE_BATCH_KEY));
  const [retryParams, setRetryParams] = useState<CrawlParams | null>(() => readRetryParams());
  const [retrying, setRetrying] = useState(false);

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

  const startTracking = useCallback((id: string, params?: CrawlParams) => {
    localStorage.setItem(ACTIVE_BATCH_KEY, id);
    if (params) {
      localStorage.setItem(RETRY_PARAMS_KEY, JSON.stringify(params));
    } else {
      localStorage.removeItem(RETRY_PARAMS_KEY);
    }
    setRetryParams(params ?? null);
    setBatchId(id);
  }, []);

  const retryProducts = useCallback(async (productCodes: string[]) => {
    if (!retryParams) return;
    const ids = productCodes.map((c) => parseInt(c, 10)).filter((n) => !isNaN(n));
    if (ids.length === 0) {
      notify.warning('다시 실행할 상품을 찾지 못했습니다');
      return;
    }
    setRetrying(true);
    try {
      const res = await batchApi.crawlAndUpdate(
        ids, retryParams.marginRate, retryParams.couponRate, retryParams.minMarginPrice);
      const startedId = (res.data as { batchId?: string }).batchId;
      if (startedId) {
        notify.success(`문제 ${ids.length}건 재실행 시작 (batchId: ${startedId})`);
        startTracking(startedId, retryParams);
      }
    } catch {
      notify.error('재실행 시작 실패');
    } finally {
      setRetrying(false);
    }
  }, [retryParams, startTracking]);

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

  const runManualUpdate = async (items: ManualItem[]) => {
    setLoading(true);
    try {
      const res = await batchApi.manualUpdate(items);
      const data = res.data as { batchId?: string; count?: string; message?: string };
      if (data.batchId) {
        notify.success(`직접 지정 배치 시작: ${data.count ?? items.length}개 상품 (batchId: ${data.batchId}) — 아래 진행현황에서 결과를 확인하세요.`);
        startTracking(data.batchId);
      } else {
        notify.info(data.message || '배치가 시작되지 않았습니다.');
      }
    } catch {
      notify.error('직접 지정 배치 시작 실패');
    } finally {
      setLoading(false);
    }
  };

  const confirmManualUpdate = (items: ManualItem[]) => {
    const priceCount = items.filter((item) => item.price !== null).length;
    const stockCount = items.filter((item) => item.stock !== null).length;
    const preview = items.slice(0, PREVIEW_LIMIT);
    modal.confirm({
      title: `입력한 값을 ${items.length}개 상품에 적용`,
      width: 560,
      okText: '적용',
      okType: 'danger',
      cancelText: '취소',
      content: (
        <div>
          <Typography.Paragraph style={{ marginBottom: 8 }}>
            판매가 변경 {priceCount}건 · 판매상태 변경 {stockCount}건. 입력값이 그대로 판매가·판매상태가 되고
            즉시 마켓에도 반영됩니다. 되돌리려면 배치를 다시 실행해야 합니다.
          </Typography.Paragraph>
          <div
            style={{
              maxHeight: 220, overflowY: 'auto', background: '#f8f9fa',
              border: '1px solid #e5e7eb', borderRadius: 6, padding: '8px 12px', fontSize: 12,
            }}
          >
            {preview.map((item) => (
              <div key={item.productId} style={{ lineHeight: 1.8 }}>
                <Typography.Text strong>#{item.productId}</Typography.Text> {describeItem(item)}
              </div>
            ))}
            {items.length > preview.length && (
              <Typography.Text type="secondary">
                … 외 {items.length - preview.length}건
              </Typography.Text>
            )}
          </div>
        </div>
      ),
      onOk: () => runManualUpdate(items),
    });
  };

  const handleSubmit = async (values: {
    supplierCode?: string;
    productIds?: string;
    manualInput?: string;
    marginRate: number;
    couponRate: number;
    minMarginPrice: number;
  }) => {
    if (mode === 'direct') {
      const { items, error } = parseManualItems(values.manualInput || '');
      if (error) {
        notify.warning(error);
        return;
      }
      confirmManualUpdate(items);
      return;
    }
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
          notify.success(`배치 시작: ${data.count}개 상품 (batchId: ${data.batchId})`);
          startTracking(data.batchId, crawlParams(values));
        } else {
          notify.info(data.message || '해당 소싱업체의 상품이 없습니다.');
        }
      } else {
        const ids = (values.productIds || '').split(',').map((s) => parseInt(s.trim())).filter((n) => !isNaN(n));
        if (ids.length === 0) {
          notify.warning('상품 ID를 입력하세요');
          return;
        }
        const res = await batchApi.crawlAndUpdate(ids, values.marginRate, values.couponRate, values.minMarginPrice);
        const startedId = (res.data as { batchId?: string }).batchId;
        notify.success(`배치 시작 (batchId: ${startedId})`);
        if (startedId) {
          startTracking(startedId, crawlParams(values));
        }
      }
    } catch {
      notify.error('배치 시작 실패');
    } finally {
      setLoading(false);
    }
  };

  const isComplete = !!summary && summary.total > 0 && summary.done >= summary.total;
  const partialCount = summary?.partial ?? 0;
  const isClean = !!summary && summary.failed === 0 && partialCount === 0;
  const progressStatus = !summary
    ? 'active'
    : isComplete
      ? (isClean ? 'success' : 'exception')
      : 'active';
  const isDirect = mode === 'direct';

  return (
    <ConfigProvider theme={{ token: { colorPrimary: GREEN } }}>
    <div className="product-theme" style={{ padding: 24, background: '#f8f9fa', minHeight: '100%' }}>
      <Card
        title={<span style={{ color: GREEN, fontWeight: 700 }}>배치 가격/재고 일괄 업데이트</span>}
        style={{ borderRadius: 10 }}
      >
        <Radio.Group
          value={mode}
          onChange={(e) => setMode(e.target.value as BatchMode)}
          style={{ marginBottom: 16 }}
        >
          <Radio.Button value="supplier">소싱업체별 자동 산정</Radio.Button>
          <Radio.Button value="crawl">상품ID 지정 자동 산정</Radio.Button>
          <Radio.Button value="direct">값 직접 지정</Radio.Button>
        </Radio.Group>

        <Alert
          type={isDirect ? 'warning' : 'info'}
          showIcon
          style={{ marginBottom: 20 }}
          message={isDirect ? '값 직접 지정 — 입력한 판매가를 그대로 적용합니다' : '자동 산정 — 소싱처 크롤 가격으로 판매가를 계산합니다'}
          description={
            isDirect
              ? '마진율·쿠폰율·최소 마진가는 사용하지 않습니다. 입력한 판매가가 그대로 저장되고 마켓에 반영되므로, 적용 전 확인창의 목록을 반드시 확인하세요.'
              : '소싱처에서 매입가·재고를 크롤한 뒤 아래 마진율·쿠폰율·최소 마진가로 판매가를 산정해 반영합니다.'
          }
        />

        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          {isDirect ? (
            <>
              <Form.Item
                name="manualInput"
                label="한 줄에 한 상품 — 상품ID, 판매가, 재고"
                extra="판매가나 재고를 비우면(또는 -) 그 값은 바꾸지 않습니다. 재고 0 이하는 품절, 1 이상은 판매중으로 바뀝니다. 판매가에 천 단위 콤마는 넣지 마세요(32900)."
                style={{ marginBottom: 16 }}
              >
                <Input.TextArea rows={8} placeholder={MANUAL_PLACEHOLDER} style={{ maxWidth: 520, fontFamily: 'monospace' }} />
              </Form.Item>
              <Form.Item style={{ marginBottom: 0 }}>
                <Button type="primary" danger htmlType="submit" loading={loading} style={{ fontWeight: 600 }}>
                  입력값 적용
                </Button>
              </Form.Item>
            </>
          ) : (
            <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-end' }}>
              {mode === 'supplier' ? (
                <Form.Item name="supplierCode" label="소싱업체 코드" initialValue="IHB" style={{ marginBottom: 0 }}>
                  <Select
                    style={{ width: 160 }}
                    onChange={(v: string) => setSupplierCode(v)}
                    options={vendorPolicies.map((p) => ({ value: p.vendor, label: p.vendor }))}
                  />
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
          )}
          {!isDirect && mode === 'supplier' && activePolicy && (
            <div style={{ marginTop: 14, fontSize: 13, color: '#4b5563', lineHeight: 1.8 }}>
              <span style={{ fontWeight: 600, color: GREEN }}>{activePolicy.vendor} 배송비 조건</span>
              {' — '}
              {activePolicy.shipCurrency && activePolicy.shipBaseAmount != null
                && Number(activePolicy.shipBaseAmount) > 0 ? (
                <>
                  해외 {activePolicy.shipCurrency} {activePolicy.shipBaseAmount}
                  {` / ${activePolicy.shipBaseWeightG}g 까지, 이후 ${activePolicy.shipStepWeightG}g 마다 +${activePolicy.shipStepAmount}`}
                </>
              ) : (
                <>해외 배송비 없음</>
              )}
              {activePolicy.domesticFee != null && Number(activePolicy.domesticFee) > 0 && (
                <>
                  {' · 국내 '}
                  {Number(activePolicy.domesticFee).toLocaleString()}원
                  {activePolicy.domesticFreeOver != null
                    && ` (${Number(activePolicy.domesticFreeOver).toLocaleString()}원 이상 무료)`}
                </>
              )}
              <span style={{ marginLeft: 8, color: '#9ca3af' }}>설정 및 연동에서 변경</span>
            </div>
          )}
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
                  성공 {summary.success}
                  {partialCount > 0 && ` · 부분실패 ${partialCount}`}
                  {' · '}실패 {summary.failed} · 대기 {summary.pending}
                </Typography.Text>
                {isComplete && (
                  <Typography.Text type={isClean ? 'success' : 'danger'} strong>
                    완료 (성공 {summary.success}
                    {partialCount > 0 && ` / 부분실패 ${partialCount}`}
                    {' / '}실패 {summary.failed})
                  </Typography.Text>
                )}
                {partialCount > 0 && (
                  <Typography.Text type="warning" style={{ fontSize: 12 }}>
                    부분실패 — 상품은 저장됐지만 일부 마켓 전송이 거부됐습니다.
                  </Typography.Text>
                )}
              </>
            ) : (
              <Typography.Text type="secondary">진행현황 불러오는 중…</Typography.Text>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>batchId: {batchId}</Typography.Text>
            <BatchResultTable
              batchId={batchId}
              polling={!isComplete}
              onRetry={retryParams ? retryProducts : undefined}
              retryLoading={retrying}
            />
          </Space>
        </Card>
      )}
    </div>
    </ConfigProvider>
  );
};

export default BatchUpdatePage;
