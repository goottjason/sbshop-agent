import { useEffect, useRef, useState } from 'react';
import { Modal, Image, Collapse, Tooltip, Popconfirm } from 'antd';
import { UploadOutlined, LinkOutlined, CloudDownloadOutlined } from '@ant-design/icons';
import { productApi, type ProductDetail, type ImageUploadResult, type ProductEditFields } from '../../api/productApi';
import { notify } from '../../utils/notify';

type Fields = Partial<ProductEditFields>;

type Opt = { value: string; label: string };

const GREEN = '#166534';

const CATEGORY_OPTIONS: Opt[] = [
  { value: 'SUPPLEMENT', label: '영양제' },
  { value: 'FOOD', label: '식품' },
  { value: 'COSMETICS', label: '화장품' },
  { value: 'UNKNOWN', label: '기타' },
];

const VENDOR_OPTIONS: Opt[] = ['IHB', 'AMZ', 'FTN', 'COK', 'OCD', 'TES', 'VTB'].map((v) => ({ value: v, label: v }));

const MEASURE_UNIT_OPTIONS: Opt[] = [
  ['EA', '개'], ['CAPSULE', '캡슐'], ['TABLET', '정(타블렛)'], ['PIECE', '조각'], ['PACK', '팩'],
  ['BOX', '박스'], ['BOTTLE', '병'], ['T_BAG', '티백'], ['COUNT', '개(COUNT)'],
  ['MG', '밀리그램'], ['G', '그램'], ['KG', '킬로그램'], ['OZ', '온스'], ['LB', '파운드'],
  ['ML', '밀리리터'], ['L', '리터'], ['UNKNOWN', '기타/미지정'],
].map(([value, label]) => ({ value, label }));

function extractErrorMessage(e: unknown): string {
  const data = (e as { response?: { data?: unknown } })?.response?.data;
  if (typeof data === 'string' && data) return data;
  if (data && typeof data === 'object' && typeof (data as { message?: unknown }).message === 'string') {
    return (data as { message: string }).message;
  }
  if (e instanceof Error && e.message) return e.message;
  return '알 수 없는 오류';
}

function safeHttpUrl(value: string | number | undefined): string | null {
  if (typeof value !== 'string' || value.trim() === '') return null;
  try {
    const u = new URL(value.trim());
    return (u.protocol === 'http:' || u.protocol === 'https:') ? u.href : null;
  } catch {
    return null;
  }
}

function toFields(d: ProductDetail): Fields {
  return {
    brand: d.brand, productName: d.productName, baseName: d.baseName, originalName: d.originalName,
    category: d.category, costPrice: d.priceInfo?.costPrice, salePrice: d.priceInfo?.salePrice,
    marginRate: d.priceInfo?.marginRate, stock: d.logisticsInfo?.stock, weight: d.logisticsInfo?.weight,
    bundleQuantity: d.logisticsInfo?.bundleQuantity, barcode: d.productSpec?.barcode,
    capacity: d.productSpec?.capacity, measureUnit: d.productSpec?.measureUnit,
    vendor: d.sourcingInfo?.vendor, manufacturer: d.sourcingInfo?.manufacturer,
    origin: d.sourcingInfo?.origin, hsCode: d.sourcingInfo?.hsCode, sourceUrl: d.sourcingInfo?.sourceUrl,
    memo: d.memo, detailHtml: d.detailHtml,
  };
}

function EditRow({ label, value, type = 'text', full = false, link = false, onChange }: {
  label: string;
  value: string | number | undefined;
  type?: 'text' | 'number';
  full?: boolean;
  link?: boolean;
  onChange: (v: string | number | undefined) => void;
}) {
  const href = link ? safeHttpUrl(value) : null;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 0', gridColumn: full ? '1 / -1' : undefined, borderBottom: '1px solid #f4f4f5' }}>
      <span style={{ color: '#9ca3af', fontSize: 13 }}>•</span>
      <span style={{ color: '#6b7280', fontSize: 13, whiteSpace: 'nowrap', flexShrink: 0 }}>{label}</span>
      <input
        className="pd-inp"
        type={type}
        value={value ?? ''}
        onChange={(e) => onChange(type === 'number' ? (e.target.value === '' ? undefined : Number(e.target.value)) : e.target.value)}
      />
      {link && (
        href ? (
          <Tooltip title="새 탭으로 열기">
            <a href={href} target="_blank" rel="noopener noreferrer" className="pd-openlink" aria-label={`${label} 새 탭으로 열기`}>
              <LinkOutlined /> 열기
            </a>
          </Tooltip>
        ) : (
          <span className="pd-openlink pd-openlink-off" title="열 수 있는 http(s) 주소가 아닙니다">
            <LinkOutlined /> 열기
          </span>
        )
      )}
    </div>
  );
}

function EditSelectRow({ label, value, options, full = false, onChange }: {
  label: string;
  value: string | undefined;
  options: Opt[];
  full?: boolean;
  onChange: (v: string) => void;
}) {
  const hasValue = value != null && value !== '';
  const known = options.some((o) => o.value === value);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 0', gridColumn: full ? '1 / -1' : undefined, borderBottom: '1px solid #f4f4f5' }}>
      <span style={{ color: '#9ca3af', fontSize: 13 }}>•</span>
      <span style={{ color: '#6b7280', fontSize: 13, whiteSpace: 'nowrap', flexShrink: 0 }}>{label}</span>
      <select className="pd-inp pd-sel" value={value ?? ''} onChange={(e) => onChange(e.target.value)}>
        <option value="">— 선택 —</option>
        {hasValue && !known && <option value={value}>{value}</option>}
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}

export function ProductDetailModal({ productId, open, onClose, onSaved }: {
  productId: number | null;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [detail, setDetail] = useState<ProductDetail | null>(null);
  const [fields, setFields] = useState<Fields>({});
  const [baseline, setBaseline] = useState<Fields>({});
  const [urlInput, setUrlInput] = useState('');
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open || productId == null) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setUrlInput('');
    productApi.fetchProductDetail(productId)
      .then((res) => {
        const d = res.data as ProductDetail;
        const f = toFields(d);
        setDetail(d);
        setFields(f);
        setBaseline(f);
      })
      .catch(() => notify.error('상품 상세 조회에 실패했습니다.'))
      .finally(() => setLoading(false));
  }, [open, productId]);

  const set = <K extends keyof Fields>(name: K, value: Fields[K]) => setFields((f) => ({ ...f, [name]: value }));

  const dirty = JSON.stringify(fields) !== JSON.stringify(baseline);

  const refreshDetail = async () => {
    if (productId == null) return;
    try {
      const res = await productApi.fetchProductDetail(productId);
      const f = toFields(res.data as ProductDetail);
      setDetail(res.data as ProductDetail);
      setFields(f);
      setBaseline(f);
    } catch { notify.error('상세 정보 갱신 실패'); }
  };

  const handleSave = async () => {
    if (productId == null) return;
    setSaving(true);
    try {
      const origSale = detail?.priceInfo?.salePrice;
      if (fields.salePrice != null && fields.salePrice !== origSale) {
        await productApi.updatePriceStock(productId, fields.salePrice, null);
      }
      const { productName, ...rest } = fields;
      await productApi.updateProduct(productId, { ...rest, name: productName });
      notify.success('상품 정보가 저장되었습니다.');
      onSaved();
      onClose();
    } catch (e) {
      notify.error(`상품 정보 저장 실패: ${extractErrorMessage(e)}`);
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
      notify.success(`${r.imagesSucceeded}장 업로드 완료`);
      await refreshDetail();
    } catch {
      notify.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleUploadByUrl = async () => {
    if (productId == null) return;
    const urls = urlInput.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
    if (urls.length === 0) { notify.warning('이미지 URL을 입력하세요.'); return; }
    setUploading(true);
    try {
      await productApi.uploadImagesByUrl(productId, urls);
      notify.success(`${urls.length}개 이미지 등록 완료`);
      setUrlInput('');
      await refreshDetail();
    } catch {
      notify.error('이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요.');
    } finally { setUploading(false); }
  };

  const handleCrawl = async () => {
    if (productId == null) return;
    if (detail?.sourcingInfo?.vendor !== 'IHB') {
      notify.warning('이 벤더는 아직 소스이미지 크롤을 지원하지 않습니다 (현재 iHerb 상품만 지원).');
      return;
    }
    setUploading(true);
    try {
      await productApi.crawlAndUpload(productId);
      notify.success('소스이미지 크롤·업로드 완료');
      await refreshDetail();
    } catch { notify.error('소스 이미지 크롤·업로드에 실패했습니다.'); }
    finally { setUploading(false); }
  };

  const row = (label: string, name: keyof Fields, type: 'text' | 'number' = 'text', full = false,
    link = false) => (
    <EditRow label={label} value={fields[name] as string | number | undefined} type={type} full={full}
      link={link} onChange={(v) => set(name, v as Fields[typeof name])} />
  );

  const selectRow = (label: string, name: keyof Fields, options: Opt[], full = false) => (
    <EditSelectRow label={label} value={fields[name] as string | undefined} options={options} full={full}
      onChange={(v) => set(name, (v === '' ? undefined : v) as Fields[typeof name])} />
  );

  const sectionTitle: React.CSSProperties = { fontSize: 12, fontWeight: 700, color: GREEN, letterSpacing: 0.3, margin: '14px 0 2px' };
  const grid2: React.CSSProperties = { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 28px' };

  const d = detail;

  return (
    <Modal
      open={open}
      onCancel={onClose}
      width={660}
      centered
      title={null}
      styles={{ body: { maxHeight: '72vh', overflowY: 'auto', padding: '4px 28px 8px' } }}
      footer={
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '4px 20px 4px' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minWidth: 64, fontSize: 12, fontWeight: 600,
            color: dirty ? GREEN : 'transparent', visibility: d ? 'visible' : 'hidden' }}>
            <span style={{ width: 7, height: 7, borderRadius: 999, background: dirty ? GREEN : 'transparent' }} />
            {dirty ? '변경됨' : ''}
          </span>
          <button onClick={onClose}
            style={{ flex: 1, padding: '11px 0', background: '#fff', color: '#374151', border: '1px solid #d1d5db', borderRadius: 8, fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>
            닫기
          </button>
          <button onClick={handleSave} disabled={saving || loading || !d || !dirty}
            style={{ flex: 1.4, padding: '11px 0', background: saving || loading || !d || !dirty ? '#9ca3af' : GREEN, color: '#fff', border: 'none', borderRadius: 8, fontSize: 14, fontWeight: 700, cursor: saving || loading || !d || !dirty ? 'default' : 'pointer' }}>
            {saving ? '저장 중…' : '저장'}
          </button>
        </div>
      }
    >
      <style>{`
        .pd-inp { flex: 1; min-width: 0; border: none; border-bottom: 1px solid transparent; background: transparent;
          text-align: right; font-weight: 600; color: #111827; font-size: 13px; outline: none; padding: 2px 0; }
        .pd-inp:hover { border-bottom-color: #e5e7eb; }
        .pd-inp:focus { border-bottom-color: ${GREEN}; }
        .pd-inp::placeholder { color: #cbd5e1; font-weight: 400; }
        .pd-openlink { flex-shrink: 0; display: inline-flex; align-items: center; gap: 4px;
          font-size: 12px; font-weight: 600; line-height: 1.6; padding: 1px 8px; border-radius: 4px;
          white-space: nowrap; text-decoration: none; color: ${GREEN}; background: #f1f8e9;
          border: 1px solid #dcedc8; transition: background .15s, border-color .15s; }
        .pd-openlink:hover { background: #e8f5e9; border-color: ${GREEN}; color: ${GREEN}; }
        .pd-openlink-off { color: #cbd5e1; background: #fff; border-color: #eef0f2; cursor: not-allowed; }
        .pd-sel { cursor: pointer; text-align: right; text-align-last: right; padding-right: 2px;
          appearance: none; -webkit-appearance: none; -moz-appearance: none; }
        .pd-sel:hover { border-bottom-color: #e5e7eb; }
        .pd-ta { width: 100%; border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px 10px; font-size: 13px;
          outline: none; resize: vertical; font-family: inherit; box-sizing: border-box; }
        .pd-ta:focus { border-color: ${GREEN}; }
        .pd-imgbtn { display: inline-flex; align-items: center; gap: 6px; padding: 7px 12px; border-radius: 8px;
          border: 1px solid #d1d5db; background: #fff; color: #374151; font-size: 13px; font-weight: 600; cursor: pointer; }
        .pd-imgbtn:hover { border-color: ${GREEN}; color: ${GREEN}; }
        .pd-imgbtn:disabled { opacity: 0.5; cursor: default; }
      `}</style>

      {loading || !d ? (
        <div style={{ textAlign: 'center', padding: 64, color: '#94a3b8' }}>불러오는 중…</div>
      ) : (
        <>
          <div style={{ textAlign: 'center', paddingTop: 14 }}>
            <div style={{ fontSize: 22, fontWeight: 800, color: '#111827', lineHeight: 1.25 }}>{d.productName || '상품 상세'}</div>
            <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 12, fontWeight: 700, color: GREEN, background: 'var(--product-badge-bg, #dcfce7)', border: `1px solid ${GREEN}`, borderRadius: 999, padding: '2px 10px' }}>
                {CATEGORY_OPTIONS.find((o) => o.value === d.category)?.label || d.category || '카테고리 없음'}
              </span>
              <span style={{ fontSize: 13, color: '#6b7280' }}>
                {d.sbCode}{d.brand ? ` · ${d.brand}` : ''}{d.sourcingInfo?.vendor ? ` · ${d.sourcingInfo.vendor}` : ''}
              </span>
            </div>
          </div>

          <div style={{ borderBottom: '2px solid #1f2937', margin: '16px 0 4px' }} />

          <div style={sectionTitle}>기본 정보</div>
          <div style={grid2}>
            {row('브랜드', 'brand')}
            {selectRow('카테고리', 'category', CATEGORY_OPTIONS)}
            {row('상품명', 'productName', 'text', true)}
            {row('기본명', 'baseName')}
            {row('원문명', 'originalName')}
          </div>

          <div style={sectionTitle}>가격</div>
          <div style={grid2}>
            {row('원가', 'costPrice', 'number')}
            {row('판매가', 'salePrice', 'number')}
            {row('마진율(%)', 'marginRate', 'number')}
          </div>

          <div style={sectionTitle}>물류 · 스펙</div>
          <div style={grid2}>
            {row('재고', 'stock', 'number')}
            {row('무게', 'weight', 'number')}
            {row('묶음수량', 'bundleQuantity', 'number')}
            {row('바코드', 'barcode')}
            {row('용량', 'capacity', 'number')}
            {selectRow('단위', 'measureUnit', MEASURE_UNIT_OPTIONS)}
          </div>

          <div style={sectionTitle}>소싱</div>
          <div style={grid2}>
            {selectRow('소싱처', 'vendor', VENDOR_OPTIONS)}
            {row('제조사', 'manufacturer')}
            {row('원산지', 'origin')}
            {row('HS코드', 'hsCode')}
            {row('소스 URL', 'sourceUrl', 'text', true, true)}
          </div>

          <div style={sectionTitle}>메모</div>
          <textarea className="pd-ta" rows={2} value={fields.memo ?? ''} onChange={(e) => set('memo', e.target.value)} placeholder="메모" />

          <div style={{ marginTop: 16, background: '#f8fafc', border: '1px solid #eef2f7', borderRadius: 12, padding: 16 }}>
            <div style={{ fontSize: 14, fontWeight: 700, color: '#111827', marginBottom: 10 }}>이미지</div>
            <div style={{ marginBottom: 12 }}>
              {d.hostedImages && d.hostedImages.length > 0 ? (
                <Image.PreviewGroup>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                    {d.hostedImages.map((url, i) => (
                      <Image key={`h-${i}`} src={url} width={64} height={64} style={{ objectFit: 'cover', borderRadius: 6, border: '1px solid #e5e7eb' }} />
                    ))}
                  </div>
                </Image.PreviewGroup>
              ) : <span style={{ color: '#94a3b8', fontSize: 13 }}>등록된 이미지 없음</span>}
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
              <input ref={fileInputRef} type="file" accept="image/*" multiple style={{ display: 'none' }}
                onChange={(e) => handleFilesSelected(e.target.files)} />
              <button className="pd-imgbtn" disabled={uploading} onClick={() => fileInputRef.current?.click()}>
                <UploadOutlined /> 파일 업로드
              </button>
              <Popconfirm title="소스 이미지 크롤·업로드"
                description="크롤한 이미지를 R2에 업로드하고 연동된 모든 마켓에 재게시합니다. 진행할까요?"
                okText="진행" cancelText="취소" onConfirm={handleCrawl}>
                <Tooltip title={d.sourcingInfo?.vendor !== 'IHB' ? '현재 iHerb 상품만 지원' : ''}>
                  <button className="pd-imgbtn" disabled={uploading}><CloudDownloadOutlined /> 소스 이미지 크롤</button>
                </Tooltip>
              </Popconfirm>
            </div>
            <textarea className="pd-ta" rows={2} placeholder="이미지 URL을 줄바꿈 또는 쉼표로 구분해 입력"
              value={urlInput} onChange={(e) => setUrlInput(e.target.value)} />
            <div style={{ marginTop: 8 }}>
              <button className="pd-imgbtn" disabled={uploading} style={{ borderColor: GREEN, color: GREEN }} onClick={handleUploadByUrl}>
                <LinkOutlined /> URL로 등록
              </button>
            </div>
          </div>

          {d.detailHtml && (
            <Collapse style={{ marginTop: 14 }} items={[{
              key: 'detailHtml', label: '상세 설명 (HTML, 읽기전용)',
              children: <iframe title="detailHtml" sandbox="" srcDoc={d.detailHtml}
                style={{ width: '100%', height: 320, border: '1px solid #eee' }} />,
            }]} />
          )}
        </>
      )}
    </Modal>
  );
}
