import { useState } from 'react';
import { Input, Button, Table, Space, message, Typography } from 'antd';
import { sourcingApi, type SourcingResult } from '../api/sourcingApi';

const { TextArea } = Input;
const { Title } = Typography;

const ProductRegisterPage = () => {
  const [urls, setUrls] = useState('');
  const [scraped, setScraped] = useState<SourcingResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

  const handleCrawl = async () => {
    const urlList = urls.split('\n').filter((u) => u.trim());
    if (urlList.length === 0) {
      message.warning('URL을 입력하세요');
      return;
    }
    setLoading(true);
    try {
      const res = await sourcingApi.sourceFromIherb(urlList);
      setScraped(res.data || []);
      message.success(`${res.data?.length || 0}개 상품 크롤링 완료`);
    } catch {
      message.error('크롤링 실패');
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async () => {
    const selected = scraped.filter((_, i) => selectedRowKeys.includes(i));
    if (selected.length === 0) {
      message.warning('저장할 상품을 선택하세요');
      return;
    }
    setLoading(true);
    try {
      await sourcingApi.saveProductsBulk(
        selected.map((s) => ({
          sourceUrl: s.sourceUrl,
          baseName: s.baseName,
          originalName: s.originalName,
          brand: s.brand,
          costPrice: s.costPrice,
          capacity: s.capacity,
          sourceImages: s.sourceImages,
          isAvailable: s.isAvailable,
          bundleQuantity: 1,
          marginRate: 20,
          vendor: 'IHB',
        }))
      );
      message.success(`${selected.length}개 상품 저장 완료`);
      setScraped([]);
      setSelectedRowKeys([]);
    } catch {
      message.error('저장 실패');
    } finally {
      setLoading(false);
    }
  };

  const columns = [
    { title: '브랜드', dataIndex: 'brand', width: 100 },
    { title: '상품명', dataIndex: 'baseName', ellipsis: true },
    { title: '원본명', dataIndex: 'originalName', ellipsis: true },
    { title: '원가', dataIndex: 'costPrice', width: 100, render: (v: number) => v ? `$${v}` : '' },
    { title: '용량', dataIndex: 'capacity', width: 80 },
    { title: '이미지', dataIndex: 'sourceImages', width: 60, render: (v: string[]) => v?.length || 0 },
    { title: '재고', dataIndex: 'isAvailable', width: 60, render: (v: boolean) => v ? 'O' : 'X' },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>신규 상품 등록 (iHerb)</Title>
      <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
        <TextArea
          rows={5}
          placeholder="iHerb 상품 URL을 한 줄에 하나씩 입력하세요"
          value={urls}
          onChange={(e) => setUrls(e.target.value)}
        />
        <Space>
          <Button type="primary" loading={loading} onClick={handleCrawl}>크롤링</Button>
          {scraped.length > 0 && (
            <Button type="primary" loading={loading} onClick={handleSave}>
              선택한 상품 저장 ({selectedRowKeys.length}개)
            </Button>
          )}
        </Space>
      </Space>

      {scraped.length > 0 && (
        <Table<SourcingResult>
          rowKey={(_, i) => i ?? 0}
          columns={columns}
          dataSource={scraped}
          rowSelection={{
            selectedRowKeys,
            onChange: setSelectedRowKeys,
          }}
          pagination={{ pageSize: 20 }}
          size="small"
          scroll={{ y: 500 }}
        />
      )}
    </div>
  );
};

export default ProductRegisterPage;
