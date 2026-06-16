# 주문 동기화 아키텍처

## 도메인 모델

### 엔티티

#### `Order` (`sb_order`)

| VO | 컬럼 | 타입 | 출처 | 설명 |
|----|------|------|------|------|
| — | `market_type` | enum `MarketType` | Adapter | COUPANG, SMART_STORE, ELEVEN_STREET, GMARKET, AUCTION |
| — | `market_order_no` | String | Adapter | 마켓별 고유 주문번호 |
| — | `order_date` | LocalDateTime | Adapter | 결제확정 시각 |
| — | `recipient_name` | String | detail | 수령인 |
| — | `recipient_phone` | String | detail | 수령인 전화번호 |
| — | `zipcode` | String | detail | 우편번호 |
| — | `address` | String | detail | 기본주소 + 상세주소 |
| — | `message` | String | detail | 배송메시지 |
| — | `orderer_name` | String | detail | 주문자명 |
| — | `orderer_phone` | String | detail | 주문자 전화번호 |
| — | `shipment_box_id` | String | 쿠팡만 | 묶음배송번호 (발주확인용) |
| CustomsData | `customs_clearance_no` | String | detail | 개인통관고유부호 |
| CustomsData | `customs_status` | enum `CustomsStatus` | 통관 확인 | PENDING / VALID / INVALID |

#### `OrderLineItem` (`sb_order_line_item`)

| VO | 컬럼 | 타입 | 출처 | 설명 |
|----|------|------|------|------|
| — | `order_id` | Long (FK) | — | Order 참조 |
| — | `product_id` | Long (FK) | 코드 매칭 | SB 상품 ID |
| — | `quantity` | int | Adapter | 주문 수량 |
| — | `market_product_name` | String | Adapter | 마켓 상품명 (vendorItemName 등) |
| — | `market_product_code` | String | Adapter | SB 코드 / 판매자 상품 코드 |
| — | `seller_product_name` | String | Adapter | 마켓 등록 상품명 |
| SourcingData | `sourcing_vendor` | String | 사용자 입력 | 소싱처 (IHB 등) |
| SourcingData | `sourcing_account` | String | 사용자 입력 | 구매계정 |
| SourcingData | `sourcing_order_no` | String | 사용자 입력 | 소싱주문번호 |
| SourcingData | `sourcing_amount` | BigDecimal | 사용자 입력 | 소싱금액 (원가) |
| SourcingData | `discount_code` | String | 사용자 입력 | 할인코드 |
| SettlementData | `sale_price` | BigDecimal | Adapter | 판매가 (단가) |
| SettlementData | `settlement_amount` | BigDecimal | Adapter / 정산 동기화 | 정산금액 |
| SettlementData | `shipping_fee` | BigDecimal | 사용자 입력 | 배송비 |
| SettlementData | `net_profit` | BigDecimal | 정산 동기화 | 순이익 (정산금액 - 소싱금액) |
| ShippingData | `tracking_no` | String | Adapter | 송장번호 |
| ShippingData | `shipping_status` | enum `ShippingStatus` | Adapter | 배송상태 |
| ShippingData | `shipping_carrier` | enum `ShippingCarrier` | Adapter | 택배사 |
| ShippingData | `is_unipass_done` | Boolean | 사용자 입력 | 유니패스 신고 완료 여부 |
| ShippingData | `marketplace_synced` | Boolean | 사용자 입력 | 마켓 송장 동기화 여부 |

### 열거형

#### `ShippingStatus` (우선순위 포함)
- `NEW(0)` — 결제완료
- `PREPARING(1)` — 구매준비
- `PURCHASED(2)` — 구매완료
- `SHIPPED(3)` — 배송중
- `DELIVERED(4)` — 배송완료
- `CANCELED(-1)` — 취소됨 (터미널)
- `RETURNED(-1)` — 반품됨 (터미널)
- `EXCHANGED(-1)` — 교환됨 (터미널)

**상태 다운그레이드 방지**: `updateShippingWithCarrier()`는 `ShippingStatus.isDowngrade()`를 검사 — SHIPPED → PREPARING 절대 이동 불가. 터미널 상태(-1)는 항상 허용.

---

## 아키텍처

### 계층 구조

```
┌──────────────────────────────────────┐
│  API 계층 (OrderController)          │
│  OrderSyncController                 │
├──────────────────────────────────────┤
│  애플리케이션 계층                    │
│  ┌──────────────────────────────┐    │
│  │  AbstractOrderSyncService    │    │
│  │  (템플릿 메서드)              │    │
│  │  ├─ CoupangOrderSyncService  │    │
│  │  ├─ SmartStore...            │    │
│  │  ├─ Elevenst...              │    │
│  │  └─ Esmplus...               │    │
│  └──────────┬───────────────────┘    │
│  ┌──────────▼───────────────────┐    │
│  │  MarketOrderPort (인터페이스) │    │
│  │  └── Adapter 구현체           │    │
│  └──────────────────────────────┘    │
│  OrderService, OrderShipService      │
│  StatusMappers (마켓별)               │
├──────────────────────────────────────┤
│  도메인 계층                          │
│  Order, OrderLineItem 엔티티          │
│  VO, Enum, Repository 인터페이스      │
├──────────────────────────────────────┤
│  인프라스트럭처 계층                  │
│  API 클라이언트 (REST/Selenium/XML)  │
│  Repository 구현체 (JPA)             │
├──────────────────────────────────────┤
│  Worker 계층                         │
│  OrderSyncScheduler                  │
│  OrderEmailParser (IMAP)             │
└──────────────────────────────────────┘
```

### 포트와 어댑터 패턴

각 마켓은 3계층 구조:

1. **`AbstractOrderSyncService` 하위클래스** — 동기화 생명주기 조율, 마켓 특화 훅 구현
2. **`MarketOrderPort` 어댑터** — API 응답을 `MarketOrderDto`로 정규화
3. **인프라 클라이언트** — 원시 HTTP/Selenium 통신

```
SyncService → Adapter (MarketOrderPort) → ApiClient (infra)
```

### 템플릿 메서드 패턴

`AbstractOrderSyncService.syncOrders()`는 `final`:

```
1. 중복 실행 방지 (AtomicBoolean)
2. loadAndValidateCredential()
3. port.fetchOrders(fromDate, toDate)
4. processOrders(orders) ← 주문별 로직
5. postSyncProcess(orders) ← 훅
```

`processOrders(dto)` 판단 흐름:

```
for each dto:
  resolveP000xCode() → 변환된 marketProductCode 설정
  marketOrderNo로 기존 주문 조회
  
  if 기존주문 && (alwaysFetchDetail || 상품코드 없음):
    fullDto = port.fetchOrderDetail(credential, dto)
    if fullDto: updateExistingOrder(fullDto)
    else:       updateExistingOrder(dto)  // 송장만 업데이트
  elif 기존주문:
    updateExistingOrder(dto)
  elif alwaysFetchDetail || 상품코드 없음:
    fullDto = port.fetchOrderDetail(credential, dto)
    if fullDto: createNewOrder(fullDto)
    elif 상품코드 있음: createNewOrder(dto)
    else: 건너뜀
  elif 상품코드 있음:
    createNewOrder(dto)
  else:
    건너뜀
```

---

## 마켓별 통합

### 쿠팡

| 항목 | 상세 |
|--------|--------|
| **API 타입** | REST JSON, HMAC 서명 |
| **인증** | AccessKey + SecretKey + VendorId |
| **주문 조회** | 6개 상태 순차 조회 (ACCEPT, INSTRUCT, DEPARTURE, DELIVERING, FINAL_DELIVERY, NONE_TRACKING), 1초 간격 |
| **정산** | 전용 `querySettlement()` — sales-details API |
| **P000x 코드 변환** | `externalVendorSkuCode`가 `P000`으로 시작하면 `sellerProductName`/`vendorItemName` → `ProductRepository.findByProductNameProductNameContaining()`으로 조회. 또한 `sellerProductId`로 `queryProduct` API를 호출하여 올바른 `externalVendorSku` 획득. |
| **후처리** | `detectCancellations()` — DB에는 있지만 API 결과에 없는 주문 → CANCELED. `fixCarriers()` — ETC 택배사나 송장번호 누락 → API에서 보정. |
| **alwaysFetchDetail** | false (목록 API가 전체 데이터 반환) |
| **shipOrder** | `coupangOrderApiPort.shipOrder()` 호출 |
| **acceptOrders** | `coupangOrderApiPort.acceptOrders()`에 `shipmentBoxId` 전달 |
| **상태 매퍼** | `CoupangStatusMapper` — 쿠팡 API 상태코드를 `ShippingStatus`로 매핑 |

**주문 상세 페이지**: https://wing.coupang.com/orders/{orderId}

### 스마트스토어 (네이버)

| 항목 | 상세 |
|--------|--------|
| **API 타입** | REST JSON, OAuth2 + BCrypt 토큰 교환 |
| **인증** | ClientId + SecretKey |
| **주문 조회** | 1일 단위 청크 (API 제한), `productOrderStatus`로 조회 |
| **통관번호** | "undefined" 값 필터링 |
| **alwaysFetchDetail** | false (목록 API가 전체 데이터 반환) |
| **shipOrder** | `smartStoreOrderApiPort.shipOrder()` 호출 |
| **acceptOrders** | `smartStoreOrderApiPort.confirmOrders()` 호출 |
| **참고** | 주문자(ordererName)와 수취인(receiver)이 다를 수 있음. Order의 recipientName은 배송지 주소의 name 사용. |

### 11번가

| 항목 | 상세 |
|--------|--------|
| **API 타입** | XML over REST |
| **인증** | API Key (accessKey) |
| **주문 조회** | 7일 청크 × 4개 엔드포인트: `fetchCompletedOrders` (전체), `fetchPackagingOrders` (전체), `fetchShippingOrders` (최소), `fetchCompletedDeliveryOrders` (전체). `parseShippingElement`는 최소 데이터만 반환 (송장번호/택배사/상태). |
| **주문 상세** | `fetchOrderDetail()` — 개별 주문 상세 API로 폴백. 배송중 상태 주문 보완용. |
| **주문일** | 주문번호에서 추출 (`ordNo.substring(0,8)`) |
| **alwaysFetchDetail** | false (completed/packaging/dlvcompleted 목록 API가 전체 데이터 반환) |
| **shipOrder** | `elevenstOrderApiPort.shipOrder()` 호출 |
| **acceptOrders** | `UnsupportedOperationException` — 엔티티에 저장되지 않은 상품별 시퀀스 데이터 필요 |
| **택배사 코드** | 5자리 숫자 (00034=CJ, 00011=한진 등) |

### ESM+ (G마켓/옥션)

| 항목 | 상세 |
|--------|--------|
| **API 타입** | Selenium/ChromeDriver 웹 스크래퍼 (REST API 없음) |
| **인증** | masterId + password |
| **주문 조회** | 로그인 → 주문목록 페이지 이동, 페이지에 포함된 JSON 스크래핑. CANCELED/EXCHANGED 스킵. |
| **주문 상세** | `fetchOrderDetail()` — 개별 주문 상세 페이지로 이동, HTML 테이블에서 전화번호/주소/우편번호/메시지 파싱 |
| **alwaysFetchDetail** | **true** — 목록 API에 전화번호/주소/우편번호/메시지 없음 |
| **세션 재사용** | `cachedDetailDriver` — 상세 조회 간 ChromeDriver 세션 재사용 (동기화 사이클당 1회 로그인) |
| **MarketType** | `siteId=1` → AUCTION, `siteId=2` → GMARKET |
| **shipOrder** | 미구현 (경고 로그) |
| **acceptOrders** | 미구현 (경고 로그) |

### 통관 확인 (GSI Express)

주문 동기화 흐름과 별개. `CustomsOrderSyncService`가 GSI Express 스크래퍼를 폴링하여 통관 상태 업데이트. `AbstractOrderSyncService`를 상속하지 않음.

---

## 필드 매핑 — 목록 API → MarketOrderDto

| MarketOrderDto 필드 | 쿠팡 | 스마트스토어 | 11번가 | ESM+ (목록) | ESM+ (상세) |
|---------------------|---------|------------|--------|-------------|---------------|
| marketOrderNo | `orderId` | `productOrderId` | `ordNo` | `siteOrderNo` | 동일 |
| marketProductCode | `externalVendorSkuCode` → `sellerProductId`로 변환 | `sellerProductCode` | `sellerPrdCd` | `goodsNo` | 동일 |
| productName | `vendorItemName` | `productName` | `prdNm` | `goodsName` | 동일 |
| quantity | `shippingCount` | `quantity` | `ordQty` | `orderQty` | 동일 |
| orderPrice | `orderPrice` | `totalPaymentAmount / quantity` | `selPrc` | `tradeAmnt / orderQty` | 동일 |
| totalAmount | `orderPrice * quantity` | `totalPaymentAmount` | `ordAmt` | `tradeAmnt` | 동일 |
| recipientName | `receiver.name` | `shippingAddress.name` | `rcvrNm` | `rcverName` | HTML 파싱 |
| recipientPhone | `receiver.safeNumber` (또는 `overseaShippingInfoDto.ordererPhoneNumber`) | `shippingAddress.tel1` | `rcvrPrtblNo` | `""` | HTML 파싱 |
| zipcode | `receiver.postCode` | `shippingAddress.zipCode` | `rcvrMailNo` | `""` | HTML 파싱 |
| address | `receiver.addr1 + addr2` | `shippingAddress.baseAddress + detailedAddress` | `rcvrBaseAddr + rcvrDtlsAddr` | `""` | HTML 파싱 |
| message | `parcelPrintMessage` | `shippingMemo` | `ordDlvReqCont` | `""` | HTML 파싱 |
| ordererName | `orderer.name` 또는 `overseaShippingInfoDto.ordererName` 또는 `recipientName` 폴백 | `ordererName` | `ordNm` | `buyerName` | 동일 |
| ordererPhone | `overseaShippingInfoDto.ordererPhoneNumber` | `ordererTel` | `ordPrtblTel` | `""` | 동일 |
| customsClearanceNo | `overseaShippingInfoDto.personalCustomsClearanceCode` | `individualCustomUniqueCode` ("undefined" 필터링) | `psnCscUniqNo` | — | — (ESM+ 없음) |
| trackingNo | `invoiceNumber` | `deliveryInfo.trackingNumber` | `invcNo` | `""` | 동일 |
| carrier | `deliveryCompanyName` → `ShippingCarrier.fromMarketCode()` | `deliveryInfo.deliveryCompany` → `fromMarketCode()` | `dlvEtprsCd` → 숫자 매핑 | CJ_LOGISTICS 기본값 | 동일 |
| status | CoupangStatusMapper 경유 | SmartStoreStatusMapper 경유 | ElevenstStatusMapper 경유 | EsmplusStatusMapper 경유 | 동일 |
| orderDate | `orderedAt` (ISO) | `orderDate` (ISO) | `ordNo`에서 추출 (앞 8자) | `depositConfirmDate` | 동일 |
| shipmentBoxId | `shipmentBoxId` | — | — | — | — |

---

## 동기화 동작

### 생성 (신규 주문)

`buildOrderFromDto()`는 `MarketOrderDto` → `Order`로 직접 매핑. `buildLineItemFromDto()`는 송장/택배사/상태가 포함된 `ShippingData`, 판매가/정산금액이 포함된 `SettlementData`도 함께 생성.

### 업데이트 (기존 주문)

`updateExistingOrder(order, dto)`:
1. 주문의 모든 `OrderLineItem` 조회
2. 각 라인아이템: `marketProductCode`가 다르거나 `sellerProductName`이 있으면 `updateLineItemFromDto()` 호출
3. 하나라도 업데이트되면 `updateOrderInfoFromDto()` 호출 후 저장

`updateLineItemFromDto()` 업데이트:
- `marketProductCode` — 변경 시 `findBySbCode()`로 `Product` 조회 후 `assignProductId()` 호출
- `sellerProductName`
- `trackingNo`, `status`, `carrier` — `updateShippingWithCarrier()`로 (다운그레이드 방지 포함)

`updateOrderInfoFromDto()` 업데이트:
- `marketType` (변경된 경우)
- `recipientName`, `recipientPhone`, `zipcode`, `address`, `message` (`order.updateInfo()` — null-safe, null이 아닌 값만 덮어씀)
- `ordererName`, `ordererPhone` (`updateOrdererInfo()` — 빈 문자열 안전)
- `customsClearanceNo` (`updateCustomsClearanceNo()`)
- `shipmentBoxId` (`updateShipmentBoxId()` — 빈 문자열 안전)

### 업데이트 시 덮어쓰지 않는 필드

다음 필드는 동기화 업데이트 시 **보존** (사용자 관리):
- `sourcingData` (sourcingVendor, sourcingAccount, sourcingOrderNo, sourcingAmount, discountCode) — API 명령으로만 설정
- `settlementData.shippingFee` — API로만 설정
- `settlementData.netProfit` — 쿠팡 정산 동기화 시에만 재계산
- `shippingData.isUnipassDone` — API로만
- `shippingData.marketplaceSynced` — API로만
- `customsData.customsStatus` — API/통관 확인으로만

### 필드 출처 우선순위

ESM+의 경우 `fetchOrderDetail()`은 **상세 특화 필드**만 채워진 DTO 반환 (전화번호/주소/우편번호/메시지). `AbstractOrderSyncService.updateOrderInfoFromDto()`는 null-safe 패턴으로 적용 — 상세 필드가 목록 API의 빈 칸을 채움. ESM+는 `alwaysFetchDetail()=true`이므로 상세 DTO로 완전한 DTO를 구성.

---

## 스케줄링

| 작업 | 간격 | 메서드 |
|------|----------|--------|
| IMAP 이메일 파싱 | 5분마다 | `emailFetcherService.fetchAndProcessEmails()` |
| 스마트스토어 동기화 | 1시간마다 | `smartStoreOrderSyncService.syncSmartStoreOrders()` |
| ESM+ 동기화 | 30분마다 | `esmplusOrderSyncService.syncEsmplusOrders()` |
| 쿠팡 정산 | 매일 새벽 2시 | `coupangOrderSyncService.syncCoupangSettlement()` |

쿠팡 주문 동기화는 고정 스케줄이 없음 — 수동 또는 컨트롤러를 통해 트리거.

---

## API 엔드포인트

| 엔드포인트 | 메서드 | 용도 |
|----------|--------|---------|
| `/api/v1/orders` | GET | 그리드 조회 (페이징 + 필터링) |
| `/api/v1/orders/{id}` | GET | 단건 주문 상세 |
| `/api/v1/orders/{id}` | PUT | 주문 정보 수정 (수취인, 주소 등) |
| `/api/v1/orders/sync/coupang` | POST | 쿠팡 동기화 트리거 |
| `/api/v1/orders/sync/smartstore` | POST | 스마트스토어 동기화 트리거 |
| `/api/v1/orders/sync/elevenst` | POST | 11번가 동기화 트리거 |
| `/api/v1/orders/sync/esmplus` | POST | ESM+ 동기화 트리거 |
| `/api/v1/orders/sync/settlement` | POST | 쿠팡 정산 동기화 트리거 |
| `/api/v1/orders/ship` | POST | 송장번호 등록 (배송처리) |
| `/api/v1/orders/accept` | POST | 발주확인 |

---

## 주요 설계 결정

1. **`alwaysFetchDetail()` 훅** — ESM+는 목록 API에 연락처/주소 데이터가 없어 `true`로 오버라이드. 쿠팡/스마트스토어/11번가는 목록 API에 전체 데이터 있음 (11번가 배송중 엔드포인트 제외 → fetchOrderDetail 폴백).

2. **P000x 코드 변환** — 쿠팡 주문 API의 `externalVendorSkuCode`는 부정확할 수 있음 (예: P0000NPQ000A). 어댑터가 `sellerProductId`로 `queryProduct` API를 호출하여 올바른 `externalVendorSku` 획득. 2차 폴백으로 `AbstractOrderSyncService.resolveP000xCode()`가 로컬 DB의 상품명으로 매칭.

3. **정산 동기화** — 쿠팡만 정산 동기화 보유. 야간 실행, 쿠팡 Sales Details API 조회, DELIVERED 주문만 `SettlementData.settlementAmount` 업데이트 및 `netProfit` 재계산.

4. **상태 다운그레이드 방지** — `ShippingStatus.isDowngrade()`가 SHIPPED → PREPARING 이동을 차단. 터미널 상태(CANCELED/RETURNED/EXCHANGED)는 항상 허용.

5. **취소 감지** — 쿠팡의 후처리 훅이 API 결과와 DB 비교. DB에는 있지만 API에 없는 주문은 CANCELED 처리 (이미 DELIVERED 또는 CANCELED인 경우 제외).

6. **ESM+ 세션 재사용** — `getOrCreateDetailDriver()`가 상세 조회 간 ChromeDriver 세션을 캐시하여 동기화 사이클당 9회 로그인을 1회로 줄임.

7. **ESM+ 통관번호 미지원** — ESM+ 주문 상세 페이지와 API는 개인통관고유부호를 노출하지 않음. 통관번호는 일부 마켓 플랫폼에서 해외배송 주문(globalShopType != KR)에만 제공.
