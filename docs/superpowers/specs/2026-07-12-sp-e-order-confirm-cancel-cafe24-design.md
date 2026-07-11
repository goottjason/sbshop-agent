# SP-E 설계 — 발주확인/취소 Cafe24 전환 + ESM+ Selenium 청산

- 작성일: 2026-07-12
- 상태: 설계 승인됨 (구현 계획 대기)
- 서브프로젝트: SP-E (로드맵 3순위 — 운영 중 깨진 기능)
- 선행: [[deployment-two-jvm-topology]], [[market-auth-roadmap-sp-a]] (SP-A·SP-B 완료)

---

## 1. 문제 정의

G마켓/옥션 주문 *조회*는 이미 ESM+ Selenium → Cafe24 주문 API로 전환됐으나(`Cafe24OrderSyncService`), **발주확인·취소는 아직 미해결**이다.

- **발주확인(confirm/accept)**: G마켓 → `EsmplusOrderAdapter.acceptOrders`(`EsmplusOrderAdapter.java:81-84`) → `esmplusOrderApiPort.confirmOrders`(Selenium, `EsmplusOrderApiPortImpl.java:106-145`, `RemoteWebDriver`). 컨테이너에서 Chrome/드라이버 부재로 동작 불가. 옥션 → `Cafe24AuctionOrderAdapter`는 no-op(로그만).
- **취소(cancel)**: `OrderService.cancelOrder`(`OrderService.java:136-156`)는 **로컬 상태만 CANCELED로 변경, 마켓 API 호출 0**(전 마켓 공통). `MarketplaceShippingService.cancelOrderToMarketplace`(`:114-125`)는 존재하나 미연결(죽은 메서드). 어댑터의 Selenium `cancelOrders`는 호출되지 않는 죽은 코드.

Cafe24 주문 API 표면: `Cafe24OrderApiPort`/`Cafe24OrderApiClient`에 fetch·shipments만 배선(`registerShipment` = `POST /admin/orders/{id}/shipments`). `Cafe24RestClient.put()/delete()`(`:54-95`) 존재하나 주문 confirm/cancel 미사용. G마켓/옥션 주문의 Cafe24 `order_id`는 `Order.marketOrderNo`에 저장(`Cafe24OrderSyncService.java:145`), `order_place_id`로 G마켓/옥션 구분(`:121`).

**확정(사용자):** Cafe24 주문상태 API로 발주확인·취소가 실제 G마켓/옥션 원마켓에 반영된다.

---

## 2. 목표 & 성공 기준

- G마켓·옥션 발주확인/취소가 Selenium 없이 **Cafe24 주문상태 API**로 동작(컨테이너 가능).
- ESM+ Selenium 클래스가 코드베이스에서 제거된다(fetch·confirm·cancel 전부 Cafe24).
- 취소가 G마켓/옥션에서 실제 Cafe24로 전파된다(현재 로컬-only).
- 스케줄러/컨트롤러의 죽은 ESM+ 주입·디버그 엔드포인트·오해 소지 TODO 주석 정리.

---

## 3. 설계

### 3.1 Cafe24 주문상태 API 추가
- `Cafe24OrderApiPort` + `Cafe24OrderApiClient`에 `updateOrderStatus(String cafe24OrderId, String status)` 추가 — `PUT /admin/orders/{orderId}` 바디 `{"shop_no":1, "request":{"status": status}}` (기존 Cafe24 PUT 관습 준수). `Cafe24RestClient.put()` 재사용.
- 상태값(발주확인=accept, 취소=cancel에 해당하는 Cafe24 status 문자열)은 코드에 선례 없어 **best-known으로 상수화하고 라이브 검증**. 실패는 은폐하지 말고 예외 전파(SP-A 원칙).

### 3.2 G마켓 어댑터 Selenium → Cafe24
- `EsmplusOrderAdapter`(GMARKET, Selenium) → **Cafe24 기반 어댑터**로 교체(예: `Cafe24GmarketOrderAdapter`, `getMarketType()=GMARKET`).
  - `acceptOrders(cred, order)` → `cafe24OrderApiPort.updateOrderStatus(order.getMarketOrderNo(), ACCEPT_STATUS)`.
  - `cancelOrder(cred, order)` → `updateOrderStatus(order.getMarketOrderNo(), CANCEL_STATUS)`.
- **GMARKET `MarketOrderPort` 빈은 정확히 1개**여야 `MarketplaceShippingService.getPort(GMARKET)` 충돌이 없음 — 기존 EsmplusOrderAdapter 삭제와 동시 교체.

### 3.3 옥션 어댑터 구현
- `Cafe24AuctionOrderAdapter`(AUCTION, 현재 no-op/UnsupportedOperationException)에 `acceptOrders`/`cancelOrder`를 **동일 Cafe24 status update로 구현**.
- DRY: G마켓/옥션 어댑터가 공통 `Cafe24OrderApiPort.updateOrderStatus`를 공유(별도 base/헬퍼 불필요 — 포트 메서드 직접 호출).

### 3.4 취소 전파 배선 (G마켓/옥션 한정)
- `OrderService`에 `callMarketplaceCancelApi(order, credential)` 추가(confirm의 `callMarketplaceAcceptApi`(`:523-525`) 대칭) — `port.cancelOrder(credential, order)` 호출.
- `OrderService.cancelOrder`에서 로컬 CANCELED 변경 후, **marketType ∈ {GMARKET, AUCTION}일 때만** `callMarketplaceCancelApi` 호출.
- 쿠팡·스마트스토어 취소 마켓전파는 **현행(로컬) 유지** — 이들 어댑터에 cancel 구현이 있으나 활성화하면 오취소 리스크. SP-E 범위 밖.

### 3.5 ESM+ Selenium 청산
- **삭제**: `EsmplusOrderSyncService`(fetch, Cafe24 대체됨) · `EsmplusOrderApiPortImpl`(Selenium) · `EsmplusOrderApiPort`(인터페이스) · `EsmplusScraper` · `EsmplusDriverFactory` · `EsmplusOrderAdapter`(교체됨). 관련 어댑터/포트(`EsmplusOrderApiPortImpl`의 fetch 경로 포함)도 호출처 없으면 삭제.
- **주입 제거**: `OrderSyncScheduler.java:32`(esmplusOrderSyncService), `OrderSyncController.java:43`(esmplusOrderSyncService)·`:51`(esmplusScraper).
- **엔드포인트 제거**: `OrderSyncController`의 `/esmplus/test`(`:249`)·`/esmplus/scrape`(`:273`) 디버그 엔드포인트(프론트 미매핑).
- **주석 정리**: `OrderSyncScheduler`의 `// TODO: 리팩토링 완료 후 활성화` 5개(`:38,53,83,98,113`) — 현재 활성 상태이므로 제거/정정.
- **selenium 의존성**: `infrastructure/build.gradle`의 selenium은 통관 스크래퍼(`GsiExpressScraperAdapter`)가 여전히 사용하면 **유지**(구현 시 grep 확인). ESM+만 쓰던 것이면 제거.

---

## 4. 에러 처리
- Cafe24 status update 실패 → 예외 전파 → 상위(OrderService/컨트롤러)에서 실패로 표면화. Bearer/토큰 문제는 SP-A 토큰 매니저가 fail-fast.
- 미검증 상태값이 틀리면 Cafe24가 4xx 반환 → 실패로 표면화(조용히 성공 위장 안 함).

---

## 5. 테스트 전략 (TDD Red→Green)

1. **Cafe24 클라이언트**: `Cafe24OrderApiClient.updateOrderStatus` — MockRestServiceServer로 `PUT /admin/orders/{id}` URL·바디(status) 검증.
2. **G마켓 어댑터**: `acceptOrders`/`cancelOrder`가 `updateOrderStatus(marketOrderNo, ACCEPT|CANCEL)` 호출(Mockito).
3. **옥션 어댑터**: 동일.
4. **OrderService.cancelOrder**: GMARKET/AUCTION → `callMarketplaceCancelApi` 호출 + 로컬 CANCELED; COUPANG/SMART_STORE → 로컬만(마켓 미호출) 회귀 불변.
5. **Selenium 삭제 후**: `:core:test`·`:infrastructure:test`·`:api:compile` 통과, GMARKET `MarketOrderPort` 빈 단일성(컨텍스트 로딩) 확인.

로컬 Docker-off: Mock 기반 커버, 실 status·원마켓 반영은 라이브.

---

## 6. 범위 밖 (명시)
- 쿠팡·스마트스토어 취소 마켓전파(현행 로컬 유지).
- 주문 조회(이미 Cafe24 전환 완료) 변경.
- 통관 Selenium(`GsiExpressScraperAdapter`)은 SP-E 대상 아님(의존성만 조건부 유지).
- DDL 변경 없음.

---

## 7. 영향 파일 (예상)

| 파일 | 변경 |
|---|---|
| `core/.../order/port/Cafe24OrderApiPort.java` + `infrastructure/.../cafe24/...Cafe24OrderApiClient` | `updateOrderStatus` 추가 |
| `EsmplusOrderAdapter.java` → 신규 `Cafe24GmarketOrderAdapter` | GMARKET confirm/cancel Cafe24화 |
| `Cafe24AuctionOrderAdapter.java` | AUCTION confirm/cancel 구현 |
| `core/.../application/order/OrderService.java` | `callMarketplaceCancelApi` + cancelOrder 배선(G마켓/옥션) |
| `EsmplusOrderApiPortImpl`, `EsmplusOrderApiPort`, `EsmplusOrderSyncService`, `EsmplusScraper`, `EsmplusDriverFactory` | 삭제 |
| `worker/.../OrderSyncScheduler.java` | ESM+ 주입 제거 + TODO 주석 정리 |
| `api/.../OrderSyncController.java` | ESM+ 주입·디버그 엔드포인트 제거 |
| `infrastructure/build.gradle` | selenium 조건부 정리 |
| 신규 테스트 (core/infrastructure) | 위 5개 축 |

---

## 8. 검증/배포
- 코드 게이트: `:core:test`, `:infrastructure:test`, `:api:test`(또는 compile), 컨텍스트 로딩.
- 라이브 확인(배포 후, 사용자 허가): G마켓·옥션 실주문 발주확인/취소 → Cafe24 반영 + 원마켓 상태 변경 확인, 실패 표면화. Cafe24 accept/cancel 실 상태값 확정.
- push/배포는 사용자 확인 후.
