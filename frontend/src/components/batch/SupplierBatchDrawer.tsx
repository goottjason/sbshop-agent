import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Alert, Button, Collapse, Drawer, Empty, Spin } from 'antd';
import { supplierBatchApi, type SupplierBatchRun, type SupplierBatchStage } from '../../api/supplierBatchApi';
import { batchMarketLabel, dateText, mayRetry, numberText, stageLabel, stageStatus } from './supplierBatchDisplay';

export function BatchStageBadge({ stage }: { stage: SupplierBatchStage }) {
  const status = stageStatus(stage);
  return <span className={`sb-batch-badge sb-batch-${status.tone}`}>{status.text}</span>;
}

export function SupplierBatchDrawer({ run, itemId, onClose, onRetry, retryBusy, retryFeedback }: {
  run: SupplierBatchRun; itemId: number; onClose: () => void; onRetry: (itemId: number, stage: SupplierBatchStage) => void; retryBusy: boolean;
  retryFeedback: ReactNode;
}) {
  const detail = useQuery({ queryKey: ['supplier-batch-detail', run.id, itemId], queryFn: async ({ signal }) => (await supplierBatchApi.detail(run.id, itemId, signal)).data,
    retry: false, refetchInterval: run.state === 'RUNNING' || run.state === 'PAUSING' ? 5000 : false });
  const item = detail.data?.item;
  const calculation = detail.data?.priceCalculation;
  return <Drawer title="상품 처리 상세" open onClose={onClose} width={660} rootClassName="sb-batch-drawer">
    {retryFeedback}
    {detail.isPending ? <Spin tip="처리 기록 조회 중…"><div style={{ minHeight: 160 }} /></Spin> : detail.isError ?
      <Alert type="error" showIcon message="상품 처리 기록을 조회하지 못했습니다." action={<Button onClick={() => { void detail.refetch(); }}>다시 조회</Button>} /> : !item ? <Empty description="상품 기록이 없습니다." /> : <>
      <div className="sb-batch-drawer-product">{item.thumbnailUrl && <img src={item.thumbnailUrl} alt="" referrerPolicy="no-referrer" />}<div><strong>{item.sbCode}</strong><p>{item.productName}</p></div></div>
      {item.detail && <p className="sb-batch-wrap">{item.detail}</p>}
      <div className="sb-batch-stage-overview">{item.stages.map(stage => <span key={stage.id}>{stageLabel(stage.stage, stage.market, stage.field)} <BatchStageBadge stage={stage} /></span>)}</div>
      <h3>단계별 목표와 확인 결과</h3>
      <p className="sb-batch-help">마켓 값은 해당 작업에서 실제 확인한 결과입니다. 미확인은 성공으로 처리하지 않습니다.</p>
      <div className="sb-batch-drawer-stage-list">{item.stages.map(stage => <section key={stage.id} className="sb-batch-drawer-stage">
        <div className="sb-batch-between"><strong>{stageLabel(stage.stage, stage.market, stage.field)}</strong><BatchStageBadge stage={stage} /></div>
        {(stage.field || stage.expected != null || stage.observed != null) && <dl className="sb-batch-values"><div><dt>목표값</dt><dd>{numberText(stage.expected)}{stage.expected != null ? stage.field === 'PRICE' ? '원' : stage.field === 'STOCK' ? '개' : '' : ''}</dd></div><div><dt>확인값</dt><dd>{numberText(stage.observed)}{stage.observed != null ? stage.field === 'PRICE' ? '원' : stage.field === 'STOCK' ? '개' : '' : ''}</dd></div></dl>}
        {stage.detail && <p className={`sb-batch-wrap ${stage.state === 'FAILED' ? 'sb-batch-error-text' : ''}`}>{stage.detail}</p>}
        <div className="sb-batch-stage-meta">시도 {stage.attempts}회 · {stage.finishedAt ? `마지막 처리 ${dateText(stage.finishedAt)}` : stage.startedAt ? `시작 ${dateText(stage.startedAt)}` : '아직 시작하지 않음'}{stage.nextRunAt && <span>다음 처리 가능: {dateText(stage.nextRunAt)}</span>}</div>
        {mayRetry(stage) ? <Button size="small" disabled={retryBusy} onClick={() => onRetry(item.id, stage)}>{stageLabel(stage.stage, stage.market, stage.field)} 재시도</Button> : (stage.state === 'FAILED' || stage.state === 'BLOCKED') && <small>이 단계는 자동 재시도 대상이 아닙니다. 위 사유를 먼저 해결하세요.</small>}
      </section>)}</div>
      {run.state === 'PAUSED' && <Alert type="info" message="일시정지 중에는 재시도가 대기로 접수됩니다. 배치를 재개하면 처리합니다." />}
      <Collapse items={[
        { key: 'price', label: '가격 계산 근거', children: calculation ? <div className="sb-batch-calculation">
          <p className="sb-batch-help">최소 마진은 판매가에서 소싱처 쿠폰을 적용한 총매입가와 국내 배송비를 뺀 금액 기준입니다.</p>
          <dl><div><dt>목표 마진 / 적용 쿠폰 / 최소 마진</dt><dd>{calculation.policy.marginRate}% / {calculation.appliedCouponRate ?? calculation.policy.couponRate}% / {numberText(calculation.policy.minMarginPrice)}원</dd></div>
            <div><dt>입력 쿠폰율 → 실제 적용 쿠폰율</dt><dd>{calculation.policy.couponRate}% → {calculation.appliedCouponRate ?? calculation.policy.couponRate}%</dd></div>
            <div><dt>원가 / 적용 환율</dt><dd>{numberText(calculation.costPrice)}원 / {numberText(calculation.exchangeRate)}</dd></div>
            {calculation.pricingEvidence && <><div><dt>소싱 원본 가격</dt><dd>{numberText(calculation.pricingEvidence.sourcePrice)} {calculation.pricingEvidence.currency ?? ''}</dd></div><div><dt>원본 환율 → 반영 환율</dt><dd>{numberText(calculation.pricingEvidence.observedExchangeRate)} → {numberText(calculation.pricingEvidence.normalizedExchangeRate)}</dd></div><div><dt>환산 상품가</dt><dd>{numberText(calculation.pricingEvidence.goodsPriceKrw)}원</dd></div></>}
          </dl><table className="sb-batch-mini-table"><thead><tr><th>마켓</th><th>최소 판매가</th><th>목표 판매가</th></tr></thead><tbody>{calculation.prices.map(price => <tr key={price.market}><td>{batchMarketLabel(price.market)}</td><td>{numberText(price.minimumPrice)}원</td><td>{numberText(price.salePrice)}원</td></tr>)}</tbody></table>
          {calculation.notices.map((notice, index) => <p key={index} className="sb-batch-wrap">{notice}</p>)}
        </div> : <p>이 작업에 저장된 가격 계산 근거가 없습니다.</p> },
        { key: 'history', label: `최근 처리·오류 이력 (${detail.data?.history.length ?? 0})`, children: detail.data?.history.length ? <ol className="sb-batch-attempts">{detail.data.history.map(entry => <li key={entry.id}><time>{dateText(entry.recordedAt)}</time><strong>{stageLabel(entry.stage, entry.market, entry.field)} · {entry.state}</strong><p className="sb-batch-wrap">{entry.detail ?? '상세 사유 없음'}</p></li>)}</ol> : <p>기록된 처리 이력이 없습니다.</p> },
        { key: 'references', label: '실행 기록 식별자', children: <div className="sb-batch-wrap sb-batch-help">배치: {run.id}<br />상품 작업: {item.id}<br />수집 기록: {item.sourceSnapshotId ?? '아직 없음'}<br />저장 검토: {item.editReviewId ?? '아직 없음'}</div> },
      ]} />
    </>}
  </Drawer>;
}
