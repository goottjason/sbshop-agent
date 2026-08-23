import type { Row } from '@tanstack/react-table';
import type { OrderGridDto, OrderDetailResponseDto, PageResponse } from '../../api/orderApi';

export interface StockCellInfo {
  badge: 'IN_STOCK' | 'OUT_OF_STOCK' | 'NONE';
  restockDate?: string;
  updatedAt?: string;
}

export type RowData = OrderGridDto & { isFirstLineItem?: boolean; lineItemCount?: number; totalRowCount?: number; rowType?: string; isSecondRow?: boolean; isThirdRow?: boolean };

export type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';

export type MarketSyncState = 'none' | 'synced' | 'waiting' | 'manual' | 'unknown';

export type OrdersCache = PageResponse<OrderDetailResponseDto>;

export interface OrderTableRowProps {
  row: Row<RowData>;
  isSelected: boolean;
  isOrderBoundary: boolean;
  colCount: number;
  colScale: number;
}
