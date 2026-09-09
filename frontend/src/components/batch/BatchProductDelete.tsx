import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Modal, Spin } from 'antd';
import { isAxiosError } from 'axios';
import { productApi, type ProductDeleteResult } from '../../api/productApi';
import { batchMarketLabel } from './supplierBatchDisplay';

export function BatchProductDelete({ productId, sbCode, productName, disabled, onBusy, onDeleted }: {
  productId: number; sbCode: string; productName: string; disabled: boolean;
  onBusy: (busy: boolean) => void; onDeleted: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [checked, setChecked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ProductDeleteResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const registrations = useQuery({ queryKey: ['batch-delete-markets', productId],
    queryFn: async () => (await productApi.getMarketRegistrations(productId)).data, enabled: open, retry: false });
  const execute = async () => {
    if (disabled || !checked || busy || registrations.isError || !registrations.data) return;
    setBusy(true); onBusy(true); setError(null); setResult(null);
    try {
      const response = await productApi.deleteProduct(productId);
      if (typeof response.data?.disposed !== 'boolean') throw new Error('Unverified delete response');
      setResult(response.data);
      if (response.data.disposed) onDeleted();
    } catch (failure) {
      if (isAxiosError(failure) && failure.response?.status === 409 && failure.response.data?.disposed === false) {
        setResult(failure.response.data as ProductDeleteResult);
      } else {
        setError('삭제 결과를 확인하지 못했습니다. 완료로 표시하지 않습니다. 다시 시도하면 이미 삭제 확인된 마켓은 건너뛰고 남은 마켓을 처리합니다.');
      }
    } finally { setBusy(false); onBusy(false); }
  };
  const close = () => { if (!busy) { setOpen(false); setChecked(false); } };
  return <>
    <Button danger size="small" disabled={disabled} onClick={() => { setOpen(true); setResult(null); setError(null); }}>상품 삭제</Button>
    <Modal title={`${sbCode} 상품 삭제`} open={open} onCancel={close} closable={!busy} maskClosable={!busy} keyboard={!busy}
      footer={result?.disposed ? <Button onClick={close}>닫기</Button> : <><Button disabled={busy} onClick={close}>취소</Button><Button danger type="primary" loading={busy}
        disabled={!checked || registrations.isPending || registrations.isError || disabled} onClick={() => { void execute(); }}>{result || error ? '남은 마켓 삭제 재시도' : '마켓 삭제 후 SB 폐기'}</Button></>}>
      <p>{productName}</p>
      <p>등록된 마켓을 순서대로 삭제하고 재조회합니다. 모든 마켓의 삭제가 확인돼야 SB 상품을 폐기하며, 과거 주문·배치 이력은 보존합니다.</p>
      {registrations.isPending ? <Spin /> : registrations.isError ? <Alert type="error" message="삭제할 마켓 목록을 불러오지 못했습니다." action={<Button onClick={() => { void registrations.refetch(); }}>다시 조회</Button>} /> :
        <p>등록 이력: {registrations.data?.length ? [...new Set(registrations.data.flatMap(r => [r.marketType, ...(r.marketType === 'CAFE24' && r.marketIdentifiers?.gmarket_goodsNo ? ['GMARKET'] : []), ...(r.marketType === 'CAFE24' && r.marketIdentifiers?.auction_goodsNo ? ['AUCTION'] : [])]))].map(batchMarketLabel).join(' · ') : '없음 — SB 상품만 폐기합니다.'}</p>}
      {registrations.data?.some(r => r.marketType === 'CAFE24' && (r.marketIdentifiers?.gmarket_goodsNo || r.marketIdentifiers?.auction_goodsNo)) && <Alert type="info" message="카페24 연동 G마켓·옥션은 해당 마켓의 삭제 확인이 필요합니다. 확인 전까지 카페24 삭제와 SB 폐기를 보류합니다." />}
      {!result?.disposed && <Checkbox checked={checked} disabled={busy} onChange={e => setChecked(e.target.checked)}>원본 상품의 생산 중단 등 삭제 사유를 확인했으며, 이 상품의 마켓 삭제를 진행합니다.</Checkbox>}
      {busy && <Alert type="info" message="마켓 삭제와 재조회 중입니다. 결과가 나올 때까지 기다려 주세요." />}
      {error && <Alert type="error" message={error} />}
      {result && <Alert type={result.disposed ? 'success' : 'warning'} message={result.disposed ? '모든 마켓 삭제 확인 · SB 폐기 완료' : '일부 마켓 삭제 미완료 · SB 상품 유지'} description={<>
        {result.deleted.length > 0 && <p>삭제 확인: {result.deleted.map(batchMarketLabel).join(' · ')}</p>}
        {Object.entries(result.failed).map(([market, reason]) => <p key={market}>{batchMarketLabel(market)} 실패: {reason}</p>)}
        {Object.entries(result.manual).map(([market, reason]) => <p key={market}>{batchMarketLabel(market)} 수동 처리 필요: {reason}</p>)}
      </>} />}
    </Modal>
  </>;
}
