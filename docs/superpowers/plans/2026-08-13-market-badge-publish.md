# 상품 그리드 마켓 배지 클릭 등록 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 상품 관리 그리드의 마켓 컬럼에서 미등록 마켓을 점선 배지로 드러내고, 그 배지를 클릭해 해당 마켓에 상품을 등록하고 상품번호를 자동 매칭한다.

**Architecture:** 배지 셀은 6개 마켓(쿠팡·N스토어·카페24·G마켓·옥션·11번가) 고정 슬롯으로 바뀌고, 서버는 마켓별 `{status, url}`을 내려준다. 등록은 기존 `POST /api/v1/products/{id}/markets/{marketType}`(ProductPublishUseCase)를 재사용하되 응답에 등록 결과를 실어 배지를 즉시 갱신한다. 어댑터가 없는 G마켓·옥션은 Cafe24 등록을 선행조건으로 두고, Playwright 사이드카(`sbshop-scraper`)가 Cafe24 마켓플러스에서 "일괄 보내기"를 대신 수행한다.

**Tech Stack:** Java 21 / Spring Boot 3.5 (core·infrastructure·api 멀티모듈), JUnit5 + Mockito + AssertJ, React 19 + TypeScript + TanStack Table + antd + react-toastify, Python FastAPI + Playwright(사이드카).

## Global Constraints

- 스펙 원본: `docs/superpowers/specs/2026-08-13-market-badge-publish-design.md`
- 백엔드 테스트: `cd backend && ./gradlew :<module>:test --tests '<FQCN>'` (모듈: core / infrastructure / api)
- 프론트 타입 게이트: `cd frontend && npx tsc -p tsconfig.app.json` (**루트 tsconfig는 references-only라 `-p` 없이 실행하면 헛-그린이 난다**)
- 프론트에는 테스트 러너가 없다(`package.json` scripts: dev/build/lint/format/preview). 프론트 검증은 타입 게이트 + `npx eslint .` + 실제 화면 확인이다.
- 스키마는 운영 DB가 단일 원본(Flyway 없음). **이 계획은 DDL 변경을 요구하지 않는다** — 새 상태는 기존 `sb_market_registration.market_identifiers`(JSON)와 `is_synced`로 표현한다.
- 배포는 `git push origin main`만 한다. SSH로 `docker compose build/up` 금지.
- 마켓명 표기는 UI 전역에서 `쿠팡 / N스토어 / 카페24 / G마켓 / 옥션 / 11번가`로 통일한다.
- 실패는 조용히 삼키지 않는다. 등록 실패·자격증명 미설정은 모두 사용자에게 보이는 오류로 표면화한다.

**스펙 대비 조정 1건(의도적):** 스펙 4장은 status를 `SYNCED|PENDING|FAILED`로 적었으나, `MarketRegistration`에는 `isSynced` 불리언만 있고 실패를 저장하는 컬럼이 없다. DDL을 늘리지 않기 위해 **서버 status는 `SYNCED|PENDING` 두 값만** 내려보내고, `PUBLISHING`·`FAILED`는 **클라이언트 세션 상태**로 표현한다(새로고침 시 소멸). 스펙 3장의 "페이지를 떠나면 유실된다"와 일관된다.

---

## File Structure

**백엔드 — 생성**
- `backend/api/src/main/java/com/sbshop/agent/api/dto/product/MarketBadgeState.java` — 배지 1칸의 서버 상태(status·url)
- `backend/api/src/main/java/com/sbshop/agent/api/dto/product/MarketPublishResponse.java` — 등록 API 응답
- `backend/core/src/main/java/com/sbshop/agent/core/application/product/MarketSalePriceResolver.java` — 마켓별 판매가 산정 단일 출처
- `backend/core/src/main/java/com/sbshop/agent/core/application/product/dto/MarketPublishOutcome.java` — publish 결과(마켓·identifiers·synced)
- `backend/core/src/main/java/com/sbshop/agent/core/application/product/MarketPlusPublisher.java` — G마켓·옥션 전송 오케스트레이션
- `backend/core/src/main/java/com/sbshop/agent/core/application/product/port/MarketPlusSendPort.java` — 사이드카 포트(core는 HTTP를 모른다)
- `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/marketplus/MarketPlusSendClient.java` — 포트 구현(사이드카 HTTP)

**백엔드 — 수정**
- `backend/api/src/main/java/com/sbshop/agent/api/dto/product/ProductListResponse.java` — `Map<String,String>` → `Map<String,MarketBadgeState>`
- `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java:425-456` — `buildMarketMap` 확장(CAFE24 키·status)
- `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductSourcingController.java:136-154` — 등록 응답 DTO 반환
- `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductPublishUseCase.java` — 결과 반환 + 마켓별 가격 주입 + G/옥션 분기
- `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductMarketSyncService.java:75-79` — `priceForMarket`을 resolver 위임으로 교체
- `backend/infrastructure/.../smartstore/adapter/SmartstoreMarketClient.java:74` — 부분 컨텍스트를 autoContext와 병합

**프론트 — 생성**
- `frontend/src/pages/product/MarketBadgeCell.tsx` — 6슬롯 배지 셀 + 클릭 등록

**프론트 — 수정**
- `frontend/src/pages/product/productGridShared.tsx:37-77` — `renderMarketBadges` 제거, 배지 메타만 export
- `frontend/src/pages/product/ProductGrid.tsx:38-42,139-141` — 필터 로직·컬럼 폭·셀 교체
- `frontend/src/api/productApi.ts:20` — `marketRegistrations` 타입 변경
- `frontend/src/api/sourcingApi.ts:36` — 등록 응답 타입 명시

**사이드카 — 생성/수정**
- `scraper/marketplus.py` — Playwright 로그인·검색·일괄보내기
- `scraper/app.py` — `POST /cafe24/mp/send` 라우트 추가
- `docker-compose.yml` — `sbshop-scraper`에 `environment` 블록 신설
- `.env.example` — 자격증명 키 문서화
- `sync-env.sh` — `SYNC_KEYS`에 자격증명 키 추가

---

## Task 1: 마켓 등록상태 응답 계약 승격

**Files:**
- Create: `backend/api/src/main/java/com/sbshop/agent/api/dto/product/MarketBadgeState.java`
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/dto/product/ProductListResponse.java`
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java:425-456`
- Test: `backend/api/src/test/java/com/sbshop/agent/api/controller/ProductControllerMarketMapTest.java`

**Interfaces:**
- Consumes: `MarketRegistration.buildMarketUrl()`, `buildGmarketUrl()`, `buildAuctionUrl()`, `getMarketType()`, `getIsSynced()` (모두 기존 public)
- Produces: `record MarketBadgeState(String status, String url)` — status는 `"SYNCED"` 또는 `"PENDING"`. `ProductListResponse.marketRegistrations`의 타입은 `Map<String, MarketBadgeState>`. `ProductController.buildMarketMap(List<MarketRegistration>)`의 반환형도 같다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

기존 `ProductControllerMarketMapTest`에 아래 두 테스트를 추가한다. 기존 테스트들은 `Map<String,String>`을 단언하므로 **Step 3에서 함께 고친다**(그 전까진 컴파일이 깨지는 게 정상이다).

```java
	@Test
	@DisplayName("CAFE24 등록행은 CAFE24 키로도 내려간다 — 프론트가 카페24 등록 여부를 알아야 G마켓/옥션 선행조건을 판정한다")
	void getProducts_includesCafe24Key() {
		when(product1.getId()).thenReturn(1L);
		Page<Product> page = new PageImpl<>(List.of(product1), PageRequest.of(0, 50), 1);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(page);
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L)))
			.thenReturn(List.of(reg(1L, MarketType.CAFE24, "{\"cafe24ProductNo\":\"77\"}")));

		ResponseEntity<Page<ProductListResponse>> res =
			controller().getProducts(null, null, PageRequest.of(0, 50));

		Map<String, MarketBadgeState> map = res.getBody().getContent().get(0).marketRegistrations();
		assertThat(map).containsKey("CAFE24");
		assertThat(map).doesNotContainKey("GMARKET");
	}

	@Test
	@DisplayName("등록행의 isSynced가 false면 status=PENDING으로 내려간다 — 등록중/미완료를 미등록과 구분해야 한다")
	void getProducts_pendingStatusWhenNotSynced() {
		when(product1.getId()).thenReturn(1L);
		Page<Product> page = new PageImpl<>(List.of(product1), PageRequest.of(0, 50), 1);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(page);
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L)))
			.thenReturn(List.of(reg(1L, MarketType.COUPANG, "{}")));

		ResponseEntity<Page<ProductListResponse>> res =
			controller().getProducts(null, null, PageRequest.of(0, 50));

		MarketBadgeState state = res.getBody().getContent().get(0).marketRegistrations().get("COUPANG");
		assertThat(state.status()).isEqualTo("PENDING");
		assertThat(state.url()).isNull();
	}
```

필요한 import를 파일 상단에 추가한다:

```java
import com.sbshop.agent.api.dto.product.MarketBadgeState;
import java.util.Map;
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `cd backend && ./gradlew :api:test --tests 'com.sbshop.agent.api.controller.ProductControllerMarketMapTest'`
Expected: 컴파일 실패 — `MarketBadgeState` 심볼 없음.

- [ ] **Step 3: 최소 구현**

`MarketBadgeState.java` 생성:

```java
package com.sbshop.agent.api.dto.product;

/**
 * 상품 그리드 마켓 배지 1칸의 서버 상태.
 *
 * <p>맵에 <b>키가 없으면 미등록</b>이다(클릭하면 등록). 키가 있으면 등록된 것이고,
 * {@code status}로 등록 완료(SYNCED)와 미완료(PENDING)를 가른다.
 *
 * <p>실패(FAILED)는 여기 담지 않는다 — {@code sb_market_registration}에 실패를 저장하는 컬럼이 없고,
 * 등록 실패는 등록행을 남기지 않거나 PENDING으로 남긴다. 클릭 실패는 화면 세션 상태로만 표시한다.
 *
 * @param status "SYNCED"(등록 완료) 또는 "PENDING"(등록행은 있으나 동기화 미완료)
 * @param url    마켓 상품페이지 URL. 링크 식별자를 아직 확보하지 못했으면 null.
 */
public record MarketBadgeState(String status, String url) {

	public static final String SYNCED = "SYNCED";
	public static final String PENDING = "PENDING";

	public static MarketBadgeState of(boolean synced, String url) {
		return new MarketBadgeState(synced ? SYNCED : PENDING, (url == null || url.isBlank()) ? null : url);
	}
}
```

`ProductListResponse.java`에서 필드 타입과 두 팩토리의 파라미터 타입을 바꾼다:

```java
	Map<String, MarketBadgeState> marketRegistrations) {
```
```java
	public static ProductListResponse from(Product p, Map<String, MarketBadgeState> marketRegistrations) {
```

`ProductController.buildMarketMap`을 교체한다:

```java
	/**
	 * 마켓 배지 상태 맵을 조립한다. 키는 프론트 소비 키(MarketType.name()),
	 * 값은 {@link MarketBadgeState}. 키가 없으면 그 마켓은 미등록이다.
	 *
	 * <p>CAFE24는 자신도 키로 내보낸다 — 프론트가 G마켓/옥션 배지의 선행조건(카페24 등록 여부)을
	 * 판정해야 하기 때문이다. G마켓/옥션은 여전히 Cafe24 등록행에 백필된 식별자에서 파생한다.
	 */
	private Map<String, MarketBadgeState> buildMarketMap(List<MarketRegistration> registrations) {
		if (registrations.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, MarketBadgeState> marketMap = new HashMap<>();
		for (MarketRegistration reg : registrations) {
			boolean synced = Boolean.TRUE.equals(reg.getIsSynced());
			switch (reg.getMarketType()) {
				case COUPANG:
				case SMART_STORE:
				case ELEVEN_STREET: {
					marketMap.put(reg.getMarketType().name(),
						MarketBadgeState.of(synced, reg.buildMarketUrl()));
					break;
				}
				case CAFE24: {
					marketMap.put("CAFE24", MarketBadgeState.of(synced, null));
					// ESM(지마켓/옥션)은 Cafe24 경유 연동 → Cafe24 등록행에 백필된 코드에서 링크 파생.
					String gUrl = reg.buildGmarketUrl();
					if (gUrl != null) {
						marketMap.put("GMARKET", MarketBadgeState.of(true, gUrl));
					}
					String aUrl = reg.buildAuctionUrl();
					if (aUrl != null) {
						marketMap.put("AUCTION", MarketBadgeState.of(true, aUrl));
					}
					break;
				}
				default:
					break;
			}
		}
		return marketMap;
	}
```

`ProductController` 상단에 import를 추가한다:

```java
import com.sbshop.agent.api.dto.product.MarketBadgeState;
```

기존 테스트들이 `Map<String,String>`을 단언하고 있으므로, 같은 파일에서 단언을 새 타입에 맞춰 고친다. 예: `assertThat(map.get("COUPANG")).isEqualTo("https://...")` → `assertThat(map.get("COUPANG").url()).isEqualTo("https://...")`, `assertThat(map.get("GMARKET")).isEmpty()` 형태는 `assertThat(map.get("GMARKET").url()).isNull()`로 바꾼다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :api:test --tests 'com.sbshop.agent.api.controller.ProductControllerMarketMapTest'`
Expected: PASS (신규 2건 포함 전부)

- [ ] **Step 5: api 모듈 전체 테스트로 회귀 확인**

Run: `cd backend && ./gradlew :api:test`
Expected: PASS. 실패하면 `ProductListResponse.from(...)`의 다른 호출부가 옛 타입을 넘기고 있는 것이니 그 호출부도 고친다.

- [ ] **Step 6: 커밋**

```bash
git add backend/api/src/main/java/com/sbshop/agent/api/dto/product/MarketBadgeState.java \
        backend/api/src/main/java/com/sbshop/agent/api/dto/product/ProductListResponse.java \
        backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java \
        backend/api/src/test/java/com/sbshop/agent/api/controller/ProductControllerMarketMapTest.java
git commit -m "feat(product): 마켓 배지 상태를 status+url 객체로 승격하고 CAFE24 키를 노출한다"
```

---

## Task 2: 등록 API가 등록 결과를 반환하게 한다

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/product/dto/MarketPublishOutcome.java`
- Create: `backend/api/src/main/java/com/sbshop/agent/api/dto/product/MarketPublishResponse.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductPublishUseCase.java:45-78`
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductSourcingController.java:136-154`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductPublishOrphanPreventionTest.java`

**Interfaces:**
- Consumes: Task 1의 `MarketBadgeState`(응답 조립에 사용)
- Produces:
  - `record MarketPublishOutcome(MarketType marketType, Map<String,String> identifiers, boolean synced)`
  - `ProductPublishUseCase.publishToMarket(Long, MarketType)`의 반환형이 `void` → `MarketPublishOutcome`
  - `record MarketPublishResponse(String market, String status, String url, Map<String,String> identifiers)` + `static MarketPublishResponse from(MarketPublishOutcome, String url)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ProductPublishOrphanPreventionTest`에 추가한다:

```java
	@Test
	@DisplayName("게시 성공 시 마켓 identifiers를 담은 결과를 반환한다 — 프론트가 재조회 없이 배지를 링크로 바꿔야 한다")
	void publishToMarket_returnsOutcomeWithIdentifiers() {
		MarketPublishOutcome outcome = useCase.publishToMarket(PRODUCT_ID, MARKET);

		assertThat(outcome.marketType()).isEqualTo(MARKET);
		assertThat(outcome.synced()).isTrue();
		assertThat(outcome.identifiers()).isNotEmpty();
	}
```

import 추가:

```java
import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
```

기존 테스트의 `useCase.publishToMarket(PRODUCT_ID, MARKET);` 호출부는 반환값을 무시해도 컴파일되므로 수정 불필요하다. 이 테스트가 쓰는 `useCase`·`PRODUCT_ID`·`MARKET`·스텁 설정은 그 파일에 이미 있다.

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.ProductPublishOrphanPreventionTest'`
Expected: 컴파일 실패 — `MarketPublishOutcome` 없음 / `publishToMarket`이 void.

- [ ] **Step 3: 최소 구현**

`MarketPublishOutcome.java` 생성:

```java
package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;

/**
 * 마켓 게시 1건의 결과.
 *
 * @param marketType  게시한 마켓
 * @param identifiers 마켓이 돌려준 식별자(쿠팡 sellerProductId, 스토어 originProductNo 등)
 * @param synced      등록행이 SYNCED로 갱신됐는지. 마켓플러스 전송처럼 "접수만 된" 경우 false.
 */
public record MarketPublishOutcome(MarketType marketType, Map<String, String> identifiers, boolean synced) {
}
```

`ProductPublishUseCase.publishToMarket`의 시그니처와 마지막 두 줄을 바꾼다:

```java
	public MarketPublishOutcome publishToMarket(Long productId, MarketType marketType) {
```
```java
		log.info("상품 마켓 등록 완료: productId={}, market={}, identifiers={}", productId, marketType, identifiers);
		return new MarketPublishOutcome(marketType, identifiers, true);
	}
```

`MarketPublishResponse.java` 생성:

```java
package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import java.util.Map;

/**
 * 배지 클릭 등록의 응답. 프론트는 이 값만으로 목록 재조회 없이 배지를 갱신한다.
 *
 * @param market      MarketType.name()
 * @param status      "SYNCED" 또는 "PENDING"({@link MarketBadgeState}와 같은 어휘)
 * @param url         마켓 상품페이지 URL(아직 링크 식별자가 없으면 null)
 * @param identifiers 마켓이 돌려준 식별자 원본
 */
public record MarketPublishResponse(String market, String status, String url, Map<String, String> identifiers) {

	public static MarketPublishResponse from(MarketPublishOutcome outcome, String url) {
		return new MarketPublishResponse(
			outcome.marketType().name(),
			outcome.synced() ? MarketBadgeState.SYNCED : MarketBadgeState.PENDING,
			(url == null || url.isBlank()) ? null : url,
			outcome.identifiers());
	}
}
```

`ProductSourcingController.publishToMarket`을 바꾼다. URL은 등록행에서 파생해야 하므로 `MarketRegistrationRepository`를 주입받는다(생성자는 `@RequiredArgsConstructor`이므로 필드만 추가하면 된다):

```java
	private final MarketRegistrationRepository marketRegistrationRepository;
```
```java
	@PostMapping("/products/{id}/markets/{marketType}")
	public ResponseEntity<MarketPublishResponse> publishToMarket(
		@PathVariable
		Long id,
		@PathVariable
		String marketType) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		// D-076: 마켓 등록(게시) — 결과만 기록(marketType은 경로변수에서).
		try {
			MarketPublishOutcome outcome = productPublishUseCase.publishToMarket(id, type);
			// 등록 직후 링크 식별자가 확보됐으면 URL까지 내려 배지를 바로 링크로 바꾼다.
			String url = marketRegistrationRepository.findByProductIdAndMarketType(id, type)
				.map(MarketRegistration::buildMarketUrl)
				.orElse(null);
			actionLogService.record(ActionLogConstants.PRODUCT_PUBLISH, type.name(),
				ActionStatus.SUCCESS, "마켓 게시 성공 (상품 " + id + ")");
			return ResponseEntity.ok(MarketPublishResponse.from(outcome, url));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_PUBLISH, type.name(),
				ActionStatus.FAILED, "마켓 게시 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}
```

import를 추가한다:

```java
import com.sbshop.agent.api.dto.product.MarketPublishResponse;
import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
```

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.ProductPublishOrphanPreventionTest'`
Expected: PASS

- [ ] **Step 5: 전 모듈 컴파일 확인**

Run: `cd backend && ./gradlew :core:test :infrastructure:test :api:test`
Expected: PASS. `DraftPublishUseCase` 등 `publishToMarket` 호출부가 있으면 반환값 무시로 그대로 컴파일된다.

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/product/dto/MarketPublishOutcome.java \
        backend/api/src/main/java/com/sbshop/agent/api/dto/product/MarketPublishResponse.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductPublishUseCase.java \
        backend/api/src/main/java/com/sbshop/agent/api/controller/ProductSourcingController.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductPublishOrphanPreventionTest.java
git commit -m "feat(product): 마켓 게시 API가 등록 식별자와 상태를 반환한다"
```

---

## Task 3: 배지 셀 6슬롯 렌더링(표시 전용)

**Files:**
- Create: `frontend/src/pages/product/MarketBadgeCell.tsx`
- Modify: `frontend/src/pages/product/productGridShared.tsx:37-77`
- Modify: `frontend/src/pages/product/ProductGrid.tsx:12,38-42,139-141`
- Modify: `frontend/src/api/productApi.ts:17-20`

**Interfaces:**
- Consumes: Task 1의 응답 계약 `{ "COUPANG": { "status": "SYNCED", "url": "..." } }`
- Produces:
  - `frontend/src/api/productApi.ts`: `export interface MarketBadgeState { status: 'SYNCED' | 'PENDING'; url: string | null }`, `ProductList.marketRegistrations?: Record<string, MarketBadgeState>`
  - `productGridShared.tsx`: `export const MARKET_BADGES: { key: string; label: string; bg: string; text: string }[]` (기존 상수를 export로 승격, 카페24 추가)
  - `MarketBadgeCell.tsx`: `export function MarketBadgeCell({ product }: { product: ProductList })`

- [ ] **Step 1: 타입을 바꾼다(여기서 타입 게이트가 깨지는 게 이 태스크의 "실패하는 테스트"다)**

`frontend/src/api/productApi.ts`의 `ProductList` 안 주석과 필드를 교체한다:

```ts
// 마켓 배지 1칸의 서버 상태. 키가 없으면 그 마켓은 미등록(클릭하면 등록).
// status: 'SYNCED' 등록 완료 · 'PENDING' 등록행은 있으나 동기화 미완료.
// url: 마켓 상품페이지. 링크 식별자 미확보면 null.
export interface MarketBadgeState {
  status: 'SYNCED' | 'PENDING';
  url: string | null;
}
```
```ts
  // D-047: 마켓별 등록상태 맵. 키는 백엔드 MarketType.name()
  // (COUPANG / SMART_STORE / ELEVEN_STREET / GMARKET / AUCTION / CAFE24).
  marketRegistrations?: Record<string, MarketBadgeState>;
```

- [ ] **Step 2: 타입 게이트가 깨지는지 확인한다**

Run: `cd frontend && npx tsc -p tsconfig.app.json`
Expected: FAIL — `productGridShared.tsx`의 `renderMarketBadges(links?: Record<string, string>)`가 새 타입을 못 받는다.

- [ ] **Step 3: 배지 메타 상수를 확장·export하고 renderMarketBadges를 제거한다**

`productGridShared.tsx`에서 `MARKET_BADGES` 블록과 `renderMarketBadges` 함수 전체(37~77행)를 아래로 교체한다:

```tsx
// ─── 마켓 등록 배지 ───
// 통합 주문 관리 배지와 동일한 파스텔 팔레트(연배경 + 채도 낮춘 글자색)를 채용한다.
// 순서는 화면 표시 순서 그대로다. 카페24가 G마켓·옥션의 선행조건이라 그 앞에 둔다.
export const MARKET_BADGES: { key: string; label: string; bg: string; text: string }[] = [
  { key: 'COUPANG', label: '쿠팡', bg: '#fce4ec', text: '#c2185b' },
  { key: 'SMART_STORE', label: 'N스토어', bg: '#f1f8e9', text: '#689f38' },
  { key: 'CAFE24', label: '카페24', bg: '#ede7f6', text: '#5e35b1' },
  { key: 'GMARKET', label: 'G마켓', bg: '#c8e6c9', text: '#1b5e20' },
  { key: 'AUCTION', label: '옥션', bg: '#fff3e0', text: '#e65100' },
  { key: 'ELEVEN_STREET', label: '11번가', bg: '#e3f2fd', text: '#1565c0' },
];

// ESM 계열(G마켓·옥션)은 Cafe24 등록행을 경유해야 전송할 수 있다.
export const ESM_MARKET_KEYS = ['GMARKET', 'AUCTION'];
```

파일 상단의 `import type { CSSProperties } from 'react';`는 `inputStyle`이 계속 쓰므로 그대로 둔다.

- [ ] **Step 4: `MarketBadgeCell.tsx`를 만든다(이 태스크에서는 클릭 없이 표시만)**

```tsx
import type { CSSProperties } from 'react';
import type { ProductList } from '../../api/productApi';
import { MARKET_BADGES, ESM_MARKET_KEYS } from './productGridShared';

// 배지 1칸이 가질 수 있는 화면 상태.
//  registered  등록 완료 + 상품페이지 링크 확보 → 채색 배지, 클릭 시 새 탭
//  linkless    등록됐으나 링크 식별자 미확보 → 채색 테두리 반투명, 클릭 없음
//  missing     미등록 → 점선 배지, 클릭 시 등록
//  blocked     미등록 + 선행조건 미충족(카페24 미등록 상태의 G마켓·옥션) → 흐린 점선, 클릭 불가
export type BadgeVisual = 'registered' | 'linkless' | 'missing' | 'blocked';

const baseStyle: CSSProperties = {
  fontSize: 11, fontWeight: 600, padding: '2px 6px', borderRadius: 4, lineHeight: 1.5,
  whiteSpace: 'nowrap',
};

export function badgeVisual(product: ProductList, marketKey: string): BadgeVisual {
  const regs = product.marketRegistrations ?? {};
  const state = regs[marketKey];
  if (state) return state.url ? 'registered' : 'linkless';
  // 카페24 등록행이 없으면 G마켓·옥션은 마켓플러스로 보낼 수 없다.
  if (ESM_MARKET_KEYS.includes(marketKey) && !regs['CAFE24']) return 'blocked';
  return 'missing';
}

export function MarketBadgeCell({ product }: { product: ProductList }) {
  const regs = product.marketRegistrations ?? {};
  return (
    // nowrap: 6개 마켓이 한 줄에 모두 보이도록(줄바꿈 방지). 컬럼 폭은 ProductGrid에서 확보.
    <div style={{ display: 'flex', flexWrap: 'nowrap', gap: 3, alignItems: 'center', justifyContent: 'center' }}>
      {MARKET_BADGES.map((m) => {
        const visual = badgeVisual(product, m.key);
        if (visual === 'registered') {
          return (
            <a key={m.key} href={regs[m.key].url as string} target="_blank" rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()} title={`${m.label} 상품 페이지 열기`}
              style={{ ...baseStyle, color: m.text, background: m.bg, textDecoration: 'none', cursor: 'pointer' }}>
              {m.label}
            </a>
          );
        }
        if (visual === 'linkless') {
          return (
            <span key={m.key} title={`${m.label} 등록됨 · 링크 식별자 미확보`}
              style={{ ...baseStyle, color: m.text, background: '#fff', border: `1px solid ${m.text}`, opacity: 0.5 }}>
              {m.label}
            </span>
          );
        }
        if (visual === 'blocked') {
          return (
            <span key={m.key} title={`${m.label} — 카페24 등록 후 가능`}
              style={{ ...baseStyle, color: '#cbd5e1', background: '#fff', border: '1px dashed #e2e8f0',
                cursor: 'not-allowed' }}>
              {m.label}
            </span>
          );
        }
        return (
          <span key={m.key} title={`${m.label} 미등록`}
            style={{ ...baseStyle, color: '#94a3b8', background: '#fff', border: '1px dashed #cbd5e1' }}>
            {m.label}
          </span>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 5: `ProductGrid.tsx`를 새 셀에 연결한다**

12행 import를 바꾼다:

```tsx
import { MARKET_FILTER_OPTIONS } from './productGridShared';
import { MarketBadgeCell } from './MarketBadgeCell';
```

139~141행 컬럼 정의를 바꾼다(6배지가 들어가도록 폭도 늘린다):

```tsx
    columnHelper.display({
      id: 'markets', header: '마켓', size: 340,
      cell: ({ row }) => <MarketBadgeCell product={row.original} />,
    }),
```

38~42행 필터 로직의 주석을 정확하게 고친다(`regs[m] !== undefined` 판정은 새 타입에서도 그대로 유효하다):

```tsx
    // 마켓 등록상태: 선택 마켓 중 하나라도 등록돼 있으면 통과. 전체선택이면 통과.
    // 값이 객체로 바뀌었지만 "키 존재 = 등록"이라는 판정은 그대로다.
```

- [ ] **Step 6: 타입 게이트·린트 통과 확인**

Run: `cd frontend && npx tsc -p tsconfig.app.json && npx eslint src/pages/product src/api`
Expected: 오류 없음

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/pages/product/MarketBadgeCell.tsx \
        frontend/src/pages/product/productGridShared.tsx \
        frontend/src/pages/product/ProductGrid.tsx \
        frontend/src/api/productApi.ts
git commit -m "feat(product): 마켓 배지를 6슬롯 고정 렌더로 바꾸고 미등록을 점선으로 드러낸다"
```

---

## Task 4: 배지 클릭 등록

**Files:**
- Modify: `frontend/src/pages/product/MarketBadgeCell.tsx`
- Modify: `frontend/src/pages/product/ProductGrid.tsx:139-141`
- Modify: `frontend/src/api/sourcingApi.ts:35-36`

**Interfaces:**
- Consumes: Task 2의 `POST /api/v1/products/{id}/markets/{marketType}` → `MarketPublishResponse`
- Produces: `MarketBadgeCell`의 props가 `{ product, onPublished }`로 확장된다. `onPublished: () => void` — 등록 성공 후 목록 재조회 콜백(ProductGrid의 react-query `refetch`).

- [ ] **Step 1: API 응답 타입을 명시한다**

`frontend/src/api/sourcingApi.ts`:

```ts
export interface MarketPublishResponse {
  market: string;
  status: 'SYNCED' | 'PENDING';
  url: string | null;
  identifiers: Record<string, string>;
}
```
```ts
  publishToMarket: (productId: number, marketType: string) =>
    apiClient.post<MarketPublishResponse>(`/api/v1/products/${productId}/markets/${marketType}`),
```

- [ ] **Step 2: `MarketBadgeCell`에 클릭·진행·실패 상태를 넣는다**

파일 상단 import를 추가한다:

```tsx
import { useState } from 'react';
import { Modal as AntModal } from 'antd';
import { toast } from 'react-toastify';
import { sourcingApi } from '../../api/sourcingApi';
```

컴포넌트 시그니처와 본문을 아래로 교체한다(`badgeVisual`·`baseStyle`·타입 선언은 Task 3 그대로 둔다):

```tsx
export function MarketBadgeCell({ product, onPublished }:
  { product: ProductList; onPublished: () => void }) {
  const regs = product.marketRegistrations ?? {};
  // 등록 진행/실패는 서버에 저장되지 않는 화면 세션 상태다(새로고침하면 서버 상태로 복원).
  const [publishing, setPublishing] = useState<string | null>(null);
  const [failed, setFailed] = useState<Record<string, string>>({});

  const publish = (marketKey: string, label: string) => {
    AntModal.confirm({
      title: `${label} 등록`,
      content: `'${label}'에 해당 상품을 등록하시겠습니까?`,
      okText: '등록', cancelText: '취소',
      onOk: async () => {
        setPublishing(marketKey);
        setFailed((f) => { const next = { ...f }; delete next[marketKey]; return next; });
        try {
          await sourcingApi.publishToMarket(product.id, marketKey);
          toast.success(`${label} 등록 완료 — ${product.sbCode}`);
          onPublished();
        } catch (e) {
          // 실패를 조용히 삼키지 않는다. 사유를 배지 툴팁과 토스트 양쪽에 남긴다.
          const msg = extractError(e);
          setFailed((f) => ({ ...f, [marketKey]: msg }));
          toast.error(`${label} 등록 실패 — ${msg}`);
        } finally {
          setPublishing(null);
        }
      },
    });
  };
  ...
```

`missing` 분기를 클릭 가능하게 바꾸고, 진행/실패 분기를 앞에 둔다. `MARKET_BADGES.map` 콜백 안 맨 위에 추가한다:

```tsx
        if (publishing === m.key) {
          return (
            <span key={m.key} title={`${m.label} 등록 진행 중`}
              style={{ ...baseStyle, color: '#475569', background: '#f1f5f9',
                border: '1px solid #cbd5e1', animation: 'pulse 1.2s ease-in-out infinite' }}>
              등록중…
            </span>
          );
        }
        if (failed[m.key]) {
          return (
            <span key={m.key} title={`${m.label} 등록 실패 — ${failed[m.key]} (다시 클릭하면 재시도)`}
              onClick={(e) => { e.stopPropagation(); publish(m.key, m.label); }}
              style={{ ...baseStyle, color: '#dc2626', background: '#fff',
                border: '1px dashed #dc2626', cursor: 'pointer' }}>
              {m.label}
            </span>
          );
        }
```

`missing` 분기(마지막 return)를 클릭 가능하게 바꾼다:

```tsx
        return (
          <span key={m.key} title={`${m.label} 미등록 — 클릭하면 등록합니다`}
            onClick={(e) => { e.stopPropagation(); publish(m.key, m.label); }}
            style={{ ...baseStyle, color: '#94a3b8', background: '#fff',
              border: '1px dashed #cbd5e1', cursor: 'pointer' }}>
            {m.label}
          </span>
        );
```

파일 하단에 오류 메시지 추출 헬퍼를 둔다:

```tsx
// axios 오류에서 사용자에게 보여줄 사유를 뽑는다. 백엔드는 { message } 또는 { error }로 내려준다.
function extractError(e: unknown): string {
  const res = (e as { response?: { data?: Record<string, unknown>; status?: number } }).response;
  const data = res?.data;
  const msg = (data?.message ?? data?.error) as string | undefined;
  if (msg) return msg;
  if (res?.status === 409) return '카페24 등록이 먼저 필요합니다';
  return '알 수 없는 오류';
}
```

`등록중…` 배지의 펄스 애니메이션을 위해 `ProductGrid.tsx`의 기존 `<style>{...}</style>` 블록 안에 아래를 추가한다:

```css
        @keyframes pulse { 0%,100% { opacity: 1 } 50% { opacity: .45 } }
```

- [ ] **Step 3: `ProductGrid`에서 콜백을 넘긴다**

```tsx
    columnHelper.display({
      id: 'markets', header: '마켓', size: 340,
      cell: ({ row }) => <MarketBadgeCell product={row.original} onPublished={refetch} />,
    }),
```

`columns`의 `useMemo` 의존성 배열이 `[]`이므로 `[refetch]`로 바꾼다.

- [ ] **Step 4: 타입 게이트·린트 통과 확인**

Run: `cd frontend && npx tsc -p tsconfig.app.json && npx eslint src/pages/product src/api`
Expected: 오류 없음

- [ ] **Step 5: 실제 화면에서 확인**

Run: `cd frontend && npm run dev` 후 상품 관리 화면에서
1. 미등록 배지가 점선으로 보이는지
2. 클릭 시 "'쿠팡'에 해당 상품을 등록하시겠습니까?" 다이얼로그가 뜨는지
3. 취소 시 아무 요청도 나가지 않는지(개발자도구 Network)
4. 카페24 미등록 행의 G마켓·옥션 배지가 흐리고 클릭되지 않는지

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/pages/product/MarketBadgeCell.tsx \
        frontend/src/pages/product/ProductGrid.tsx \
        frontend/src/api/sourcingApi.ts
git commit -m "feat(product): 미등록 마켓 배지를 클릭해 상품을 등록한다"
```

---

## Task 5: 마켓별 판매가 산정을 단일 출처로 추출

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/product/MarketSalePriceResolver.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductMarketSyncService.java:36-79`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/MarketSalePriceResolverTest.java` (신규)

**Interfaces:**
- Consumes: `MarketFeeService.feeRate(MarketType)`, `MarginCalculator.calculateSalePrice(BigDecimal buyPrice, int bundleQty, BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate)`, `PricingInputs`
- Produces:
  - `MarketSalePriceResolver.resolve(PricingInputs, MarketType) -> Integer`
  - `MarketSalePriceResolver.resolveForProduct(Product, MarketType) -> BigDecimal` (Task 6이 쓴다. 재료가 없으면 `product.getSalePrice()` 폴백)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`MarketSalePriceResolverTest.java`:

```java
package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 마켓별 판매가 산정의 단일 출처 검증.
 * 동기화 경로(ProductMarketSyncService)와 신규 등록 경로(ProductPublishUseCase)가
 * 같은 계산을 쓰게 하려고 추출한 컴포넌트다.
 */
@ExtendWith(MockitoExtension.class)
class MarketSalePriceResolverTest {

	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private Product product;

	private final MarginCalculator marginCalculator = new MarginCalculator();

	private MarketSalePriceResolver resolver() {
		return new MarketSalePriceResolver(marginCalculator, marketFeeService);
	}

	@Test
	@DisplayName("수수료가 높은 마켓일수록 판매가가 높게 산정된다")
	void resolve_higherFeeYieldsHigherPrice() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(marketFeeService.feeRate(MarketType.ELEVEN_STREET)).thenReturn(new BigDecimal("18"));
		PricingInputs inputs = new PricingInputs(new BigDecimal("10000"), 1,
			new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000"));

		Integer coupang = resolver().resolve(inputs, MarketType.COUPANG);
		Integer elevenst = resolver().resolve(inputs, MarketType.ELEVEN_STREET);

		assertThat(elevenst).isGreaterThan(coupang);
	}

	@Test
	@DisplayName("상품에 원가·마진이 없으면 기준가로 폴백한다 — 계산 재료가 없다고 등록을 막지 않는다")
	void resolveForProduct_fallsBackToStoredSalePrice() {
		when(product.getPriceInfo()).thenReturn(null);
		when(product.getSalePrice()).thenReturn(new BigDecimal("90600"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.GMARKET);

		assertThat(price).isEqualByComparingTo("90600");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.MarketSalePriceResolverTest'`
Expected: 컴파일 실패 — `MarketSalePriceResolver` 없음.

- [ ] **Step 3: 최소 구현**

```java
package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * D-094: 마켓별 실수수료(sb_fee_policy)로 그 마켓의 판매가를 산정한다.
 *
 * <p>이 계산의 <b>단일 출처</b>다. 동기화 경로({@link ProductMarketSyncService})와
 * 신규 등록 경로({@link ProductPublishUseCase})가 서로 다른 가격을 만들면,
 * 등록 직후와 배치 이후의 가격이 달라져 원인을 알 수 없는 가격 변동으로 보인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSalePriceResolver {

	private final MarginCalculator marginCalculator;
	private final MarketFeeService marketFeeService;

	/** 계산 재료가 모두 있을 때(배치 경로): 마켓 실수수료로 산정한 판매가(원, 정수). */
	public Integer resolve(PricingInputs p, MarketType marketType) {
		BigDecimal fee = marketFeeService.feeRate(marketType);
		return marginCalculator.calculateSalePrice(p.buyPrice(), p.bundleQty(), p.marginRate(),
			p.couponRate(), p.minMarginPrice(), fee).intValue();
	}

	/**
	 * 저장된 상품 값만으로 산정한다(신규 등록 경로).
	 *
	 * <p>쿠폰율·최소마진은 배치 실행 파라미터라 상품에 저장되지 않는다. 그래서 둘은 null로 두고
	 * 원가·마진율·묶음수량만으로 계산한다 — 쿠폰 미반영분만큼 <b>보수적으로(약간 높게)</b> 산정되며,
	 * 다음 재가격 배치가 정확한 값으로 낮춘다. 마진이 깎이는 방향이 아니므로 이 편향은 안전하다.
	 *
	 * <p>원가·마진율이 없으면 기준가(sale_price)를 그대로 쓴다. 재료가 없다는 이유로
	 * 등록 자체를 막지는 않는다.
	 */
	public BigDecimal resolveForProduct(Product product, MarketType marketType) {
		BigDecimal costPrice = product.getPriceInfo() != null ? product.getPriceInfo().getCostPrice() : null;
		BigDecimal marginRate = product.getPriceInfo() != null ? product.getPriceInfo().getMarginRate() : null;
		if (costPrice == null || costPrice.signum() <= 0 || marginRate == null) {
			log.info("[등록가] 원가·마진 미보유 → 기준가로 등록: sbCode={}, market={}",
				product.getSbCode(), marketType);
			return product.getSalePrice();
		}
		int bundleQty = product.getLogisticsInfo() != null
			&& product.getLogisticsInfo().getBundleQuantity() != null
				? product.getLogisticsInfo().getBundleQuantity() : 1;
		BigDecimal fee = marketFeeService.feeRate(marketType);
		return marginCalculator.calculateSalePrice(costPrice, bundleQty, marginRate, null, null, fee);
	}
}
```

`getPriceInfo().getMarginRate()`·`getLogisticsInfo().getBundleQuantity()`의 실제 getter 이름은 `Product`의 임베디드 타입을 열어 확인하고 다르면 맞춘다(`grep -n "marginRate\|bundleQuantity" backend/core/src/main/java/com/sbshop/agent/core/domain/product/`).

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.MarketSalePriceResolverTest'`
Expected: PASS

- [ ] **Step 5: 동기화 경로를 resolver 위임으로 바꾼다(동작 불변 리팩토링)**

`ProductMarketSyncService`에서 `marginCalculator`·`marketFeeService` 필드를 지우고 resolver를 주입한다:

```java
	private final MarketSalePriceResolver marketSalePriceResolver;
```

`priceForMarket`을 교체한다:

```java
	/** 마켓 실수수료로 산정한 그 마켓의 판매가(원, 정수). 계산은 MarketSalePriceResolver가 단독 소유한다. */
	private Integer priceForMarket(PricingInputs p, MarketType marketType) {
		return marketSalePriceResolver.resolve(p, marketType);
	}
```

쓰지 않게 된 import(`MarketFeeService`, `MarginCalculator`, 필요 없어진 `BigDecimal`)를 제거한다.

- [ ] **Step 6: 기존 동기화 테스트로 동작 불변을 확인한다**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.ProductMarketSyncPerMarketPriceTest' --tests 'com.sbshop.agent.core.application.product.ProductMarketSyncServiceTest'`
Expected: PASS. 생성자 인자가 바뀌었으므로 테스트의 수동 생성 부분을 resolver 주입으로 고친다(`new MarketSalePriceResolver(new MarginCalculator(), marketFeeService)`를 넘기면 계산 결과가 동일하다).

- [ ] **Step 7: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/product/MarketSalePriceResolver.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductMarketSyncService.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/
git commit -m "refactor(product): 마켓별 판매가 산정을 MarketSalePriceResolver로 단일화"
```

---

## Task 6: 신규 등록에 마켓별 판매가를 반영

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductPublishUseCase.java`
- Modify: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/smartstore/adapter/SmartstoreMarketClient.java:74`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductPublishPerMarketPriceTest.java` (신규)

**Interfaces:**
- Consumes: Task 5의 `MarketSalePriceResolver.resolveForProduct(Product, MarketType)`, `MarketPublishContext`
- Produces: `ProductPublishUseCase`가 `client.publish(product)` 대신 `client.publish(product, context)`를 호출한다. context는 `salePrice`만 채운 부분 컨텍스트다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductSanitizer;
import com.sbshop.agent.core.domain.product.component.ProductValidator;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 신규 등록도 마켓별 실수수료 반영가로 올라가야 한다.
 * 기준가(쿠팡 기준)로 등록하면 다음 재가격 배치까지 그 마켓은 틀린 가격으로 팔린다.
 */
@ExtendWith(MockitoExtension.class)
class ProductPublishPerMarketPriceTest {

	private static final Long PRODUCT_ID = 1L;

	@Mock private ProductReader productReader;
	@Mock private MarketClientRouter marketClientRouter;
	@Mock private MarketRegistrationTxService registrationTxService;
	@Mock private ProductSanitizer productSanitizer;
	@Mock private ProductValidator productValidator;
	@Mock private MarketSalePriceResolver marketSalePriceResolver;
	@Mock private MarketClient client;
	@Mock private Product product;
	@Mock private MarketRegistration registration;

	@Test
	@DisplayName("게시 시 마켓별 산정가를 MarketPublishContext.salePrice로 전달한다")
	void publish_passesPerMarketPrice() {
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(marketClientRouter.hasClient(MarketType.ELEVEN_STREET)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.ELEVEN_STREET)).thenReturn(client);
		when(registrationTxService.savePending(any(), any(), any())).thenReturn(registration);
		when(marketSalePriceResolver.resolveForProduct(product, MarketType.ELEVEN_STREET))
			.thenReturn(new BigDecimal("103000"));
		when(client.publish(any(), any())).thenReturn(Map.of("elevenstId", "999"));

		new ProductPublishUseCase(productReader, marketClientRouter, registrationTxService,
			new ObjectMapper(), productSanitizer, productValidator, marketSalePriceResolver)
			.publishToMarket(PRODUCT_ID, MarketType.ELEVEN_STREET);

		ArgumentCaptor<MarketPublishContext> captor = ArgumentCaptor.forClass(MarketPublishContext.class);
		verify(client).publish(any(), captor.capture());
		assertThat(captor.getValue().salePrice()).isEqualByComparingTo("103000");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.ProductPublishPerMarketPriceTest'`
Expected: 컴파일 실패 — `ProductPublishUseCase` 생성자 인자 수 불일치.

- [ ] **Step 3: `ProductPublishUseCase`에 resolver를 주입하고 컨텍스트로 게시한다**

필드를 추가한다:

```java
	private final MarketSalePriceResolver marketSalePriceResolver;
```

`client.publish(product)` 호출 부분을 바꾼다:

```java
		// 2) 되돌릴 수 없는 외부 게시 — 트랜잭션 밖에서 호출.
		//    D-094: 등록 순간부터 그 마켓의 실수수료 반영가로 올린다. 기준가(쿠팡 기준)로 올리면
		//    다음 재가격 배치까지 수수료가 다른 마켓은 목표 마진을 벗어난 가격으로 팔린다.
		BigDecimal salePrice = marketSalePriceResolver.resolveForProduct(product, marketType);
		MarketPublishContext context = new MarketPublishContext(
			null, null, salePrice, List.of(), Map.of(), Map.of());
		Map<String, String> identifiers = client.publish(product, context);
```

import를 추가한다:

```java
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import java.math.BigDecimal;
import java.util.List;
```

- [ ] **Step 4: 스마트스토어가 부분 컨텍스트에 깨지지 않게 병합한다**

`SmartstoreMarketClient.publish(product)`는 `autoContext(product)`로 카테고리·주소록·A/S를 채운다. 이제 `publish(product, context)`가 호출되므로, **카테고리 없는 부분 컨텍스트가 그대로 payload로 가면 커머스API 필수필드가 비어 등록이 거절된다.** `publish(Product, MarketPublishContext)` 본문 첫 줄에 병합을 넣는다:

```java
	@Override
	public Map<String, String> publish(Product product, MarketPublishContext context) {
		// 부분 컨텍스트(예: 판매가만 담긴 등록 경로)로 들어오면 빈 칸을 autoContext로 채운다.
		// 채우지 않으면 카테고리·주소록·A/S 같은 커머스API 필수필드가 비어 등록이 거절된다.
		context = mergeWithAuto(product, context);
		log.info("[Smartstore] 상품 등록 시작: {}", product.getSbCode());
```

같은 클래스에 병합 메서드를 추가한다:

```java
	/** 검수 컨텍스트가 이긴다. 비어 있는 칸만 autoContext 값으로 채운다. */
	private MarketPublishContext mergeWithAuto(Product product, MarketPublishContext context) {
		if (context.hasCategory() && !context.extraFields().isEmpty()) {
			return context;
		}
		MarketPublishContext auto = autoContext(product);
		Map<String, Object> extra = new HashMap<>(auto.extraFields());
		extra.putAll(context.extraFields());
		return new MarketPublishContext(
			context.hasCategory() ? context.categoryId() : auto.categoryId(),
			context.categoryPath() != null ? context.categoryPath() : auto.categoryPath(),
			context.salePrice() != null ? context.salePrice() : auto.salePrice(),
			context.keywords().isEmpty() ? auto.keywords() : context.keywords(),
			context.noticeFields().isEmpty() ? auto.noticeFields() : context.noticeFields(),
			extra);
	}
```

- [ ] **Step 5: 통과 확인 + 전 모듈 회귀**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.ProductPublishPerMarketPriceTest'`
Expected: PASS

Run: `cd backend && ./gradlew :core:test :infrastructure:test :api:test`
Expected: PASS. `ProductPublishOrphanPreventionTest`의 생성자 호출도 인자 하나가 늘었으니 함께 고친다(resolver mock을 넘기고 `resolveForProduct`는 stub 없이 null 반환이면 컨텍스트 salePrice가 null이 되어 어댑터 폴백이 동작한다 — 그 테스트의 관심사가 아니므로 무방하다).

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductPublishUseCase.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/smartstore/adapter/SmartstoreMarketClient.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/
git commit -m "fix(product): 신규 마켓 등록도 마켓별 실수수료 반영가로 올린다"
```

---

## Task 7: 마켓플러스 자격증명 배선 + 사이드카 로그인 스파이크

**Files:**
- Create: `scraper/marketplus.py`
- Modify: `scraper/app.py`
- Modify: `docker-compose.yml:67-75`
- Modify: `.env.example`
- Modify: `sync-env.sh`
- Create: `docs/normalize/working_history/20260813_marketplus_스파이크.md`

**Interfaces:**
- Produces:
  - `scraper/marketplus.py`: `def send_to_market(sb_code: str, target_market: str) -> dict` — 반환 `{"ok": bool, "reason": str|None, "marketProductNo": str|None}`
  - `scraper/app.py`: `POST /cafe24/mp/send`, 요청 `{"sbCode": str, "targetMarket": "GMARKET"|"AUCTION"}`
  - 환경변수 `CAFE24_MP_USERNAME`, `CAFE24_MP_PASSWORD`

- [ ] **Step 1: 자격증명 배선(코드보다 먼저 — 없으면 스파이크도 못 돈다)**

`docker-compose.yml`의 `sbshop-scraper` 서비스에 `environment` 블록을 추가한다:

```yaml
  sbshop-scraper:
    build:
      context: .
      dockerfile: Dockerfile.scraper
    container_name: projects-sbshop-scraper-1
    shm_size: "2g"
    environment:
      # Cafe24 마켓플러스 로그인 계정. 비어 있으면 /cafe24/mp/send가 503으로 거절한다
      # (조용히 성공한 척하지 않는다 — G마켓·옥션 배지가 거짓으로 켜지면 안 된다).
      CAFE24_MP_USERNAME: ${CAFE24_MP_USERNAME:-}
      CAFE24_MP_PASSWORD: ${CAFE24_MP_PASSWORD:-}
    networks:
      - shared-net
    restart: unless-stopped
```

`.env.example`에 문서화한다:

```
# Cafe24 마켓플러스(mp.cafe24.com) 로그인 — G마켓·옥션 전송 자동화용
CAFE24_MP_USERNAME=
CAFE24_MP_PASSWORD=
```

`sync-env.sh`의 `SYNC_KEYS` 배열에 두 줄을 추가한다:

```bash
  CAFE24_MP_USERNAME
  CAFE24_MP_PASSWORD
```

- [ ] **Step 2: 로그인·목록 진입까지만 구현한다(스파이크)**

`scraper/marketplus.py`:

```python
"""Cafe24 마켓플러스(mp.cafe24.com) 자동화.

G마켓·옥션에는 상품등록 공개 API가 없다. Cafe24에 등록된 상품을 마켓플러스의
'미판매 상품' 목록에서 골라 '일괄 보내기'로 내보내는 것이 유일한 경로다.
"""
import os
from scrapling.fetchers import DynamicFetcher

LOGIN_URL = "https://mp.cafe24.com/"
NO_SALE_URL = "https://mp.cafe24.com/mp/product/front/noSaleAll"


class CredentialsMissing(Exception):
    """자격증명 미설정 — 실패를 성공으로 위장하지 않기 위한 명시적 예외."""


def _credentials() -> tuple[str, str]:
    user = os.getenv("CAFE24_MP_USERNAME", "")
    password = os.getenv("CAFE24_MP_PASSWORD", "")
    if not user or not password:
        raise CredentialsMissing("CAFE24_MP_USERNAME/PASSWORD 미설정")
    return user, password


def probe() -> dict:
    """스파이크용: 로그인 후 미판매 목록에 진입해 페이지 제목과 행 수를 보고한다."""
    user, password = _credentials()
    page = DynamicFetcher.fetch(
        LOGIN_URL,
        headless=True,
        network_idle=True,
        page_action=lambda p: _login(p, user, password),
    )
    return {"ok": page.status == 200, "title": page.css_first("title::text")}


def _login(page, user: str, password: str):
    # 셀렉터는 Step 3 실측으로 확정한다.
    page.goto(LOGIN_URL, wait_until="networkidle")
    page.fill("input[name='mall_id']", user)
    page.fill("input[name='userpasswd']", password)
    page.click("button[type='submit']")
    page.wait_for_load_state("networkidle")
    page.goto(NO_SALE_URL, wait_until="networkidle")
    return page
```

`scraper/app.py`에 라우트를 추가한다:

```python
@app.post("/cafe24/mp/probe")
def cafe24_mp_probe() -> dict:
    """스파이크 전용 — 로그인과 목록 진입이 되는지만 확인한다."""
    try:
        return marketplus.probe()
    except marketplus.CredentialsMissing as e:
        raise HTTPException(status_code=503, detail="credentials_missing") from e
```

상단에 `import marketplus`와 `from fastapi import HTTPException`(이미 있으면 생략)을 추가한다.

- [ ] **Step 3: 실측한다 — 이 태스크의 핵심 산출물**

로컬에서 `.env`를 읽어 스크래퍼를 띄우고 프로브를 친다:

```bash
cd scraper && source .venv/bin/activate && \
  CAFE24_MP_USERNAME=$(grep '^CAFE24_MP_USERNAME=' ../.env | cut -d= -f2-) \
  CAFE24_MP_PASSWORD=$(grep '^CAFE24_MP_PASSWORD=' ../.env | cut -d= -f2-) \
  uvicorn app:app --port 8099 &
curl -s -X POST localhost:8099/cafe24/mp/probe
```

로그인 셀렉터가 틀리면 `headless=False`로 바꿔 실제 필드명을 확인하고 `_login`을 고친다. 확인해서 문서에 적을 것:

1. 로그인 폼의 실제 input name / 2단계 인증 유무
2. `noSaleAll` 목록의 검색 입력 셀렉터와, **sbCode(Cafe24 `custom_product_code`)로 검색이 되는지**
3. 행 체크박스 셀렉터
4. "일괄 보내기" 버튼과 다이얼로그의 **G마켓/옥션 개별 선택 가능 여부**
5. 전송 후 마켓 상품번호가 화면에 즉시 노출되는지
6. 전송에 필요한 사전 설정(카테고리 매칭·배송/반품 템플릿)이 상품마다 필요한지

`docs/normalize/working_history/20260813_marketplus_스파이크.md`에 위 6개 답과 확정 셀렉터를 기록한다. **5번이 "노출된다"면 Task 9의 상품번호 저장을 즉시 반영으로 바꾼다. 6번이 "필요하다"면 그 사실을 사용자에게 보고하고 Task 8 착수 전에 처리 방법을 합의한다.**

- [ ] **Step 4: 커밋**

```bash
git add docker-compose.yml .env.example sync-env.sh scraper/marketplus.py scraper/app.py \
        docs/normalize/working_history/20260813_marketplus_스파이크.md
git commit -m "feat(scraper): 마켓플러스 자격증명 배선과 로그인 스파이크"
```

---

## Task 8: 사이드카 전송 구현

**Files:**
- Modify: `scraper/marketplus.py`
- Modify: `scraper/app.py`

**Interfaces:**
- Consumes: Task 7 스파이크에서 확정한 셀렉터
- Produces: `POST /cafe24/mp/send` — 요청 `{"sbCode": "231211FM017", "targetMarket": "GMARKET"}`, 응답 `{"ok": true, "marketProductNo": "1234567890"}` 또는 `{"ok": false, "reason": "product_not_found"}`. 실패 사유 어휘: `product_not_found` / `login_failed` / `send_rejected`.

- [ ] **Step 1: `send_to_market`을 구현한다**

`marketplus.py`에 추가한다(셀렉터는 스파이크 결과로 치환):

```python
MARKET_LABEL = {"GMARKET": "G마켓", "AUCTION": "옥션"}


def send_to_market(sb_code: str, target_market: str) -> dict:
    """미판매 목록에서 sb_code 상품을 찾아 지정 마켓으로 일괄 보내기 한다."""
    user, password = _credentials()
    label = MARKET_LABEL.get(target_market)
    if label is None:
        return {"ok": False, "reason": "unsupported_market", "marketProductNo": None}

    result: dict = {"ok": False, "reason": "send_rejected", "marketProductNo": None}

    def action(page):
        _login(page, user, password)
        # 1) 자체상품코드로 검색
        page.fill("input[name='search_keyword']", sb_code)
        page.click("button.btn_search")
        page.wait_for_load_state("networkidle")
        rows = page.query_selector_all("table.list tbody tr")
        if not rows:
            result["reason"] = "product_not_found"
            return page
        # 2) 첫 행 체크 → 일괄 보내기 → 마켓 선택 → 전송
        rows[0].query_selector("input[type='checkbox']").check()
        page.click("button.btn_send_all")
        page.wait_for_selector("div.layer_send")
        page.click(f"label:has-text('{label}')")
        page.click("div.layer_send button.btn_confirm")
        page.wait_for_load_state("networkidle")
        result["ok"] = True
        result["reason"] = None
        return page

    DynamicFetcher.fetch(LOGIN_URL, headless=True, network_idle=True, page_action=action)
    return result
```

실패 시 진단이 가능하도록 예외 경로에서 스크린샷을 남긴다:

```python
    except Exception as e:  # noqa: BLE001 — 어떤 실패든 진단 자료를 남긴다
        try:
            page.screenshot(path=f"/tmp/mp-fail-{sb_code}-{target_market}.png")
        except Exception:
            pass
        result["reason"] = f"exception:{type(e).__name__}"
```

- [ ] **Step 2: 라우트를 붙인다**

`app.py`:

```python
class MpSendRequest(BaseModel):
    sbCode: str
    targetMarket: str


@app.post("/cafe24/mp/send")
def cafe24_mp_send(req: MpSendRequest) -> dict:
    try:
        return marketplus.send_to_market(req.sbCode, req.targetMarket)
    except marketplus.CredentialsMissing as e:
        raise HTTPException(status_code=503, detail="credentials_missing") from e
```

- [ ] **Step 3: 실제 상품 하나로 검증한다**

Cafe24에 등록돼 있고 G마켓에 아직 없는 상품 하나를 골라 실행한다:

```bash
curl -s -X POST localhost:8099/cafe24/mp/send \
  -H 'Content-Type: application/json' \
  -d '{"sbCode":"<실제_SB코드>","targetMarket":"GMARKET"}'
```

Expected: `{"ok": true, ...}` 그리고 **마켓플러스 화면에서 실제로 전송됐는지 눈으로 확인한다.** 전송되지 않았는데 `ok: true`가 나오면 그것이 가장 나쁜 실패다 — 성공 판정 조건을 "버튼을 눌렀다"가 아니라 "전송 완료 표시가 나타났다"로 바꾼다.

- [ ] **Step 4: 커밋**

```bash
git add scraper/marketplus.py scraper/app.py
git commit -m "feat(scraper): 마켓플러스 일괄 보내기로 G마켓·옥션에 상품을 전송한다"
```

---

## Task 9: G마켓·옥션 등록 경로를 백엔드에 연결

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/product/port/MarketPlusSendPort.java`
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/product/MarketPlusPublisher.java`
- Create: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/marketplus/MarketPlusSendClient.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductPublishUseCase.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/MarketPlusPublisherTest.java` (신규)

**Interfaces:**
- Consumes: Task 8의 `POST /cafe24/mp/send`, Task 2의 `MarketPublishOutcome`, `MarketRegistrationRepository.findByProductIdAndMarketType`, `MarketRegistration.enrichIdentifier(String,String)`
- Produces:
  - `interface MarketPlusSendPort { MarketPlusSendResult send(String sbCode, MarketType target); }`, `record MarketPlusSendResult(boolean ok, String reason, String marketProductNo)`
  - `MarketPlusPublisher.publish(Product, MarketType) -> MarketPublishOutcome`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.product.port.MarketPlusSendPort;
import com.sbshop.agent.core.application.product.port.MarketPlusSendPort.MarketPlusSendResult;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * G마켓·옥션은 Cafe24 마켓플러스를 경유해야만 등록된다.
 * 선행조건(Cafe24 등록행)이 없으면 사이드카를 부르지도 않고 거절해야 한다 —
 * 헛호출은 브라우저 세션만 태우고 사용자에게는 원인 모를 실패로 보인다.
 */
@ExtendWith(MockitoExtension.class)
class MarketPlusPublisherTest {

	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private MarketPlusSendPort marketPlusSendPort;
	@Mock private Product product;
	@Mock private MarketRegistration cafe24Registration;

	private MarketPlusPublisher publisher() {
		return new MarketPlusPublisher(marketRegistrationRepository, marketPlusSendPort);
	}

	@Test
	@DisplayName("Cafe24 등록행이 없으면 전송하지 않고 거절한다")
	void publish_rejectsWithoutCafe24() {
		when(product.getId()).thenReturn(1L);
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MarketType.CAFE24))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> publisher().publish(product, MarketType.GMARKET))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("카페24");

		verify(marketPlusSendPort, never()).send(any(), any());
	}

	@Test
	@DisplayName("전송 성공 시 상품번호가 없으면 synced=false로 남긴다 — ESM 반영 지연을 등록완료로 위장하지 않는다")
	void publish_pendingWhenNoProductNo() {
		when(product.getId()).thenReturn(1L);
		when(product.getSbCode()).thenReturn("231211FM017");
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MarketType.CAFE24))
			.thenReturn(Optional.of(cafe24Registration));
		when(marketPlusSendPort.send("231211FM017", MarketType.GMARKET))
			.thenReturn(new MarketPlusSendResult(true, null, null));

		var outcome = publisher().publish(product, MarketType.GMARKET);

		assertThat(outcome.synced()).isFalse();
		verify(cafe24Registration).enrichIdentifier(eq("gmarket_sentAt"), anyString());
	}

	@Test
	@DisplayName("사이드카가 실패를 보고하면 예외로 표면화한다")
	void publish_surfacesFailure() {
		when(product.getId()).thenReturn(1L);
		when(product.getSbCode()).thenReturn("231211FM017");
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MarketType.CAFE24))
			.thenReturn(Optional.of(cafe24Registration));
		when(marketPlusSendPort.send("231211FM017", MarketType.GMARKET))
			.thenReturn(new MarketPlusSendResult(false, "product_not_found", null));

		assertThatThrownBy(() -> publisher().publish(product, MarketType.GMARKET))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("product_not_found");
	}
}
```

`any()`·`anyString()`·`eq()` static import를 추가한다(`org.mockito.ArgumentMatchers`). **한 verify 안에서 raw 값과 matcher를 섞으면 Mockito가 `InvalidUseOfMatchersException`을 던진다** — 그래서 `eq("gmarket_sentAt")`로 감쌌다.

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.MarketPlusPublisherTest'`
Expected: 컴파일 실패 — 포트·퍼블리셔 없음.

- [ ] **Step 3: 포트와 퍼블리셔를 만든다**

```java
package com.sbshop.agent.core.application.product.port;

import com.sbshop.agent.core.domain.order.enums.MarketType;

/** Cafe24 마켓플러스 전송 사이드카 포트. core는 HTTP를 모른다. */
public interface MarketPlusSendPort {

	/**
	 * @param sbCode 자체상품코드(Cafe24 custom_product_code로 등록돼 있어야 검색된다)
	 * @param target GMARKET 또는 AUCTION
	 */
	MarketPlusSendResult send(String sbCode, MarketType target);

	/**
	 * @param ok              마켓플러스 화면에서 전송 완료가 확인됐는지
	 * @param reason          실패 사유(product_not_found / login_failed / send_rejected 등). 성공이면 null
	 * @param marketProductNo 전송 직후 확보된 마켓 상품번호. 확보 못 했으면 null(ESM 반영 지연)
	 */
	record MarketPlusSendResult(boolean ok, String reason, String marketProductNo) {}
}
```

```java
package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import com.sbshop.agent.core.application.product.port.MarketPlusSendPort;
import com.sbshop.agent.core.application.product.port.MarketPlusSendPort.MarketPlusSendResult;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * G마켓·옥션 등록. 두 마켓에는 상품등록 API가 없어 Cafe24 마켓플러스의 '일괄 보내기'를 경유한다.
 *
 * <p>선행조건은 <b>Cafe24 등록행</b>이다. 마켓플러스 미판매 목록은 Cafe24에 등록된 상품만 보여주므로,
 * Cafe24 등록 없이 전송을 시도하면 브라우저 세션만 태우고 "상품 없음"으로 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketPlusPublisher {

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketPlusSendPort marketPlusSendPort;

	public MarketPublishOutcome publish(Product product, MarketType marketType) {
		MarketRegistration cafe24 = marketRegistrationRepository
			.findByProductIdAndMarketType(product.getId(), MarketType.CAFE24)
			.orElseThrow(() -> new IllegalStateException(
				"카페24 등록이 먼저 필요합니다 — G마켓·옥션은 마켓플러스를 경유합니다"));

		MarketPlusSendResult result = marketPlusSendPort.send(product.getSbCode(), marketType);
		if (!result.ok()) {
			throw new IllegalStateException("마켓플러스 전송 실패: " + result.reason());
		}

		String prefix = marketType == MarketType.GMARKET ? "gmarket" : "auction";
		cafe24.enrichIdentifier(prefix + "_sentAt", LocalDateTime.now().toString());

		Map<String, String> identifiers = new HashMap<>();
		if (result.marketProductNo() != null) {
			// 상품번호를 즉시 확보했으면 링크 파생 키에 바로 넣는다(buildGmarketUrl/buildAuctionUrl가 읽는 키).
			cafe24.enrichIdentifier(prefix + "_goodsNo", result.marketProductNo());
			identifiers.put(prefix + "_goodsNo", result.marketProductNo());
		}
		marketRegistrationRepository.save(cafe24);

		// 상품번호가 없으면 "전송 접수"까지만 참이다. ESM 반영 후 기존 백필 경로가 goodsNo를 채운다.
		boolean synced = result.marketProductNo() != null;
		log.info("[마켓플러스] 전송 완료: sbCode={}, market={}, goodsNo={}",
			product.getSbCode(), marketType, result.marketProductNo());
		return new MarketPublishOutcome(marketType, identifiers, synced);
	}
}
```

`ProductPublishUseCase.publishToMarket` 진입부에 분기를 넣는다(`hasClient` 검사보다 **앞에** 둔다 — G마켓·옥션은 어댑터가 없으므로 검사에 걸린다):

```java
		// G마켓·옥션에는 상품등록 API가 없다. Cafe24 마켓플러스 경유로 위임한다.
		if (marketType == MarketType.GMARKET || marketType == MarketType.AUCTION) {
			return marketPlusPublisher.publish(product, marketType);
		}
```

필드를 추가한다:

```java
	private final MarketPlusPublisher marketPlusPublisher;
```

- [ ] **Step 4: 인프라 어댑터를 만든다**

```java
package com.sbshop.agent.infrastructure.client.marketplus;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.product.port.MarketPlusSendPort;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Playwright 사이드카(sbshop-scraper)의 /cafe24/mp/send 호출. */
@Slf4j
@Component
public class MarketPlusSendClient implements MarketPlusSendPort {

	private final RestClient restClient;

	public MarketPlusSendClient(@Value("${scraper.base-url:http://sbshop-scraper:8099}") String baseUrl) {
		this.restClient = RestClient.builder().baseUrl(baseUrl).build();
	}

	@Override
	public MarketPlusSendResult send(String sbCode, MarketType target) {
		try {
			JsonNode body = restClient.post()
				.uri("/cafe24/mp/send")
				.body(Map.of("sbCode", sbCode, "targetMarket", target.name()))
				.retrieve()
				.onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
					// 503 = 자격증명 미설정. 조용히 실패로 뭉개지 않고 사유를 그대로 올린다.
					throw new IllegalStateException("마켓플러스 사이드카 오류: " + res.getStatusCode());
				})
				.body(JsonNode.class);
			if (body == null) {
				return new MarketPlusSendResult(false, "empty_response", null);
			}
			String no = body.path("marketProductNo").asText(null);
			return new MarketPlusSendResult(body.path("ok").asBoolean(false),
				body.path("reason").asText(null), (no == null || no.isBlank()) ? null : no);
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			log.error("[마켓플러스] 사이드카 호출 실패: sbCode={}, market={}", sbCode, target, e);
			throw new IllegalStateException("마켓플러스 사이드카 호출 실패: " + e.getMessage(), e);
		}
	}
}
```

`scraper.base-url` 프로퍼티가 이미 있는지 확인하고(`grep -rn "SCRAPER_BASE_URL\|scraper" backend/api/src/main/resources/application*.yml`), 없으면 `ScraplingSourcingClient`가 쓰는 프로퍼티 키와 동일하게 맞춘다.

- [ ] **Step 5: 통과 확인 + 전 모듈 회귀**

Run: `cd backend && ./gradlew :core:test --tests 'com.sbshop.agent.core.application.product.MarketPlusPublisherTest'`
Expected: PASS

Run: `cd backend && ./gradlew :core:test :infrastructure:test :api:test`
Expected: PASS (`ProductPublishUseCase` 생성자 인자가 또 하나 늘었으니 기존 테스트들을 함께 고친다)

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/product/port/MarketPlusSendPort.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/product/MarketPlusPublisher.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/marketplus/MarketPlusSendClient.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductPublishUseCase.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/MarketPlusPublisherTest.java
git commit -m "feat(product): G마켓·옥션 등록을 Cafe24 마켓플러스 경유로 연결한다"
```

---

## Task 10: 배포와 라이브 검증

**Files:**
- Modify: `docs/normalize/defect-ledger.md` (신규 기능이 드러낸 결함이 있으면 기록)
- Create: `docs/normalize/working_history/20260813_1600_결과서.md`

- [ ] **Step 1: 전 모듈 게이트를 통과시킨다**

```bash
cd backend && ./gradlew :core:test :infrastructure:test :api:test
cd ../frontend && npx tsc -p tsconfig.app.json && npx eslint . && npm run build
```
Expected: 모두 성공. **하나라도 실패하면 배포하지 않는다.**

- [ ] **Step 2: 배포한다**

```bash
git push origin main
```

**주의:** push는 곧 운영 API 재시작이다. 진행 중인 배치가 있으면 끝난 뒤에 push한다(`/batch/status/{id}/summary`로 확인).

- [ ] **Step 3: 배포를 확인한다**

```bash
ssh -i ssh-key-2026-06-25.key <운영서버> "cd /root/Projects && git log --oneline -1 && docker ps --format '{{.Names}}\t{{.CreatedAt}}' | grep -E 'sbshop-(api|scraper)'"
```
Expected: 서버 저장소 최신 커밋 = 방금 push한 커밋이고, 컨테이너 생성시각이 그 커밋 시각보다 **뒤**여야 한다.

- [ ] **Step 4: 라이브 검증**

상품 관리 화면에서 순서대로 확인한다:

1. `231211FM017`(N스토어만 등록)의 배지가 N스토어만 채색이고 나머지 5개가 점선인지
2. `220915IHB015`(전 마켓 등록)의 배지가 모두 채색인지
3. 점선 쿠팡 배지 클릭 → 확인 다이얼로그 → 등록 → 배지가 채색 링크로 바뀌는지
4. 쿠팡 판매자센터에서 **실제 등록가가 그 마켓 수수료 반영가인지**(기준가와 다를 수 있다 — 그게 정상이다)
5. 카페24 미등록 상품의 G마켓 배지가 흐리고 클릭 불가인지
6. 카페24 등록 상품의 G마켓 배지 클릭 → 마켓플러스에 실제로 전송됐는지

- [ ] **Step 5: 결과서를 쓰고 커밋한다**

`docs/normalize/working_history/20260813_1600_결과서.md`에 검증 결과·미해결 항목·다음 단계 참조를 기록한다. 4번에서 가격이 기준가 그대로였다면 그것은 미해결 결함이므로 `defect-ledger.md`에 등재한다.

```bash
git add docs/normalize/
git commit -m "docs(normalize): 마켓 배지 클릭 등록 라이브 검증 결과"
git push origin main
```

---

## 자체 점검 결과

**스펙 커버리지**

| 스펙 절 | 담당 태스크 |
|---|---|
| 3. 배지 셀(6슬롯·상태별 표현·확인 다이얼로그·세션 상태) | Task 3, 4 |
| 4. 응답 계약 확장(객체 승격·CAFE24 키·publish 응답 DTO) | Task 1, 2 |
| 5.1 API 4마켓 + 마켓별 판매가 | Task 5, 6 |
| 5.2 G마켓·옥션 마켓플러스 경유 + 선행조건 + 전송마커 | Task 7, 8, 9 |
| 5.3 자격증명 배선(3파일 + 미설정 시 503) | Task 7 |
| 6. 실패 처리(409·502·503·스크린샷·액션로그) | Task 4(표시), 8(스크린샷), 9(예외) |
| 7. 테스트(core·api·프론트 게이트) | 각 태스크 Step에 포함 |
| 8. 단계 구분 | Task 1–4=Phase 1, 5–6=Phase 2, 7–9=Phase 3, 10=배포 |

**미커버 항목 1건(의도적):** 스펙 6장의 "어댑터 없는 마켓 400"은 Task 9가 G마켓·옥션을 어댑터 없이 처리하도록 만들면서 사라진다. 남은 400 경로는 `MarketType.valueOf` 실패(잘못된 경로변수)뿐이며 기존 예외 핸들러가 처리한다.

**타입 일관성:** `MarketBadgeState.status` 어휘(`SYNCED`/`PENDING`)를 `MarketPublishResponse.status`와 프론트 `MarketBadgeState` 인터페이스가 동일하게 쓴다. `gmarket_goodsNo`/`auction_goodsNo` 키는 `MarketRegistration.buildGmarketUrl()`/`buildAuctionUrl()`이 읽는 키와 일치한다(Task 9 Step 3).

**남은 불확실성:** Task 7 Step 3의 스파이크 결과 6항목. 특히 "전송에 상품별 사전 설정(카테고리 매칭·반품 템플릿)이 필요한가"가 참이면 Task 8의 범위가 늘어난다 — 그때는 사용자와 합의 후 진행한다.
