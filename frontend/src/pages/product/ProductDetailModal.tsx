import { useEffect, useRef, useState } from 'react';
import {
  Modal, Form, Input, InputNumber, Button, Space, Spin, Image, Divider,
  Typography, Collapse, message, Tooltip, Popconfirm,
} from 'antd';
import { UploadOutlined, LinkOutlined, CloudDownloadOutlined } from '@ant-design/icons';
import { productApi, type ProductDetail, type ImageUploadResult, type ProductEditFields } from '../../api/productApi';
import { updateProductFields } from './productMockApi';

const { TextArea } = Input;

export function ProductDetailModal({ productId, open, onClose, onSaved }: {
  productId: number | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<ProductEditFields>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [detail, setDetail] = useState<ProductDetail | null>(null);
  const [urlInput, setUrlInput] = useState('');
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open || productId == null) return;
    // 모달 오픈 시 상세를 비동기 로드하기 위한 로딩 시딩. 기존 모달(Settings 등)과 동일 패턴이라
    // set-state-in-effect 규칙만 이 라인에 한정 완화한다(코드베이스 baseline 동일 클래스).
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setUrlInput('');
    productApi.fetchProductDetail(productId)
      .then((res) => {
        const d = res.data as ProductDetail;
        setDetail(d);
        form.setFieldsValue({
          brand: d.brand, productName: d.productName, baseName: d.baseName, originalName: d.originalName,
          category: d.category, costPrice: d.priceInfo?.costPrice, salePrice: d.priceInfo?.salePrice,
          marginRate: d.priceInfo?.marginRate, stock: d.logisticsInfo?.stock, weight: d.logisticsInfo?.weight,
          bundleQuantity: d.logisticsInfo?.bundleQuantity, barcode: d.productSpec?.barcode,
          capacity: d.productSpec?.capacity, measureUnit: d.productSpec?.measureUnit,
          vendor: d.sourcingInfo?.vendor, manufacturer: d.sourcingInfo?.manufacturer,
          origin: d.sourcingInfo?.origin, hsCode: d.sourcingInfo?.hsCode, sourceUrl: d.sourcingInfo?.sourceUrl,
          memo: d.memo, detailHtml: d.detailHtml,
        });
      })
      .catch(() => message.error('상품 상세 조회에 실패했습니다.'))
      .finally(() => setLoading(false));
  }, [open, productId, form]);

  const refreshDetail = async () => {
    if (productId == null) return;
    try {
      const res = await productApi.fetchProductDetail(productId);
      setDetail(res.data as ProductDetail);
    } catch { message.error('상세 정보 갱신 실패'); }
  };

  const handleSave = async () => {
    if (productId == null) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      await updateProductFields(productId, values); // MOCK
      message.success('상품 정보 저장됨 (백엔드 반영은 다음 세션 구현)');
      onSaved();
      onClose();
    } catch {
      message.error('상품 정보 저장 실패');
    } finally {
      setSaving(false);
    }
  };

  const handleFilesSelected = async (files: FileList | null) => {
    if (productId == null || !files || files.length === 0) return;
    const fd = new FormData();
    Array.from(files).forEach((f) => fd.append('images', f));
    setUploading(true);
    try {
      const res = await productApi.uploadImages(productId, fd);
      const r = res.data as ImageUploadResult;
      message.success(`${r.imagesSucceeded}장 업로드 완료`);
      await refreshDetail();
    } catch {
      message.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleUploadByUrl = async () => {
    if (productId == null) return;
    const urls = urlInput.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
    if (urls.length === 0) { message.warning('이미지 URL을 입력하세요.'); return; }
    setUploading(true);
    try {
      await productApi.uploadImagesByUrl(productId, urls);
      message.success(`${urls.length}개 이미지 등록 완료`);
      setUrlInput('');
      await refreshDetail();
    } catch {
      message.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally { setUploading(false); }
  };

  const handleCrawl = async () => {
    if (productId == null) return;
    if (detail?.sourcingInfo?.vendor !== 'IHB') {
      message.warning('이 벤더는 아직 소스이미지 크롤을 지원하지 않습니다 (현재 iHerb 상품만 지원).');
      return;
    }
    setUploading(true);
    try {
      await productApi.crawlAndUpload(productId);
      message.success('소스이미지 크롤·업로드 완료');
      await refreshDetail();
    } catch { message.error('소스 이미지 크롤·업로드에 실패했습니다.'); }
    finally { setUploading(false); }
  };

  const num = (label: string, name: keyof ProductEditFields) => (
    <Form.Item label={label} name={name} style={{ marginBottom: 8 }}>
      <InputNumber style={{ width: '100%' }} />
    </Form.Item>
  );
  const txt = (label: string, name: keyof ProductEditFields, span2 = false) => (
    <Form.Item label={label} name={name} style={{ marginBottom: 8, gridColumn: span2 ? '1 / -1' : undefined }}>
      <Input />
    </Form.Item>
  );

  return (
    <Modal
      title={detail ? `상품 편집 · ${detail.productName}` : '상품 편집'}
      open={open}
      onCancel={onClose}
      width={880}
      footer={[
        <Button key="cancel" onClick={onClose}>닫기</Button>,
        <Button key="save" type="primary" loading={saving} onClick={handleSave}
          style={{ background: '#166534', borderColor: '#166534' }}>저장</Button>,
      ]}
    >
      {loading || !detail ? (
        <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
      ) : (
        <Form form={form} layout="vertical" size="small">
          <Typography.Text type="secondary">SB코드: {detail.sbCode}</Typography.Text>
          <Divider style={{ margin: '10px 0' }}>기본 정보</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
            {txt('브랜드', 'brand')}
            {txt('카테고리', 'category')}
            {txt('상품명', 'productName', true)}
            {txt('기본명', 'baseName')}
            {txt('원문명', 'originalName')}
          </div>

          <Divider style={{ margin: '10px 0' }}>가격</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0 16px' }}>
            {num('원가', 'costPrice')}{num('판매가', 'salePrice')}{num('마진율(%)', 'marginRate')}
          </div>

          <Divider style={{ margin: '10px 0' }}>물류·스펙</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0 16px' }}>
            {num('재고', 'stock')}{num('무게', 'weight')}{num('묶음수량', 'bundleQuantity')}
            {txt('바코드', 'barcode')}{num('용량', 'capacity')}{txt('단위', 'measureUnit')}
          </div>

          <Divider style={{ margin: '10px 0' }}>소싱</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
            {txt('소싱처', 'vendor')}{txt('제조사', 'manufacturer')}
            {txt('원산지', 'origin')}{txt('HS코드', 'hsCode')}
            {txt('소스 URL', 'sourceUrl', true)}
          </div>

          <Form.Item label="메모" name="memo" style={{ marginTop: 8 }}>
            <TextArea rows={2} />
          </Form.Item>

          <Divider style={{ margin: '10px 0' }}>이미지</Divider>
          <Typography.Text type="secondary">등록 이미지 (hosted)</Typography.Text>
          <div style={{ marginTop: 8, marginBottom: 12 }}>
            {detail.hostedImages && detail.hostedImages.length > 0 ? (
              <Image.PreviewGroup>
                <Space wrap>{detail.hostedImages.map((url, i) => (
                  <Image key={`h-${i}`} src={url} width={72} height={72} style={{ objectFit: 'cover', borderRadius: 4 }} />
                ))}</Space>
              </Image.PreviewGroup>
            ) : <Typography.Text type="secondary"> 없음</Typography.Text>}
          </div>
          <Space direction="vertical" style={{ width: '100%' }} size="small">
            <Space wrap>
              <input ref={fileInputRef} type="file" accept="image/*" multiple style={{ display: 'none' }}
                onChange={(e) => handleFilesSelected(e.target.files)} />
              <Button icon={<UploadOutlined />} loading={uploading} onClick={() => fileInputRef.current?.click()}>파일 업로드</Button>
              <Popconfirm title="소스 이미지 크롤·업로드"
                description="크롤한 이미지를 R2에 업로드하고 연동된 모든 마켓에 재게시합니다. 진행할까요?"
                okText="진행" cancelText="취소" onConfirm={handleCrawl}>
                <Tooltip title={detail.sourcingInfo?.vendor !== 'IHB' ? '현재 iHerb 상품만 지원' : ''}>
                  <Button icon={<CloudDownloadOutlined />} loading={uploading}>소스 이미지 크롤</Button>
                </Tooltip>
              </Popconfirm>
            </Space>
            <TextArea placeholder="이미지 URL을 줄바꿈 또는 쉼표로 구분해 입력" value={urlInput}
              onChange={(e) => setUrlInput(e.target.value)} rows={2} />
            <Button type="primary" icon={<LinkOutlined />} loading={uploading} onClick={handleUploadByUrl}
              style={{ background: '#166534', borderColor: '#166534' }}>URL로 등록</Button>
          </Space>

          {detail.detailHtml && (
            <Collapse style={{ marginTop: 12 }} items={[{
              key: 'detailHtml', label: '상세 설명 (HTML, 읽기전용)',
              children: <iframe title="detailHtml" sandbox="" srcDoc={detail.detailHtml}
                style={{ width: '100%', height: 320, border: '1px solid #eee' }} />,
            }]} />
          )}
        </Form>
      )}
    </Modal>
  );
}
