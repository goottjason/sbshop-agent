import type { OrderGridDto } from '../api/orderApi';
import { marketLabel } from './marketLabels';

interface ColumnSpec {
  header: string;
  width: number;
  text?: boolean;
  value: (row: OrderGridDto, label: LabelFn) => string | number | Date | null;
}

type LabelFn = (category: string, name: string) => string;

const CARRIER_LABELS: Record<string, string> = {
  CJ_LOGISTICS: 'CJ대한통운',
  HANJIN: '한진택배',
  KOREA_POST: '우체국',
  LOTTE_LOGISTICS: '롯데택배',
  HYUNDAI_LOGISTICS: '현대택배',
  ROCKET: '쿠팡로켓',
};

const PURCHASE_STATUS_LABELS: Record<string, string> = {
  NOT_PURCHASED: '미구매',
  PURCHASED: '구매완료',
  WAITING_STOCK: '입고대기',
};

const STOCK_STATUS_LABELS: Record<string, string> = {
  IN_STOCK: '있음',
  OUT_OF_STOCK: '품절',
};

const COLUMNS: ColumnSpec[] = [
  { header: '마켓', width: 10, value: r => marketLabel(r.order?.marketType) },
  { header: '마켓 주문번호', width: 20, text: true, value: r => r.order?.marketOrderNo || '' },
  { header: '주문일시', width: 17, value: r => formatDateTime(r.order?.orderDate) },
  { header: '주문상태', width: 10, value: (r, label) => {
    const s = r.lineItem?.shippingData?.shippingStatus;
    return s ? label('shippingStatus', s) : '';
  } },
  { header: '구매상태', width: 10, value: r => PURCHASE_STATUS_LABELS[r.lineItem?.purchaseStatus || ''] || '' },
  { header: '주문자명', width: 12, value: r => r.order?.ordererName || '' },
  { header: '주문자연락처', width: 14, text: true, value: r => r.order?.ordererPhone || '' },
  { header: '수취인명', width: 12, value: r => r.order?.recipientName || '' },
  { header: '수취인연락처', width: 14, text: true, value: r => r.order?.recipientPhone || '' },
  { header: '우편번호', width: 10, text: true, value: r => r.order?.zipcode || '' },
  { header: '주소', width: 45, value: r => r.order?.address || '' },
  { header: '배송메시지', width: 24, value: r => r.order?.message || '' },
  { header: '통관번호', width: 16, text: true, value: r => r.order?.customsData?.customsClearanceNo || '' },
  { header: '통관상태', width: 10, value: r => r.order?.customsData?.customsStatus || '' },
  { header: '통관확인자', width: 12, value: r => r.order?.customsData?.verifiedPerson || '' },
  { header: 'SB코드', width: 14, text: true, value: r => r.product?.sbCode || '' },
  { header: '상품명', width: 40, value: r => r.product?.productName || '' },
  { header: '원문상품명', width: 40, value: r => r.product?.originalName || '' },
  { header: '상품URL', width: 50, text: true, value: r => r.product?.sourcingInfo?.sourceUrl || '' },
  { header: '카테고리', width: 14, value: r => r.product?.category || '' },
  { header: '공급처', width: 12, value: r => r.product?.vendor || '' },
  { header: '재고상태', width: 10, value: r => STOCK_STATUS_LABELS[r.product?.stockStatus || ''] || r.product?.stockStatus || '' },
  { header: '수량', width: 7, value: r => r.lineItem?.quantity ?? null },
  { header: '단가', width: 12, value: r => r.lineItem?.unitPrice ?? null },
  { header: '소싱처', width: 10, value: r => r.lineItem?.sourcingData?.sourcingVendor || '' },
  { header: '소싱계정', width: 26, value: r => r.lineItem?.sourcingData?.sourcingAccount || '' },
  { header: '소싱주문번호', width: 16, text: true, value: r => r.lineItem?.sourcingData?.sourcingOrderNo || '' },
  { header: '소싱금액', width: 12, value: r => r.lineItem?.sourcingData?.sourcingAmount ?? null },
  { header: '물류비', width: 10, value: r => r.lineItem?.sourcingData?.logisticsCost ?? null },
  { header: '할인코드', width: 12, value: r => r.lineItem?.sourcingData?.discountCode || '' },
  { header: '송장번호', width: 18, text: true, value: r => r.lineItem?.shippingData?.trackingNo || '' },
  { header: '택배사', width: 12, value: r => {
    const c = r.lineItem?.shippingData?.shippingCarrier;
    return c ? CARRIER_LABELS[c] || c : '';
  } },
  { header: '정산액', width: 12, value: r => r.lineItem?.settlementData?.settlementAmount ?? null },
  { header: '정산확정', width: 10, value: r => (r.lineItem?.settlementData?.settlementVerified ? 'Y' : 'N') },
  { header: '유니패스', width: 10, value: r => (r.lineItem?.isUnipassDone ? 'Y' : 'N') },
];

const formatDateTime = (value?: string): string => {
  if (!value) return '';
  const normalized = value.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(value) ? value : `${value}Z`;
  const d = new Date(normalized);
  if (Number.isNaN(d.getTime())) return value;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

const timestamp = (): string => {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`;
};

export const exportOrdersToExcel = async (rows: OrderGridDto[], label: LabelFn): Promise<void> => {
  const ExcelJS = await import('exceljs');
  const workbook = new ExcelJS.Workbook();
  workbook.created = new Date();
  const sheet = workbook.addWorksheet('주문');
  sheet.columns = COLUMNS.map(c => ({ header: c.header, key: c.header, width: c.width }));
  const headerRow = sheet.getRow(1);
  headerRow.font = { bold: true, size: 10 };
  headerRow.alignment = { vertical: 'middle', horizontal: 'center' };
  headerRow.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFE8F5E9' } };
  headerRow.border = { bottom: { style: 'thin', color: { argb: 'FFB0BEC5' } } };
  headerRow.height = 20;
  rows.forEach(row => {
    const values = COLUMNS.map(c => c.value(row, label));
    const added = sheet.addRow(values);
    added.font = { size: 10 };
    COLUMNS.forEach((c, i) => {
      const cell = added.getCell(i + 1);
      if (c.text) {
        cell.numFmt = '@';
        cell.alignment = { horizontal: 'left' };
      } else if (typeof cell.value === 'number') {
        cell.numFmt = '#,##0';
      }
    });
  });
  sheet.views = [{ state: 'frozen', ySplit: 1 }];
  sheet.autoFilter = { from: { row: 1, column: 1 }, to: { row: 1, column: COLUMNS.length } };
  const buffer = await workbook.xlsx.writeBuffer();
  const blob = new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `주문내역_${timestamp()}.xlsx`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
};
