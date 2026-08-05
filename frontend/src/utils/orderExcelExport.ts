import type { OrderGridDto } from '../api/orderApi';

/**
 * 주문 그리드 → 엑셀(.xlsx) 내보내기.
 *
 * 그리드는 한 칸에 여러 값을 겹쳐 보여주고(주문정보=마켓+주문번호+주문일, 배송정보=송장+택배사 …)
 * 한 주문상품을 3행(order/product/fulfillment)에 나눠 그리므로 화면과 1:1로 옮길 수 없다.
 * 그래서 엑셀에서는 <b>주문상품 1건 = 1행</b>으로 되돌리고 모든 값을 개별 컬럼으로 평탄화한다.
 *
 * exceljs는 번들이 크므로 이 모듈 자체를 호출 시점에 dynamic import 하도록 설계했다
 * (호출부에서 `await import('../utils/orderExcelExport')`). 다운로드를 누르지 않는 사용자는
 * 이 코드를 내려받지 않는다.
 */

/** 셀 하나의 정의. `text: true`면 숫자로 해석될 값이라도 문자열 서식을 강제한다. */
interface ColumnSpec {
  header: string;
  width: number;
  /**
   * 주문번호(17자리)·송장(12~13자리)·우편번호는 엑셀이 숫자로 읽으면 지수표기(2.02607E+16)로
   * 뭉개지거나 앞자리 0이 사라진다. 텍스트 서식을 강제해 원본을 보존한다.
   */
  text?: boolean;
  value: (row: OrderGridDto, label: LabelFn) => string | number | Date | null;
}

type LabelFn = (category: string, name: string) => string;

/** 택배사 enum → 한글명. 그리드의 CARRIER_LABELS와 같은 표를 쓴다. */
const CARRIER_LABELS: Record<string, string> = {
  CJ_LOGISTICS: 'CJ대한통운',
  HANJIN: '한진택배',
  KOREA_POST: '우체국',
  LOTTE_LOGISTICS: '롯데택배',
  HYUNDAI_LOGISTICS: '현대택배',
  ROCKET: '쿠팡로켓',
};

const MARKET_LABELS: Record<string, string> = {
  COUPANG: '쿠팡',
  SMART_STORE: 'N스토어',
  ELEVEN_STREET: '11번가',
  CAFE24: '카페24',
  GMARKET: 'G마켓',
  AUCTION: '옥션',
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

/** 백엔드 LocalDateTime(zone 없는 UTC 벽시계값)을 KST 표시 문자열로. 그리드와 같은 규칙. */
const formatDateTime = (value?: string): string => {
  if (!value) return '';
  const normalized = value.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(value) ? value : `${value}Z`;
  const d = new Date(normalized);
  if (Number.isNaN(d.getTime())) return value;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

const COLUMNS: ColumnSpec[] = [
  { header: '마켓', width: 10, value: r => MARKET_LABELS[r.order?.marketType || ''] || r.order?.marketType || '' },
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

/** 파일명에 쓸 `YYYYMMDD_HHmm`. */
const timestamp = (): string => {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`;
};

/**
 * 선택된 주문상품 행을 xlsx로 만들어 브라우저 다운로드를 띄운다.
 *
 * @param rows   주문상품 단위 행(그리드의 3행 분할을 이미 접어 놓은 상태여야 한다)
 * @param label  공통코드 라벨 조회 함수(그리드의 getCommonLabel과 동일 출처)
 */
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
        // 긴 자릿수 식별자가 지수표기로 뭉개지지 않도록 텍스트 서식 고정.
        cell.numFmt = '@';
        cell.alignment = { horizontal: 'left' };
      } else if (typeof cell.value === 'number') {
        cell.numFmt = '#,##0';
      }
    });
  });

  // 헤더 고정 + 자동 필터 — 수백 행을 다룰 때 실사용에서 바로 필요해진다.
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
