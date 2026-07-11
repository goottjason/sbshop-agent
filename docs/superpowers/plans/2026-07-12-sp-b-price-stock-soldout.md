# SP-B: 가격/재고·품절 정식 재설계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 품절을 재고수량과 분리된 1급 개념으로 만든다 — `StockStatus`를 마켓 동기화 레이어까지 관통시키고, 마켓 경계에서 수량을 항상 ≥1로 클램프하며, 품절은 마켓별 판매상태 API로 반영한다.

**Architecture:** `MarketClient.syncPriceAndStock` 포트에 `(quantity, soldOut)`를 추가한다. `ProductMarketSyncService`가 `StockStatus`로부터 `soldOut`과 `quantity`(soldOut?1:999)를 중앙 계산해 각 클라이언트에 전달하고, 각 클라이언트는 자기 마켓의 판매상태 메커니즘을 적용한다(11번가 stop/restart 기존·스마트스토어 status:OUTOFSTOCK·쿠팡 판매중지 API·Cafe24 selling F). 도메인·수동 API·배치 크롤 세 경로 모두 이 관통 구조를 쓴다.

**Tech Stack:** Java 21, Spring Boot 3.5(core/infrastructure/api), React 19/Vite/TS(frontend, antd), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- `Product.DEFAULT_IN_STOCK_QUANTITY = 999` (판매중 기본 수량, 단일 상수 — 도메인·서비스 공용).
- 마켓에 전송하는 수량은 **항상 ≥1** (0 금지): `quantity = soldOut ? 1 : DEFAULT_IN_STOCK_QUANTITY`.
- `soldOut = (stockStatus == StockStatus.OUT_OF_STOCK)`.
- 포트-인-코어: 인터페이스는 `core`, 구현은 `infrastructure`.
- 마켓별 판매상태 실패는 은폐하지 말고 표면화(SP-A 원칙) — 예외 전파로 `MarketRepublishResult`의 failed에 집계(기존 서비스 try/catch가 수행).
- **쿠팡 판매중지/재개 API, Cafe24 `selling` 필드, 스마트스토어 `OUTOFSTOCK` 상태**는 코드에 없어 실 API 미검증 — 설계대로 구현하되 라이브 확인 대상(각 태스크에 명시).
- DDL 변경 금지(`stock_status`/`stock` 컬럼 기존 존재).
- 게이트: `:core:test`, `:infrastructure:test`, `:api:test`, 프론트 `tsc -p tsconfig.app.json` 0 / `npm run build` 0.
- 커밋 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: 도메인 — 품절을 stockStatus로 (재고=0 관습 제거)

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/product/Product.java` (create 팩토리 `:133-158`, `createLogisticsInfo` `:374-379`)
- Test: `backend/core/src/test/java/com/sbshop/agent/core/domain/product/ProductSoldOutCreateTest.java`

**Interfaces:**
- Produces: `Product.DEFAULT_IN_STOCK_QUANTITY` (public static final int = 999). `Product.create(sbCode, command)`가 stock=999(항상) + stockStatus(IN_STOCK/OUT_OF_STOCK) 설정.

- [ ] **Step 1: 실패 테스트 작성**

`ProductSoldOutCreateTest.java` — `ProductCreateCommand` 생성이 프로젝트마다 다르므로, 먼저 기존 테스트(`backend/core/src/test/java`에서 `Product.create` 또는 `ProductCreateCommand`를 생성하는 테스트)를 찾아 그 빌더/생성 패턴을 그대로 재사용해 command를 만든다. 검증 골자:
```java
// isAvailable=true 인 command로 생성 시:
Product p = Product.create("SB-TEST-1", availableCommand);
assertThat(p.getStock()).isEqualTo(999);
assertThat(p.getStockStatus()).isEqualTo(StockStatus.IN_STOCK);

// isAvailable=false 인 command로 생성 시:
Product q = Product.create("SB-TEST-2", unavailableCommand);
assertThat(q.getStock()).isEqualTo(999);          // 0이 아니다 (핵심)
assertThat(q.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
```
`getStockStatus()` 게터가 없으면 이 태스크에서 추가한다: `public StockStatus getStockStatus() { return stockStatus; }` (Product.java, 다른 게터 옆).

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductSoldOutCreateTest*'`
Expected: FAIL — 현재 `createLogisticsInfo`가 `isAvailable ? 999 : 0`이라 unavailable일 때 stock=0, stockStatus는 null.

- [ ] **Step 3: 구현**

(a) 상수 추가 — `Product.java` 클래스 상단 필드 근처:
```java
	/** 판매중 상태에서 마켓에 전송하는 기본 재고 수량(실수량 추적 안 함 — 판매중/품절 이분법). */
	public static final int DEFAULT_IN_STOCK_QUANTITY = 999;
```
(b) `createLogisticsInfo`(`:374-379`)의 stock 라인 변경:
```java
			.stock(DEFAULT_IN_STOCK_QUANTITY)   // 기존: command.isAvailable() ? 999 : 0
```
(c) `create` 팩토리(`:133-158`)에서 `return new Product(...);`(`:155`)를 지역변수로 받아 stockStatus 설정 후 반환 (기존 생성자 인자는 그대로 유지):
```java
		Product created = new Product( /* 기존 인자 그대로 */ );
		created.updateStockStatus(command.isAvailable() ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK);
		return created;
```
(d) `getStockStatus()` 게터 없으면 추가.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductSoldOutCreateTest*'`
Expected: PASS

- [ ] **Step 5: core 전체 회귀 확인** (기존 create 테스트가 stock=0 가정에 의존하는지)

Run: `cd backend && ./gradlew :core:test`
Expected: 신규 PASS. 기존 테스트가 unavailable→stock=0을 단언하면 그 테스트를 새 계약(999+OUT_OF_STOCK)으로 갱신(한 곳이라도 있으면 함께 커밋). SmartStore 관련 pre-existing 실패(`SmartStoreOrderFetchFailureTest`)는 SP-B와 무관 — 무시.

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/product/Product.java \
        backend/core/src/test/java/com/sbshop/agent/core/domain/product/ProductSoldOutCreateTest.java
git commit -m "feat(SP-B): 도메인 품절을 stockStatus로 — Product.create 재고 999 고정 + stockStatus 설정

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 포트 + 서비스 + 4개 마켓 클라이언트 (soldOut 관통)

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/market/client/MarketClient.java` (`syncPriceAndStock` `:19-23`)
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductMarketSyncService.java` (`syncPriceStock` `:32`, 호출부 `:53`)
- Modify: `backend/infrastructure/.../coupang/adapter/CoupangMarketClient.java` (`:161`)
- Modify: `backend/infrastructure/.../smartstore/adapter/SmartstoreMarketClient.java` (`:105`)
- Modify: `backend/infrastructure/.../elevenst/adapter/ElevenstMarketClient.java` (`:72`)
- Modify: `backend/infrastructure/.../cafe24/adapter/Cafe24MarketClient.java` (`:112`)
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductMarketSyncServiceSoldOutTest.java`
- Test: 각 클라이언트 특성화 테스트 4종 (아래 Step들)

**Interfaces:**
- Consumes: `Product.DEFAULT_IN_STOCK_QUANTITY`(Task 1), `StockStatus`.
- Produces:
  - Port: `Map<String,Object> syncPriceAndStock(String marketItemId, Map<String,Object> currentRawData, Integer price, int quantity, boolean soldOut)`.
  - Service primary: `MarketRepublishResult syncPriceStock(Long productId, Integer price, StockStatus stockStatus)`.
  - Service compat(임시): `MarketRepublishResult syncPriceStock(Long productId, Integer price, Integer stock)` → `stockStatus = (stock==null||stock<=0)?OUT_OF_STOCK:IN_STOCK`로 primary 위임 (기존 caller 무변경 유지; Task 4에서 제거).

> **주의(마이그레이션):** 포트 시그니처가 바뀌므로 4개 클라이언트가 모두 새 시그니처를 구현해야 컴파일된다. 기존 테스트에 old 4-arg `syncPriceAndStock`를 호출/모킹하는 곳이 있으면 이 태스크에서 함께 새 시그니처로 갱신한다(`grep -rn syncPriceAndStock backend/*/src/test`).

- [ ] **Step 1: 포트 시그니처 변경**

`MarketClient.java`:
```java
	Map<String, Object> syncPriceAndStock(
		String marketItemId,
		Map<String, Object> currentRawData,
		Integer price,
		int quantity,
		boolean soldOut);
```

- [ ] **Step 2: 서비스 실패 테스트 작성**

`ProductMarketSyncServiceSoldOutTest.java`: `MarketRegistrationRepository`·`MarketClientRouter`·`MarketClient`를 Mockito로 두고, 한 마켓이 등록된 상태에서 `syncPriceStock(productId, 1000, StockStatus.OUT_OF_STOCK)` 호출 시 클라이언트가 `syncPriceAndStock(anyItemId, anyRaw, 1000, 1, true)`로 호출되는지, `IN_STOCK`이면 `(..., 999, false)`로 호출되는지 검증.
```java
// 준비: reg.getMarketType()=COUPANG, router.hasClient(COUPANG)=true, router.getClient(COUPANG)=client,
//       reg.extractMarketCode()="V1", reg.getMarketDetailedInfo()="{}"
// 품절:
service.syncPriceStock(1L, 1000, StockStatus.OUT_OF_STOCK);
verify(client).syncPriceAndStock(eq("V1"), any(), eq(1000), eq(1), eq(true));
// 판매중:
service.syncPriceStock(1L, 1000, StockStatus.IN_STOCK);
verify(client).syncPriceAndStock(eq("V1"), any(), eq(1000), eq(999), eq(false));
```
(기존 서비스 테스트가 있으면 그 준비 패턴을 재사용.)

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductMarketSyncServiceSoldOutTest*'`
Expected: FAIL(컴파일) — 새 시그니처·primary 메서드 미존재.

- [ ] **Step 4: 서비스 구현**

`ProductMarketSyncService.java` — primary + compat + 호출부 변경:
```java
	public MarketRepublishResult syncPriceStock(Long productId, Integer price, StockStatus stockStatus) {
		boolean soldOut = stockStatus == StockStatus.OUT_OF_STOCK;
		int quantity = soldOut ? 1 : Product.DEFAULT_IN_STOCK_QUANTITY;
		return syncInternal(productId, price, quantity, soldOut);
	}

	/** 임시 호환 오버로드(기존 caller 유지용). stock<=0 → 품절. Task 4에서 caller 이관 후 제거. */
	public MarketRepublishResult syncPriceStock(Long productId, Integer price, Integer stock) {
		StockStatus status = (stock == null || stock <= 0) ? StockStatus.OUT_OF_STOCK : StockStatus.IN_STOCK;
		return syncPriceStock(productId, price, status);
	}
```
그리고 기존 `syncPriceStock` 본문을 `private MarketRepublishResult syncInternal(Long productId, Integer price, int quantity, boolean soldOut)`로 이름만 바꾸고, 내부 `client.syncPriceAndStock(...)` 호출(`:53`)을 새 시그니처로:
```java
				Map<String, Object> updated =
					client.syncPriceAndStock(marketItemId, currentRawData, price, quantity, soldOut);
```
(`import com.sbshop.agent.core.domain.product.Product;`, `import com.sbshop.agent.core.domain.product.enums.StockStatus;` 추가.)

- [ ] **Step 5: 서비스 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductMarketSyncServiceSoldOutTest*'`
Expected: PASS

- [ ] **Step 6: 11번가 클라이언트 — soldOut 기준 stop/restart**

Test `ElevenstMarketClientSoldOutTest` (기존 Elevenst 테스트 패턴 재사용, `restClient` 모킹): soldOut=true → `stopdisplay` PUT, soldOut=false → `restartdisplay` PUT, price 있으면 price GET.
구현 `ElevenstMarketClient.syncPriceAndStock`(`:72`) 본문 교체 — stock 대신 soldOut:
```java
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut) {
		try {
			if (price != null) {
				restClient.get("/rest/prodservices/product/price/" + marketItemId + "/" + price);
				log.info("[Elevenst] 가격 업데이트: {} -> {}", marketItemId, price);
			}
			// 11번가는 수량 개념 없음 — 판매상태로만 처리(soldOut 기준).
			if (soldOut) {
				restClient.put("/rest/prodstatservice/stat/stopdisplay/" + marketItemId, "");
			} else {
				restClient.put("/rest/prodstatservice/stat/restartdisplay/" + marketItemId, "");
			}
			log.info("[Elevenst] 판매상태 업데이트: {} -> soldOut={}", marketItemId, soldOut);
			if (currentRawData != null && price != null) {
				currentRawData.put("salePrice", price);
			}
		} catch (Exception e) {
			log.error("[Elevenst] 가격/판매상태 업데이트 실패: {}", e.getMessage());
			throw e; // 실패 표면화(SP-A 원칙)
		}
		return currentRawData;
	}
```
Run: `cd backend && ./gradlew :infrastructure:test --tests '*ElevenstMarketClientSoldOutTest*'` → PASS

- [ ] **Step 7: 스마트스토어 — status OUTOFSTOCK/SALE 명시 + 수량≥1**

Test `SmartstoreMarketClientSoldOutTest`: soldOut=true → PUT 바디 `originProduct.status == "OUTOFSTOCK"`, `stockQuantity == 1`; soldOut=false → `status == "SALE"`, `stockQuantity == 999`.
구현 `SmartstoreMarketClient.syncPriceAndStock`(`:105`) — stock 처리부를 quantity+soldOut로:
```java
			if (price != null)
				originProduct.put("salePrice", price);
			originProduct.put("stockQuantity", quantity);
			originProduct.put("status", soldOut ? "OUTOFSTOCK" : "SALE");
```
(currentRawData 업데이트도 `stockQuantity`=quantity로. try/catch에서 실패 시 `throw e`로 표면화 — 현재는 log만 하므로 catch 끝에 `throw e;` 추가.)
Run: `cd backend && ./gradlew :infrastructure:test --tests '*SmartstoreMarketClientSoldOutTest*'` → PASS

- [ ] **Step 8: 쿠팡 — quantity≥1 + 판매중지/재개 API**

Test `CoupangMarketClientSoldOutTest`: soldOut=true → `.../quantities/1` PUT + `.../sales/stop` PUT; soldOut=false → `.../quantities/999` PUT + `.../sales/resume` PUT; price 있으면 `.../prices/{price}` PUT.
구현 `CoupangMarketClient.syncPriceAndStock`(`:161`):
```java
		String base = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + marketItemId;
		if (price != null) {
			restClient.put(base + "/prices/" + price, java.util.Map.of());
		}
		restClient.put(base + "/quantities/" + quantity, java.util.Map.of()); // 항상 ≥1
		// 판매상태: 코드에 없던 신규 경로 — 라이브 검증 필요. 빈 JSON 바디는 기존 411 회피 관습.
		restClient.put(base + (soldOut ? "/sales/stop" : "/sales/resume"), java.util.Map.of());
		log.info("[쿠팡] 가격/재고/판매상태: vendorItemId={}, price={}, qty={}, soldOut={}",
			marketItemId, price, quantity, soldOut);
		return currentRawData;
```
> 라이브 검증: 쿠팡 vendor-items `/sales/stop`·`/sales/resume` 엔드포인트 실존/응답 확인. 실패 시 예외 전파 → failed 마켓으로 표면화.
Run: `cd backend && ./gradlew :infrastructure:test --tests '*CoupangMarketClientSoldOutTest*'` → PASS

- [ ] **Step 9: Cafe24 — supply_quantity≥1 + selling T/F**

Test `Cafe24MarketClientSoldOutTest`: soldOut=true → PUT 바디 `request.supply_quantity=="1"`, `request.selling=="F"`; soldOut=false → `"999"`, `"T"`.
구현 `Cafe24MarketClient.syncPriceAndStock`(`:112`) — stock 처리부를 quantity+soldOut로:
```java
		if (price != null) {
			productData.put("price", price + ".00");
		}
		productData.put("supply_quantity", String.valueOf(quantity)); // 항상 ≥1
		productData.put("selling", soldOut ? "F" : "T");              // 신규, 라이브 검증 필요
```
(currentRawData variants[0].quantity 업데이트도 quantity로.)
> 라이브 검증: Cafe24 `PUT /admin/products/{id}` 바디의 `selling`(T/F) 필드 실효성 확인.
Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24MarketClientSoldOutTest*'` → PASS

- [ ] **Step 10: infrastructure + core 전체 컴파일·테스트**

Run: `cd backend && ./gradlew :core:test :infrastructure:test --tests '*MarketClient*' --tests '*ProductMarketSync*'`
Expected: PASS. old 4-arg 시그니처를 참조하던 기존 테스트가 있으면 새 시그니처로 갱신했는지 확인.

- [ ] **Step 11: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/market/client/MarketClient.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductMarketSyncService.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/coupang/adapter/CoupangMarketClient.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/smartstore/adapter/SmartstoreMarketClient.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/adapter/ElevenstMarketClient.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/adapter/Cafe24MarketClient.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductMarketSyncServiceSoldOutTest.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client
git commit -m "feat(SP-B): soldOut/quantity를 마켓 동기화 포트로 관통 + 4마켓 판매상태 분기

수량 항상>=1 클램프, 품절은 마켓별 판매상태로(11번가 stop/restart·스마트스토어
OUTOFSTOCK·쿠팡 sales stop/resume·Cafe24 selling F). 쿠팡·Cafe24 판매상태 API는
라이브 검증 대상.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 수동 API 경로 — (price, soldOut) 계약

**Files:**
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/dto/product/PriceStockUpdateRequest.java`
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java` (`updatePriceStock` `:95`)
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductManageUseCase.java` (`updatePriceStock` `:43-62`)
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductManageUpdatePriceStockSoldOutTest.java`

**Interfaces:**
- Consumes: 서비스 `syncPriceStock(Long, Integer, StockStatus)`(Task 2), `Product.updateStockStatus`, `StockStatus`.
- Produces: `PriceStockUpdateRequest(BigDecimal price, Boolean soldOut)`. `ProductManageUseCase.updatePriceStock(Long productId, BigDecimal price, boolean soldOut) : MarketRepublishResult`.

- [ ] **Step 1: 유스케이스 실패 테스트 작성**

`ProductManageUpdatePriceStockSoldOutTest.java`: `productReader.findById` → 상품, `productMarketSyncService`·`productWriter` 모킹. `updatePriceStock(1L, new BigDecimal("1000"), true)` 호출 시 (a) 상품 stockStatus가 OUT_OF_STOCK로 갱신, (b) `syncPriceStock(1L, 1000, StockStatus.OUT_OF_STOCK)` 호출; soldOut=false면 IN_STOCK.
```java
verify(productMarketSyncService).syncPriceStock(1L, 1000, StockStatus.OUT_OF_STOCK);
assertThat(product.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
```
(기존 usecase 테스트 준비 패턴 재사용.)

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductManageUpdatePriceStockSoldOutTest*'`
Expected: FAIL(컴파일) — 시그니처 `updatePriceStock(Long, BigDecimal, boolean)` 미존재.

- [ ] **Step 3: 유스케이스 구현**

`ProductManageUseCase.updatePriceStock`(`:43-62`) 교체:
```java
	@Transactional
	public MarketRepublishResult updatePriceStock(Long productId, BigDecimal price, boolean soldOut) {
		Product product = productReader.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + productId));

		// 가격만 command로 갱신(수량은 판매중/품절 이분법으로 대체 — DB 수량은 건드리지 않음).
		ProductUpdateCommand command = new ProductUpdateCommand(
			null, null, null, null, null,
			null, null, null, null, price,
			null, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null, null, null, null);
		product.update(command);
		StockStatus stockStatus = soldOut ? StockStatus.OUT_OF_STOCK : StockStatus.IN_STOCK;
		product.updateStockStatus(stockStatus);
		productWriter.save(product);

		log.info("상품 가격/판매상태 업데이트: id={}, price={}, soldOut={}", productId, price, soldOut);

		Integer priceInt = price != null ? price.intValue() : null;
		return productMarketSyncService.syncPriceStock(productId, priceInt, stockStatus);
	}
```
(`ProductUpdateCommand`의 stock 자리(11번째 인자)를 `null`로 — 위 예시가 그 형태. `import ...enums.StockStatus;` 추가.)

- [ ] **Step 4: 유스케이스 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductManageUpdatePriceStockSoldOutTest*'`
Expected: PASS

- [ ] **Step 5: 요청 DTO + 컨트롤러 변경**

`PriceStockUpdateRequest.java`:
```java
package com.sbshop.agent.api.dto.product;

import java.math.BigDecimal;

public record PriceStockUpdateRequest(
	BigDecimal price,
	Boolean soldOut) {
}
```
`ProductController.updatePriceStock`(`:95-114`)의 호출 라인:
```java
			MarketRepublishResult result = productManageUseCase.updatePriceStock(
				id, request.price(), Boolean.TRUE.equals(request.soldOut()));
```
(활동로그 record 호출은 그대로 유지.)

- [ ] **Step 6: api 컴파일 + 테스트**

Run: `cd backend && ./gradlew :api:compileJava :api:compileTestJava :core:test`
Expected: SUCCESS / PASS. (컨트롤러 테스트가 old `stock()` 필드를 참조하면 새 `soldOut` 계약으로 갱신.)

- [ ] **Step 7: 커밋**

```bash
git add backend/api/src/main/java/com/sbshop/agent/api/dto/product/PriceStockUpdateRequest.java \
        backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductManageUseCase.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductManageUpdatePriceStockSoldOutTest.java
git commit -m "feat(SP-B): 수동 가격/재고 API를 (price, soldOut) 계약으로 — stockStatus 갱신·전파

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 배치/크롤 경로 — StockStatus 전달 + 호환 오버로드 제거

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/BatchPriceStockService.java` (`:72-73`, `:120`)
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductMarketSyncService.java` (Task 2의 compat 오버로드 제거)
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/BatchForwardsStockStatusTest.java`

**Interfaces:**
- Consumes: 서비스 primary `syncPriceStock(Long, Integer, StockStatus)`. 크롤 결과 `StockCheckResult.status()`(이미 존재, `:67`에서 `product.updateStockStatus(result.status())`로 사용 중).

- [ ] **Step 1: 실패 테스트 작성**

`BatchForwardsStockStatusTest.java`: 크롤러 포트가 `StockCheckResult(status=OUT_OF_STOCK, ...)`를 반환하도록 모킹하고 배치 실행 시 `productMarketSyncService.syncPriceStock(productId, price, StockStatus.OUT_OF_STOCK)`가 호출되는지 검증(현재는 `result.stock()` 정수를 넘김).
```java
verify(productMarketSyncService).syncPriceStock(eq(productId), any(), eq(StockStatus.OUT_OF_STOCK));
```
(기존 `BatchPriceStockService` 테스트 준비 패턴 재사용.)

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*BatchForwardsStockStatusTest*'`
Expected: FAIL — 현재 `syncPriceStock(productId, price, result.stock())`(Integer)로 호출.

- [ ] **Step 3: 배치 호출부 변경 (2곳)**

`BatchPriceStockService.java:72-73` 및 `:120` 근처의 `syncPriceStock(..., result.stock())`를 `result.status()`(StockStatus) 전달로 변경:
```java
				MarketRepublishResult sync = productMarketSyncService.syncPriceStock(
					productId, salePrice != null ? salePrice.intValue() : null, result.status());
```
(`:120`의 `manualUpdatePriceStock` 경로도 해당 위치의 `StockCheckResult`/status를 동일하게 전달. 그 경로가 status를 갖고 있지 않으면 그 지점의 크롤 결과에서 `.status()`를 사용.)

- [ ] **Step 4: 테스트 통과 + compat 오버로드 제거**

먼저 Step 3 통과 확인:
Run: `cd backend && ./gradlew :core:test --tests '*BatchForwardsStockStatusTest*'` → PASS
그 다음 `ProductMarketSyncService`의 임시 compat `syncPriceStock(Long, Integer, Integer stock)` 오버로드를 제거하고, 남은 참조가 없는지 확인:
Run: `grep -rn "syncPriceStock(" backend --include="*.java" | grep -v "StockStatus\|/test/"` → primary(StockStatus) 호출만 남아야 함. (테스트가 Integer 오버로드를 쓰면 primary로 갱신.)

- [ ] **Step 5: core 전체 테스트**

Run: `cd backend && ./gradlew :core:test`
Expected: PASS (SmartStore pre-existing 실패 제외).

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/product/BatchPriceStockService.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductMarketSyncService.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/BatchForwardsStockStatusTest.java
git commit -m "feat(SP-B): 배치 크롤이 StockStatus를 마켓 동기화로 전달 + 호환 오버로드 제거

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 프론트 — 가격/재고 모달 판매중/품절 토글

**Files:**
- Modify: `frontend/src/pages/ProductPage.tsx` (`:128` 상태, `:395-425` 모달, `:404` 호출, `:155` 오픈 시드)
- Modify: `frontend/src/api/productApi.ts` (`:73-74` updatePriceStock)

**Interfaces:**
- Consumes: 백엔드 `PUT /api/v1/products/{id}/price-stock` 바디 `{ price, soldOut }`(Task 3).

- [ ] **Step 1: productApi 변경**

`productApi.ts:73-74`:
```ts
  updatePriceStock: (id: number, price: number, soldOut: boolean) =>
    apiClient.put(`/api/v1/products/${id}/price-stock`, { price, soldOut }),
```

- [ ] **Step 2: 모달 상태·시드 변경**

`ProductPage.tsx:128` 상태 타입에서 `stock?: number` → `soldOut?: boolean`:
```tsx
  const [priceStockModal, setPriceStockModal] = useState<{ visible: boolean; id?: number; price?: number; soldOut?: boolean }>({ visible: false });
```
모달 오픈 핸들러(`:155` 부근 `setPriceStockModal({...})`)에서 행의 현재 판매상태로 시드: 행 데이터에 `stockStatus`가 있으면 `soldOut: row.stockStatus === 'OUT_OF_STOCK'`, 없으면 `soldOut: false`.

- [ ] **Step 3: onOk 호출 + 토글 UI 변경**

`:404` 호출:
```tsx
              const res = await productApi.updatePriceStock(priceStockModal.id, priceStockModal.price || 0, priceStockModal.soldOut === true);
```
모달 본문(`:414-424`)에서 재고 InputNumber 블록을 판매중/품절 토글로 교체(antd `Switch` import 추가):
```tsx
          <div>
            <label>판매가: </label>
            <InputNumber min={0} value={priceStockModal.price} onChange={(v) => setPriceStockModal({ ...priceStockModal, price: v || 0 })} />
          </div>
          <div>
            <label>판매상태: </label>
            <Switch
              checked={priceStockModal.soldOut === true}
              checkedChildren="품절"
              unCheckedChildren="판매중"
              onChange={(checked) => setPriceStockModal({ ...priceStockModal, soldOut: checked })}
            />
          </div>
```
(파일 상단 antd import에 `Switch` 추가.)

- [ ] **Step 4: 타입체크 + 빌드**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: tsc 0, build 성공. (`priceStockModal.stock` 잔여 참조가 있으면 제거.)

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/ProductPage.tsx frontend/src/api/productApi.ts
git commit -m "feat(SP-B): 가격/재고 모달 판매중/품절 토글 — 수량 입력 제거, soldOut 제출

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 통합 게이트 + 라이브 확인 준비

**Files:** 없음(검증 전용).

- [ ] **Step 1: 백엔드 전체 게이트**

Run: `cd backend && ./gradlew :core:test :infrastructure:test :api:compileTestJava`
Expected: SP-B 신규/변경 테스트 PASS. pre-existing 무관 실패(core `SmartStoreOrderFetchFailureTest`, infra `ImageDownloadServiceCharacterizationTest` 네이티브 SIGABRT)는 SP-B와 무관 — diff 밖임을 `git diff --name-only <base>..HEAD`로 확인해 기록.

- [ ] **Step 2: 프론트 게이트**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: 0 / 성공.

- [ ] **Step 3: 라이브 확인 체크리스트 문서화**

배포 후(사용자 허가): 각 마켓에서 품절 토글 → 실제 판매중지 반영, 판매중 복귀, **어떤 마켓에도 수량 0 미전송**. 특히 미검증 API 실동작: 쿠팡 `/sales/stop`·`/sales/resume`, Cafe24 `selling` T/F, 스마트스토어 `status:OUTOFSTOCK`. 실패는 `MarketRepublishResult` failed로 표면화되는지.

---

## Self-Review 체크

- **Spec 커버리지:** soldOut 관통(Task 2)·수량≥1 클램프(Task 2)·마켓별 품절 API(Task 2 4클라)·도메인 재고0 관습 제거(Task 1)·수동 (price,soldOut) 계약(Task 3)·배치 StockStatus 전달(Task 4)·프론트 토글(Task 5)·게이트(Task 6). DDL 없음. ✅
- **Placeholder:** 코드/명령/기대출력 구체화. 도메인 create의 `new Product(...)` 기존 인자는 "그대로 유지"로 명시(전체 인자 미재현은 오류 방지 목적). ✅
- **타입 일관성:** 포트 `syncPriceAndStock(String, Map, Integer price, int quantity, boolean soldOut)` — Task 2 정의와 4개 클라이언트·서비스 호출 일치. 서비스 primary `syncPriceStock(Long, Integer, StockStatus)` — Task 3(usecase)·Task 4(batch) 사용 일치. compat 오버로드는 Task 2 도입→Task 4 제거로 수명 명시. `DEFAULT_IN_STOCK_QUANTITY`(Product) — Task 1 정의, Task 2 사용. ✅
- **미검증 API 주의:** 쿠팡 sales stop/resume, Cafe24 selling, 스마트스토어 OUTOFSTOCK은 라이브 검증 대상으로 각 Step에 명시. 구현은 실패 표면화(throw) 규율 준수.
