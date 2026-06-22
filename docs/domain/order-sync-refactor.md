# 주문 동기화 아키텍처

## 도메인 모델

### 엔티티

#### `Order` (`sb_order`)

| VO | 컬럼 | 타입 | 설명 | 쿠팡 | 스마트스토어 | 11번가 | ESM+ |
|----|------|------|------|------|------------|--------|------|
| — | `market_type` | enum `MarketType` | 마켓 타입 | `COUPANG` | `SMART_STORE` | `ELEVEN_STREET` | `siteId` 매핑 (1→AUCTION, 2→GMARKET) |
| — | `market_order_no` | String | 마켓 고유 주문번호 | `orderId` | `productOrderId` | `ordNo` | `siteOrderNo` |
| — | `order_date` | LocalDateTime | 결제확정 시각 | `orderedAt` | `orderDate` | `ordNo` 앞 8자리에서 추출 | `depositConfirmDate` |
| — | `recipient_name` | String | 수령인 | `receiver.name` | `shippingAddress.name` | `rcvrNm` | `rcverName` / HTML |
| — | `recipient_phone` | String | 수령인 전화번호 | `overseaShippingInfoDto.ordererPhoneNumber` | `shippingAddress.tel1` (`-` 제거) | `rcvrPrtblNo` | `—` / HTML |
| — | `zipcode` | String | 우편번호 | `receiver.postCode` | `shippingAddress.zipCode` | `rcvrMailNo` | `—` / HTML |
| — | `address` | String | 기본주소 + 상세주소 | `receiver.addr1 + " " + receiver.addr2` | `shippingAddress.baseAddress + " " + shippingAddress.detailedAddress` | `rcvrBaseAddr + " " + rcvrDtlsAddr` | `—` / HTML |
| — | `message` | String | 배송메시지 | `parcelPrintMessage` | `shippingMemo` | `ordDlvReqCont` | `—` / HTML |
| — | `orderer_name` | String | 주문자명 | `orderer.name` | `ordererName` | `ordNm` | `buyerName` |
| — | `orderer_phone` | String | 주문자 전화번호 | `receiver.safeNumber` | `ordererTel` (`-` 제거) | `ordPrtblTel` | `—` |
| — | `shipment_box_id` | String | 묶음배송번호 (발주확인용) | `shipmentBoxId` | `—` | `—` | `—` |
| CustomsData | `customs_clearance_no` | String | 개인통관고유부호 | `overseaShippingInfoDto.personalCustomsClearanceCode` | `individualCustomUniqueCode` ("undefined" 필터링) | `psnCscUniqNo` | `—` |
| CustomsData | `customs_status` | enum `CustomsStatus` | 통관상태 | `—` (통관 확인에서 설정) | `—` (통관 확인에서 설정) | `—` (통관 확인에서 설정) | `—` (통관 확인에서 설정) |

#### `OrderLineItem` (`sb_order_line_item`)

| VO | 컬럼 | 타입 | 설명 | 쿠팡 | 스마트스토어 | 11번가 | ESM+ |
|----|------|------|------|------|------------|--------|------|
| — | `order_id` | Long (FK) | Order 참조 | `—` (생성 시 할당) | `—` (생성 시 할당) | `—` (생성 시 할당) | `—` (생성 시 할당) |
| — | `product_id` | Long (FK) | SB 상품 ID | `vendorItemId` → `MarketRegistration` → `sb_product_id` | `sellerProductCode` → `Product.findBySbCode()` | `sellerPrdCd` → `Product.findBySbCode()` | `goodsNo` → `Product.findBySbCode()` |
| — | `quantity` | int | 주문 수량 | `shippingCount` | `quantity` | `ordQty` | `orderQty` |
| SourcingData | `sourcing_vendor` | String | 소싱처 (IHB 등) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) |
| SourcingData | `sourcing_account` | String | 구매계정 | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) |
| SourcingData | `sourcing_order_no` | String | 소싱주문번호 | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) |
| SourcingData | `sourcing_amount` | BigDecimal | 소싱금액 (원가) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) |
| SourcingData | `logistics_cost` | BigDecimal | 물류비 | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) |
| SourcingData | `discount_code` | String | 할인코드 | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) |
| SettlementData | `settlement_amount` | BigDecimal | 정산금액 | `totalAmount × 0.89` (수수료 11% 반영) | `totalPaymentAmount` | `ordAmt` | `tradeAmnt` |
| SettlementData | `settlement_verified` | Boolean | 정산 검증 완료 여부 | 쿠팡 정산 동기화 시 `true` | `—` | `—` | `—` |
| ShippingData | `tracking_no` | String | 송장번호 | `invoiceNumber` ("null" 문자열 필터링) | `deliveryInfo.trackingNumber` | `invcNo` | `—` / HTML |
| ShippingData | `shipping_status` | enum `ShippingStatus` | 배송상태 | `CoupangStatusMapper` | `SmartStoreStatusMapper` | `ElevenstStatusMapper` | `EsmplusStatusMapper` |
| ShippingData | `shipping_carrier` | enum `ShippingCarrier` | 택배사 | `deliveryCompanyName` → `fromMarketCode()` | `deliveryInfo.deliveryCompany` → `fromMarketCode()` | `dlvEtprsCd` (5자리 숫자 매핑) | `CJ_LOGISTICS` (기본값) |
| ShippingData | `tracking_sent_to_market` | Boolean | 마켓 송장 전송 완료 여부 | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) |
| — | `is_unipass_done` | Boolean | 유니패스 신고 완료 여부 | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) | `—` (사용자 입력) |

#### `MarketRegistration` (`sb_market_registration`)

마켓별 상품 매핑 정보를 저장하는 엔티티. 쿠팡의 `vendorItemId` → SB 상품 매핑에 사용.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `product_id` | Long | 마켓 상품 ID |
| `sb_product_id` | Long | SB 상품 ID (`sb_product` 참조) |
| `market_type` | enum `MarketType` | 마켓 타입 |
| `market_product_name` | String | 마켓 등록 상품명 |
| `market_identifiers` | TEXT | 마켓 식별자 JSON (쿠팡: `{"vendorItemId": "..."}`) |
| `market_detailed_info` | LONGTEXT | 마켓 상세 정보 |
| `is_synced` | Boolean | 동기화 완료 여부 |
| `last_synced_at` | LocalDateTime | 마지막 동기화 시각 |

### 열거형

#### `ShippingStatus` (우선순위 포함)
- `UNKNOWN(-2)` — 알수없음 (모든 매핑의 기본값)
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
│  MarketplaceShippingService         │
│  StatusMappers (마켓별)               │
├──────────────────────────────────────┤
│  도메인 계층                          │
│  Order, OrderLineItem 엔티티          │
│  MarketRegistration 엔티티            │
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
  resolveProductId() → productId 설정 (쿠팡: vendorItemId → MarketRegistration → sb_product_id, 
                                        기타: marketProductCode → Product.findBySbCode())
  marketOrderNo로 기존 주문 조회
  
  if 기존주문 && alwaysFetchDetail:
    fullDto = port.fetchOrderDetail(credential, dto)
    if fullDto: updateExistingOrder(fullDto)
    else:       updateExistingOrder(dto)
  elif 기존주문:
    updateExistingOrder(dto)
  elif alwaysFetchDetail:
    fullDto = port.fetchOrderDetail(credential, dto)
    if fullDto: createNewOrder(fullDto)
    else:       createNewOrder(dto)  // 기본 데이터로 생성
  else:
    createNewOrder(dto)
```

---

## 마켓별 통합

### 쿠팡

| 항목 | 상세 |
|--------|--------|
| **API 타입** | REST JSON, HMAC 서명 |
| **인증** | AccessKey + SecretKey + VendorId |
| **주문 조회** | 6개 상태 순차 조회 (ACCEPT, INSTRUCT, DEPARTURE, DELIVERING, FINAL_DELIVERY, NONE_TRACKING), 1초 간격 |
| **정산** | 전용 `querySettlement()` — sales-details API. 초기 정산금액 = `totalAmount × 0.89` (수수료 11% 반영) |
| **상품 매핑** | `vendorItemId` → `sb_market_registration.market_identifiers` (JSON) → `sb_product_id` |
| **후처리** | `detectCancellations()` — DB에는 있지만 API 결과에 없는 주문 → CANCELED. `fixCarriers()` — ETC 택배사나 송장번호 누락 → API에서 보정. |
| **alwaysFetchDetail** | false (목록 API가 전체 데이터 반환) |
| **shipOrder** | `coupangOrderApiPort.shipOrder()` 호출. `resolveVendorItemId()`로 `MarketRegistration`에서 vendorItemId 조회 |
| **acceptOrders** | `coupangOrderApiPort.acceptOrders()`에 `shipmentBoxId` 전달 |
| **상태 매퍼** | `CoupangStatusMapper` — 쿠팡 API 상태코드를 `ShippingStatus`로 매핑. 미등록 상태는 `UNKNOWN` |

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
| marketType | `COUPANG` (고정) | `SMART_STORE` (고정) | `ELEVEN_STREET` (고정) | `siteId` 매핑 | 동일 |
| marketOrderNo | `orderId` | `productOrderId` | `ordNo` | `siteOrderNo` | 동일 |
| marketProductCode | `vendorItemId` (orderItems → vendorItemId) | `sellerProductCode` | `sellerPrdCd` | `goodsNo` | 동일 |
| quantity | `shippingCount` | `quantity` | `ordQty` | `orderQty` | 동일 |
| orderPrice | `orderPrice` | `totalPaymentAmount / quantity` | `selPrc` | `tradeAmnt / orderQty` | 동일 |
| totalAmount | `orderPrice * quantity` | `totalPaymentAmount` | `ordAmt` | `tradeAmnt` | 동일 |
| recipientName | `receiver.name` | `shippingAddress.name` | `rcvrNm` | `rcverName` | HTML 파싱 |
| recipientPhone | `overseaShippingInfoDto.ordererPhoneNumber` (없으면 `receiver.safeNumber`) | `shippingAddress.tel1` | `rcvrPrtblNo` | `""` | HTML 파싱 |
| zipcode | `receiver.postCode` | `shippingAddress.zipCode` | `rcvrMailNo` | `""` | HTML 파싱 |
| address | `receiver.addr1 + addr2` | `shippingAddress.baseAddress + detailedAddress` | `rcvrBaseAddr + rcvrDtlsAddr` | `""` | HTML 파싱 |
| message | `parcelPrintMessage` | `shippingMemo` | `ordDlvReqCont` | `""` | HTML 파싱 |
| ordererName | `orderer.name` | `ordererName` | `ordNm` | `buyerName` | 동일 |
| ordererPhone | `receiver.safeNumber` | `ordererTel` | `ordPrtblTel` | `""` | 동일 |
| customsClearanceNo | `overseaShippingInfoDto.personalCustomsClearanceCode` | `individualCustomUniqueCode` ("undefined" 필터링) | `psnCscUniqNo` | — | — (ESM+ 없음) |
| trackingNo | `invoiceNumber` | `deliveryInfo.trackingNumber` | `invcNo` | `""` | 동일 |
| carrier | `deliveryCompanyName` → `ShippingCarrier.fromMarketCode()` | `deliveryInfo.deliveryCompany` → `fromMarketCode()` | `dlvEtprsCd` → 숫자 매핑 | CJ_LOGISTICS 기본값 | 동일 |
| status | CoupangStatusMapper 경유 | SmartStoreStatusMapper 경유 | ElevenstStatusMapper 경유 | EsmplusStatusMapper 경유 | 동일 |
| orderDate | `orderedAt` (ISO) | `orderDate` (ISO) | `ordNo`에서 추출 (앞 8자) | `depositConfirmDate` | 동일 |
| shipmentBoxId | `shipmentBoxId` | — | — | — | — |

---

## 동기화 동작

### 생성 (신규 주문)

`buildOrderFromDto()`는 `MarketOrderDto` → `Order`로 직접 매핑. `buildLineItemFromDto()`는 송장/택배사/상태가 포함된 `ShippingData`, 정산금액이 포함된 `SettlementData`도 함께 생성.

- 쿠팡: `SettlementData.settlementAmount` = `totalAmount × 0.89` (수수료 11% 반영)
- 기타 마켓: `SettlementData.settlementAmount` = `totalAmount`
- 모든 마켓: `SettlementData.settlementVerified` = `false` (초기값)

### 업데이트 (기존 주문)

`updateExistingOrder(order, dto)`:
1. 주문의 모든 `OrderLineItem` 조회
2. 각 라인아이템: `resolveProductId()`로 `productId` 변경 시 `assignProductId()` 호출
3. `updateOrderInfoFromDto()` 호출 후 저장 (변경 여부 무관)

`updateLineItemFromDto()` 업데이트:
- `productId` — `resolveProductId()`로 재설정
- `trackingNo`, `status`, `carrier` — `updateShippingWithCarrier()`로 (다운그레이드 방지 포함)

`updateOrderInfoFromDto()` 업데이트:
- `marketType` (변경된 경우)
- `recipientName`, `recipientPhone`, `zipcode`, `address`, `message` (`order.updateInfo()` — null-safe, null이 아닌 값만 덮어씀)
- `ordererName`, `ordererPhone` (`updateOrdererInfo()` — 빈 문자열 안전)
- `customsClearanceNo` (`updateCustomsClearanceNo()`)
- `shipmentBoxId` (`updateShipmentBoxId()` — 빈 문자열 안전)

### 업데이트 시 덮어쓰지 않는 필드

다음 필드는 동기화 업데이트 시 **보존** (사용자 관리):
- `sourcingData` (sourcingVendor, sourcingAccount, sourcingOrderNo, sourcingAmount, logisticsCost, discountCode) — API 명령으로만 설정
- `settlementData.settlementVerified` — 쿠팡 정산 동기화 시에만 `true`
- `isUnipassDone` — API로만
- `shippingData.trackingSentToMarket` — API로만
- `customsData.customsStatus` — API/통관 확인으로만

### 필드 출처 우선순위

ESM+의 경우 `fetchOrderDetail()`은 **상세 특화 필드**만 채워진 DTO 반환 (전화번호/주소/우편번호/메시지). `AbstractOrderSyncService.updateOrderInfoFromDto()`는 null-safe 패턴으로 적용 — 상세 필드가 목록 API의 빈 칸을 채움. ESM+는 `alwaysFetchDetail()=true`이므로 상세 DTO로 완전한 DTO를 구성.

---

## 상품 매핑 (상세)

### 쿠팡 vendorItemId 매핑

쿠팡은 `sb_market_registration` 테이블을 통해 `vendorItemId` → SB 상품 매핑:

```
1. MarketOrderDto.marketProductCode = orderItems[0].vendorItemId
2. AbstractOrderSyncService.resolveProductId():
   marketRegistrationRepository.findByMarketTypeAndIdentifiersContaining(COUPANG, vendorItemId)
   → MarketRegistration.sbProductId 반환
3. shipOrder 시:
   resolveVendorItemId()로 MarketRegistration.marketIdentifiers JSON에서 vendorItemId 조회
```

### 타 마켓 SB 코드 매핑

```
MarketOrderDto.marketProductCode = SB 코드 (예: "SB-001")
→ ProductRepository.findBySbCode(sbCode) → Product.id
```

### P000x 코드 변환 (제거됨)

기존 `resolveP000xCode()`는 `externalVendorSkuCode`가 `P000`으로 시작할 때 상품명 매칭으로 SB 코드를 찾는 로직이었으나, `sb_market_registration` 기반 매핑으로 대체되어 제거됨.

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
| `/api/v1/orders/{id}` | PATCH | 주문 정보 수정 (address, customsClearanceNo) |
| `/api/v1/orders/{id}/unipass` | PATCH | 유니패스 완료 여부 수정 |
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

2. **상품 ID 매핑** — 쿠팡은 `vendorItemId` → `MarketRegistration.marketIdentifiers`(JSON) → `sb_product_id`로 조회 (`MarketRegistrationRepository`). 다른 마켓은 `marketProductCode`(SB 코드) → `Product.findBySbCode()`로 조회.

3. **정산 동기화** — 쿠팡만 정산 동기화 보유. 야간 실행, 쿠팡 Sales Details API 조회, DELIVERED 주문만 `SettlementData.settlementAmount` 업데이트 및 `settlementVerified = true` 설정. 순수익(netProfit)은 DB 저장하지 않고 프론트에서 실시간 계산.

4. **상태 다운그레이드 방지** — `ShippingStatus.isDowngrade()`가 SHIPPED → PREPARING 이동을 차단. 터미널 상태(CANCELED/RETURNED/EXCHANGED)는 항상 허용. 미등록 상태는 `UNKNOWN(-2)`으로 매핑.

5. **취소 감지** — 쿠팡의 후처리 훅이 API 결과와 DB 비교. DB에는 있지만 API에 없는 주문은 CANCELED 처리 (이미 DELIVERED 또는 CANCELED인 경우 제외).

6. **ESM+ 세션 재사용** — `getOrCreateDetailDriver()`가 상세 조회 간 ChromeDriver 세션을 캐시하여 동기화 사이클당 9회 로그인을 1회로 줄임.

7. **ESM+ 통관번호 미지원** — ESM+ 주문 상세 페이지와 API는 개인통관고유부호를 노출하지 않음. 통관번호는 일부 마켓 플랫폼에서 해외배송 주문(globalShopType != KR)에만 제공.
