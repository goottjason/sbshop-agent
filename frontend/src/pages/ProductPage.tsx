import { useState, useCallback, useRef } from 'react';
import { AgGridReact } from 'ag-grid-react';
import type { ColDef, CellClickedEvent } from 'ag-grid-community';
import { Input, Button, Space, Modal, InputNumber, message, Descriptions, Image, Spin, Collapse, Typography, Divider, Pagination, Tooltip, Switch } from 'antd';
import { SearchOutlined, ReloadOutlined, UploadOutlined, LinkOutlined, CloudDownloadOutlined } from '@ant-design/icons';
import { productApi, type ProductList, type ProductDetail, type ImageUploadResult, type PriceStockSyncResult } from '../api/productApi';

// D-060: 마켓 코드 → 한글 라벨
const MARKET_LABELS: Record<string, string> = {
  COUPANG: '쿠팡', SMART_STORE: '스토어', ELEVEN_STREET: '11번가',
  GMARKET: 'G마켓', AUCTION: '옥션', CAFE24: '카페24',
};
const marketLabel = (code: string) => MARKET_LABELS[code] || code;

// D-047: 마켓별 연동코드 컬럼 정의. 키는 백엔드 MarketType.name()과 정확히 일치해야 한다.
// G마켓·옥션은 마켓 상품코드가 없어(ESM+ 연동코드 부재) 컬럼에서 제외한다.
const MARKET_COLUMNS: { key: string; header: string }[] = [
  { key: 'COUPANG', header: '쿠팡' },
  { key: 'SMART_STORE', header: '스토어' },
  { key: 'ELEVEN_STREET', header: '11번가' },
  { key: 'CAFE24', header: '카페24' },
];

// D-047: 마켓 상품코드 → 마켓 상품 페이지 URL.
// 스토어(스토어 slug 필요)·카페24(자사 도메인 필요)는 코드만으로 신뢰 링크를 만들 수 없어 null(코드 텍스트만 표시).
// 쿠팡/11번가/G마켓/옥션 패턴은 공개 상품 URL 기준 best-guess — 실제 응답/문서로 재확인 필요(_workspace/fixes/batchD.md).
const buildMarketUrl = (marketKey: string, code: string): string | null => {
  switch (marketKey) {
    case 'COUPANG':
      return `https://www.coupang.com/vp/products/${code}`;
    case 'ELEVEN_STREET':
      return `https://www.11st.co.kr/products/${code}`;
    case 'GMARKET':
      return `http://item.gmarket.co.kr/Item?goodscode=${code}`;
    case 'AUCTION':
      return `http://itempage3.auction.co.kr/DetailView.aspx?itemno=${code}`;
    default:
      return null;
  }
};

// D-047: 마켓별 연동코드 셀.
// - 미등록: '-'
// - 등록됐으나 코드가 내부 productId 폴백(= row.id, D-046 미해결 시 쿠팡 등): '미확인' 배지(오클릭 방지, 링크 없음)
// - 실제 마켓코드: 클릭 시 새 탭으로 마켓 상품 페이지 이동(링크 불가 마켓은 코드 텍스트)
const renderMarketCell = (marketKey: string) => (params: { data?: ProductList }) => {
  const code = params.data?.marketRegistrations?.[marketKey];
  if (!code) {
    return <span style={{ color: '#ccc' }}>-</span>;
  }
  const isFallback = params.data != null && code === String(params.data.id);
  if (isFallback) {
    return (
      <span
        style={{ color: '#faad14', fontSize: 12 }}
        title="등록됨 · 마켓 상품코드 미확인 (해당 마켓 연동정보에 상품코드 키 없음)"
      >
        미확인
      </span>
    );
  }
  const url = buildMarketUrl(marketKey, code);
  if (!url) {
    return <span title="마켓 상품코드">{code}</span>;
  }
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      onClick={(e) => e.stopPropagation()}
      title="마켓 상품 페이지 열기"
    >
      {code}
    </a>
  );
};

// 상품 그리드 전용 스타일(quartz 테마 토큰 + 가격/재고 버튼). .sb-product-grid로 스코프해 타 그리드에 영향 없음.
const ProductGridStyle = () => (
  <style>{`
    .sb-product-grid {
      --ag-font-family: 'Inter', sans-serif;
      --ag-font-size: 13px;
      --ag-foreground-color: #334155;
      --ag-header-foreground-color: #475569;
      --ag-header-background-color: #f8fafc;
      --ag-background-color: #ffffff;
      --ag-odd-row-background-color: #ffffff;
      --ag-border-color: #e5e7eb;
      --ag-row-border-color: #eef2f7;
      --ag-row-hover-color: #f1f5f9;
      --ag-selected-row-background-color: #e8effb;
      --ag-header-column-resize-handle-color: #cbd5e1;
      --ag-wrapper-border-radius: 10px;
      --ag-cell-horizontal-padding: 14px;
      --ag-accent-color: #334155;
      border: 1px solid #e5e7eb;
      border-radius: 10px;
      overflow: hidden;
      box-shadow: 0 1px 2px rgba(0,0,0,0.04);
    }
    .sb-product-grid .ag-header { border-bottom: 1px solid #e5e7eb; }
    .sb-product-grid .ag-header-cell-label { font-weight: 600; }
    .sb-pricestock-btn {
      border: 1px solid var(--primary-color);
      background: #fff;
      color: var(--primary-color);
      padding: 4px 14px;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      transition: background-color 0.15s ease, color 0.15s ease;
      line-height: 1.4;
    }
    .sb-pricestock-btn:hover { background: var(--primary-color); color: #fff; }
  `}</style>
);

const ProductPage = () => {
  const [rowData, setRowData] = useState<ProductList[]>([]);
  const [keyword, setKeyword] = useState('');
  const [totalCount, setTotalCount] = useState(0);
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [, setLoading] = useState(false);
  const [priceStockModal, setPriceStockModal] = useState<{ visible: boolean; id?: number; price?: number; soldOut?: boolean }>({ visible: false });

  // D-035: 상품 상세 모달
  const [detailModal, setDetailModal] = useState<{ visible: boolean; loading: boolean; id?: number; data?: ProductDetail }>({ visible: false, loading: false });
  // D-036: 이미지 변경 UI 상태
  const [urlInput, setUrlInput] = useState('');
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadData = useCallback(async (page: number, size: number, kw?: string) => {
    setLoading(true);
    try {
      const res = await productApi.fetchProducts(page, size, kw);
      setRowData(res.data.content || []);
      setTotalCount(res.data.totalElements || 0);
      setCurrentPage(page);
      setPageSize(size);
    } catch {
      message.error('상품 목록 조회 실패');
    } finally {
      setLoading(false);
    }
  }, []);

  useState(() => { loadData(0, 50); });

  const openPriceStockModal = (product: ProductList) => {
    setPriceStockModal({
      visible: true,
      id: product.id,
      price: product.salePrice ?? 0,
      soldOut: false,
    });
  };

  // D-035: 상품명 클릭 → 상세 조회 → 모달
  const openDetailModal = async (id: number) => {
    setDetailModal({ visible: true, loading: true, id });
    setUrlInput('');
    try {
      const res = await productApi.fetchProductDetail(id);
      setDetailModal({ visible: true, loading: false, id, data: res.data as ProductDetail });
    } catch {
      message.error('상품 상세 조회에 실패했습니다.');
      setDetailModal({ visible: false, loading: false });
    }
  };

  const refreshDetail = async (id: number) => {
    try {
      const res = await productApi.fetchProductDetail(id);
      setDetailModal((prev) => ({ ...prev, data: res.data as ProductDetail }));
    } catch {
      message.error('상세 정보 갱신에 실패했습니다.');
    }
  };

  // D-049(반려 재수정): 업로드 응답의 마켓별 재게시 결과를 사용자에게 표면화.
  // 자사 저장(R2/DB)은 성공했더라도 일부 마켓 재게시가 실패하면 조용히 삼키지 않고 경고로 노출한다.
  const surfaceUploadResult = (baseMsg: string, result?: ImageUploadResult) => {
    const failed = result?.failed ?? [];
    if (failed.length > 0) {
      const failedNames = failed.map((f) => f.label).join(', ');
      const okCount = result?.synced.length ?? 0;
      message.warning(
        `${baseMsg} — 단, ${failed.length}개 마켓 재게시 실패: ${failedNames}` +
          (okCount > 0 ? ` (${okCount}개 마켓은 반영 완료)` : ''),
        6,
      );
    } else if ((result?.synced.length ?? 0) > 0) {
      const okNames = result!.synced.map((s) => s.label).join(', ');
      message.success(`${baseMsg} — ${okNames} 재게시 완료`);
    } else {
      message.success(baseMsg);
    }
  };

  // D-060: 가격/재고 마켓 동기화 결과 표면화. DB는 저장됐어도 마켓 반영 실패를 명확히 알린다.
  const surfacePriceStockResult = (result?: PriceStockSyncResult) => {
    const synced = result?.synced ?? [];
    const skipped = result?.skipped ?? [];
    const failedEntries = Object.entries(result?.failed ?? {});
    const syncedMsg = synced.length > 0 ? ` — ${synced.map(marketLabel).join(', ')} 반영 완료` : '';
    if (failedEntries.length > 0) {
      const failedNames = failedEntries.map(([m]) => marketLabel(m)).join(', ');
      message.warning(`저장됨${syncedMsg}. 단, ${failedEntries.length}개 마켓 반영 실패: ${failedNames}`, 6);
    } else if (synced.length > 0) {
      message.success(`수정 완료${syncedMsg}`);
    } else if (skipped.length > 0) {
      message.success(`수정 완료 (연동 마켓 없음: ${skipped.map(marketLabel).join(', ')})`);
    } else {
      message.success('수정 완료 (연동된 마켓 없음)');
    }
  };

  // D-036: 파일 업로드 (multipart → uploadImages)
  const handleFilesSelected = async (files: FileList | null) => {
    if (!detailModal.id || !files || files.length === 0) return;
    const formData = new FormData();
    Array.from(files).forEach((f) => formData.append('images', f));
    setUploading(true);
    try {
      const res = await productApi.uploadImages(detailModal.id, formData);
      surfaceUploadResult(`${files.length}개 이미지 업로드 완료`, res.data as ImageUploadResult);
      await refreshDetail(detailModal.id);
    } catch {
      // D-020: R2 미설정 등 서버 스토리지 이슈일 수 있으므로 명확한 안내(조용한 실패 금지)
      message.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  // D-036: URL 입력 등록 (uploadImagesByUrl)
  const handleUploadByUrl = async () => {
    if (!detailModal.id) return;
    const urls = urlInput.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
    if (urls.length === 0) {
      message.warning('이미지 URL을 입력하세요 (줄바꿈 또는 쉼표로 구분).');
      return;
    }
    setUploading(true);
    try {
      const res = await productApi.uploadImagesByUrl(detailModal.id, urls);
      surfaceUploadResult(`${urls.length}개 이미지 등록 완료`, res.data as ImageUploadResult);
      setUrlInput('');
      await refreshDetail(detailModal.id);
    } catch {
      message.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally {
      setUploading(false);
    }
  };

  // D-036(선택): 소싱처 소스 이미지 크롤 → URL 입력창에 채워 검토 후 등록
  // D-049(결정①): 크롤러(IherbScraperClient)가 iHerb 전용이라 비-iHerb 벤더는 미지원 — 무음 실패 대신 명시적 안내.
  const handleCrawl = async () => {
    if (!detailModal.id) return;
    if (detailModal.data?.vendor !== 'IHB') {
      message.warning('이 벤더는 아직 소스이미지 크롤을 지원하지 않습니다 (현재 iHerb 상품만 지원).');
      return;
    }
    setUploading(true);
    try {
      const res = await productApi.crawlSourceImages(detailModal.id);
      const urls = (res.data as string[]) || [];
      if (urls.length === 0) {
        // D-078: 빈 결과 무음 제거 — 왜 비었는지(소싱 URL/크롤 실패) 사용자에게 안내.
        message.warning('소스이미지를 찾지 못했습니다 (소싱 URL/크롤 확인).');
        return;
      }
      setUrlInput(urls.join('\n'));
      message.success(`${urls.length}개 소스 이미지를 불러왔습니다. 'URL로 등록'을 눌러 반영하세요.`);
    } catch {
      message.error('소스 이미지 크롤에 실패했습니다.');
    } finally {
      setUploading(false);
    }
  };

  const columnDefs: ColDef<ProductList>[] = [
    {
      headerName: '이미지',
      field: 'repImageUrl',
      width: 72,
      cellStyle: { display: 'flex', alignItems: 'center', justifyContent: 'center' },
      cellRenderer: (params: { value?: string }) =>
        params.value ? (
          <img
            src={params.value}
            style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 8, border: '1px solid #e5e7eb' }}
          />
        ) : (
          <div style={{ width: 44, height: 44, borderRadius: 8, background: '#f1f5f9', border: '1px solid #e5e7eb' }} />
        ),
    },
    { headerName: 'SB코드', field: 'sbCode', width: 120, cellStyle: { fontWeight: 600, color: '#475569' } },
    { headerName: '브랜드', field: 'brand', width: 100, cellStyle: { color: '#64748b' } },
    {
      headerName: '상품명',
      field: 'productName',
      flex: 1,
      minWidth: 200,
      cellStyle: { cursor: 'pointer', color: '#1677ff' },
      onCellClicked: (e: CellClickedEvent<ProductList>) => {
        if (e.data) openDetailModal(e.data.id);
      },
    },
    { headerName: '소싱처', field: 'vendor', width: 80, cellStyle: { color: '#64748b' } },
    { headerName: '판매가', field: 'salePrice', width: 110, type: 'rightAligned',
      cellStyle: { fontWeight: 600, color: '#0f172a', justifyContent: 'flex-end' },
      valueFormatter: (p: { value?: number }) => p.value ? `${p.value.toLocaleString()}원` : '-' },
    { headerName: '재고', field: 'stock', width: 80, type: 'rightAligned',
      cellStyle: { justifyContent: 'flex-end', color: '#334155' } },
    // D-047: 마켓별 연동코드 컬럼(클릭 시 마켓 상품 페이지 새 탭)
    ...MARKET_COLUMNS.map((m): ColDef<ProductList> => ({
      headerName: m.header,
      width: 90,
      sortable: false,
      cellRenderer: renderMarketCell(m.key),
    })),
    {
      headerName: '관리',
      width: 110,
      sortable: false,
      cellStyle: { display: 'flex', alignItems: 'center', justifyContent: 'center' },
      cellRenderer: (params: { data?: ProductList }) =>
        params.data ? (
          <button className="sb-pricestock-btn" onClick={() => openPriceStockModal(params.data!)}>가격/재고</button>
        ) : null,
    },
  ];

  const d = detailModal.data;

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', padding: '16px 24px', background: '#f8f9fa' }}>
      <ProductGridStyle />

      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        background: '#fff', border: '1px solid #e5e7eb', borderRadius: 10,
        padding: '12px 16px', marginBottom: 12, boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ fontSize: 16, fontWeight: 700, color: 'var(--primary-color)' }}>상품 관리</span>
          <span style={{ fontSize: 13, color: '#94a3b8' }}>총 {totalCount.toLocaleString()}개</span>
        </div>
        <Space>
          <Input
            placeholder="상품명, SB코드, 브랜드 검색"
            prefix={<SearchOutlined style={{ color: '#94a3b8' }} />}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={() => loadData(0, pageSize, keyword)}
            style={{ width: 320 }}
            allowClear
          />
          <Button type="primary" onClick={() => loadData(0, pageSize, keyword)}>검색</Button>
          <Button icon={<ReloadOutlined />} onClick={() => { setKeyword(''); loadData(0, pageSize); }}>새로고침</Button>
        </Space>
      </div>

      <div className="ag-theme-quartz sb-product-grid" style={{ flex: 1, minHeight: 400 }}>
        <AgGridReact
          rowData={rowData}
          columnDefs={columnDefs}
          pagination={false}
          rowHeight={56}
          headerHeight={44}
          defaultColDef={{ sortable: true, resizable: true }}
        />
      </div>

      {/* D-048: 서버사이드 페이징 — ag-Grid 내장 페이징(clientSide) 대신 antd Pagination으로 백엔드 페이지 이동 배선. 검색어(keyword)는 페이지 이동 시에도 유지. */}
      <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ color: '#888' }}>총 {totalCount}개 상품</span>
        <Pagination
          current={currentPage + 1}
          pageSize={pageSize}
          total={totalCount}
          showSizeChanger
          pageSizeOptions={[20, 50, 100, 200]}
          showQuickJumper
          onChange={(page, size) => loadData(page - 1, size, keyword)}
        />
      </div>

      <Modal
        title="가격/재고 수정"
        open={priceStockModal.visible}
        onCancel={() => setPriceStockModal({ visible: false })}
        onOk={async () => {
          if (priceStockModal.id !== undefined) {
            try {
              const res = await productApi.updatePriceStock(priceStockModal.id, priceStockModal.price || 0, priceStockModal.soldOut === true);
              // D-060: 마켓 동기화 결과(성공/실패 마켓)를 표면화. 자사 DB는 저장됐어도 일부 마켓 반영 실패를 조용히 삼키지 않는다.
              surfacePriceStockResult(res.data as PriceStockSyncResult);
              setPriceStockModal({ visible: false });
              loadData(currentPage, pageSize, keyword);
            } catch {
              message.error('수정 실패');
            }
          }
        }}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <div>
            <label>판매가: </label>
            <InputNumber min={0} value={priceStockModal.price} onChange={(v) => setPriceStockModal({ ...priceStockModal, price: v || 0 })} />
          </div>
          <div>
            <label>판매상태: </label>
            <Switch
              checked={priceStockModal.soldOut === true}
              checkedChildren="품절"
              unCheckedChildren="판매중"
              onChange={(checked) => setPriceStockModal({ ...priceStockModal, soldOut: checked })}
            />
          </div>
        </Space>
      </Modal>

      {/* D-035: 상품 상세 모달 */}
      <Modal
        title={d ? `상품 상세 · ${d.productName}` : '상품 상세'}
        open={detailModal.visible}
        onCancel={() => setDetailModal({ visible: false, loading: false })}
        footer={<Button onClick={() => setDetailModal({ visible: false, loading: false })}>닫기</Button>}
        width={860}
      >
        {detailModal.loading || !d ? (
          <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
        ) : (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions title="기본 정보" bordered size="small" column={2}>
              <Descriptions.Item label="SB코드">{d.sbCode}</Descriptions.Item>
              <Descriptions.Item label="브랜드">{d.brand}</Descriptions.Item>
              <Descriptions.Item label="상품명" span={2}>{d.productName}</Descriptions.Item>
              <Descriptions.Item label="기본명">{d.baseName}</Descriptions.Item>
              <Descriptions.Item label="원문명">{d.originalName}</Descriptions.Item>
              <Descriptions.Item label="카테고리">{d.category}</Descriptions.Item>
              <Descriptions.Item label="소싱처">{d.vendor}</Descriptions.Item>
            </Descriptions>

            <Descriptions title="가격" bordered size="small" column={3}>
              <Descriptions.Item label="원가">{d.priceInfo?.costPrice?.toLocaleString()}원</Descriptions.Item>
              <Descriptions.Item label="판매가">{d.priceInfo?.salePrice?.toLocaleString()}원</Descriptions.Item>
              <Descriptions.Item label="마진율">{d.priceInfo?.marginRate}%</Descriptions.Item>
            </Descriptions>

            <Descriptions title="물류" bordered size="small" column={3}>
              <Descriptions.Item label="재고">{d.logisticsInfo?.stock}</Descriptions.Item>
              <Descriptions.Item label="무게">{d.logisticsInfo?.weight}</Descriptions.Item>
              <Descriptions.Item label="묶음수량">{d.logisticsInfo?.bundleQuantity}</Descriptions.Item>
            </Descriptions>

            <Descriptions title="스펙" bordered size="small" column={3}>
              <Descriptions.Item label="바코드">{d.productSpec?.barcode}</Descriptions.Item>
              <Descriptions.Item label="용량">{d.productSpec?.capacity}</Descriptions.Item>
              <Descriptions.Item label="단위">{d.productSpec?.measureUnit}</Descriptions.Item>
            </Descriptions>

            <Descriptions title="소싱" bordered size="small" column={2}>
              <Descriptions.Item label="소싱처">{d.sourcingInfo?.vendor}</Descriptions.Item>
              <Descriptions.Item label="제조사">{d.sourcingInfo?.manufacturer}</Descriptions.Item>
              <Descriptions.Item label="원산지">{d.sourcingInfo?.origin}</Descriptions.Item>
              <Descriptions.Item label="HS코드">{d.sourcingInfo?.hsCode}</Descriptions.Item>
              <Descriptions.Item label="소스 URL" span={2}>
                {d.sourcingInfo?.sourceUrl ? (
                  <a href={d.sourcingInfo.sourceUrl} target="_blank" rel="noopener noreferrer">{d.sourcingInfo.sourceUrl}</a>
                ) : '-'}
              </Descriptions.Item>
            </Descriptions>

            {/* D-036: 이미지 변경 섹션 */}
            <div>
              <Typography.Title level={5}>이미지</Typography.Title>
              <Typography.Text type="secondary">등록 이미지 (hosted)</Typography.Text>
              <div style={{ marginTop: 8, marginBottom: 12 }}>
                {d.hostedImages && d.hostedImages.length > 0 ? (
                  <Image.PreviewGroup>
                    <Space wrap>
                      {d.hostedImages.map((url, i) => (
                        <Image key={`h-${i}`} src={url} width={72} height={72} style={{ objectFit: 'cover', borderRadius: 4 }} />
                      ))}
                    </Space>
                  </Image.PreviewGroup>
                ) : (
                  <Typography.Text type="secondary"> 없음</Typography.Text>
                )}
              </div>
              {d.sourceImages && d.sourceImages.length > 0 && (
                <>
                  <Typography.Text type="secondary">소스 이미지 (source)</Typography.Text>
                  <div style={{ marginTop: 8, marginBottom: 12 }}>
                    <Image.PreviewGroup>
                      <Space wrap>
                        {d.sourceImages.map((url, i) => (
                          <Image key={`s-${i}`} src={url} width={72} height={72} style={{ objectFit: 'cover', borderRadius: 4 }} />
                        ))}
                      </Space>
                    </Image.PreviewGroup>
                  </div>
                </>
              )}

              <Divider style={{ margin: '12px 0' }} />

              <Space direction="vertical" style={{ width: '100%' }} size="small">
                <Space wrap>
                  {/* 파일 업로드 (multipart) */}
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/*"
                    multiple
                    style={{ display: 'none' }}
                    onChange={(e) => handleFilesSelected(e.target.files)}
                  />
                  <Button icon={<UploadOutlined />} loading={uploading} onClick={() => fileInputRef.current?.click()}>
                    파일 업로드
                  </Button>
                  {/* D-078: disabled 버튼은 antd Tooltip이 안 떠 "먹통"으로 체감 → 항상 클릭 가능하게 두고
                      handleCrawl 내 비-iHerb warning으로 사유를 안내(무음 실패 제거). Tooltip은 보조 안내로 유지. */}
                  <Tooltip
                    title={d.vendor !== 'IHB'
                      ? '이 벤더는 아직 소스이미지 크롤을 지원하지 않습니다 (현재 iHerb 상품만 지원).'
                      : ''}
                  >
                    <Button
                      icon={<CloudDownloadOutlined />}
                      loading={uploading}
                      onClick={handleCrawl}
                    >
                      소스 이미지 크롤
                    </Button>
                  </Tooltip>
                </Space>
                <Input.TextArea
                  placeholder="이미지 URL을 줄바꿈 또는 쉼표로 구분해 입력하세요"
                  value={urlInput}
                  onChange={(e) => setUrlInput(e.target.value)}
                  rows={3}
                />
                <Button type="primary" icon={<LinkOutlined />} loading={uploading} onClick={handleUploadByUrl}>
                  URL로 등록
                </Button>
              </Space>
            </div>

            {d.memo && (
              <Descriptions title="메모" bordered size="small" column={1}>
                <Descriptions.Item label="메모">{d.memo}</Descriptions.Item>
              </Descriptions>
            )}

            {d.detailHtml && (
              <Collapse
                items={[{
                  key: 'detailHtml',
                  label: '상세 설명 (HTML)',
                  children: (
                    // XSS 방지: 스크립트 실행이 차단된 sandbox iframe에 srcDoc으로 렌더(신뢰 불가 HTML 대비)
                    <iframe
                      title="detailHtml"
                      sandbox=""
                      srcDoc={d.detailHtml}
                      style={{ width: '100%', height: 400, border: '1px solid #eee' }}
                    />
                  ),
                }]}
              />
            )}
          </Space>
        )}
      </Modal>
    </div>
  );
};

export default ProductPage;
