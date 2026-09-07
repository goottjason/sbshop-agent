import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Modal, Table } from 'antd';
import { productApi, type ProductList } from '../../api/productApi';
import { ProductSourceRefreshModal } from './ProductSourceRefreshModal';

/** Choose an explicit bounded subset; a supplier-wide search never silently starts thousands of crawls. */
export function ProductSourceBatchPicker({ vendor, onClose }: { vendor: string; onClose: () => void }) {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<number[]>([]);
  const [reviewing, setReviewing] = useState<number[] | null>(null);
  const products = useQuery({ queryKey: ['source-batch-products', vendor, page],
    queryFn: async () => (await productApi.fetchProducts({ page, size: 50, vendors: [vendor], sort: 'workspacePriority,asc' })).data,
    retry: false });
  return <><Modal open title={`${vendor} 소싱 갱신 대상 선택`} width={1000} onCancel={onClose}
    footer={<><Button onClick={onClose}>닫기</Button><Button type="primary" disabled={!selected.length || products.isError}
      onClick={() => setReviewing([...selected])}>선택 {selected.length}개 수집·검토</Button></>}>
    <Alert type="info" showIcon message="현재 페이지에서 최대 50개를 선택합니다. 수집 후 가격·재고를 비교하여 저장합니다."
      description="상품별 기존 가격 정책을 사용합니다. 마진·쿠폰·최소마진 변경은 상품관리의 가격 계산 검토에서 진행하세요." />
    {products.isError && <Alert type="error" message="상품 목록을 가져오지 못했습니다." action={<Button onClick={() => { void products.refetch(); }}>다시 조회</Button>} />}
    <Table<ProductList> rowKey="id" size="small" loading={products.isPending} dataSource={products.data?.content ?? []}
      rowSelection={{ selectedRowKeys: selected, onChange: keys => setSelected(keys.map(Number)), preserveSelectedRowKeys: false }}
      pagination={{ current: page + 1, pageSize: 50, total: products.data?.totalElements ?? 0, showSizeChanger: false,
        onChange: next => { setPage(next - 1); setSelected([]); } }}
      columns={[{ title: 'SB코드', dataIndex: 'sbCode', width: 150 }, { title: '상품명', dataIndex: 'productName' },
        { title: '브랜드', dataIndex: 'brand', width: 170 }, { title: '재고 상태', dataIndex: 'stockStatus', width: 100,
          render: value => value === 'IN_STOCK' ? '재고 있음' : value === 'OUT_OF_STOCK' ? '품절' : '미확인' }]} />
  </Modal>{reviewing && <ProductSourceRefreshModal productIds={reviewing} onClose={() => setReviewing(null)} onSaved={() => { void products.refetch(); }} />}</>;
}
