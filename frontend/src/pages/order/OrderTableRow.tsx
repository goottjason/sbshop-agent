import React from 'react';
import { flexRender } from '@tanstack/react-table';
import type { OrderTableRowProps } from './types';
import { ORDER_SPANNED_COLUMNS, LINEITEM_SPANNED_COLUMNS, TWO_ROW_COLUMNS, ORDER_COLUMNS, PRODUCT_COLUMNS } from './constants';
import { TableRow, TableCell } from '../../components/ui/Table';

const OrderTableRow = React.memo(function OrderTableRow({ row, isOrderBoundary, colCount, colScale }: OrderTableRowProps) {
  const baseBgCol = row.original.isFirstLineItem ? '#ffffff' : row.original.isSecondRow ? '#fdfdfd' : '#f9f9f9';
  return (
    <>
      <TableRow data-order-id={row.original.order?.id ?? undefined} style={{ backgroundColor: baseBgCol }}>
        {row.getVisibleCells().map(cell => {
          const isOrderSpanned = ORDER_SPANNED_COLUMNS.includes(cell.column.id);
          const isLineItemSpanned = LINEITEM_SPANNED_COLUMNS.includes(cell.column.id);
          const isTwoRowColumn = TWO_ROW_COLUMNS.includes(cell.column.id);
          const isOrderColumn = ORDER_COLUMNS.includes(cell.column.id);
          const isProductColumn = PRODUCT_COLUMNS.includes(cell.column.id);
          if (isOrderSpanned && !row.original.isFirstLineItem) return null;
          if (isLineItemSpanned && row.original.rowType !== 'order') return null;
          if (isTwoRowColumn && row.original.rowType === 'fulfillment') return null;
          if (isOrderColumn && row.original.rowType !== 'order') return null;
          if (isProductColumn && row.original.rowType !== 'product') return null;
          const meta = cell.column.columnDef.meta as { frozen?: boolean; freezeLeft?: number } | undefined;
          const isFrozen = meta?.frozen;
          const freezeLeft = meta?.freezeLeft;
          return (
            <TableCell
              key={cell.id}
              rowSpan={isOrderSpanned ? row.original.totalRowCount || 1 : isLineItemSpanned ? 3 : 1}
              style={{
                borderRight: '1px solid #e5e7eb',
                width: cell.column.getSize(),
                minWidth: cell.column.getSize(),
                height: 'var(--row-h)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'normal',
                wordBreak: 'break-word',
                padding: 'var(--cell-pad)',
                textAlign: ['shippingInfoPair', 'productNamePair'].includes(cell.column.id) ? 'left' : 'center',
                position: isFrozen ? 'sticky' : undefined,
                left: isFrozen ? (freezeLeft ?? 0) * colScale : undefined,
                zIndex: isFrozen ? 2 : undefined,
                backgroundColor: isFrozen ? baseBgCol : undefined,
                boxShadow: isFrozen ? '2px 0 4px rgba(0,0,0,0.1)' : undefined,
              }}
            >
              {flexRender(cell.column.columnDef.cell, cell.getContext())}
            </TableCell>
          );
        })}
      </TableRow>
      {isOrderBoundary && (
        <tr aria-hidden="true">
          <td colSpan={colCount} style={{ padding: 0, height: '2px', backgroundColor: '#9ca3af' }} />
        </tr>
      )}
    </>
  );
}, (prev, next) =>
  prev.row.original === next.row.original
  && prev.isSelected === next.isSelected
  && prev.isOrderBoundary === next.isOrderBoundary
  && prev.colCount === next.colCount,
);
export default OrderTableRow;
