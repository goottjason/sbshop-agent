export const inputStyle = { width: '100%', padding: 'var(--field-pad)', fontSize: 'var(--field-fs)', border: '1px solid #d1d5db', borderRadius: '4px', boxSizing: 'border-box' as const, outline: 'none', backgroundColor: '#fdfdfd' };

export const CARRIER_LABELS: Record<string, string> = {
  CJ_LOGISTICS: 'CJ대한통운',
  HANJIN: '한진택배',
  KOREA_POST: '우체국',
  LOTTE_LOGISTICS: '롯데택배',
  HYUNDAI_LOGISTICS: '현대택배',
  ROCKET: '쿠팡로켓',
};

export const CARRIER_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '-' },
  ...Object.entries(CARRIER_LABELS).map(([value, label]) => ({ value, label })),
];

export const ACCOUNT_OPTIONS = [
  '',
  'shouldbe.shopping@gmail.com', 'kimjw8712@gmail.com', '369butterfly369@gmail.com',
  'younzara@gmail.com', 'kimjongwon0907@gmail.com', 'spreadyourwings33@gmail.com',
  'goottjason@gmail.com', 'gootkimjw8712@gmail.com', 'kimsubi.0007@gmail.com',
  'mariahcarey0815@gmail.com', 'jongwon@skku.edu', 'tomkim8712@gmail.com',
  'kimshou31@gmail.com', 'kimshou825@gmail.com', 'inegg@g.skku.edu',
  'wavesea88@naver.com', 'ordinary_things@naver.com', 'younzara@naver.com',
  'palme86@naver.com', 'tonyworld@daum.net', 'oasis_0907@daum.net',
  'dnglglzpzp@daum.net', 'younzara@nate.com',
];

export const VENDOR_OPTIONS = ['', 'IHB', 'AMZ', 'FTN', 'COK', 'OCD', 'TES', 'VTB'];

export const toolbarBtnBase = { padding: '4px 10px', border: 'none', borderRadius: '6px', cursor: 'pointer', fontSize: '12px', fontWeight: 600, whiteSpace: 'nowrap' as const };

export const toolbarBtn = { ...toolbarBtnBase, backgroundColor: 'var(--primary-color)', color: '#fff', boxShadow: '0 1px 2px rgba(0,0,0,0.06)' };

export const NO_SEND_STATUSES = ['CANCELED', 'RETURNED', 'EXCHANGED'];

export const SYNC_BADGE: Record<'synced' | 'waiting' | 'manual' | 'unknown', { text: string; title: string; fg: string; bg: string; line: string }> = {
  manual: {
    text: '수정요망',
    title: '마켓이 송장 수정을 거부했습니다(배송중 등). 재시도로는 해결되지 않으니 마켓 판매자센터에서 직접 수정하세요. 고치면 다음 동기화에서 이 표시가 사라집니다.',
    fg: '#a52432', bg: '#fdeef0', line: '#f0aab3',
  },
  waiting: {
    text: '대기중',
    title: '송장은 저장됐지만 마켓에는 아직 반영되지 않았습니다. 다음 사이클에 자동으로 다시 시도합니다.',
    fg: '#92600c', bg: '#fdf4e0', line: '#eccb8a',
  },
  synced: {
    text: '반영됨',
    title: '마켓도 같은 송장을 갖고 있습니다.',
    fg: '#1a6b4f', bg: '#e8f5ef', line: '#a8d8c3',
  },
  unknown: {
    text: '미확인',
    title: '마켓에 전송한 기록은 있지만, 마켓이 어떤 송장을 갖고 있는지 아직 확인하지 못했습니다. '
      + '반영 여부를 단정할 수 없어 그대로 표시합니다(구매확정 등으로 조회 목록에서 벗어난 주문이 여기 해당합니다).',
    fg: '#5a6270', bg: '#f2f3f5', line: '#d3d7dd',
  },
};

export const SOURCE_ICON = {
  EMAIL: {
    path: 'M638-80 468-250l56-56 114 114 226-226 56 56L638-80ZM480-520l320-200H160l320 200Zm0 80L160-640v400h206l80 80H160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v174l-80 80v-174L480-440Zm0 0Zm0-80Zm0 80Z',
    title: 'iHerb 발송메일이 확인해 준 진짜 송장입니다.',
    color: '#1a6b4f',
  },
  MANUAL: {
    path: 'M480-240Zm-320 80v-112q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q37 0 73 4.5t72 14.5l-67 68q-20-3-39-5t-39-2q-56 0-111 13.5T260-306q-9 5-14.5 14t-5.5 20v32h240v80H160Zm400 40v-123l221-220q9-9 20-13t22-4q12 0 23 4.5t20 13.5l37 37q8 9 12.5 20t4.5 22q0 11-4 22.5T903-340L683-120H560Zm300-263-37-37 37 37ZM620-180h38l121-122-18-19-19-18-122 121v38Zm141-141-19-18 37 37-18-19ZM367-527q-47-47-47-113t47-113q47-47 113-47t113 47q47 47 47 113t-47 113q-47 47-113 47t-113-47Zm169.5-56.5Q560-607 560-640t-23.5-56.5Q513-720 480-720t-56.5 23.5Q400-673 400-640t23.5 56.5Q447-560 480-560t56.5-23.5ZM480-640Z',
    title: '사람이나 마켓이 넣은 값입니다. 진짜인지 가송장인지 알 수 없습니다 — iHerb 메일이 도착하면 자동으로 진짜 송장으로 바뀝니다.',
    color: '#92600c',
  },
  LEGACY: {
    path: 'm424-296 282-282-56-56-226 226-114-114-56 56 170 170Zm56 216q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z',
    title: '이 기능이 생기기 전에 처리된 주문이라 출처가 기록돼 있지 않습니다(대부분 배송이 끝난 건입니다).',
    color: '#c4c8ce',
  },
} as const;

export const ORDER_SPANNED_COLUMNS = ['select', 'orderInfo', 'shippingStatus'];

export const LINEITEM_SPANNED_COLUMNS = ['sbCode', 'stockInfo', 'quantity', 'unipass', 'purchaseStatus', 'fulfillmentInfoPair', 'sourcingInfoPair'];

export const TWO_ROW_COLUMNS = ['ordererInfo', 'customsInfo', 'shippingInfoPair', 'productNamePair', 'financialInfoPair'];

export const ORDER_COLUMNS: string[] = [];

export const PRODUCT_COLUMNS: string[] = [];

export const TERMINAL_STATUSES = ['CANCELED', 'RETURNED', 'EXCHANGED'];

export const ALL_STATUSES = ['UNKNOWN', 'NEW', 'PREPARING', 'DISPATCHED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RETURNED', 'EXCHANGED'];

export const DEFAULT_VISIBLE_STATUSES = ALL_STATUSES.filter(s => !TERMINAL_STATUSES.includes(s));

export const FILTER_OPEN_KEY = 'sbshop.orderFilter.open';
