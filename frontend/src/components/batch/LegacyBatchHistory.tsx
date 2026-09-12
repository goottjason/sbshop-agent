import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Input, Table, Tag } from 'antd';
import { batchApi } from '../../api/batchApi';
import { ProcessStatusDetail, type ProcessStatusItem } from './BatchResultTable';

const previousId = () => {
  try { return localStorage.getItem('sbshop.activeBatchId') ?? ''; }
  catch { return ''; }
};
const labels: Record<string, string> = { SUCCESS: '기존 성공 기록', PARTIAL_FAILED: '기존 부분실패', FAILED: '실패', PENDING: '대기' };

export function LegacyBatchHistory() {
  const [value, setValue] = useState(previousId);
  const [id, setId] = useState<string | null>(null);
  const [selected, setSelected] = useState<ProcessStatusItem | null>(null);
  const rows = useQuery({ queryKey: ['legacy-batch-read', id], enabled: !!id, retry: false,
    queryFn: async () => (await batchApi.getBatchStatus(id!)).data as ProcessStatusItem[] });
  return <section className="sb-batch-legacy" aria-label="이전 배치 기록 조회">
    <Alert type="info" showIcon message="이전 방식으로 실행한 배치의 저장 기록입니다."
      description="신규 배치의 단계별 재조회 결과와 구분합니다. 이 화면에서는 과거 배치를 다시 실행하지 않습니다." />
    <form onSubmit={event => { event.preventDefault(); if (value.trim()) { setId(value.trim()); if (id === value.trim()) void rows.refetch(); } }}>
      <Input aria-label="이전 배치 ID" value={value} maxLength={120} onChange={event => setValue(event.target.value)} placeholder="이전 배치 ID" />
      <Button htmlType="submit" disabled={!value.trim()} loading={rows.isFetching}>기록 조회</Button>
    </form>
    {rows.isError && <Alert type="error" message="이전 배치 기록을 조회하지 못했습니다. ID를 확인하고 다시 조회하세요." />}
    {id && !rows.isError && <Table<ProcessStatusItem> size="small" rowKey="id" loading={rows.isPending} dataSource={rows.data}
      pagination={{ pageSize: 20, showSizeChanger: false }} scroll={{ x: 720 }} locale={{ emptyText: '이 ID로 저장된 이전 배치 기록이 없습니다.' }} columns={[
        { title: '상품 코드', dataIndex: 'productCode', width: 160 },
        { title: '저장된 상태', dataIndex: 'processStatus', width: 150, render: state => <Tag color={state === 'FAILED' || state === 'PARTIAL_FAILED' ? 'red' : 'default'}>{labels[state] ?? state}</Tag> },
        { title: '내용', dataIndex: 'message', render: (text, row) => <>
          <span className="sb-batch-wrap">{text || '저장된 설명 없음'}</span>
          <Button type="link" size="small" onClick={() => setSelected(row)} style={{ paddingLeft: 0 }}>상세 사유 보기</Button>
        </> },
      ]} />}
    <ProcessStatusDetail row={selected} open={!!selected} onClose={() => setSelected(null)} />
  </section>;
}
