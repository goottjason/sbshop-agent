# SP-C: 이미지 파이프라인 완결 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 소스이미지 크롤→R2 업로드→마켓 반영을 원클릭으로 만들고, 마켓별 이미지 재게시의 no-op·부분·경로 결함을 정상화한다.

**Architecture:** 재게시 배관(`updateImagesAndHtml`→`republishToMarkets`→4마켓 `syncImagesAndHtml`)은 이미 존재하므로, 각 마켓 클라이언트 구현 품질을 고치고 marketItemId 추출을 정정한다. 크롤→업로드는 컨트롤러 단일 엔드포인트로 원자화한다. 3개 마켓 클라이언트의 내부 예외 삼킴을 제거해 실패가 `republishToMarkets`의 마켓별 실패 수집으로 표면화되게 한다.

**Tech Stack:** Java 21, Spring Boot 3.5 (core/infrastructure/api), React 19/Vite/TS(antd), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- 마켓별 재게시 실패는 은폐 금지 — 각 클라이언트는 내부 catch로 삼키지 말고 예외를 전파해 `ProductManageUseCase.republishToMarkets`의 마켓별 `failed` 맵으로 표면화한다(SP-A 원칙).
- 포트-인-코어. DDL 없음. 신규 의존성 없음.
- 11번가 상세설명수정: `POST /rest/prodservices/updateProductDetailCont/{prdNo}`, XML `<ProductDetailCont><prdDescContClob><![CDATA[html]]></prdDescContClob></ProductDetailCont>`, EUC-KR, `openapikey` 헤더(ElevenstMarketRestClient가 처리). 대표이미지(prdImage01)는 범위 밖.
- 쿠팡 이미지는 "승인필요" 상품수정 API `PUT .../seller-products/{sellerProductId}` (재심사 유발은 쿠팡 정책). sellerProductId는 `currentRawData.get("sellerProductId")`에서 읽음.
- 스마트스토어 다중이미지 필드는 `publish()` 관습과 동일: `originProduct.representativeImage`(String, hostedImages[0]) + `originProduct.optionalImages`(List<String>, hostedImages[1..]). **실 필드 구조는 라이브 검증 대상.**
- marketItemId는 `reg.extractMarketCode()`로 추출(마켓별 올바른 코드). null/빈값이면 `IllegalStateException`으로 표면화(SP-B `ProductMarketSyncService`와 동일 규율).
- 커밋 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: marketItemId 추출 정정 (republishToMarkets)

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductManageUseCase.java` (`republishToMarkets` 내 marketItemId 추출 ~라인 116-119)
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductManageRepublishMarketCodeTest.java`

**Interfaces:**
- Consumes: `MarketRegistration.extractMarketCode() : String` (기존), `MarketClientRouter.hasClient/getClient`, `MarketClient.syncImagesAndHtml`.
- Produces: (동작 변경) republishToMarkets가 `extractMarketCode()`로 marketItemId를 추출하고 null/빈값이면 그 마켓을 실패로 수집.

- [ ] **Step 1: 실패 테스트 작성**

`ProductManageRepublishMarketCodeTest.java`: `updateImagesAndHtml`를 통해 republish 경로를 태우거나, republish를 직접 검증하기 어려우면 기존 `ProductManageUseCase` 테스트 준비 패턴을 재사용. 핵심: SMART_STORE 등록의 marketIdentifiers가 `{"originProductNo":"OP123"}`일 때 클라이언트에 전달되는 marketItemId가 `"OP123"`인지 검증(현재는 vendorItemId 없어 productId 폴백).
```java
// reg: marketType=SMART_STORE, marketIdentifiers={"originProductNo":"OP123"}, 클라이언트 있음
// updateImagesAndHtml 실행 시:
verify(smartstoreClient).syncImagesAndHtml(eq("OP123"), any(), anyList(), any());
```
(imageStorageClient.uploadImages·htmlImageReplacer·productReader·marketRegistrationRepository·marketClientRouter를 Mockito로 구성. 기존 테스트가 있으면 그 setup 재사용.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductManageRepublishMarketCodeTest*'`
Expected: FAIL — 현재 `extractVendorItemId()`가 originProductNo를 못 읽어 productId 폴백 값이 전달됨.

- [ ] **Step 3: 추출 로직 교체**

`ProductManageUseCase.republishToMarkets`의 marketItemId 블록(현재):
```java
				String marketItemId = reg.extractVendorItemId();
				if (marketItemId == null || marketItemId.isEmpty()) {
					marketItemId = String.valueOf(reg.getProductId());
				}
```
를 다음으로 교체:
```java
				String marketItemId = reg.extractMarketCode();
				if (marketItemId == null || marketItemId.isEmpty()) {
					throw new IllegalStateException("마켓 상품코드 없음(연동정보에 코드 키 부재)");
				}
```
(try 블록 안이므로 throw는 해당 마켓 실패로 수집되고 나머지 마켓은 계속 진행.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ProductManageRepublishMarketCodeTest*'`
Expected: PASS

- [ ] **Step 5: core 회귀 확인**

Run: `cd backend && ./gradlew :core:test`
Expected: 신규 PASS. 기존 republish 테스트가 productId 폴백을 가정하면 새 계약으로 갱신. pre-existing `SmartStoreOrderFetchFailureTest` 실패는 무관.

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/product/ProductManageUseCase.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/ProductManageRepublishMarketCodeTest.java
git commit -m "fix(SP-C): 이미지 재게시 marketItemId를 extractMarketCode로 정정 (D-052 계열)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 스마트스토어 다중이미지 + 실패 표면화

**Files:**
- Modify: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/smartstore/adapter/SmartstoreMarketClient.java` (`syncImagesAndHtml`)
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/smartstore/SmartstoreMarketClientImagesTest.java`

**Interfaces:**
- Consumes: `restClient.get/put` (기존).
- Produces: `syncImagesAndHtml`가 `originProduct.optionalImages`(hostedImages[1..]) 세팅 + 실패 시 예외 전파.

- [ ] **Step 1: 실패 테스트 작성**

`SmartstoreMarketClientImagesTest.java`: `restClient`(SmartstoreRestClient) Mockito mock. GET가 `{"originProduct":{"representativeImage":"old"}}` 반환하도록 스텁, `syncImagesAndHtml("OP1", raw, ["u0","u1","u2"], "<html>")` 호출 후 PUT 바디(ArgumentCaptor)에서 `originProduct.representativeImage=="u0"`, `originProduct.optionalImages==["u1","u2"]`, `originProduct.detailContent`가 세팅됐는지 검증. (기존 SmartstoreMarketClientSoldOutTest의 mock/캡처 패턴 재사용.)
```java
// hostedImages 1개(대표만)일 때 optionalImages는 세팅하지 않거나 빈 리스트인지도 1케이스 검증.
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*SmartstoreMarketClientImagesTest*'`
Expected: FAIL — 현재 optionalImages 미세팅.

- [ ] **Step 3: 구현**

`SmartstoreMarketClient.syncImagesAndHtml`의 이미지 세팅부(현재 representativeImage만)를 다음으로:
```java
			if (!hostedImages.isEmpty()) {
				originProduct.put("representativeImage", hostedImages.get(0));
				if (hostedImages.size() > 1) {
					originProduct.put("optionalImages", hostedImages.subList(1, hostedImages.size()));
				}
			}
			if (newDetailHtml != null) {
				originProduct.put("detailContent", newDetailHtml.replace("\"", "\\\"").replace("\n", ""));
			}
```
그리고 메서드의 `catch (Exception e) { log.error(...); }`를 **제거하고 예외를 전파**(try 자체를 없애거나 catch에서 `throw new RuntimeException("[Smartstore] 이미지/HTML 동기화 실패: " + e.getMessage(), e);`). GET/PUT은 그대로.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*SmartstoreMarketClientImagesTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/smartstore/adapter/SmartstoreMarketClient.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/smartstore/SmartstoreMarketClientImagesTest.java
git commit -m "feat(SP-C): 스마트스토어 이미지 재게시 optionalImages(다중이미지) + 실패 표면화

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 11번가 no-op 해소 (상세설명수정 API)

**Files:**
- Modify: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/adapter/ElevenstMarketClient.java` (`syncImagesAndHtml`)
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/elevenst/ElevenstMarketClientImagesTest.java`

**Interfaces:**
- Consumes: `ElevenstMarketRestClient.post(String path, String xmlBody) : String` (기존, EUC-KR·openapikey 처리). IO 오류 시 `<resultCode>ERROR</resultCode>...` 문자열 반환(throw 안 함).
- Produces: `syncImagesAndHtml`가 상세HTML을 updateProductDetailCont로 전송; 실패 응답 시 예외 전파.

- [ ] **Step 1: 실패 테스트 작성**

`ElevenstMarketClientImagesTest.java`: `ElevenstMarketRestClient` Mockito mock. `syncImagesAndHtml("PRD9", raw, ["u0"], "<p>hi</p>")` 호출 시:
```java
// 성공 응답 스텁
when(restClient.post(eq("/rest/prodservices/updateProductDetailCont/PRD9"),
    org.mockito.ArgumentMatchers.contains("prdDescContClob"))).thenReturn("<Product/><message>성공</message>");
// 실행
client.syncImagesAndHtml("PRD9", raw, List.of("u0"), "<p>hi</p>");
// 검증: 올바른 URL + CDATA로 감싼 상세HTML XML 전송
verify(restClient).post(eq("/rest/prodservices/updateProductDetailCont/PRD9"),
    org.mockito.ArgumentMatchers.contains("<![CDATA[<p>hi</p>]]>"));
```
그리고 실패 케이스: `restClient.post(...)` 가 `"<resultCode>ERROR</resultCode><message>실패</message>"` 반환 시 `syncImagesAndHtml`이 `RuntimeException`을 throw하는지(`assertThatThrownBy`).

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*ElevenstMarketClientImagesTest*'`
Expected: FAIL — 현재 no-op(log.warn), post 호출 없음.

- [ ] **Step 3: 구현**

`ElevenstMarketClient.syncImagesAndHtml` 본문 교체(no-op → 상세설명수정):
```java
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		// 11번가는 이미지/상세를 개별 필드로 못 바꾸나, 상세설명수정 전용 API로 상세HTML(임베드 이미지 포함)을 반영한다.
		// 대표이미지(prdImage01)는 상품수정 전체전문이 필요해 범위 밖.
		String xml = "<?xml version=\"1.0\" encoding=\"euc-kr\"?>"
			+ "<ProductDetailCont>"
			+ "<prdDescContClob><![CDATA[" + (newDetailHtml == null ? "" : newDetailHtml) + "]]></prdDescContClob>"
			+ "</ProductDetailCont>";
		String response = restClient.post("/rest/prodservices/updateProductDetailCont/" + marketItemId, xml);
		if (response == null || response.contains("ERROR") || response.contains("resultCode>500")) {
			throw new RuntimeException("[Elevenst] 상세설명 수정 실패: " + response);
		}
		log.info("[Elevenst] 상세HTML 재게시 완료: {}", marketItemId);
		return currentRawData;
	}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*ElevenstMarketClientImagesTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/adapter/ElevenstMarketClient.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/elevenst/ElevenstMarketClientImagesTest.java
git commit -m "feat(SP-C): 11번가 이미지 재게시 no-op 해소 — 상세설명수정 API(EUC-KR XML)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 쿠팡 sellerProductId 경로 + 실패 표면화

**Files:**
- Modify: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/coupang/adapter/CoupangMarketClient.java` (`syncImagesAndHtml`)
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/coupang/CoupangMarketClientImagesTest.java`

**Interfaces:**
- Consumes: `restClient.put(String path, Object body)` (기존). sellerProductId는 `currentRawData.get("sellerProductId")`.
- Produces: `syncImagesAndHtml`가 `.../seller-products/{sellerProductId}` 경로로 PUT; sellerProductId 부재 시 예외; 실패 전파.

- [ ] **Step 1: 실패 테스트 작성**

`CoupangMarketClientImagesTest.java`: `restClient`(CoupangRestClient) Mockito mock. `currentRawData`에 `items`(1개, Map) + `sellerProductId=305L`. `syncImagesAndHtml("V1", raw, ["u0","u1"], "<html>")` 호출 후:
```java
verify(restClient).put(eq("/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/305"), any());
```
그리고 sellerProductId 부재 케이스: raw에 sellerProductId 없으면 `IllegalStateException` throw 검증.

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*CoupangMarketClientImagesTest*'`
Expected: FAIL — 현재 고정 경로(ID 없음).

- [ ] **Step 3: 구현**

`CoupangMarketClient.syncImagesAndHtml`에서 PUT 경로 조립 + 예외 전파:
- 이미지/contents 세팅 로직은 유지.
- 고정 path 라인
  ```java
  String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
  restClient.put(path, currentRawData);
  ```
  를 다음으로:
  ```java
  Object sellerProductId = currentRawData.get("sellerProductId");
  if (sellerProductId == null || String.valueOf(sellerProductId).isBlank()) {
      throw new IllegalStateException("쿠팡 sellerProductId 없음(rawData) — 이미지 재게시 불가");
  }
  String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + sellerProductId;
  restClient.put(path, currentRawData);
  ```
- 메서드의 `catch (Exception e) { log.error(...); }`를 제거하고 예외 전파(또는 catch에서 `throw new RuntimeException("[쿠팡] 이미지/HTML 동기화 실패: " + e.getMessage(), e);`). 단 초반의 `if (currentRawData == null || !containsKey("items")) return currentRawData;` 가드는 유지.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*CoupangMarketClientImagesTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/coupang/adapter/CoupangMarketClient.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/coupang/CoupangMarketClientImagesTest.java
git commit -m "fix(SP-C): 쿠팡 이미지 재게시 PUT 경로에 sellerProductId 포함 + 실패 표면화 (D-046)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 크롤→업로드 원클릭 엔드포인트 (백엔드)

**Files:**
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java` (신규 엔드포인트)
- Test: `backend/api/src/test/java/com/sbshop/agent/api/controller/ProductControllerCrawlUploadTest.java`

**Interfaces:**
- Consumes: `productSearchUseCase.getProductDetail(id)`, `product.getSourcingUrl()`, `productInfoCrawlerPort.crawlProductInfoAsDto(url) : ScrapedProductDto`(`.sourceImages() : List<String>`), `imageDownloadClient.downloadAndConvert(List<String>) : List<ImageUploadFile>`, `productManageUseCase.updateImagesAndHtml(id, files) : MarketRepublishResult`.
- Produces: `POST /api/v1/products/{id}/images/crawl-and-upload` → `MarketRepublishResult`(기존 `ImageUploadResponse.from`).

- [ ] **Step 1: 실패 테스트 작성**

`ProductControllerCrawlUploadTest.java` (`@WebMvcTest` 또는 기존 컨트롤러 테스트 스타일 재사용 — `ProductControllerActionLogDetailTest` 패턴 참조): 크롤이 `["u0","u1"]` 반환하도록 mock, 엔드포인트 호출 시 `imageDownloadClient.downloadAndConvert(["u0","u1"])` → `productManageUseCase.updateImagesAndHtml(id, files)` 순서로 호출되는지 verify. 소싱 URL 없으면 빈결과/400.
```java
mockMvc.perform(post("/api/v1/products/7/images/crawl-and-upload")).andExpect(status().isOk());
verify(productManageUseCase).updateImagesAndHtml(eq(7L), any());
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests '*ProductControllerCrawlUploadTest*'`
Expected: FAIL — 엔드포인트 없음(404).

- [ ] **Step 3: 엔드포인트 구현**

`ProductController`에 추가(기존 crawlSourceImages + uploadImagesByUrl 조합):
```java
	@PostMapping("/{id}/images/crawl-and-upload")
	public ResponseEntity<ImageUploadResponse> crawlAndUpload(@PathVariable Long id) {
		try {
			Product product = productSearchUseCase.getProductDetail(id);
			String sourcingUrl = product.getSourcingUrl();
			if (sourcingUrl == null || sourcingUrl.isEmpty()) {
				actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
					ActionStatus.SUCCESS, "소스이미지 없음 — 소싱 URL 미등록 (상품 " + id + ")");
				return ResponseEntity.ok(ImageUploadResponse.from(
					new MarketRepublishResult(java.util.List.of(), java.util.List.of(), java.util.Map.of())));
			}
			ScrapedProductDto scraped = productInfoCrawlerPort.crawlProductInfoAsDto(sourcingUrl);
			java.util.List<String> images = (scraped == null || scraped.sourceImages() == null)
				? java.util.List.of() : scraped.sourceImages();
			if (images.isEmpty()) {
				actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
					ActionStatus.SUCCESS, "소스이미지 0개 — 크롤 결과 없음 (상품 " + id + ")");
				return ResponseEntity.ok(ImageUploadResponse.from(
					new MarketRepublishResult(java.util.List.of(), java.util.List.of(), java.util.Map.of())));
			}
			java.util.List<ImageUploadFile> files = imageDownloadClient.downloadAndConvert(images);
			MarketRepublishResult result = productManageUseCase.updateImagesAndHtml(id, files);
			actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
				ActionStatus.SUCCESS,
				buildMarketResultMessage(id, "소스이미지 " + images.size() + "개 크롤·업로드 완료", result));
			return ResponseEntity.ok(ImageUploadResponse.from(result));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
				ActionStatus.FAILED, "소스이미지 크롤·업로드 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}
```
(필요 import: `MarketRepublishResult`, `ScrapedProductDto`, `ImageUploadFile`, `ImageUploadResponse` — 파일에 대부분 이미 있음. `MarketRepublishResult` 생성자 인자 순서는 SP-B에서 쓰인 `(synced, skipped, failed)` — 기존 crawl/upload 코드의 사용처 확인해 맞춤. `buildMarketResultMessage`는 기존 헬퍼.)

- [ ] **Step 4: 테스트 통과 + api 컴파일**

Run: `cd backend && ./gradlew :api:test --tests '*ProductControllerCrawlUploadTest*' :api:compileJava`
Expected: PASS / 컴파일 성공.

- [ ] **Step 5: 커밋**

```bash
git add backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java \
        backend/api/src/test/java/com/sbshop/agent/api/controller/ProductControllerCrawlUploadTest.java
git commit -m "feat(SP-C): 크롤→업로드 원클릭 엔드포인트 (crawl-and-upload)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 프론트 크롤 버튼 원클릭

**Files:**
- Modify: `frontend/src/api/productApi.ts` (신규 crawlAndUpload)
- Modify: `frontend/src/pages/ProductPage.tsx` (`handleCrawl`)

**Interfaces:**
- Consumes: `POST /api/v1/products/{id}/images/crawl-and-upload`(Task 5) → `ImageUploadResult`(기존 `surfaceUploadResult`가 처리하는 타입).

- [ ] **Step 1: productApi 추가**

`productApi.ts`에 추가(기존 crawlSourceImages 옆):
```ts
  crawlAndUpload: (id: number) =>
    apiClient.post(`/api/v1/products/${id}/images/crawl-and-upload`),
```

- [ ] **Step 2: handleCrawl 원클릭화**

`ProductPage.tsx`의 `handleCrawl`(현재 크롤→텍스트박스 채움)을 크롤→업로드 원클릭으로 교체. 비-iHerb 가드는 유지:
```tsx
  const handleCrawl = async () => {
    if (!detailModal.id) return;
    if (detailModal.data?.vendor !== 'IHB') {
      message.warning('이 벤더는 아직 소스이미지 크롤을 지원하지 않습니다 (현재 iHerb 상품만 지원).');
      return;
    }
    setUploading(true);
    try {
      const res = await productApi.crawlAndUpload(detailModal.id);
      surfaceUploadResult('소스이미지 크롤·업로드 완료', res.data as ImageUploadResult);
      await refreshDetail(detailModal.id);
    } catch {
      message.error('소스 이미지 크롤·업로드에 실패했습니다.');
    } finally {
      setUploading(false);
    }
  };
```
(기존 `handleUploadByUrl`·`handleFilesSelected`·URL 텍스트박스는 수동 보정용으로 유지 — 삭제하지 않음.)

- [ ] **Step 3: 타입체크 + 빌드**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: tsc 0, build 성공. (`surfaceUploadResult`·`ImageUploadResult`·`refreshDetail`은 기존. `setUrlInput` 미사용이 되면 크롤 버튼에서만 안 쓰이는지 확인 — 텍스트박스/handleUploadByUrl은 유지되므로 계속 사용됨.)

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/api/productApi.ts frontend/src/pages/ProductPage.tsx
git commit -m "feat(SP-C): 소스이미지 크롤 버튼 원클릭화 (크롤→업로드→마켓반영)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 통합 게이트 + 라이브 확인 준비

**Files:** 없음(검증 전용).

- [ ] **Step 1: 백엔드 전체 게이트**

Run: `cd backend && ./gradlew :core:test :infrastructure:test --tests '*MarketClient*' --tests '*ProductManage*' :api:test --tests '*ProductController*'`
Expected: SP-C 신규/변경 테스트 PASS. pre-existing 무관 실패(core `SmartStoreOrderFetchFailureTest`, infra `ImageDownloadServiceCharacterizationTest` SIGABRT)는 `git diff --name-only <base>..HEAD`로 diff 밖임을 확인해 기록.

- [ ] **Step 2: 프론트 게이트**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: 0 / 성공.

- [ ] **Step 3: 라이브 확인 체크리스트 문서화**

배포 후(사용자 허가): iHerb 상품 소스이미지 크롤 버튼 → 크롤·R2·4마켓 반영 원클릭. 마켓별: 스마트스토어 다중이미지(optionalImages 실 필드 구조) 반영, 11번가 상세HTML(updateProductDetailCont) 실반영, 쿠팡 승인요청 정상(sellerProductId 경로·재심사), 카페24 유지. 실패 마켓이 진행현황/토스트에 표면화되는지.

---

## Self-Review 체크

- **Spec 커버리지:** 크롤 원클릭(Task 5+6)·marketItemId 정정(Task 1)·스마트스토어 다중이미지(Task 2)·11번가 no-op 해소(Task 3)·쿠팡 sellerProductId(Task 4)·게이트(Task 7). DDL 없음. ✅
- **Placeholder:** 코드/명령/기대출력 구체화. 11번가 성공/실패 응답 판정은 `ERROR`/`resultCode>500` 문자열 검사로 명시(rest client의 IO-오류 sentinel + API 오류코드). ✅
- **타입 일관성:** `extractMarketCode() : String`(Task 1) — 기존 메서드. `syncImagesAndHtml(String, Map, List<String>, String)` 포트 시그니처 — Task 2/3/4에서 불변(내부 구현만 변경). `crawlAndUpload`(productApi) ↔ `POST .../crawl-and-upload`(Task 5) 일치. `MarketRepublishResult(synced, skipped, failed)` 생성자 — Task 5에서 사용(기존 SP-B 확인). ✅
- **실패 표면화 일관:** Task 2/3/4 모두 내부 catch 제거→예외 전파, republishToMarkets가 마켓별 수집(Task 1 파일). 단일 규율. ✅
- **미검증 라이브 주의:** 스마트스토어 optionalImages 실 필드 구조, 11번가 상세설명수정 실동작, 쿠팡 재심사는 Task 7 라이브 확인에 명시.
