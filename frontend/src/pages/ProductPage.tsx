import { useState, useCallback, useRef } from 'react';
import { AgGridReact } from 'ag-grid-react';
import type { ColDef, CellClickedEvent } from 'ag-grid-community';
import { Input, Button, Space, Modal, InputNumber, message, Descriptions, Image, Spin, Collapse, Typography, Divider } from 'antd';
import { SearchOutlined, ReloadOutlined, UploadOutlined, LinkOutlined, CloudDownloadOutlined } from '@ant-design/icons';
import { productApi, type ProductList, type ProductDetail } from '../api/productApi';

const ProductPage = () => {
  const [rowData, setRowData] = useState<ProductList[]>([]);
  const [keyword, setKeyword] = useState('');
  const [totalCount, setTotalCount] = useState(0);
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [, setLoading] = useState(false);
  const [priceStockModal, setPriceStockModal] = useState<{ visible: boolean; id?: number; price?: number; stock?: number }>({ visible: false });

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
      stock: product.stock ?? 0,
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

  // D-036: 파일 업로드 (multipart → uploadImages)
  const handleFilesSelected = async (files: FileList | null) => {
    if (!detailModal.id || !files || files.length === 0) return;
    const formData = new FormData();
    Array.from(files).forEach((f) => formData.append('images', f));
    setUploading(true);
    try {
      await productApi.uploadImages(detailModal.id, formData);
      message.success(`${files.length}개 이미지 업로드 완료`);
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
      await productApi.uploadImagesByUrl(detailModal.id, urls);
      message.success(`${urls.length}개 이미지 등록 완료`);
      setUrlInput('');
      await refreshDetail(detailModal.id);
    } catch {
      message.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally {
      setUploading(false);
    }
  };

  // D-036(선택): 소싱처 소스 이미지 크롤 → URL 입력창에 채워 검토 후 등록
  const handleCrawl = async () => {
    if (!detailModal.id) return;
    setUploading(true);
    try {
      const res = await productApi.crawlSourceImages(detailModal.id);
      const urls = (res.data as string[]) || [];
      if (urls.length === 0) {
        message.info('크롤된 소스 이미지가 없습니다 (소싱 URL을 확인하세요).');
        return;
      }
      setUrlInput(urls.join('\n'));
      message.success(`${urls.length}개 소스 이미지를 찾았습니다. 'URL로 등록'을 눌러 반영하세요.`);
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
      width: 80,
      cellRenderer: (params: { value?: string }) =>
        params.value ? (
          <img src={params.value} style={{ width: 50, height: 50, objectFit: 'cover' }} />
        ) : null,
    },
    { headerName: 'SB코드', field: 'sbCode', width: 120 },
    { headerName: '브랜드', field: 'brand', width: 100 },
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
    { headerName: '소싱처', field: 'vendor', width: 80 },
    { headerName: '판매가', field: 'salePrice', width: 100, valueFormatter: (p: { value?: number }) => p.value ? `${p.value.toLocaleString()}원` : '' },
    { headerName: '재고', field: 'stock', width: 80 },
    {
      headerName: '관리',
      width: 120,
      cellRenderer: (params: { data?: ProductList }) =>
        params.data ? (
          <button onClick={() => openPriceStockModal(params.data!)}>가격/재고</button>
        ) : null,
    },
  ];

  const d = detailModal.data;

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <Space style={{ marginBottom: 16 }}>
        <Input
          placeholder="상품명, SB코드, 브랜드 검색"
          prefix={<SearchOutlined />}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={() => loadData(0, pageSize, keyword)}
          style={{ width: 300 }}
        />
        <Button type="primary" onClick={() => loadData(0, pageSize, keyword)}>검색</Button>
        <Button icon={<ReloadOutlined />} onClick={() => { setKeyword(''); loadData(0, pageSize); }}>새로고침</Button>
      </Space>

      <div className="ag-theme-quartz" style={{ flex: 1, minHeight: 400 }}>
        <AgGridReact
          rowData={rowData}
          columnDefs={columnDefs}
          pagination={true}
          paginationPageSize={pageSize}
          defaultColDef={{ sortable: true, resizable: true }}
        />
      </div>

      <div style={{ marginTop: 8, color: '#888' }}>총 {totalCount}개 상품</div>

      <Modal
        title="가격/재고 수정"
        open={priceStockModal.visible}
        onCancel={() => setPriceStockModal({ visible: false })}
        onOk={async () => {
          if (priceStockModal.id !== undefined) {
            try {
              await productApi.updatePriceStock(priceStockModal.id, priceStockModal.price || 0, priceStockModal.stock || 0);
              message.success('수정 완료');
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
            <InputNumber value={priceStockModal.price} onChange={(v) => setPriceStockModal({ ...priceStockModal, price: v || 0 })} />
          </div>
          <div>
            <label>재고: </label>
            <InputNumber value={priceStockModal.stock} onChange={(v) => setPriceStockModal({ ...priceStockModal, stock: v || 0 })} />
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
                  <Button icon={<CloudDownloadOutlined />} loading={uploading} onClick={handleCrawl}>
                    소스 이미지 크롤
                  </Button>
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
