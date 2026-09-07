import { useState } from 'react';
import { Alert, Button, Modal, Tag } from 'antd';
import { productEditApi, type EditCommit, type EditReview } from '../../api/productChangeApi';
import { marketLabel } from '../../utils/marketLabels';
import { formatNumericPreviewValue } from './productNumericDisplay';

import { editFieldLabel, editValue } from './productEditDisplay';

const stateLabels: Record<string, string> = { READY: '저장 가능', UNCHANGED: '변경 없음', EXCLUDED: '저장 제외', NOT_FOUND: '상품 없음', SAVED: 'DB 저장 완료', CONFLICT: '다시 검토 필요', FAILED: '저장 실패' };

export function ProductSaveReview({ review, onClose, onSaved }: { review: EditReview; onClose: () => void; onSaved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<EditCommit | null>(null);
  const [error, setError] = useState(false);
  const ready = review.items.filter(i => i.state === 'READY').length;
  const retry = error || result?.items.some(i => i.state === 'FAILED');
  const save = async () => {
    setBusy(true); setError(false);
    try {
      const response = (await productEditApi.commit(review.reviewId)).data;
      setResult(response);
      if (response.items.some(i => i.state === 'SAVED')) onSaved();
    } catch { setError(true); } finally { setBusy(false); }
  };
  return <Modal open title="변경 내용 확인 · 시스템상품 저장" width={1050} onCancel={() => { if (!busy) onClose(); }} maskClosable={!busy} closable={!busy}
    footer={<><Button disabled={busy} onClick={onClose}>닫기</Button><Button type="primary" loading={busy}
      disabled={ready === 0 || (!!result && !retry)} onClick={() => { void save(); }}>{retry ? '같은 변경으로 저장 재시도' : `검토한 ${ready}개 상품 저장`}</Button></>}>
    <Alert type="info" showIcon message="상품별로 모든 변경값을 함께 저장합니다."
      description="제외된 상품은 저장하지 않습니다. 검토 후 상품·연결·가격 정책이 바뀌면 다시 검토해야 합니다. DB 저장과 마켓 반영 결과는 별도로 관리합니다." />
    <p className="pw-change-note">검토 유효 시각: {new Date(review.expiresAt).toLocaleString('ko-KR')} · 마켓 미반영 기록은 저장됩니다. 자동 마켓 전송은 아직 제공되지 않습니다.</p>
    {error && <Alert type="error" showIcon message="저장 결과를 확인하지 못했습니다. 같은 변경으로 재시도하세요."
      description="서버에 이미 저장됐다면 중복 적용하지 않고 기존 저장 결과를 반환합니다." />}
    <div className="pw-preview-results"><table><thead><tr><th>상품</th><th>변경 전 → 저장할 값</th><th>상태·마켓 영향</th></tr></thead>
      <tbody>{review.items.map(item => {
        const saved = result?.items.find(r => r.productId === item.productId);
        return <tr key={item.productId}><td>{item.sbCode ?? item.productId}</td>
          <td>{item.changes.map(c => <div key={c.field} className="pw-preview-field"><strong>{editFieldLabel(c.field)} {c.derived && <Tag color="gold">파생 변경</Tag>}</strong>
            <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 150, overflow: 'auto' }}>{editValue(c.field, c.before)} → {editValue(c.field, c.after)}</span></div>)}</td>
          <td><Tag color={saved?.state === 'SAVED' ? 'green' : item.state === 'READY' ? 'blue' : 'orange'}>{stateLabels[saved?.state ?? item.state] ?? saved?.state}</Tag>
            {item.notices?.map(notice => <p key={notice} className="pw-change-note" style={{ color: '#92400e' }}>{notice}</p>)}
            {item.reasons.map(reason => <p key={reason} className="pw-change-note">{reason}</p>)}
            {saved && <p>{saved.reason}</p>}
            {item.connections.length > 0 && <p className="pw-change-note">연결 기록: {item.connections.map(c => marketLabel(c.market)).join(' · ')}</p>}
            {item.prices.map(p => <div key={p.market} className="pw-change-note">{marketLabel(p.market)} 계산가 {formatNumericPreviewValue(p.salePrice)}원 / 최소마진 하한 {formatNumericPreviewValue(p.minimumPrice)}원</div>)}
          </td></tr>;
      })}</tbody></table></div>
  </Modal>;
}
