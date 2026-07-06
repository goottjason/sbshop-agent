import { useState, useCallback } from 'react';
import { AgGridReact } from 'ag-grid-react';
import type { ColDef } from 'ag-grid-community';
import { Input, Button, Space, Modal, InputNumber, message } from 'antd';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { productApi, type ProductList } from '../api/productApi';

const ProductPage = () => {
  const [rowData, setRowData] = useState<ProductList[]>([]);
  const [keyword, setKeyword] = useState('');
  const [totalCount, setTotalCount] = useState(0);
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [priceStockModal, setPriceStockModal] = useState<{ visible: boolean; id?: number; price?: number; stock?: number }>({ visible: false });

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

  const columnDefs: ColDef<ProductList>[] = [
    {
      headerName: '이미지',
      field: 'repImageUrl',
      width: 80,
      cellRenderer: (params: { value?: string }) =>
        params.value ? `<img src="${params.value}" style="width:50px;height:50px;object-fit:cover;" />` : '',
    },
    { headerName: 'SB코드', field: 'sbCode', width: 120 },
    { headerName: '브랜드', field: 'brand', width: 100 },
    { headerName: '상품명', field: 'productName', flex: 1, minWidth: 200 },
    { headerName: '소싱처', field: 'vendor', width: 80 },
    { headerName: '판매가', field: 'salePrice', width: 100, valueFormatter: (p: { value?: number }) => p.value ? `${p.value.toLocaleString()}원` : '' },
    { headerName: '재고', field: 'stock', width: 80 },
    {
      headerName: '관리',
      width: 120,
      cellRenderer: (params: { data?: ProductList }) =>
        params.data ? `<button onclick="window.__editProduct(${params.data.id}, ${params.data.salePrice || 0}, ${params.data.stock || 0})">가격/재고</button>` : '',
    },
  ];

  const onGridReady = () => {
    (window as unknown as { __editProduct: (id: number, price: number, stock: number) => void }).__editProduct = (id: number, price: number, stock: number) => {
      setPriceStockModal({ visible: true, id, price, stock });
    };
  };

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
          onGridReady={onGridReady}
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
    </div>
  );
};

export default ProductPage;
