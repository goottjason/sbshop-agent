import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Collapse, Input, Modal, Table, Tag } from 'antd';
import { marketPublicationApi, type RegistrationCandidate, type RegistrationTask } from '../../api/marketPublicationApi';
import { ProductPublicationInputs } from './ProductPublicationInputs';
import type { PublicationInputContext } from '../../api/productPublicationInputsApi';
import { marketLabel } from '../../utils/marketLabels';
const markets = ['SMART_STORE', 'COUPANG', 'ELEVEN_STREET', 'CAFE24', 'GMARKET', 'AUCTION'];
const stateLabels: Record<string, string> = { PREVIEW: '등록 전 검토', QUEUED: '등록 전송 대기', POST_STARTED: '등록 전송 중', VERIFY: '생성 상품 재조회 대기', AWAITING_APPROVAL: '마켓 심사 결과 대기', UNKNOWN_CREATE: '생성 여부 미확인', ACTION_REQUIRED: '결과 확인 필요', REGISTERED: '등록·주요 정보 확인', STALE: '다시 검토 필요' };
const candidateKey = (row: RegistrationCandidate) => `${row.productId}:${row.market}`;
export function ProductRegistrationJobs({ productIds, onClose }: { productIds: number[]; onClose: () => void }) {
  const [candidates, setCandidates] = useState<RegistrationCandidate[]>([]);
  const [selected, setSelected] = useState<React.Key[]>([]);
  const [prepared, setPrepared] = useState<RegistrationTask[]>([]);
  const [toCommit, setToCommit] = useState<React.Key[]>([]);
  const [reviewed, setReviewed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resolve, setResolve] = useState<RegistrationTask | null>(null);
  const [listingId, setListingId] = useState('');
  const [contexts, setContexts] = useState<Record<string, PublicationInputContext | null>>({});
  const selectedCoupang = candidates.filter(c => c.selectable && c.market === 'COUPANG' && selected.includes(candidateKey(c)));
  const missingInputs = candidates.some(c => selected.includes(candidateKey(c)) && c.market === 'COUPANG' && !contexts[candidateKey(c)]);
  const recent = useQuery({ queryKey: ['market-registration-jobs'], queryFn: async ({ signal }) => (await marketPublicationApi.recent(signal)).data, refetchInterval: 5000, retry: false });
  const loadCandidates = async () => {
    setBusy(true); setError(null); setCandidates([]); setSelected([]); setContexts({}); setPrepared([]); setToCommit([]); setReviewed(false);
    try { setCandidates((await marketPublicationApi.candidates(productIds, markets)).data); }
    catch { setError('등록 후보 조회 실패. 연결 상태를 확인하지 못해 등록 후보를 표시하지 않습니다.'); }
    finally { setBusy(false); }
  };
  const prepare = async () => {
    if (missingInputs) { setError('선택한 쿠팡 상품의 필수 등록 정보를 먼저 입력하세요.'); return; }
    setBusy(true); setError(null); setPrepared([]); setToCommit([]); setReviewed(false);
    try {
      const result = (await marketPublicationApi.prepare(candidates.filter(c => selected.includes(candidateKey(c)) && c.selectable).map(c => ({ productId: c.productId, market: c.market, ...(c.market === 'COUPANG' ? { context: contexts[candidateKey(c)]! } : {}) })))).data;
      setPrepared(result.prepared);
      if (result.excluded.length) setError(result.excluded.map(c => `${c.sbCode ?? c.productId} · ${marketLabel(c.market)}: ${c.reason}`).join('\n'));
      await recent.refetch();
    } catch { setError('등록 요청 준비 결과를 확인하지 못했습니다. 최근 검토 내역을 확인하거나 다시 준비하세요. 이 단계에서는 상품을 등록하지 않습니다.'); }
    finally { setBusy(false); }
  };
  const commit = async () => {
    setBusy(true); setError(null);
    const submitted: RegistrationTask[] = []; const failed: string[] = [];
    for (const row of prepared.filter(p => toCommit.includes(p.id) && !p.committed)) {
      try { submitted.push((await marketPublicationApi.commit(row.id)).data); }
      catch { failed.push(row.sbCode); }
    }
    setPrepared(old => old.map(p => submitted.find(s => s.id === p.id) ?? p));
    setToCommit(old => old.filter(id => !submitted.some(s => s.id === id)));
    if (failed.length) setError(`접수 결과를 확인하지 못한 상품: ${failed.join(', ')}. 같은 검토로 다시 접수할 수 있습니다. 중복 생성 요청은 실행하지 않습니다.`);
    await recent.refetch(); setBusy(false);
  };
  const recheck = async () => {
    if (!resolve) return;
    setBusy(true); setError(null);
    try { await marketPublicationApi.recheck(resolve.id, listingId.trim()); setResolve(null); await recent.refetch(); }
    catch { setError('원상품 재조회 접수 실패. 과거 삭제 상품번호인지, 등록 요청 계정과 같은 계정인지 확인하세요.'); }
    finally { setBusy(false); }
  };
  return <Modal open title="미등록·삭제 상품 등록" width={1180} footer={null} maskClosable={!busy} closable={!busy} onCancel={() => { if (!busy) onClose(); }}>
    <Alert type="info" showIcon message="삭제 사유와 새 등록 내용을 확인한 상품만 선택해 등록합니다."
      description="스마트스토어·쿠팡·카페24 본상품의 내용을 검토하고 등록합니다. 영구 판매금지와 생성 여부 미확인 상품은 제외합니다. 쿠팡 심사 대기는 성공으로 표시하지 않으며, 카페24 상품 생성 후 마켓플러스 전달은 현재 설정에 따르며, G마켓·옥션 등록 완료는 별도로 확인합니다." />
    <p><Button disabled={!productIds.length || productIds.length > 500 || busy} onClick={() => { void loadCandidates(); }}>선택 {productIds.length}개 상품의 등록 후보 조회</Button></p>
    {error && <Alert type="error" message={<span style={{ whiteSpace: 'pre-wrap' }}>{error}</span>} />}
    {!!candidates.length && <>
      <Table<RegistrationCandidate> size="small" rowKey={candidateKey} dataSource={candidates} scroll={{ x: 850, y: 300 }} pagination={{ pageSize: 12, showSizeChanger: false }}
        rowSelection={{ selectedRowKeys: selected, onChange: keys => { setSelected(keys); setContexts(old => Object.fromEntries(Object.entries(old).filter(([key]) => keys.includes(key)))); }, getCheckboxProps: r => ({ disabled: busy || !r.selectable }) }} columns={[
          { title: 'SB코드', dataIndex: 'sbCode', width: 145 }, { title: '마켓', dataIndex: 'market', width: 120, render: marketLabel },
          { title: '과거 상품번호', dataIndex: 'oldListingId', width: 160, render: v => v ?? '없음' },
          { title: '후보 여부·삭제 근거', dataIndex: 'reason', render: (v, r) => <><Tag color={r.selectable ? 'blue' : 'default'}>{r.selectable ? '선택 가능' : '제외'}</Tag>{v}</> },
        ]} />
      {!!selectedCoupang.length && <Collapse accordion defaultActiveKey={candidateKey(selectedCoupang[0])} style={{ margin: '16px 0' }} items={selectedCoupang.map(c => ({
        key: candidateKey(c), label: <><strong>{c.sbCode} · 쿠팡 필수 등록 정보</strong> <Tag color={contexts[candidateKey(c)] ? 'green' : 'orange'}>{contexts[candidateKey(c)] ? '입력 완료' : '입력 필요'}</Tag></>,
        children: <section aria-label={`${c.sbCode} 쿠팡 필수 등록 정보`}><ProductPublicationInputs productId={c.productId} market={c.market} disabled={busy} onChange={context => setContexts(old => old[candidateKey(c)] === context ? old : { ...old, [candidateKey(c)]: context })} /></section>,
      }))} />}
      <Button loading={busy} disabled={!selected.length || selected.length > 100 || missingInputs} onClick={() => { void prepare(); }}>선택 후보 {selected.length}건 등록 내용 준비</Button>
    </>}
    {!!prepared.length && <>
      <p><strong>실제로 전송할 등록 내용</strong> · 카테고리·상품명·가격·수량을 확인한 뒤 선택하세요.</p>
      <Table<RegistrationTask> size="small" rowKey="id" dataSource={prepared} pagination={false} scroll={{ x: 950, y: 320 }}
        rowSelection={{ selectedRowKeys: toCommit, onChange: keys => setToCommit(keys), getCheckboxProps: r => ({ disabled: busy || r.committed }) }} columns={[
          { title: '상품', key: 'name', width: 310, render: (_, r) => <><img src={r.image} alt="대표 이미지" style={{ width: 40, height: 40, objectFit: 'contain', float: 'left', marginRight: 8 }} /><strong>{r.sbCode}</strong><br />{r.name}</> },
          { title: '마켓', dataIndex: 'market', width: 110, render: marketLabel },
          { title: '마켓 카테고리', key: 'category', render: (_, r) => <>{r.categoryPath ?? '분류명 미제공'}<br /><small>코드 {r.categoryId}</small></> },
          { title: '판매가 / 판매용 수량', key: 'price', width: 170, render: (_, r) => <>{Number(r.price).toLocaleString('ko-KR')}원 / {r.quantity}개</> },
          { title: '배송·반품 검토', key: 'shipping', width: 220, render: (_, r) => Object.entries(r.shippingSummary ?? {}).length ? Object.entries(r.shippingSummary ?? {}).map(([label, value]) => <div key={label}><small>{label}</small>: {value}</div>) : '마켓 기본 설정' },
          { title: '상태', key: 'state', width: 130, render: (_, r) => stateLabels[r.state] ?? r.state },
        ]} />
      <p><Checkbox checked={reviewed} disabled={busy} onChange={e => setReviewed(e.target.checked)}>삭제 사유와 새 상품명·카테고리·가격·수량·배송 및 판매 시작 계획을 확인했으며, 선택한 상품 등록과 필요한 마켓 심사 요청에 동의합니다.</Checkbox></p>
      <Button type="primary" loading={busy} disabled={!reviewed || !toCommit.length} onClick={() => { void commit(); }}>선택 {toCommit.length}건 등록 접수</Button>
    </>}
    <p><strong>최근 등록 검토·작업</strong></p>
    {recent.isError ? <Alert type="error" message="최근 등록 작업 조회 실패. 이전 결과를 완료로 표시하지 않습니다." action={<Button onClick={() => { void recent.refetch(); }}>다시 조회</Button>} /> : <Table<RegistrationTask> size="small" rowKey="id" dataSource={recent.data} pagination={{ pageSize: 10, showSizeChanger: false }} scroll={{ x: 850 }} columns={[
      { title: 'SB코드 / 마켓', key: 'product', width: 185, render: (_, r) => <>{r.sbCode}<br /><small>{marketLabel(r.market)} · {new Date(r.createdAt).toLocaleString('ko-KR')}</small></> },
      { title: '상태·사유', key: 'state', render: (_, r) => <><Tag color={r.state === 'REGISTERED' ? 'green' : ['UNKNOWN_CREATE', 'ACTION_REQUIRED'].includes(r.state) ? 'orange' : 'blue'}>{stateLabels[r.state] ?? r.state}</Tag>{r.detail}{r.listingId && <div>원상품 번호 {r.listingId}</div>}</> },
      { title: '작업', key: 'action', width: 175, render: (_, r) => r.state === 'PREVIEW' ? <Button disabled={busy} size="small" onClick={() => { setPrepared([r]); setToCommit([]); setReviewed(false); }}>등록 내용 다시 보기</Button> : ['UNKNOWN_CREATE', 'ACTION_REQUIRED'].includes(r.state) ? <Button disabled={busy} size="small" onClick={() => { setResolve(r); setListingId(r.listingId ?? ''); setError(null); }}>원상품 번호로 재조회</Button> : '—' },
    ]} />}
    <Modal open={!!resolve} title="생성된 원상품 번호로 확인" onCancel={() => { if (!busy) setResolve(null); }} onOk={() => { void recheck(); }} confirmLoading={busy} okText="원상품 재조회 접수" okButtonProps={{ disabled: !/^[1-9][0-9]{0,17}$/.test(listingId.trim()) }}>
      <p>{resolve?.sbCode} · 등록을 요청한 {marketLabel(resolve?.market ?? '')} 계정에서 생성된 원상품 번호를 입력하세요. 과거 삭제 상품번호는 사용할 수 없습니다. SB코드와 검토한 주요 정보가 일치할 때 연결을 확정합니다.</p>
      <Input aria-label="생성된 원상품 번호" value={listingId} onChange={e => setListingId(e.target.value)} />
      {error && <Alert type="error" message={error} />}
    </Modal>
  </Modal>;
}
