import { useState } from 'react';
import { Input, Button, Table, Space, Typography, Steps, InputNumber, Select, Result, Tag, Alert } from 'antd';
import { sourcingApi, type SourcingResult, type IherbSourcingResponse, type BulkProductCreateResponse } from '../api/sourcingApi';
import { notify } from '../utils/notify';

interface EditableRow extends SourcingResult {
  origin?: string;
  weight?: number;
  rawCategory?: string;
  bundleQuantity: number;
  marginRate: number;
  vendor: string;
}

interface PublishOutcome { productId: number; market: string; ok: boolean; error?: string; }

const { TextArea } = Input;

const { Title } = Typography;

const VENDOR_OPTIONS = ['IHB', 'AMZ', 'FTN', 'COK', 'OCD', 'TES', 'VTB'];

const MARKETS = ['COUPANG', 'SMART_STORE', 'ELEVEN_STREET', 'CAFE24'];

const ProductRegisterPage = () => {
  const [current, setCurrent] = useState(0);
  const [urls, setUrls] = useState('');
  const [rows, setRows] = useState<EditableRow[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [loading, setLoading] = useState(false);
  const [savedIds, setSavedIds] = useState<number[]>([]);
  const [selectedMarkets, setSelectedMarkets] = useState<string[]>([]);
  const [outcomes, setOutcomes] = useState<PublishOutcome[]>([]);
  const [crawlFailures, setCrawlFailures] = useState<{ url: string; reason: string }[]>([]);
  const [saveFailures, setSaveFailures] = useState<{ index: number; baseName: string; reason: string }[]>([]);

  const handleCrawl = async () => {
    const urlList = urls.split('\n').map((u) => u.trim()).filter(Boolean);
    if (urlList.length === 0) { notify.warning('URL을 입력하세요'); return; }
    setLoading(true);
    try {
      const res = await sourcingApi.sourceFromIherb(urlList);
      const data = res.data as IherbSourcingResponse;
      const scraped = data.succeeded || [];
      const failed = data.failed || [];
      setRows(scraped.map((s) => ({ ...s, bundleQuantity: 1, marginRate: 20, vendor: 'IHB' })));
      setSelectedRowKeys(scraped.map((_, i) => i));
      setCrawlFailures(failed);
      if (failed.length > 0) notify.warning(`${scraped.length}개 크롤링 완료, ${failed.length}개 실패`);
      else notify.success(`${scraped.length}개 상품 크롤링 완료`);
      if (scraped.length > 0) setCurrent(1);
    } catch { notify.error('크롤링 실패'); }
    finally { setLoading(false); }
  };

  const updateRow = (index: number, patch: Partial<EditableRow>) => {
    setRows((prev) => prev.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  };

  const handleSave = async () => {
    const selected = rows.filter((_, i) => selectedRowKeys.includes(i));
    if (selected.length === 0) { notify.warning('저장할 상품을 선택하세요'); return; }
    setLoading(true);
    try {
      const res = await sourcingApi.saveProductsBulk(
        selected.map((s) => ({
          sourceUrl: s.sourceUrl, baseName: s.baseName, originalName: s.originalName,
          brand: s.brand, costPrice: s.costPrice, origin: s.origin ?? null,
          weight: s.weight ?? null, capacity: s.capacity, measureUnit: null,
          sourceImages: s.sourceImages, rawSourceHtml: null, rawCategory: s.rawCategory ?? null,
          isAvailable: s.isAvailable, bundleQuantity: s.bundleQuantity,
          marginRate: s.marginRate, vendor: s.vendor,
        }))
      );
      const data = res.data as BulkProductCreateResponse;
      const succeeded = data.succeeded || [];
      const failed = data.failed || [];
      const ids = succeeded.map((s) => s.productId);
      setSavedIds(ids);
      setSaveFailures(failed);
      if (failed.length > 0) notify.warning(`${ids.length}개 저장 완료, ${failed.length}개 실패`);
      else notify.success(`${ids.length}개 상품 저장 완료`);
      setCurrent(2);
    } catch { notify.error('저장 실패'); }
    finally { setLoading(false); }
  };

  const handlePublish = async () => {
    if (selectedMarkets.length === 0) { notify.warning('등록할 마켓을 선택하세요'); return; }
    setLoading(true);
    const results: PublishOutcome[] = [];
    for (const id of savedIds) {
      for (const market of selectedMarkets) {
        try {
          await sourcingApi.publishToMarket(id, market);
          results.push({ productId: id, market, ok: true });
        } catch (e) {
          const err = e as { response?: { data?: { message?: string } | string }; message?: string };
          const reason =
            (typeof err.response?.data === 'object' ? err.response?.data?.message : err.response?.data) ??
            err.message ?? '알 수 없는 오류';
          results.push({ productId: id, market, ok: false, error: reason });
        }
      }
    }
    setOutcomes(results);
    setLoading(false);
    const failed = results.filter((r) => !r.ok).length;
    if (failed === 0) notify.success('모든 마켓 등록 완료');
    else notify.warning(`${failed}개 조합 등록 실패 — 결과를 확인하세요`);
    setCurrent(3);
  };

  const reset = () => {
    setCurrent(0); setUrls(''); setRows([]); setSelectedRowKeys([]);
    setSavedIds([]); setSelectedMarkets([]); setOutcomes([]);
    setCrawlFailures([]); setSaveFailures([]);
  };

  const editColumns = [
    { title: '브랜드', dataIndex: 'brand', width: 90, ellipsis: true },
    { title: '상품명', dataIndex: 'baseName', ellipsis: true },
    { title: '원가($)', dataIndex: 'costPrice', width: 80 },
    { title: '원산지', width: 110, render: (_: unknown, r: EditableRow, i: number) => (
      <Input size="small" value={r.origin} onChange={(e) => updateRow(i, { origin: e.target.value })} />) },
    { title: '중량', width: 90, render: (_: unknown, r: EditableRow, i: number) => (
      <InputNumber size="small" value={r.weight} onChange={(v) => updateRow(i, { weight: v ?? undefined })} />) },
    { title: '카테고리', width: 130, render: (_: unknown, r: EditableRow, i: number) => (
      <Input size="small" value={r.rawCategory} onChange={(e) => updateRow(i, { rawCategory: e.target.value })} />) },
    { title: '묶음', width: 70, render: (_: unknown, r: EditableRow, i: number) => (
      <InputNumber size="small" min={1} value={r.bundleQuantity} onChange={(v) => updateRow(i, { bundleQuantity: v ?? 1 })} />) },
    { title: '마진율(%)', width: 90, render: (_: unknown, r: EditableRow, i: number) => (
      <InputNumber size="small" min={0} value={r.marginRate} onChange={(v) => updateRow(i, { marginRate: v ?? 0 })} />) },
    { title: '공급처', width: 100, render: (_: unknown, r: EditableRow, i: number) => (
      <Select size="small" style={{ width: 90 }} value={r.vendor} options={VENDOR_OPTIONS.map((v) => ({ value: v, label: v }))}
        onChange={(v) => updateRow(i, { vendor: v })} />) },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>신규 상품 등록 (iHerb)</Title>
      <Steps current={current} style={{ marginBottom: 24 }} items={[
        { title: '크롤링' }, { title: '보정·가격' }, { title: '마켓 등록' }, { title: '완료' },
      ]} />

      {current === 0 && (
        <Space direction="vertical" style={{ width: '100%' }}>
          <TextArea rows={5} placeholder="iHerb 상품 URL을 한 줄에 하나씩 입력하세요"
            value={urls} onChange={(e) => setUrls(e.target.value)} />
          <Button type="primary" loading={loading} onClick={handleCrawl}>크롤링</Button>
        </Space>
      )}

      {current === 1 && (
        <Space direction="vertical" style={{ width: '100%' }}>
          {crawlFailures.length > 0 && (
            <Alert type="warning" showIcon
              message={`크롤링 실패 ${crawlFailures.length}건`}
              description={
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {crawlFailures.map((f, i) => (
                    <li key={i}>{f.url} — {f.reason}</li>
                  ))}
                </ul>
              } />
          )}
          <Table<EditableRow> rowKey={(_, i) => i ?? 0} columns={editColumns} dataSource={rows}
            rowSelection={{ selectedRowKeys, onChange: setSelectedRowKeys }}
            pagination={false} size="small" scroll={{ y: 460 }} />
          <Space>
            <Button onClick={() => setCurrent(0)}>이전</Button>
            <Button type="primary" loading={loading} onClick={handleSave}>
              선택한 상품 저장 ({selectedRowKeys.length}개)
            </Button>
          </Space>
        </Space>
      )}

      {current === 2 && (
        <Space direction="vertical" style={{ width: '100%' }}>
          {saveFailures.length > 0 && (
            <Alert type="warning" showIcon
              message={`저장 실패 ${saveFailures.length}건`}
              description={
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {saveFailures.map((f) => (
                    <li key={f.index}>{f.baseName} — {f.reason}</li>
                  ))}
                </ul>
              } />
          )}
          <div>저장된 상품 {savedIds.length}개. 등록할 마켓을 선택하세요.</div>
          <Select mode="multiple" style={{ width: 400 }} placeholder="마켓 선택"
            value={selectedMarkets} onChange={setSelectedMarkets}
            options={MARKETS.map((m) => ({ value: m, label: m }))} />
          <Space>
            <Button type="primary" loading={loading} onClick={handlePublish}>
              마켓 등록 ({savedIds.length}상품 × {selectedMarkets.length}마켓)
            </Button>
            <Button onClick={reset}>마켓 등록 건너뛰고 종료</Button>
          </Space>
        </Space>
      )}

      {current === 3 && (
        <Result status={outcomes.every((o) => o.ok) ? 'success' : 'warning'}
          title="마켓 등록 결과"
          subTitle={`성공 ${outcomes.filter((o) => o.ok).length} / 실패 ${outcomes.filter((o) => !o.ok).length}`}
          extra={[
            <Space key="list" direction="vertical" style={{ textAlign: 'left' }}>
              {outcomes.map((o, i) => (
                <div key={i}>
                  <Tag color={o.ok ? 'green' : 'red'}>{o.ok ? '성공' : '실패'}</Tag>
                  상품 {o.productId} · {o.market}{o.error ? ` — ${o.error}` : ''}
                </div>
              ))}
            </Space>,
            <Button key="new" type="primary" onClick={reset}>새 등록</Button>,
          ]} />
      )}
    </div>
  );
};

export default ProductRegisterPage;
