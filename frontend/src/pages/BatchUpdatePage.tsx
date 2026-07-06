import { useState } from 'react';
import { Form, Input, InputNumber, Button, Radio, message, Card } from 'antd';
import { batchApi } from '../api/batchApi';

const BatchUpdatePage = () => {
  const [mode, setMode] = useState<'supplier' | 'manual'>('supplier');
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

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
        message.success(`배치 시작: ${res.data.count}개 상품 (batchId: ${res.data.batchId})`);
      } else {
        const ids = (values.productIds || '').split(',').map((s) => parseInt(s.trim())).filter((n) => !isNaN(n));
        if (ids.length === 0) {
          message.warning('상품 ID를 입력하세요');
          return;
        }
        const res = await batchApi.crawlAndUpdate(ids, values.marginRate, values.couponRate, values.minMarginPrice);
        message.success(`배치 시작 (batchId: ${res.data.batchId})`);
      }
    } catch {
      message.error('배치 시작 실패');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ padding: 24 }}>
      <Card title="배치 가격/재고 일괄 업데이트">
        <Radio.Group value={mode} onChange={(e) => setMode(e.target.value)} style={{ marginBottom: 16 }}>
          <Radio.Button value="supplier">소싱업체별</Radio.Button>
          <Radio.Button value="manual">상품ID 지정</Radio.Button>
        </Radio.Group>

        <Form form={form} layout="vertical" onFinish={handleSubmit} initialValues={{ marginRate: 15, couponRate: 20, minMarginPrice: 5000 }}>
          {mode === 'supplier' ? (
            <Form.Item name="supplierCode" label="소싱업체 코드" initialValue="IHB">
              <InputNumber style={{ width: 200 }} />
            </Form.Item>
          ) : (
            <Form.Item name="productIds" label="상품 ID (콤마 구분)">
              <Input placeholder="1, 2, 3" />
            </Form.Item>
          )}
          <Form.Item name="marginRate" label="마진율 (%)">
            <InputNumber min={0} max={100} />
          </Form.Item>
          <Form.Item name="couponRate" label="쿠폰율 (%)">
            <InputNumber min={0} max={100} />
          </Form.Item>
          <Form.Item name="minMarginPrice" label="최소 마진가 (원)">
            <InputNumber min={0} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading}>배치 실행</Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
};

export default BatchUpdatePage;
