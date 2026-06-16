# SB Shop Agent - 프로젝트 분석 문서

> 작성일: 2026-06-16
> Java 17 · Spring Boot 3.2.3 · Gradle Multi-Module

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [전체 아키텍처](#2-전체-아키텍처)
3. [모듈 의존성 구조](#3-모듈-의존성-구조)
4. [도메인 모델](#4-도메인-모델)
5. [패키지 구조 상세](#5-패키지-구조-상세)
6. [API 엔드포인트 목록](#6-api-엔드포인트-목록)
7. [Port / Adapter 아키텍처](#7-port--adapter-아키텍처)
8. [외부 시스템 연동](#8-외부-시스템-연동)
9. [동기화 파이프라인](#9-동기화-파이프라인)
10. [이벤트 시스템](#10-이벤트-시스템)
11. [스케줄러](#11-스케줄러)
12. [주요 데이터 흐름](#12-주요-데이터-흐름)
13. [보안 및 인증](#13-보안-및-인증)
14. [코드 리뷰 체크리스트](#14-코드-리뷰-체크리스트)

---

## 1. 프로젝트 개요

**SB Shop Agent**는 국내외 이커머스 플랫폼 간 해외배송 판매를 관리하는 **주문 통합 관리 시스템**입니다.

### 핵심 기능
- **4대 한국 마켓플레이스** 주문 동기화: 쿠팡, 네이버 스마트스토어, 11번가, G마켓/옥션(ESM+)
- **해외 구매대행(소싱)** 자동화: 아이허브 이메일 파싱 (배송추적번호, 결제확인금액)
- **통관 상태** 자동 조회: GSI Express 관세청 개인통관고유부호(PCCC) 일괄 검증
- **재고 동기화**: 아이허브 상품 재고/가격 크롤링
- **Cafe24 OAuth** 인증 관리

### 기술 스택
| 영역 | 기술 |
|------|------|
| Framework | Spring Boot 3.2.3 |
| Language | Java 17 |
| Build | Gradle 8.x |
| Database | MariaDB (Cafe24 호스팅) |
| ORM | Spring Data JPA + Hibernate 6 + QueryDSL 5 |
| Migration | Flyway (설정만, 비활성화) |
| External API | RestClient, Jsoup, Selenium |
| Code Format | Spotless (Naver Eclipse Formatter) |
| Mail | Jakarta Mail (IMAP) |

---

## 2. 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                        worker (8081)                        │
│  ┌─────────────────┐  ┌──────────────────┐                  │
│  │ OrderSyncScheduler │  │ EmailFetcherService │              │
│  │ (5분/30분/1시간/매일) │  │ (IMAP → iHerb 파싱)  │              │
│  └────────┬────────┘  └────────┬─────────┘                  │
│           │                    │                            │
└───────────┼────────────────────┼────────────────────────────┘
            │                    │
┌───────────┼────────────────────┼────────────────────────────┐
│           │                    │           api (8080)        │
│  ┌────────┴────────┐  ┌───────┴──────────┐                  │
│  │ OrderSyncController│  │ OrderController   │              │
│  │ (POST /sync/*)   │  │ (CRUD /api/v1/orders/*)│           │
│  └────────┬────────┘  └────────┬─────────┘                  │
│           │                    │                            │
│  ┌────────┴────────────────────┴─────────────────────────┐  │
│  │               SSE Notification                         │  │
│  │          SseNotificationController                     │  │
│  └───────────────────────────────────────────────────────┘  │
└───────────┬────────────────────┬────────────────────────────┘
            │                    │
┌───────────┴────────────────────┴────────────────────────────┐
│                        core (라이브러리)                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │          Service Layer (application.order.service)   │   │
│  │  CoupangOrderSyncService  SmartStoreOrderSyncService│   │
│  │  ElevenstOrderSyncService  EsmplusOrderSyncService  │   │
│  │  CustomsOrderSyncService   OrderService             │   │
│  │  OrderShipService          MarketCredentialService  │   │
│  └───────────┬──────────────────────────────────────┬───┘   │
│              │                                      │       │
│  ┌───────────┴────────┐              ┌──────────────┴───┐   │
│  │  Adapter Layer     │              │   Port Layer     │   │
│  │  (application.adapter)         │   │   (application.port)│  │
│  │  CoupangOrderAdapter│              │  MarketOrderPort │   │
│  │  SmartStoreOrderAdapter│           │  CoupangOrderApi │   │
│  │  ElevenstOrderAdapter│              │  SmartStoreApi  │   │
│  │  EsmplusOrderAdapter│              │  ElevenstApi    │   │
│  └───────────┬────────┘              │  EsmplusApi     │   │
│              │                      └───────┬──────────┘   │
│  ┌───────────┴──────────────────────────────┴──────────┐   │
│  │              Domain Layer (domain.order)            │   │
│  │  Order, OrderLineItem, Product, MarketCredential   │   │
│  │  ShippingData, SourcingData, SettlementData         │   │
│  └─────────────────────────────────────────────────────┘   │
└───────────┬────────────────────────────────────────────────┘
            │
┌───────────┴────────────────────────────────────────────────┐
│                  infrastructure (라이브러리)                    │
│  ┌────────┐ ┌─────────┐ ┌──────────┐ ┌────────┐ ┌──────┐  │
│  │Coupang │ │SmartStore│ │ Elevenst │ │ ESM+  │ │기타  │  │
│  │ApiClient│ │ApiClient│ │ApiClient │ │Scraper│ │...   │  │
│  └────────┘ └─────────┘ └──────────┘ └────────┘ └──────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Repository 구현체 (QueryDSL)                          │  │
│  │  OrderRepositoryImpl, OrderLineItemRepositoryImpl    │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

### 아키텍처 특징

- **Hexagonal (Ports & Adapters)**: `core` 모듈에 Port 인터페이스와 Adapter 구현체를 함께 배치
- **모듈 분리**: `core`(비즈니스 로직) - `infrastructure`(외부 연동) - `api`(REST) - `worker`(백그라운드)
- **async 주문 동기화**: 모든 마켓 동기화는 `@Async("syncTaskExecutor")`로 비동기 실행
- **SSE 알림**: 동기화 완료/실패 이벤트를 Server-Sent Events로 프론트에 실시간 전달
- **의존 방향**: `api`/`worker` → `infrastructure` → `core` (core는 다른 모듈을 모름)

---

## 3. 모듈 의존성 구조

### 의존 관계 그래프
```
worker ──→ infrastructure ──→ core
  │                            ↑
  └────────────────────────────┘
  
api ──→ infrastructure ──→ core
  │        ↑
  └────────┘
```

### core
- `bootJar { enabled = false }` — 라이브러리 모듈
- 의존성: JPA, QueryDSL, Jackson, jBCrypt
- 역할: 도메인 엔티티, 비즈니스 서비스, Port 인터페이스, Adapter

### infrastructure
- `bootJar { enabled = false }` — 라이브러리 모듈
- 의존성: core, JPA, Web, Jsoup, Selenium, MariaDB, Flyway
- 역할: HTTP API 클라이언트, 웹 스크래퍼, QueryDSL Repository 구현

### api
- 의존성: core, infrastructure, Web, Validation, Mail
- 역할: REST API 컨트롤러, 전역 예외 처리, SSE

### worker
- 의존성: core, infrastructure, Mail, JPA
- 역할: 백그라운드 스케줄러, IMAP 이메일 수신 및 파싱

---

## 4. 도메인 모델

### 엔티티 관계도

```
MarketCredential              MarketRegistration
  │ (marketType unique)          │ (productId + marketType unique)
  │                              │
  └─── MarketType ────┘
         │
    ┌────┴────┐
    │  Order  │── 1:N ── OrderLineItem
    └─────────┘              │
                             │ N:1
                             │
                          Product ── 1:N ── MarketRegistration
```

### 주요 엔티티 상세

#### Order (`sb_order`)

| 필드 | 타입 | 설명 | 비고 |
|------|------|------|------|
| id | Long | PK | auto-increment |
| status | RecordStatus | 상태 (ACTIVE/ARCHIVED/DELETED) | |
| createdAt | LocalDateTime | 생성일 | |
| updatedAt | LocalDateTime | 수정일 | |
| **marketType** | MarketType | 마켓 구분 | COUPANG, SMART_STORE, ELEVEN_STREET, GMARKET, AUCTION, CAFE24 |
| **marketOrderNo** | String | 마켓 주문번호 | unique |
| orderDate | LocalDateTime | 주문일/결제일 | |
| recipientName | String | 수취인 이름 | |
| recipientPhone | String | 수취인 전화번호 | |
| zipcode | String | 우편번호 | |
| address | String | 주소 | |
| message | String | 배송 메시지 | |
| ordererName | String | 주문자 이름 | |
| ordererPhone | String | 주문자 전화번호 | |
| shipmentBoxId | String | 쿠팡 묶음배송 ID | 쿠팡 전용 |

Embedded: `customsData` → CustomsData(customsClearanceNo, customsStatus)

#### OrderLineItem (`sb_order_line_item`)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| orderId | Long | FK → Order |
| productId | Long | FK → Product |
| quantity | Integer | 수량 |
| sourcingData | SourcingData(embedded) | 소싱 정보 (sourcingAccount, sourcingOrderNo, sourcingAmount, discountCode, sourcingVendor) |
| settlementData | SettlementData(embedded) | 정산 정보 (settlementAmount, shippingFee, settlementVerified) |
| shippingData | ShippingData(embedded) | 배송 정보 (trackingNo, isUnipassDone, shippingStatus, shippingCarrier, trackingSentToMarket) |

#### Product (`sb_product`)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| sbCode | String | 내부 상품코드 (unique) |
| category | ProductCategory | SUPPLEMENT, FOOD, COSMETICS, UNKNOWN |
| vendor | VendorType | IHB(아이허브), AMZ, FTN, COK, OCD, TES, VTB |
| barcode | String | 바코드 (UPC/EAN) |
| stockStatus | StockStatus | IN_STOCK, OUT_OF_STOCK |
| restockDate | LocalDate | 입고예정일 |

Embedded: `productName`(brand, originalName, baseName, productName), `productSpec`(capacity, measureUnit, weight, bundleQuantity), `priceInfo`(costPrice, exchangeRate, marginRate, salePrice), `sourcingInfo`(url, manufacturer, origin, hsCode, stock), `mediaInfo`(sourceImages, hostedImages, searchKeywords, detailHtml, memo)

#### MarketCredential (`sb_market_credential`)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| marketType | MarketType | 마켓 (unique) |
| clientId | String | Mall ID / Vendor ID |
| accessKey | String | API Access Key |
| secretKey | String | API Secret Key |
| refreshToken | String | OAuth Refresh Token |
| accessToken | String | OAuth Access Token |
| tokenExpiresAt | LocalDateTime | 토큰 만료일시 |
| redirectUri | String | OAuth Redirect URI |
| isActive | Boolean | 활성 여부 |

#### MarketRegistration (`sb_market_registration`)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long | PK |
| productId | Long | FK → Product |
| sbProductId | Long | (미사용) |
| marketType | MarketType | 마켓 |
| marketProductName | String | 마켓 상품명 |
| marketIdentifiers | String(TEXT) | 마켓 상품 식별자 (JSON) |
| marketDetailedInfo | String(LONGTEXT) | 마켓 상세 정보 |
| isSynced | Boolean | 동기화 여부 |
| lastSyncedAt | LocalDateTime | 마지막 동기화일 |

### 주요 enum

**MarketType**
```
COUPANG, SMART_STORE, ELEVEN_STREET, GMARKET, AUCTION, CAFE24, UNKNOWN
```

**ShippingStatus** (값이 중복되는 것에 주의)
```
UNKNOWN(-2), NEW(0), PREPARING(1), PURCHASED(2), SHIPPED(3), DELIVERED(4),
CANCELED(-1), RETURNED(-1), EXCHANGED(-1)
```

**ShippingCarrier**
```
CJ_LOGISTICS, HANJIN, KOREA_POST, LOTTE_LOGISTICS,
HYUNDAI_LOGISTICS, ROCKET, ETC
```

---

## 5. 패키지 구조 상세

### core (62개 파일)

```
com.sbshop.agent.core
├── config/
│   ├── AsyncConfig.java              # @Async 스레드풀 설정
│   ├── EmailAccountProperties.java   # 이메일 계정 설정 (@ConfigurationProperties)
│   └── JpaAuditingConfig.java        # JPA Auditing 활성화
│
├── domain/
│   ├── common/
│   │   ├── BaseEntity.java           # 공통 엔티티 (id, status, createdAt, updatedAt)
│   │   ├── RecordStatus.java         # ACTIVE / ARCHIVED / DELETED
│   │   └── enums/
│   │       ├── EnumMapperType.java   # Enum 매핑 인터페이스
│   │       └── EnumMapperValue.java  # Enum -> {code, value} 변환
│   │
│   ├── fee/
│   │   └── FeePolicy.java            # 수수료 정책 (마켓별, 카테고리별 수수료율)
│   │
│   ├── market/
│   │   ├── MarketCredential.java     # 마켓 API 인증 정보
│   │   ├── MarketRegistration.java   # 마켓 상품 등록 매핑
│   │   └── repository/
│   │       ├── MarketCredentialRepository.java
│   │       └── MarketRegistrationRepository.java
│   │
│   ├── order/
│   │   ├── Order.java                # 주문 (Root Aggregate)
│   │   ├── OrderLineItem.java        # 주문 상품 라인
│   │   ├── enums/
│   │   │   ├── CustomsStatus.java    # 통관 상태 (PENDING/VALID/INVALID)
│   │   │   ├── MarketType.java
│   │   │   ├── ShippingCarrier.java
│   │   │   └── ShippingStatus.java
│   │   ├── repository/
│   │   │   ├── OrderRepository.java
│   │   │   ├── OrderLineItemRepository.java
│   │   │   ├── OrderRepositoryCustom.java
│   │   │   └── OrderLineItemRepositoryCustom.java
│   │   ├── util/
│   │   │   └── BusinessDayCalculator.java  # 영업일 계산기
│   │   └── vo/
│   │       ├── CustomsData.java
│   │       ├── SettlementData.java
│   │       ├── ShippingData.java
│   │       └── SourcingData.java
│   │
│   └── product/
│       ├── Product.java
│       ├── ProductRepository.java
│       ├── enums/
│       │   ├── MeasureUnit.java
│       │   ├── ProductCategory.java
│       │   ├── StockStatus.java
│       │   └── VendorType.java
│       └── vo/
│           ├── MediaInfo.java
│           ├── PriceInfo.java
│           ├── ProductName.java
│           ├── ProductSpec.java
│           └── SourcingInfo.java
│
├── application/
│   ├── market/
│   │   ├── MarketCredentialService.java
│   │   └── dto/
│   │       ├── MarketCredentialDto.java
│   │       └── MarketCredentialSaveCommand.java
│   │
│   ├── order/
│   │   ├── adapter/            *── 4개 MarketOrderPort 구현체
│   │   ├── dto/                *── Command/DTO 5개
│   │   ├── event/              *── SyncCompletedEvent
│   │   ├── mapper/             *── 마켓별 상태 매퍼 5개
│   │   ├── port/               *── Port 인터페이스 6개
│   │   ├── service/            *── 7개 서비스
│   │   └── util/
│   │       └── ElevenstXmlUtils.java
│   │
│   └── product/
│       ├── ProductSyncService.java
│       ├── dto/
│       │   └── StockCheckResult.java
│       └── port/
│           └── ProductStockCrawlerPort.java
```

### infrastructure (12개 파일)

```
com.sbshop.agent.infrastructure
├── config/
│   └── QueryDslConfig.java
│
├── client/
│   ├── cafe24/
│   │   └── Cafe24TokenManager.java        # Cafe24 OAuth 토큰 관리
│   ├── coupang/
│   │   └── CoupangOrderApiClient.java     # 쿠팡 API (HMAC 인증)
│   ├── customs/
│   │   └── GsiExpressScraperAdapter.java  # 통관조회 (Jsoup)
│   ├── elevenst/
│   │   ├── ElevenstOrderApiClient.java    # 11번가 API (XML)
│   │   └── ElevenstRestClient.java        # 11번가 HTTP 클라이언트
│   ├── esmplus/
│   │   ├── EsmplusOrderApiPortImpl.java   # ESM+ API (Selenium)
│   │   └── EsmplusScraper.java            # ESM+ Selenium 로그인/스크래핑
│   ├── smartstore/
│   │   └── SmartStoreOrderApiClient.java  # 스마트스토어 API (OAuth)
│   └── sourcing/
│       └── IherbScraperClient.java       # 아이허브 재고 크롤링
│
└── repository/
    ├── order/
    │   ├── OrderLineItemRepositoryImpl.java  # QueryDSL 구현
    │   └── OrderRepositoryImpl.java          # QueryDSL 구현
    └── product/
        └── ProductJpaRepository.java         # Spring Data JPA + ProductRepository
```

### api (14개 파일)

```
com.sbshop.agent.api
├── ApiApplication.java
├── controller/
│   ├── Cafe24AuthController.java      # GET /api/admin/sync/cafe24/auth/callback
│   ├── CommonCodeController.java      # GET /api/v1/common/codes
│   ├── MarketCredentialController.java # CRUD /api/v1/market-credentials
│   ├── OrderController.java           # 주문 CRUD /api/v1/orders
│   ├── OrderSyncController.java       # 동기화 트리거 /api/v1/orders/sync
│   ├── ProductSyncController.java     # 재고 동기화 /api/v1/products/sync/stock
│   └── SseNotificationController.java # SSE 구독 /api/v1/notifications/subscribe
├── dto/
│   ├── OrderLineItemUpdateRequest.java
│   ├── OrderShipRequest.java
│   └── OrderUpdateRequest.java
├── exception/
│   └── GlobalExceptionHandler.java
└── service/
    └── IherbEmailSearchService.java
```

### worker (5개 파일)

```
com.sbshop.agent.worker
├── WorkerApplication.java
├── config/
│   └── EmailAccount.java
├── scheduler/
│   └── OrderSyncScheduler.java       # 4개 스케줄러 메서드
└── service/
    ├── EmailFetcherService.java      # IMAP 이메일 수신
    └── OrderEmailParser.java         # 이메일 본문 파싱
```

---

## 6. API 엔드포인트 목록

### 주문 관리 (`OrderController`)

| 메서드 | URL | 설명 |
|--------|-----|------|
| `GET` | `/api/v1/orders` | 주문 목록 검색 (페이징, 필터링) |
| `PATCH` | `/api/v1/orders/{id}` | 주문 정보 수정 (수취인/주소/통관) |
| `DELETE` | `/api/v1/orders/{id}` | 주문 삭제 (soft delete) |
| `PATCH` | `/api/v1/orders/line-items/{id}` | 라인아이템 수정 (소싱/배송/정산) |
| `POST` | `/api/v1/orders/ship` | 일괄 출고처리 (마켓에 송장 전송) |
| `POST` | `/api/v1/orders/{id}/confirm` | 주문 확정/승인 |
| `POST` | `/api/v1/orders/confirm/batch` | 일괄 주문 확정 |
| `POST` | `/api/v1/orders/{id}/cancel` | 주문 취소 |
| `POST` | `/api/v1/orders/line-items/{id}/purchase` | 상품 매입 처리 (PREPARING→PURCHASED) |
| `POST` | `/api/v1/orders/line-items/{id}/ship` | 출고 처리 (PURCHASED→SHIPPED) |
| `PUT` | `/api/v1/orders/line-items/{id}/tracking` | 송장정보 업데이트 |

### 주문 동기화 (`OrderSyncController`)

| 메서드 | URL | 설명 |
|--------|-----|------|
| `POST` | `/api/v1/orders/sync/coupang` | 쿠팡 주문 동기화 시작 |
| `POST` | `/api/v1/orders/sync/coupang/settlement` | 쿠팡 정산 동기화 |
| `POST` | `/api/v1/orders/sync/smartstore` | 스마트스토어 주문 동기화 |
| `POST` | `/api/v1/orders/sync/elevenstreet` | 11번가 주문 동기화 |
| `POST` | `/api/v1/orders/sync/esmplus` | ESM+(G마켓/옥션) 주문 동기화 |
| `POST` | `/api/v1/orders/sync/customs` | 통관 상태 동기화 |
| `POST` | `/api/v1/orders/sync/esmplus/test` | ESM+ 로그인 테스트 |
| `POST` | `/api/v1/orders/sync/esmplus/scrape` | ESM+ 스크래핑 테스트 |

### 마켓 인증정보 (`MarketCredentialController`)

| 메서드 | URL | 설명 |
|--------|-----|------|
| `GET` | `/api/v1/market-credentials` | 전체 인증정보 조회 |
| `GET` | `/api/v1/market-credentials/{marketType}` | 특정 마켓 인증정보 조회 |
| `PUT` | `/api/v1/market-credentials/{marketType}` | 인증정보 저장/갱신 |

### 기타

| 메서드 | URL | 설명 |
|--------|-----|------|
| `POST` | `/api/v1/products/sync/stock` | 재고 동기화 (준비주문 상품) |
| `GET` | `/api/v1/common/codes` | 모든 Enum 코드 조회 |
| `GET` | `/api/v1/notifications/subscribe` | SSE 이벤트 구독 (text/event-stream) |
| `GET` | `/api/admin/sync/cafe24/auth/callback` | Cafe24 OAuth 콜백 |

> **총 26개 엔드포인트** (컨트롤러 7개)

---

## 7. Port / Adapter 아키텍처

### Port 인터페이스와 구현체 매핑

| Port (core) | 역할 | Adapter (core) | Client (infrastructure) |
|---|---|---|---|
| `MarketOrderPort` | 통합 마켓 주문 인터페이스 | `CoupangOrderAdapter` | `CoupangOrderApiClient` |
| | | `SmartStoreOrderAdapter` | `SmartStoreOrderApiClient` |
| | | `ElevenstOrderAdapter` | `ElevenstOrderApiClient` + `ElevenstRestClient` |
| | | `EsmplusOrderAdapter` | `EsmplusOrderApiPortImpl` + `EsmplusScraper` |
| `CoupangOrderApiPort` | 쿠팡 전용 API | (직접 구현) | `CoupangOrderApiClient` |
| `SmartStoreOrderApiPort` | 스마트스토어 전용 API | (직접 구현) | `SmartStoreOrderApiClient` |
| `ElevenstOrderApiPort` | 11번가 전용 API | (직접 구현) | `ElevenstOrderApiClient` |
| `EsmplusOrderApiPort` | ESM+ 전용 API | (직접 구현) | `EsmplusOrderApiPortImpl` |
| `CustomsClearancePort` | 통관 조회 | (직접 구현) | `GsiExpressScraperAdapter` |
| `ProductStockCrawlerPort` | 재고 크롤링 | (직접 구현) | `IherbScraperClient` |
| `MarketStatusMapper` | 마켓 상태 매핑 | 각 마켓별 구현체 (mapper/) | - |

### MarketOrderPort 인터페이스

```java
public interface MarketOrderPort {
    MarketType getMarketType();
    List<MarketOrderDto> fetchOrders(MarketCredential credential, LocalDate startDate, LocalDate endDate);
    void shipOrder(MarketCredential credential, Order order, OrderLineItem item);
    void acceptOrders(MarketCredential credential, List<Order> orders);
    Map<String, BigDecimal> querySettlement(MarketCredential credential, String orderNo);
    ShippingCarrier mapCarrierCode(String carrierCode);
    MarketOrderDto fetchOrderDetail(MarketCredential credential, String orderNo);
}
```

### 특징
- 4개 마켓의 상이한 API를 `MarketOrderPort`로 통일
- SyncService는 `MarketOrderPort`만 의존 (전략 패턴)
- 마켓별 어댑터는 Port → 마켓 전용 API Port로 위임
- 스마트스토어/11번가/ESM+는 `fetchOrders`에서 `fetchOrderDetail` 재사용하지 않음

---

## 8. 외부 시스템 연동

### 연동 매트릭스

| 시스템 | 방식 | 인증 | 데이터 형식 | 구현 복잡도 |
|--------|------|------|-------------|-------------|
| **Coupang** | REST API | HMAC-SHA256 | JSON | 중 |
| **SmartStore** | REST API | OAuth2 + BCrypt | JSON | 중 |
| **11st** | REST API | Header API Key | XML (EUC-KR) | 중 |
| **ESM+** | Selenium 스크래핑 | 로그인 + 세션쿠키 | JSON (XHR) | **高** |
| **Cafe24** | REST API | OAuth2 | JSON | 하 (토큰만) |
| **GSI Express** | Form POST (Jsoup) | 없음 | HTML | 하 |
| **iHerb** | REST API | 없음 | JSON | 하 |

### 각 시스템 상세

#### 1. Coupang
- **Base URL**: `https://api-gateway.coupang.com`
- **인증**: 모든 요청에 HMAC-SHA256 서명 (accessKey + secretKey)
- **VendorId**: MarketCredential.clientId에서 조회
- **주요 엔드포인트**:
  - `GET /v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets` — 주문조회
  - `POST .../invoices` — 출고처리(송장전송)
  - `PUT .../acknowledgement` — 구매확정
  - `GET .../v1/revenue-history` — 매출정산 조회
- **특이사항**: 30일 이내 주문만 조회 가능, HMAC 서명 생성 복잡

#### 2. SmartStore (Naver)
- **Base URL**: `https://api.commerce.naver.com`
- **인증**: OAuth2 client_credentials → access_token 발급 (1년 유효)
- **토큰 발급**: BCrypt로 signature 생성 후 `POST /external/v1/oauth2/token`
- **주요 엔드포인트**:
  - `GET /external/v1/pay-order/seller/product-orders/last-changed-statuses` — 상태변경 주문
  - `POST .../product-orders/query` — 주문 상세조회
  - `POST .../dispatch` — 출고처리
  - `POST .../confirm` — 구매확정
- **특이사항**: changed-statuses는 일회성 조회 (재조회 불가), 200건 제한

#### 3. 11st
- **Base URL**: `https://api.11st.co.kr`
- **인증**: 요청 헤더에 `openapikey` (secretKey)
- **응답**: XML (EUC-KR 인코딩, Java에서 `EUC-KR`로 디코딩)
- **주요 엔드포인트**:
  - `GET /rest/ordservices/complete/{start}/{end}` — 결제완료 주문
  - `GET /rest/ordservices/packaging/{start}/{end}` — 포장중 주문
  - `GET /rest/ordservices/shipping/{start}/{end}` — 배송중 주문
  - `GET /rest/ordservices/dlvcompleted/{start}/{end}` — 배송완료 주문
  - `PUT /rest/ordservices/reqpackaging/...` — 구매확정
  - `PUT /rest/ordservices/reqdelivery/...` — 출고처리
  - `GET /rest/claimservice/orderlistalladdr/{ordNo}` — 주문 상세
- **특이사항**: XML 파싱 + EUC-KR 변환 필요, 일별 구간 조회

#### 4. ESM+ (Gmarket / Auction)
- **방식**: Selenium WebDriver (headless Chrome)
- **인증**: `https://signin.esmplus.com/login`에 ID/PW 로그인 → 세션 유지
- **데이터 수집**: 페이지 내 XHR 요청을 WebDriver로 캡처 (order-integration/orders API)
- **Site ID**: 1=옥션(AUCTION), 2=G마켓(GMARKET)
- **주요 동작**:
  1. ChromeDriver 실행
  2. ESM+ 로그인
  3. 주문통합 페이지 이동
  4. `orders-api` 요청 캡처
  5. JSON 응답 추출
  6. 주문 데이터 파싱
- **특이사항**: Selenium 의존성으로 인해 실행환경에 ChromeDriver 필요, 속도 느림, 불안정

#### 5. Cafe24
- **Base URL**: `https://{mall_id}.cafe24api.com/api/v2`
- **인증**: OAuth2 authorization code → refresh_token → access_token
- **상태**: 토큰 발급/갱신만 구현, 실제 API 호출은 미구현
- **Callback**: `/api/admin/sync/cafe24/auth/callback`

#### 6. GSI Express (통관조회)
- **URL**: `https://www.gsiexpress.com/pcc_chk.php`
- **방식**: Jsoup HTTP POST (파라미터: `pccNo[]` = 개인통관고유부호 목록)
- **결과**: 각 PCCC의 상태(VALID/INVALID/PHONE_MISMATCH) 반환
- **특이사항**: 대량 조회 가능 (POST 배열 파라미터)

#### 7. iHerb (아이허브)
- **URL**: `https://catalog.app.iherb.com/product/{productId}`
- **방식**: HTTP GET (User-Agent: Chrome)
- **수집 데이터**: 재고상태, 가격, 재고수량, 재입고예정일
- **특이사항**: 공식 API 아님 (크롤링), Product 엔티티의 skuCode를 iHerb productId로 사용

---

## 9. 동기화 파이프라인

### 주문 동기화 흐름 (공통)

```
1. 트리거
   ├── 수동: POST /api/v1/orders/sync/{market}
   └── 자동: OrderSyncScheduler (Worker)

2. 각 SyncService.sync{Market}Orders()
   ├── @Async("syncTaskExecutor") — 비동기
   ├── AtomicBoolean 중복 실행 방지
   ├── 인증정보 로드 → 유효성 검증
   ├── Adapter.fetchOrders() → List<MarketOrderDto>
   └── 각 주문 처리:
       ├── 기존 주문: updateExistingOrder()
       └── 신규 주문: createNewOrder()
           ├── Order 저장
           ├── OrderLineItem 저장
           ├── CustomsData 설정
           ├── SettlementData 초기화 (쿠팡: totalAmount × 0.89)
           └── Product 연결 (resolveProductId)

3. 후처리
   ├── eventPublisher.publishEvent(SyncCompletedEvent)
   └── log 결과 (처리 건수)
```

### 마켓별 동기화 방식

| 마켓 | 주문 조회 방식 | 수집 기간 | 업데이트 정책 |
|------|---------------|-----------|-------------|
| Coupang | OrderSheet API (JSON) | 최근 30일 | status만 덮어씀 |
| SmartStore | last-changed-statuses (JSON) | 마지막 동기화 이후 | status만 덮어씀 |
| Elevenst | 상태별 API 4회 호출 (XML) | 일별 구간 (최근 30일) | status만 덮어씀 |
| ESM+ | Selenium XHR 캡처 (JSON) | 최근 30일 | status만 덮어씀 |

### 커스텀 데이터 초기화 (쿠팡 정산)
```java
// CoupangOrderSyncService.createNewOrder()
// 정산 초기값: totalAmount × 0.89 (수수료 11% 공제)
BigDecimal settlementAmount = totalAmount.multiply(new BigDecimal("0.89"));
lineItem.setSettlementData(SettlementData.builder()
    .settlementAmount(settlementAmount)
    .settlementVerified(false)
    .build());
```

---

## 10. 이벤트 시스템

### SyncCompletedEvent
```java
public class SyncCompletedEvent extends ApplicationEvent {
    MarketType marketType;
    boolean success;
    String errorMessage;
}
```

### 발행 (Publish)
모든 SyncService는 동기화 완료 시점에 이벤트 발행:
- 성공시: `new SyncCompletedEvent(this, marketType, true, null)`
- 실패시: `new SyncCompletedEvent(this, marketType, false, errorMessage)`

### 구독 (Subscribe)
`SseNotificationController`가 `@EventListener`로 수신:
```java
@EventListener
public void handleSyncCompleted(SyncCompletedEvent event) {
    // SSE emit: event name = marketType + "_SYNC_COMPLETED" or "_SYNC_FAILED"
    // data = success / errorMessage
}
```

### SSE 엔드포인트
- `GET /api/v1/notifications/subscribe` — `text/event-stream`
- 프론트에서 EventSource로 구독

---

## 11. 스케줄러

### OrderSyncScheduler (Worker)

| 메서드 | 주기 | 설명 |
|--------|------|------|
| `syncOrders()` | 5분마다 | IMAP 이메일 수신 → iHerb 배송/확인 메일 파싱 |
| `syncSmartStoreOrders()` | 1시간마다 | 스마트스토어 주문 동기화 |
| `syncEsmplusOrders()` | 30분마다 | ESM+ 주문 동기화 |
| `syncCoupangSettlement()` | 매일 오전 2시 | 쿠팡 정산 동기화 |

### 이메일 수신 파이프라인

```
IMAP (Gmail)
  → EmailFetcherService.fetchAndProcessEmails()
    → 각 계정별: searchInAccountForOrderNo() = IMAP SEARCH (ON since last-sync)
      → OrderEmailParser.parseIherbShipment() = iHerb 배송 메일에서 trackingNo 추출
      → OrderEmailParser.parseIherbConfirmation() = iHerb 결제확인 메일에서 금액 추출
    → OrderLineItem 업데이트 (sourcingAmount, shippingData)
```

---

## 12. 주요 데이터 흐름

### 쿠팡 주문 → 배송 완료

```
1. [Worker/Scheduler] syncCoupangOrder()
   └─ CoupangOrderSyncService.syncCoupangOrders() (async)
      ├─ credential 조회
      ├─ CoupangOrderApiClient.fetchOrders() → List<MarketOrderDto>
      └─ 각 주문 처리 (신규/업데이트)

2. [수동] OrderController.confirmOrder()
   └─ CoupangOrderSyncService.confirmOrder()
      └─ CoupangOrderApiClient.acceptOrders()

3. [수동] OrderController.markLineItemPurchased()
   └─ OrderService.markAsPurchased() (소싱 정보 저장)

4. [수동] OrderController.processShipping()
   └─ OrderService.processShipping() (송장번호 등록)

5. [수동] OrderController.shipOrders() / processShipping()
   └─ OrderShipService.bulkShipOrders()
      └─ CoupangOrderApiClient.shipOrder() (마켓에 송장 전송)

6. [Worker/Scheduler] syncCoupangOrder()
   └─ 기존 주문 업데이트 (배송완료 등 상태 반영)
```

### 아이허브 이메일 처리

```
1. [Worker/5분] EmailFetcherService.fetchAndProcessEmails()
   └─ IMAP SEARCH (since last-sync time)
      ├─ iHerb 배송메일 → OrderEmailParser.parseIherbShipment()
      │  └─ OrderLineItem.shippingData.trackingNo 업데이트
      └─ iHerb 결제확인메일 → OrderEmailParser.parseIherbConfirmation()
         └─ OrderLineItem.sourcingData.sourcingAmount 업데이트
```

### 통관 상태 동기화

```
1. [수동] POST /api/v1/orders/sync/customs
   └─ CustomsOrderSyncService.syncCustomsStatus()
      ├─ 통관번호 있는 모든 Order 조회
      ├─ GsiExpressScraperAdapter.verifyBulk() — POST to gsiexpress.com
      └─ 각 Order.customsData.customsStatus 업데이트
```

---

## 13. 보안 및 인증

### 저장된 시크릿
- `.env` 파일에 DB 접속정보, 이메일 계정정보, API 키가 평문 저장
- `MarketCredential` 테이블에 마켓 API 키/시크릿이 평문 저장

### API 인증 방식
- **Coupang**: HMAC-SHA256 서명 (요청마다 서명 생성)
- **SmartStore**: OAuth2 access token (1년) + BCrypt signature
- **11st**: Header `openapikey` (고정값)
- **ESM+**: Selenium 로그인 세션
- **Cafe24**: OAuth2 (authorization code + refresh token)

### 중요: 현재 `.env`에 DB 비밀번호, 이메일 비밀번호가 평문으로 노출되어 있음

---

## 14. 코드 리뷰 체크리스트

### 아키텍처
- [ ] Port 인터페이스와 Adapter 분리가 명확한가?
- [ ] 모듈 간 의존 방향이 올바른가? (web → service → domain)
- [ ] 동기화 서비스 4개에 중복 코드가 많은데 추상화가 필요하지 않은가?

### 도메인
- [ ] Order와 OrderLineItem의 Aggregate 경계가 적절한가?
- [ ] ShippingStatus enum에 중복 값(-1)이 3개나 되는 이유는?
- [ ] Embedded Value Object의 Null-safe 처리가 되어 있는가?

### 동기화
- [ ] 중복 실행 방지(AtomicBoolean)가 충분한가? (분산 환경 고려?)
- [ ] 마켓별 API 에러 처리 및 재시도 전략이 있는가?
- [ ] 대량 주문 처리 시 트랜잭션 경계가 적절한가?
- [ ] ESM+ Selenium 스크래핑의 장애 대응 방안은?

### 보안
- [ ] `.env` 파일이 Git에 커밋되지 않도록 `.gitignore`에 포함되어 있는가?
- [ ] API 키가 로그에 노출되지 않는가?
- [ ] DB 접속정보가 안전하게 관리되고 있는가?

### 품질
- [ ] 예외 처리와 로깅이 적절한가?
- [ ] QueryDSL 쿼리가 인덱스를 활용할 수 있는가?
- [ ] 테스트 커버리지는 충분한가? (현재 테스트 거의 없음)
- [ ] XML(EUC-KR) 파싱의 인코딩 처리가 올바른가?
- [ ] Selenium WebDriver 리소스가 적절히 정리되는가?

### 유지보수
- [ ] 각 마켓의 API 변경에 대응할 수 있는 구조인가?
- [ ] 새로운 마켓 추가 시 변경해야 할 파일이 최소화되어 있는가?
- [ ] 하드코딩된 값(매직 넘버)이 없는가?

---

## 부록: 주요 클래스 매핑 (파일 → 패키지)

### core/application/order/ 패키지 구조 (리팩토링 완료)

| 서브패키지 | 파일 | 설명 |
|-----------|------|------|
| `service/` | `CoupangOrderSyncService.java` | 쿠팡 동기화 |
| `service/` | `SmartStoreOrderSyncService.java` | 스마트스토어 동기화 |
| `service/` | `ElevenstOrderSyncService.java` | 11번가 동기화 |
| `service/` | `EsmplusOrderSyncService.java` | ESM+ 동기화 |
| `service/` | `CustomsOrderSyncService.java` | 통관 동기화 |
| `service/` | `OrderService.java` | 주문 CRUD |
| `service/` | `OrderShipService.java` | 출고처리 |
| `adapter/` | `CoupangOrderAdapter.java` | 쿠팡 MarketOrderPort |
| `adapter/` | `SmartStoreOrderAdapter.java` | 스마트스토어 MarketOrderPort |
| `adapter/` | `ElevenstOrderAdapter.java` | 11번가 MarketOrderPort |
| `adapter/` | `EsmplusOrderAdapter.java` | ESM+ MarketOrderPort |
| `mapper/` | `MarketStatusMapper.java` | 상태 매핑 인터페이스 |
| `mapper/` | `CoupangStatusMapper.java` | 쿠팡 상태 매핑 |
| `mapper/` | `SmartStoreStatusMapper.java` | 스마트스토어 상태 매핑 |
| `mapper/` | `ElevenstStatusMapper.java` | 11번가 상태 매핑 |
| `mapper/` | `EsmplusStatusMapper.java` | ESM+ 상태 매핑 |
| `dto/` | `MarketOrderDto.java` | 마켓 주문 DTO |
| `dto/` | `OrderGridDto.java` | 주문 그리드 DTO |
| `dto/` | `OrderSearchCondition.java` | 검색 조건 DTO |
| `dto/` | `OrderUpdateCommand.java` | 주문 수정 커맨드 |
| `dto/` | `OrderLineItemUpdateCommand.java` | 라인아이템 수정 커맨드 |
| `event/` | `SyncCompletedEvent.java` | 동기화 완료 이벤트 |
| `port/` | `MarketOrderPort.java` | 통합 마켓 Port |
| `port/` | `CoupangOrderApiPort.java` | 쿠팡 Port |
| `port/` | `SmartStoreOrderApiPort.java` | 스마트스토어 Port |
| `port/` | `ElevenstOrderApiPort.java` | 11번가 Port |
| `port/` | `EsmplusOrderApiPort.java` | ESM+ Port |
| `port/` | `CustomsClearancePort.java` | 통관 조회 Port |

### 프로젝트 파일 현황

| 모듈 | Java 파일 수 |
|------|------------|
| core | 62 |
| infrastructure | 12 |
| api | 14 |
| worker | 5 |
| **합계** | **93** |
