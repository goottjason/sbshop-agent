import React from 'react';
import { createColumnHelper } from '@tanstack/react-table';
import type { RowData } from './types';
import { formatPhone } from '../../utils/phone';
import { stockCellInfo, marketSyncState } from './helpers';
import { InlineInput, FinancialEditCell, ShippingEditCell, SourcingEditCell } from './cells';

const columnHelper = createColumnHelper<RowData>();

export interface OrderColumnDeps {
  getCommonLabel: (category: string, name: string) => string;
  handleUpdate: (orderId: number, lineItemId: number, field: string, value: unknown) => Promise<unknown>;
  handleSyncCustoms: () => void;
  handleSyncProductStock: () => void;
  timeAgo: (dateStr: string | null) => string;
}

export function buildOrderColumns({ getCommonLabel, handleUpdate, handleSyncCustoms, handleSyncProductStock, timeAgo }: OrderColumnDeps) {
  return [
    columnHelper.display({
      id: 'select',
      header: ({ table }) => (
        <input type="checkbox" checked={table.getIsAllRowsSelected()} onChange={table.getToggleAllRowsSelectedHandler()} />
      ),
      cell: ({ row }) => (
        <input type="checkbox" checked={row.getIsSelected()} onChange={row.getToggleSelectedHandler()} />
      ),
      size: 40,
      meta: { frozen: true, freezeLeft: 0 },
    }),
    columnHelper.display({
      id: 'orderInfo',
      header: '주문정보',
      size: 130,
      cell: ({ row }) => {
        const dateObj = row.original.order?.orderDate ? new Date(row.original.order.orderDate as string) : null;
        const dateStr = dateObj ? `${dateObj.getFullYear()}-${String(dateObj.getMonth() + 1).padStart(2, '0')}-${String(dateObj.getDate()).padStart(2, '0')} ${String(dateObj.getHours()).padStart(2, '0')}:${String(dateObj.getMinutes()).padStart(2, '0')}` : '-';
        const market = row.original.order?.marketType || '';
        const orderNo = row.original.order?.marketOrderNo || '-';
        const marketColorMap: Record<string, { bg: string; text: string }> = {
          'SMART_STORE': { bg: '#f1f8e9', text: '#689f38' },
          'COUPANG': { bg: '#fce4ec', text: '#c2185b' },
          'ELEVEN_STREET': { bg: '#e3f2fd', text: '#1565c0' },
          'CAFE24': { bg: '#fffde7', text: '#fbc02d' },
          'GMARKET': { bg: '#c8e6c9', text: '#1b5e20' },
          'AUCTION': { bg: '#fff3e0', text: '#e65100' }
        };
        const style = marketColorMap[market] || { bg: '#f5f5f5', text: '#666' };
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '3px', lineHeight: '1.2', fontSize: '12px', textAlign: 'center' }}>
            <div>{dateStr}</div>
            <div><span style={{ backgroundColor: style.bg, color: style.text, padding: '2px 6px', borderRadius: '4px', fontWeight: 600, fontSize: '11px' }}>{getCommonLabel('marketType', market)}</span></div>
            <div style={{ fontSize: '11px', color: '#555' }}>{orderNo}</div>
          </div>
        );
      },
      meta: { frozen: true, freezeLeft: 40 },
    }),
    columnHelper.accessor('lineItem.shippingData.shippingStatus', {
      id: 'shippingStatus',
      header: '주문상태',
      size: 90,
      meta: { frozen: true, freezeLeft: 170 },
      cell: info => {
        const val = info.getValue() as string;
        const colorMap: Record<string, { bg: string; text: string }> = {
          'UNKNOWN':    { bg: '#f5f5f5', text: '#666' },
          'NEW':        { bg: '#e0f7fa', text: '#006064' },
          'PREPARING':  { bg: '#fff3e0', text: '#e65100' },
          'DISPATCHED': { bg: '#fce4ec', text: '#880e4f' },
          'SHIPPED':    { bg: '#f1f8e9', text: '#558b2f' },
          'DELIVERED':  { bg: '#e1f5fe', text: '#0277bd' },
          'CANCELED':   { bg: '#ffebee', text: '#c62828' },
          'RETURNED':   { bg: '#f3e5f5', text: '#6a1b9a' },
          'EXCHANGED':  { bg: '#e8eaf6', text: '#283593' }
        };
        const style = colorMap[val] || { bg: '#f5f5f5', text: '#666' };
        return val ? <span style={{ backgroundColor: style.bg, color: style.text, padding: '2px 6px', borderRadius: '4px', fontWeight: 600 }}>{getCommonLabel('shippingStatus', val)}</span> : '-';
      }
    }),
    columnHelper.display({
      id: 'ordererInfo',
      header: '주문자정보',
      size: 120,
      meta: { frozen: true, freezeLeft: 260 },
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          return <span>{formatPhone(row.original.order?.recipientPhone) || '-'}</span>;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const recipientName = (row.original.order?.recipientName || '').trim();
        const ordererName = (row.original.order?.ordererName || '').trim();
        const customsStatus = row.original.order?.customsData?.customsStatus;
        if (!recipientName) return '-';
        if (!ordererName || recipientName === ordererName) {
          return <span style={{ fontWeight: 600 }}>{recipientName}</span>;
        }
        const verifiedPerson = row.original.order?.customsData?.verifiedPerson;
        let recipientColor = '#000';
        let ordererColor = '#000';
        if (customsStatus === 'VALID') {
          if (verifiedPerson === 'RECIPIENT') recipientColor = '#1565c0';
          else if (verifiedPerson === 'ORDERER') ordererColor = '#1565c0';
        } else if (customsStatus === 'INVALID_PCCC' || customsStatus === 'INVALID_PHONE' || customsStatus === 'INVALID_ZIPCODE') {
          recipientColor = '#e65100';
          ordererColor = '#e65100';
        }
        return (
          <div style={{ lineHeight: '1.3', fontSize: '12px' }}>
            <div style={{ color: recipientColor, fontWeight: 600 }}>{recipientName}</div>
            <div style={{ color: ordererColor, fontSize: '11px' }}>({ordererName})</div>
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'customsInfo',
      header: () => (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
          통관정보
          <svg onClick={handleSyncCustoms} xmlns="http://www.w3.org/2000/svg" height="18px" viewBox="0 -960 960 960" width="18px" fill="#555" style={{ cursor: 'pointer' }}>
            <path d="M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z"/>
          </svg>
        </div>
      ),
      size: 126,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          const val = row.original.order?.customsData?.customsStatus;
          if (!val) return '-';
          const customsColorMap: Record<string, { bg: string; text: string }> = {
            'VALID': { bg: '#e8f5e9', text: '#2e7d32' },
            'INVALID_PCCC': { bg: '#ffebee', text: '#c62828' },
            'INVALID_PHONE': { bg: '#fff3e0', text: '#e65100' },
            'INVALID_ZIPCODE': { bg: '#fff8e1', text: '#f57f17' },
            'PENDING': { bg: '#f5f5f5', text: '#666' },
          };
          const style = customsColorMap[val] || { bg: '#f5f5f5', text: '#666' };
          return <span style={{ backgroundColor: style.bg, color: style.text, padding: '2px 6px', borderRadius: '4px', fontWeight: 600, fontSize: '12px', whiteSpace: 'pre-line', lineHeight: '1.3' }}>{getCommonLabel('customsStatus', val)}</span>;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const val = row.original.order?.customsData?.customsClearanceNo || '';
        return <InlineInput value={val} onCommit={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.customsClearanceNo', v)} />;
      }
    }),
    columnHelper.display({
      id: 'shippingInfoPair',
      header: '배송정보',
      size: 190,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          const val = row.original.order?.address || '';
          return <InlineInput value={val} onCommit={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.address', v)} />;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const zipcode = row.original.order?.zipcode || '';
        const message = row.original.order?.message || '';
        return (
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '12px', paddingLeft: '4px' }}>
            <span style={{ fontWeight: 500, flexShrink: 0 }}>{zipcode || '-'}</span>
            <span style={{ color: '#999', flexShrink: 0 }}>|</span>
            <InlineInput
              value={message}
              selectAllOnFocus
              title="배송메시지 — 클릭하면 전체 선택됩니다"
              onCommit={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'order.message', v)}
            />
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'sbCode',
      header: '상품코드',
      size: 110,
      cell: ({ row }) => {
        const val = row.original.product?.sbCode;
        if (!val) {
          return (
            <div
              title="마켓 상품을 우리 상품에 연결하지 못했습니다. 재고·정산·소싱이 이 건을 건너뜁니다."
              style={{ textAlign: 'center', fontWeight: 700, fontSize: '11px', color: '#c62828', background: '#ffebee', border: '1px solid #ef9a9a', borderRadius: '4px', padding: '1px 4px' }}
            >
              미매핑
            </div>
          );
        }
        return <div style={{ textAlign: 'center', fontWeight: 600, fontSize: '12px' }}>{val}</div>;
      }
    }),
    columnHelper.display({
      id: 'productNamePair',
      header: '상품정보',
      size: 315,
      cell: ({ row }) => {
        const url = row.original.product?.sourcingInfo?.sourceUrl;
        if (row.original.rowType === 'product') {
          const name = row.original.product?.originalName || '';
          return <div style={{ textAlign: 'left', paddingLeft: '8px', overflow: 'hidden', textOverflow: 'ellipsis' }}>{url ? <a href={url} target="_blank" rel="noopener noreferrer" style={{ color: '#1565c0', textDecoration: 'none' }}>{name}</a> : name}</div>;
        }
        if (row.original.rowType === 'fulfillment') return null;
        const name = row.original.product?.productName || '';
        return <div style={{ textAlign: 'left', paddingLeft: '8px', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</div>;
      }
    }),
    columnHelper.display({
      id: 'quantity',
      header: '수량',
      size: 56,
      cell: ({ row }) => {
        const qty = (row.original.lineItem?.quantity || 1) as number;
        const bundle = (row.original.product?.logisticsInfo?.bundleQuantity || 1) as number;
        const total = qty * bundle;
        return (
          <div style={{ textAlign: 'center', lineHeight: '1.4' }}>
            <div style={{ fontSize: '12px', color: '#666' }}>
              {bundle} × <span style={qty >= 2 ? { fontWeight: 'bold', color: '#d32f2f' } : {}}>{qty}</span>
            </div>
            <div style={{ fontWeight: 'bold', fontSize: '13px', color: '#1e293b' }}>{total}</div>
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'stockInfo',
      header: () => (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
          재고
          <svg onClick={handleSyncProductStock} xmlns="http://www.w3.org/2000/svg" height="18px" viewBox="0 -960 960 960" width="18px" fill="#555" style={{ cursor: 'pointer' }}>
            <path d="M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z"/>
          </svg>
        </div>
      ),
      size: 64,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') return null;
        if (row.original.rowType === 'fulfillment') return null;
        const info = stockCellInfo(row.original.product);
        let badge = <span style={{ color: '#999' }}>-</span>;
        if (info.badge === 'IN_STOCK') badge = <span style={{ backgroundColor: '#e8f5e9', color: '#2e7d32', padding: '2px 6px', borderRadius: '4px', fontWeight: 600 }}>있음</span>;
        if (info.badge === 'OUT_OF_STOCK') badge = <span style={{ backgroundColor: '#ffebee', color: '#c62828', padding: '2px 6px', borderRadius: '4px', fontWeight: 600 }}>품절</span>;
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', alignItems: 'center' }}>
            <div>{badge}</div>
            {info.restockDate && (
              <div style={{ fontSize: '11px', color: '#666' }}>{`입고: ${info.restockDate}`}</div>
            )}
            {info.updatedAt && (
              <div style={{ fontSize: '10px', color: '#999' }}>{timeAgo(info.updatedAt)}</div>
            )}
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'sourcingInfoPair',
      header: '구매 정보',
      size: 190,
      cell: ({ row }) => {
        const orderId = row.original.order?.id || 0;
        const lineItemId = row.original.lineItem?.id || 0;
        const sourcingAccount = row.original.lineItem?.sourcingData?.sourcingAccount || '';
        const sourcingVendor = row.original.lineItem?.sourcingData?.sourcingVendor || '';
        const sourcingOrderNo = row.original.lineItem?.sourcingData?.sourcingOrderNo || '';
        const discountCode = row.original.lineItem?.sourcingData?.discountCode || '';
        return (
          <SourcingEditCell
            sourcingAccount={sourcingAccount}
            sourcingVendor={sourcingVendor}
            sourcingOrderNo={sourcingOrderNo}
            discountCode={discountCode}
            onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.sourcing', v)}
          />
        );
      }
    }),
    columnHelper.display({
      id: 'fulfillmentInfoPair',
      header: '배송 정보',
      size: 150,
      cell: ({ row }) => {
        const carrier = row.original.lineItem?.shippingData?.shippingCarrier || '';
        const trackingNo = row.original.lineItem?.shippingData?.trackingNo || '';
        const orderId = row.original.order?.id || 0;
        const lineItemId = row.original.lineItem?.id || 0;
        return (
          <div style={{ fontSize: '12px', textAlign: 'center' }}>
            <ShippingEditCell
              carrier={carrier}
              trackingNo={trackingNo}
              syncState={marketSyncState(row.original.lineItem, row.original.shipment)}
              marketTrackingNo={row.original.shipment?.marketTrackingNo || undefined}
              trackingSource={row.original.shipment?.trackingSource ?? null}
              onSave={(v) => handleUpdate(orderId, lineItemId, 'lineItem.shipping', v)}
            />
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'financialInfoPair',
      header: '정산 정보',
      size: 160,
      cell: ({ row }) => {
        if (row.original.rowType === 'product') {
          const sourcingAmount = (row.original.lineItem?.sourcingData?.sourcingAmount || 0) as number;
          const logisticsCost = (row.original.lineItem?.sourcingData?.logisticsCost || 0) as number;
          return (
            <FinancialEditCell
              sourcingAmount={sourcingAmount}
              logisticsCost={logisticsCost}
              onSave={(v) => handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'lineItem.financial', v)}
            />
          );
        }
        if (row.original.rowType === 'fulfillment') return null;
        const settlementAmount = (row.original.lineItem?.settlementData?.settlementAmount || 0) as number;
        const sourcingAmount = (row.original.lineItem?.sourcingData?.sourcingAmount || 0) as number;
        const logisticsCost = (row.original.lineItem?.sourcingData?.logisticsCost || 0) as number;
        const profit = settlementAmount - sourcingAmount - logisticsCost;
        return (
          <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
            <div style={{ flex: 1, textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px' }}>정산금액</div>
              <div style={{ fontWeight: 'bold', color: '#1565c0', fontSize: '13px' }}>{settlementAmount.toLocaleString()}</div>
            </div>
            <div style={{ flex: 1, textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: '#888', marginBottom: '2px' }}>순수익</div>
              <div style={{ fontWeight: 'bold', color: profit > 0 ? '#2e7d32' : '#d32f2f', fontSize: '13px' }}>{profit.toLocaleString()}</div>
            </div>
          </div>
        );
      }
    }),
    columnHelper.display({
      id: 'unipass',
      header: '유니패스',
      size: 70,
      cell: ({ row }) => {
        if (row.original.rowType !== 'order') return null;
        const isDone = row.original.lineItem?.isUnipassDone;
        return <div style={{ textAlign: 'center' }}><input type="checkbox" checked={!!isDone} onChange={(e) => { void handleUpdate(row.original.order?.id || 0, row.original.lineItem?.id || 0, 'lineItem.isUnipassDone', e.target.checked).catch(() => {}); }} /></div>;
      }
    }),
    columnHelper.accessor('lineItem.purchaseStatus', {
      id: 'purchaseStatus',
      header: '구매상태',
      size: 100,
      cell: info => {
        const row = info.row.original;
        const lineItemId = row.lineItem?.id;
        const currentVal = (info.getValue() as string) || 'NOT_PURCHASED';
        const PURCHASE_OPTIONS = [
          { value: 'NOT_PURCHASED', label: '미구매' },
          { value: 'PURCHASED',     label: '구매완료' },
          { value: 'WAITING_STOCK', label: '입고대기' },
        ] as const;
        const handleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
          if (!lineItemId) return;
          void handleUpdate(row.order?.id || 0, lineItemId, 'lineItem.purchaseStatus', e.target.value).catch(() => {});
        };
        return (
          <select value={currentVal} onChange={handleChange}
            style={{ fontSize: '12px', padding: '2px 4px', borderRadius: '4px' }}>
            {PURCHASE_OPTIONS.map(opt => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        );
      }
    }),
  ];
}
