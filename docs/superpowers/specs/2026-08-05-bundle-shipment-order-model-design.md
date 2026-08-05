# 묶음배송·다품목 주문 모델 설계

- 작성일: 2026-08-05
- 계기: 11번가 주문 `20260731088778989`(정나영) 상태 오표시 신고 → 조사 중 **전 마켓이 다품목 주문을 표현하지 못한다**는 구조적 결함 확인
- 관련 결함: D-126(배송중 목록이 진행상태 덮어씀) · D-127(발송처리 배송번호 오류) · D-129(마켓 반영 여부 표시)

---

## 1. 문제

### 1.1 발단이 된 사례

11번가 주문 `20260731088778989` 하나에 상품주문이 **2건**이다.

| 순번(`ordPrdSeq`) | 상품 | 11번가 진행상태 | 금액 |
|---|---|---|---|
| 1 | 쏜리서치 Calcium Magnesium | 결제완료 | 57,700 |
| 2 | 쏜리서치 베이직 뉴트리언트 | 발송완료 | 52,800 |

우리 DB에는 라인아이템이 **1건**만 있고, 그 1건이 두 상품주문의 정보를 뒤섞어 갖고 있었다.

- 상품·정산액은 순번 1 기준 (`product_id=312` Calcium Magnesium, 정산 47,314 = 57,700 × 0.82)
- 송장은 순번 2의 것 (`424079080471`)
- **순번 2(52,800원)는 시스템에 존재하지 않는다** → 구매·발주·정산 대상에서 누락

즉 한 행이 두 상품의 키메라가 됐고, 어느 쪽 상태를 보여줘도 틀린다. D-126에서 "진행상태 축이 배송 축을 이긴다"는 규칙으로 상태를 `NEW`로 고정했지만, 그 규칙의 전제(4개 목록이 서로 다른 축을 본다)가 **틀렸다**. 실제로는 두 목록이 각각 **다른 상품주문**을 돌려주고 있었다.

### 1.2 범위 — 11번가만의 문제가 아니다

라이브 DB 실측(2026-08-05): **모든 마켓이 주문당 라인아이템 정확히 1건**이다.

| 마켓 | 주문 | 라인아이템 | 다품목일 때 현재 동작 |
|---|---|---|---|
| 쿠팡 | 191 | 191 | `orderItems.get(0)`만 사용 → **2번째부터 완전 유실** |
| N스토어 | 22 | 22 | `productOrderId`를 주문번호로 사용 → 유실은 없으나 **한 주문으로 안 묶임** |
| 11번가 | 14 | 14 | `ordNo`로만 키잉 → **서로 덮어씀** |
| G마켓/옥션 | 12 | 12 | `items[]` 순회는 하나 갱신 시 **배열 인덱스로 짝짓기** |

Cafe24만 라인아이템 다건 생성이 가능하지만, 갱신 시 `items.size() == lineItems.size()`일 때 **인덱스 순서로** 짝지어 반영한다(`Cafe24OrderSyncService.applyItemShipping`). 마켓이 순서를 바꾸면 엉뚱한 상품에 송장이 붙는다.

**피해 규모는 현재 셀 수 없다.** 다품목이었다는 사실 자체를 저장하지 않아, 과거 몇 건이 유실됐는지 DB로 알 수 없다.

---

## 2. 확인된 API 사실

설계의 모든 근거다. 추측과 구분하기 위해 출처를 함께 적는다.

### 2.1 11번가 (오픈API 문서, 2026-08-05 확인)

**목록 조회 행 = 상품주문 단위**
`/rest/ordservices/complete/{ordNo}` 응답 `ns2:order`에 `ordPrdSeq`가 있고, `ordPayAmtPerSeq`(순번별 결제금액)·`sellerDscPrcPerSeq`·`tmallDscPrcPerSeq`(순번별 할인)처럼 **"순번별" 필드가 명시**돼 있다. 한 주문에 상품 N개면 행 N개.

**묶음배송 필드**
- `bndlDlvSeq` (Integer) — 묶음배송일련번호
- `bndlDlvYN` (Enum) — Y: 묶음배송 / N: 개별배송
- `dlvNo` (String) — 배송번호

**배송번호는 묶음 단위** — 부분발송처리 에러코드 `-3308` 설명:
> 11번가는 묶음 배송번호 기준으로, 묶음배송번호가 같은 주문번호는 배송번호(`dlv_no`)를 **한 번만 호출하여도 나머지 주문번호에 해당하는 주문상태가 모두 발송처리**됩니다.

**발송처리 두 가지**
```
전체:  /rest/ordservices/reqdelivery/{sendDt}/{dlvMthdCd}/{dlvEtprsCd}/{invcNo}/{dlvNo}
부분:  /rest/ordservices/reqdelivery/{sendDt}/{dlvMthdCd}/{dlvEtprsCd}/{invcNo}/{dlvNo}/{partDlvYn}/{ordNo}/{ordPrdSeq}
```
`partDlvYn=Y`, `ordPrdSeq`는 **복수 지정 가능**(`1,2` 형태). 배송주체가 "업체배송"인 주문만 사용 가능하며, 추가구성상품만 부분발송은 불가.

**상태 조회 — 우리가 안 쓰던 API**
```
/rest/claimservice/orderlistall/{ordNo}      ← ordNo 콤마 구분, 최대 100건
```
응답이 `ordPrdSeq`별 행으로 오고 행마다 `ordPrdStatNm`(배송중·배송준비중·반품신청·취소신청…)·`ordPrdStat`·`dlvNo`·`prdNm`·`prdNo`·`ordQty`·`ordCnQty`(취소수량)를 준다. 현재 쓰는 `claimservice/orderlistalladdr`(주소 포함 상세)와 **다른 API**다.

**미사용 필드 중 가치 있는 것**
- `stlPlnAmt` — 정산예정금액 (현재는 요율 곱해 추정 중)
- `plcodrCnfDt` — 발주확인일시
- `sndEndDt` — 발송처리일 / `invcNo` — 송장번호 (목록에 이미 포함)
- `prdNo`(11번가 상품번호) · `sellerStockCd`(판매자 재고번호)

### 2.2 쿠팡 (코드 기반 확인)

- 주문 단위 `orderId`, **배송 단위 `shipmentBoxId`**, 상품 단위 `orderItems[]`
- **모든 쓰기가 `shipmentBoxId` 기준**: 발주확인(`ordersheets/acknowledgement`, `shipmentBoxIds` 배열) · 송장등록 · 송장수정
- 현재 `sb_order.shipment_box_id`에 배송박스를 **하나만** 보관 → 다박스 주문이 오면 11번가와 같은 방식으로 뭉개진다(아직 사례 없음)
- **미확인**: `ordersheets` 응답이 shipmentBox 단위 행인지 문서로 재확인 필요(코드상 그렇게 다루고 있음)

### 2.3 N스토어 (커머스API 문서, 2026-08-05 확인)

- 계층: `order.orderId` → `productOrder.productOrderId`
- **`productOrder.packageNumber` — 묶음배송번호** (string)
- `delivery`는 `productOrder` 하위 객체: `trackingNumber` · `deliveryCompany` · `deliveryStatus` · `sendDate` · `deliveredDate` · `isWrongTrackingNumber`
- **발송처리는 상품주문 단위**
  ```
  POST /v1/pay-order/seller/product-orders/dispatch
  dispatchProductOrders: [{ productOrderId, deliveryMethod, deliveryCompanyCode, trackingNumber, dispatchDate }]  // 최대 30건
  ```
  요청에 배송 식별자가 없다. 응답도 `successProductOrderIds` / `failProductOrderInfos[].productOrderId`.
- **현재 우리는 `order.orderId`를 읽지도 않고** `productOrderId`를 `marketOrderNo`로 쓰고 있다
- 미사용 필드: `expectedSettlementAmount`(정산예정금액) · `paymentCommission`·`saleCommission`·`channelCommission` · `placeOrderStatus`/`placeOrderDate` · `initialQuantity`/`remainQuantity`(부분취소)

### 2.4 Cafe24 / G마켓·옥션 (코드 기반 확인)

- `GET|POST /admin/orders/{order_id}/shipments` — **배송이 별도 리소스**
- 주문 응답의 `items[]`가 상품 단위
- D-124에서 `fetchShipments`를 구현했으나, 여러 배송건 중 **첫 번째만 취하고 나머지는 버린다**(`Cafe24ShipmentTrackingLookup`) — 담을 곳이 없어서다
- **미확인**: shipments 응답에서 배송↔상품(items) 매핑을 어떻게 표현하는지

### 2.5 결론

**4개 마켓 모두 "주문 — 묶음/배송 — 상품주문" 3계층을 갖는다.** 쓰기 단위만 N스토어가 상품주문이고 나머지 셋은 배송이다.

---

## 3. 도메인 모델

```
Order          마켓 주문번호. 수취인·주소·통관번호·주문일 등 주문 공통 정보
 └─ Shipment   마켓 배송식별자. 송장·택배사·발송일·배송상태          ← 신설
     └─ LineItem  마켓 상품주문번호. 상품·수량·금액·진행상태·구매(소싱)정보
```

### 3.1 각 계층이 갖는 것과 그 이유

**Order** — 한 번의 주문에서 변하지 않는 것. 수취인, 배송지, 통관번호, 주문일시, 주문자.
정나영 건에서 보듯 상품마다 상태가 갈려도 **배송지와 수취인은 하나**다.

**Shipment** — 물리적으로 함께 나가는 단위. 송장 1개가 곧 Shipment 1개다.
마켓 식별자 매핑:

| 마켓 | `market_shipment_no`에 담을 값 |
|---|---|
| 11번가 | `dlvNo` (배송번호) |
| 쿠팡 | `shipmentBoxId` |
| N스토어 | `packageNumber` (없으면 `productOrderId` 폴백 — 아래 3.3) |
| Cafe24 | shipment의 `shipping_code` 등 식별자 |

**LineItem** — 상품주문 1건. 상품, 수량, 금액, **진행상태**, 그리고 우리 고유 정보(소싱처·실구매가·구매상태).

### 3.2 상태를 라인아이템에 두는 이유

`shipping_status`는 **라인아이템**에 남긴다. 정나영 건이 근거다 — 같은 주문·같은 배송지인데 순번 1은 결제완료, 순번 2는 발송완료다. 상태를 주문이나 배송에 두면 이 사실을 표현할 수 없다.

Shipment에는 **배송 자체의 상태**(집화/배송중/배송완료)만 둔다. 마켓이 주는 배송상태(N스토어 `deliveryStatus`, 11번가 배송추적)가 있으면 담고, 없으면 비운다.

### 3.3 N스토어 `packageNumber`가 없을 때

묶음이 아닌 단건 주문은 `packageNumber`가 비어 있을 수 있다. 이때는 **`productOrderId`를 배송 식별자로 삼아 Shipment 1 : LineItem 1**로 만든다. 배송 계층이 항상 존재하므로 상위 로직에 분기가 생기지 않는다.

같은 원칙을 다른 마켓에도 적용한다 — 배송 식별자를 못 얻으면 상품주문번호로 대체하고, 그 사실을 로그로 남긴다. **배송이 없는 주문은 만들지 않는다.**

---

## 4. 스키마

### 4.1 신설: `sb_shipment`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | |
| `order_id` | bigint FK → `sb_order` | |
| `market_shipment_no` | varchar(100) | 마켓 배송식별자 (dlvNo/shipmentBoxId/packageNumber/shipping_code) |
| `tracking_no` | varchar(100) | |
| `shipping_carrier` | varchar(30) | |
| `delivery_status` | varchar(30) | 마켓이 주는 배송 자체 상태. 없으면 null |
| `tracking_sent_to_market` | boolean | 마켓이 이 송장을 보유하는가 (D-129 의미) |
| `shipped_at` | timestamp | 발송처리일 |
| `created_at` / `updated_at` | timestamp | |

제약: `UNIQUE (order_id, market_shipment_no)`

### 4.2 `sb_order_line_item` 변경

| 컬럼 | 변경 | 설명 |
|---|---|---|
| `market_line_item_no` | **신설** varchar(100) | 마켓 상품주문번호 (ordPrdSeq/productOrderId/vendorItemId/item 식별자) |
| `shipment_id` | **신설** bigint FK → `sb_shipment` nullable | |
| `tracking_no`, `shipping_carrier`, `tracking_sent_to_market` | **유지(미러)** | 아래 4.4 |

제약: `UNIQUE (order_id, market_line_item_no)` — 동기화의 매칭 키

### 4.3 `sb_order` 변경

`shipment_box_id`는 Shipment로 이관 후 **제거 예정**(단계 3 쿠팡 전환 시). 그전까지는 유지.

### 4.4 미러 유지 전략

라인아이템의 `tracking_no`·`shipping_carrier`·`tracking_sent_to_market`은 **Shipment의 값을 복제해 유지**한다. 이유는 하나다 — 기존 그리드·엑셀 내보내기·정산 쿼리·이메일 파이프라인이 전부 이 컬럼을 읽는다. 한 번에 다 고치면 검증 범위가 통제 불가능해진다.

규칙:
- **쓰기는 Shipment가 단일 원본.** 라인아이템 컬럼에 직접 쓰는 코드는 남기지 않는다
- Shipment 저장 시 소속 라인아이템에 같은 값을 내려쓴다(한 트랜잭션)
- 모든 소비처가 Shipment를 보도록 옮긴 뒤 미러 컬럼을 제거한다(단계 5 이후)

---

## 5. 동기화 설계

### 5.1 어댑터 계약

각 마켓 어댑터가 마켓 응답을 **(주문 / 배송 / 상품주문) 3-튜플**로 정규화해 넘긴다. 상위 서비스는 마켓을 모른다.

```
MarketOrderDto
 ├─ marketOrderNo          주문 식별자
 ├─ 주문 공통 필드          수취인·주소·통관번호·주문일…
 └─ shipments[]
     ├─ marketShipmentNo   배송 식별자
     ├─ trackingNo / carrier / shippedAt / deliveryStatus
     └─ lineItems[]
         ├─ marketLineItemNo  상품주문 식별자
         ├─ 상품·수량·금액
         └─ status            상품주문 진행상태
```

기존 `MarketOrderDto`는 평면 구조라 **이 중첩 구조로 확장**한다. 단일 상품 주문은 `shipments[0].lineItems[0]` 하나짜리로 표현되므로 특수 케이스가 없다.

### 5.2 upsert 규칙

`MarketOrderUpsertDispatcher`를 3계층으로 확장한다.

1. `marketOrderNo`로 Order 조회 → 없으면 생성, 있으면 주문 공통 필드 갱신
2. 각 shipment를 `(order_id, market_shipment_no)`로 조회 → upsert
3. 각 lineItem을 `(order_id, market_line_item_no)`로 조회 → upsert, `shipment_id` 연결

**핵심: 매칭은 마켓 식별자로만 한다.** 배열 인덱스·순서에 의존하지 않는다(Cafe24 현재 방식의 결함).

D-126에서 도입한 "목록 병합 + 등급 우선순위"는 **제거한다.** 목록이 상품주문 단위로 오는 것이 확인됐으므로, 같은 `ordPrdSeq`가 두 목록에 동시에 나오는 일은 없다. 만약 나온다면 그건 실제 상태 전이 중인 것이고, `orderlistall` 조회로 판정한다(5.3).

### 5.3 11번가 상태 판정 — `orderlistall` 도입

현재는 4개 목록(결제완료/배송준비중/배송중/배송완료)을 긁어 "어느 목록에서 왔는가"로 상태를 정한다. 이 방식이 D-126을 낳았다.

바꾼다:
1. 4개 목록은 **주문 발견과 상세 정보 수집**에만 쓴다
2. 상태는 `claimservice/orderlistall/{ordNo}`의 `ordPrdStatNm`으로 판정한다 — 행마다 상품주문 상태를 직접 준다
3. `ordNo` 콤마 100건까지 묶을 수 있으므로 호출 비용이 낮다

이러면 "목록 소속으로 상태를 추론"하는 구조 자체가 사라진다.

### 5.4 기존 데이터 처리

동기화는 최근 30일을 매 사이클 다시 훑으므로, **30일 이내 주문은 새 규칙으로 자동 재구성**된다. 별도 배치가 필요 없다.

문제는 **기존 라인아이템에 붙은 우리 고유 정보**(소싱처·소싱주문번호·실구매가·구매상태)다. 라인아이템 1건이 2건으로 쪼개질 때 이 정보를 어디에 붙일지 자동으로 알 수 없다.

규칙:
1. 기존 라인아이템에 `market_line_item_no`가 없으면, **`product_id`가 일치하는 상품주문**에 매칭한다 (정나영 건 → 순번 1)
2. 매칭되지 않은 상품주문은 **신규 라인아이템으로 생성**한다 (구매정보 없음 = 미구매 상태로 노출되는 것이 정확하다)
3. 매칭된 라인아이템에 **다른 상품주문의 값이 섞여 있던 흔적**(예: 송장이 다른 배송에 속함)은 지우지 않고 `⚠ 확인 필요` 목록에 올려 사람이 판단한다
4. 30일 이전 주문은 손대지 않는다 — 대부분 종결 상태이고, 건드리면 정산 이력이 흔들린다

3번의 "확인 필요" 목록은 **운영 화면이 아니라 로그 + 액션로그**로 남긴다. 건수가 적을 것으로 예상되며(다품목 주문 자체가 드물다), 많아지면 그때 화면을 만든다.

---

## 6. 쓰기 경로

### 6.1 발송처리

| 마켓 | 호출 | 단위 |
|---|---|---|
| 11번가 (배송 전체) | `reqdelivery/…/{dlvNo}` | Shipment 1회 |
| 11번가 (부분) | `reqdelivery/…/{dlvNo}/Y/{ordNo}/{ordPrdSeq목록}` | Shipment 1회 + 대상 라인아이템 지정 |
| 쿠팡 | 송장등록/수정 | Shipment 1회 (`shipmentBoxId`) |
| Cafe24 | `POST /orders/{id}/shipments` | Shipment 1회 |
| N스토어 | `dispatch` | Shipment에 속한 라인아이템 배열 1회 (최대 30) |

**호출 단위가 Shipment로 통일된다.** 11번가 `-3308`(이미 발송처리됨) 같은 중복 호출 오류가 구조적으로 사라진다.

부분발송 판정: Shipment에 속한 라인아이템 중 **일부만** 발송 대상이면 `partDlvYn=Y`로 해당 `ordPrdSeq`만 지정한다. 전부면 전체 발송처리를 쓴다.

### 6.2 발주확인

| 마켓 | 단위 |
|---|---|
| 11번가 | 상품주문(`ordPrdSeq`) — 라인아이템별 |
| 쿠팡 | 배송(`shipmentBoxIds` 배열) — Shipment별 |
| N스토어 | 상품주문(`productOrderId`) |
| Cafe24 | 주문 |

단위가 마켓마다 다르므로 **어댑터가 흡수**한다. 상위 서비스는 "이 라인아이템들을 발주확인" 하나만 요청하고, 어댑터가 자기 단위로 묶어 호출한다.

이는 미해결 항목 **"11번가 발주확인이 마켓에 반영되지 않음"**의 유력한 원인이기도 하다 — 다품목 주문에서 `ordPrdSeq=1`만 발주확인하고 순번 2는 안 했다면, 주문은 결제완료 목록에 계속 남는다. `plcodrCnfDt`(발주확인일시)를 읽어 검증할 수 있다.

### 6.3 D-127 재검토

D-127에서 `shipOrder`가 `dlvNo` 자리에 주문번호를 넘기던 것을 `marketSpecificData.dlvNo`로 고쳤다. 문서로 확인한 결과 **방향은 옳았다**(마지막 경로변수는 배송번호가 맞다). 다만 다품목 주문에서는 전체 발송처리가 **의도치 않게 묶음 전체를 발송처리**할 수 있으므로(-3308 설명 참조), 부분발송 분기가 필요하다. 이 설계가 그것을 담는다.

---

## 7. 화면

그리드의 **행 구조는 그대로 둔다.** 현재 그리드는 이미 `lineItems`를 순회해 라인아이템마다 3행을 그리므로, 라인아이템이 2건이 되면 자연히 2벌이 렌더된다. 즉 다품목 표시는 프론트 변경 없이도 동작한다.

그 위에 더할 것:
- 같은 Shipment에 속한 라인아이템을 **시각적으로 묶어** 표시 (송장이 하나임을 드러냄)
- 송장 편집은 **Shipment 단위** — 같은 배송의 라인아이템 중 하나를 고쳐도 묶음 전체에 반영된다

D-129의 `⚠ 마켓 미반영` 배지는 Shipment의 `tracking_sent_to_market`을 보도록 옮긴다.

---

## 8. 단계적 롤아웃

각 단계마다 배포 → 라이브 확인 → 다음 단계. 되돌리기 쉽게 유지한다.

**단계 하나가 구현 계획 하나다.** 이 설계 전체를 한 번에 구현하지 않는다. 먼저 1단계만 계획을 세워 끝내고, 라이브 확인 후 2단계 계획을 세운다.

**1단계 — 공통 기반**
`sb_shipment` 테이블, 도메인 엔티티, 리포지토리, 중첩 `MarketOrderDto`, 3계층 upsert 디스패처. 마켓 어댑터는 아직 안 건드린다(모두 Shipment 1 : LineItem 1로 자동 래핑).
→ 이 단계는 **동작이 바뀌지 않아야 한다.** 회귀 테스트로 확인.

**2단계 — 11번가**
`orderlistall` 상태조회 도입, `ordPrdSeq` 단위 라인아이템 분리, `dlvNo`/`bndlDlvSeq` 기반 Shipment, 부분발송처리, D-126 병합 로직 제거.
→ 정나영 건이 2행으로 정확히 나뉘는지 확인.

**3단계 — 쿠팡**
`orderItems[]` 전량 파싱, `shipmentBoxId` → Shipment, `sb_order.shipment_box_id` 제거.

**4단계 — Cafe24(G마켓·옥션)**
`shipments[]` 리소스를 Shipment로 매핑, 인덱스 짝짓기 제거.

**5단계 — N스토어**
`orderId`를 주문번호로 전환(현재 `productOrderId`), `packageNumber` → Shipment, `dispatch` 배열 호출.
→ **기존 22건의 주문번호가 바뀐다.** 별도 이전 계획 필요(9.2).

**6단계 — 정리**
라인아이템 미러 컬럼 제거, 소비처를 Shipment로 이관.

---

## 9. 위험과 대응

### 9.1 정산 왜곡

라인아이템이 쪼개지면 정산액도 쪼개진다. 현재는 요율을 곱해 추정하므로 **분배 기준이 모호**하다.

대응: 마켓이 주는 실측 정산예정금액을 쓴다 — 11번가 `stlPlnAmt`, N스토어 `expectedSettlementAmount`. 상품주문별로 오므로 분배 문제가 사라진다. 쿠팡·Cafe24는 별도 확인 필요.
(이는 D-122 "수수료율 가정 괴리"의 근본 해법이기도 하다. **별도 항목으로 분리해 진행**한다 — 이번 설계의 필수 선행은 아니다.)

### 9.2 N스토어 주문번호 전환

`marketOrderNo`가 `productOrderId` → `orderId`로 바뀌면 기존 22건의 키가 달라진다. `sb_order.market_order_no`에 UNIQUE 제약이 있어 충돌 가능.

대응: 5단계에서 별도 이전 스크립트로 처리한다. 이 설계에서는 **범위 밖**으로 두고, 5단계 착수 전에 별도 계획을 세운다.

### 9.3 30일 경계

동기화 창(30일)을 벗어난 주문은 새 구조로 재구성되지 않아 라인아이템 1건인 채로 남는다. 의도된 것이다 — 종결 주문을 건드리면 정산 이력이 흔들린다. 미러 컬럼이 남아 있는 동안 기존 화면에서 정상 표시된다.

---

## 10. 테스트 전략

**단위** — 각 마켓 어댑터가 다품목 응답을 3계층으로 정규화하는지. 실제 API 응답 형태를 픽스처로 고정한다(문서의 예제 XML/JSON 사용).

**계약 고정** — 이번 조사로 확인한 사실을 테스트로 박는다:
- 11번가 목록 행이 `ordPrdSeq` 단위임
- 같은 `dlvNo`의 라인아이템은 한 Shipment에 묶임
- 부분발송이 `ordPrdSeq` 목록을 콤마로 전달함
- N스토어 `packageNumber`가 없으면 `productOrderId`로 폴백함

**회귀** — 단일 상품 주문(현재 데이터의 대부분)이 동작 불변인지. 1단계에서 특히 중요하다.

**재현** — 정나영 건(`20260731088778989`)을 픽스처로 만들어, 순번 1=NEW·순번 2=SHIPPED로 각각 분리되고 송장이 순번 2에만 붙는지 검증한다.

---

## 11. 범위 밖

이번 설계에 포함하지 않는다. 필요하면 별도로 다룬다.

- **취소·반품·교환(클레임)** — 부분취소(`ordCnQty`·`remainQuantity`)까지 얽혀 범위가 커진다
- **정산 실측값 도입** — 9.1 참조. 독립 항목
- **N스토어 주문번호 전환 이전 계획** — 9.2 참조
- **거래명세·CS 화면** — 라인아이템 분리 후 재검토

---

## 12. 미확정 사항

정직하게 남긴다. 구현 중 확인이 필요하다.

1. **쿠팡 `ordersheets` 응답 구조** — shipmentBox 단위 행인지 문서로 재확인. 코드는 그렇게 다루고 있으나 문서 미확인
2. **Cafe24 shipments ↔ items 매핑** — 배송건이 어느 상품을 담는지 응답이 어떻게 표현하는지
3. **쿠팡·Cafe24 정산 실측값 제공 여부**
4. **11번가 발주확인 미반영 원인** — 6.2의 가설(다품목 중 일부만 확인)을 `plcodrCnfDt`로 검증
