# 버그·백로그 — infrastructure 모듈 (2026-08-21 리팩토링 캠페인)

기록만 하고 수정하지 않는다.

## TODO 백로그

(infrastructure 영역 내 TODO/FIXME/XXX/HACK 주석 0건 — `grep -rn "TODO\|FIXME\|XXX\|HACK" infrastructure/src` 결과 없음)

---

## 발견 버그 (수정하지 않음)

### B-INF-1 — Cafe24 이미지·상세HTML 동기화 실패가 조용히 성공으로 보고된다 (심각도: 높음)
- 위치: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/adapter/Cafe24MarketClient.java:195-197` (상세설명 PUT 실패), `:229-231` (이미지 업로드 실패)
- 증상: `syncImagesAndHtml`이 상세설명 PUT 실패와 이미지 업로드 실패를 **`log.error`만 하고 삼킨 뒤** 정상적으로 `currentRawData`를 반환한다. 호출자는 동기화가 성공한 것으로 기록한다.
- 판단 근거: 같은 클래스의 `publish`(L105-107)·`extractMarketItem`(L127-129)은 예외를 전파하고, **다른 세 마켓 어댑터는 동일 동사에서 전부 전파**한다 — `ElevenstMarketClient:147` `throw new RuntimeException("[Elevenst] 상품수정(이미지/상세) 실패: " + resp)`, `SmartstoreMarketClient:168/207`. salvage 기록의 "실패 표면화(SP-A/SP-C 원칙)"가 Cafe24 이 경로에만 적용돼 있지 않다. 기존 이미지 삭제 실패(L203-205)만은 `log.warn` 삼키기가 의도적으로 보이지만(멱등 정리), 본 업로드/상세 PUT 실패까지 삼키는 것은 그 의도로 설명되지 않는다.
- 참고: 이 결함은 마켓 반영 실패를 반영됨으로 기록하는 D-145/D-150 계열과 같은 종류의 "거짓 성공"이다.

### B-INF-2 — Cafe24 주문취소가 발주확인과 다른(미검증) API 형태를 쓴다 (심각도: 중간)
- 위치: `backend/infrastructure/.../client/cafe24/client/Cafe24OrderApiClient.java` — `cancelOrder` → `updateStatus`
- 증상: `acceptOrder`는 D-091에서 검증된 스펙 **`PUT /admin/orders`(경로에 id 없음) + `requests[].process_status`**를 쓰는데, `cancelOrder`는 **`PUT /admin/orders/{id}` + `request.status = "C40"`**이라는 전혀 다른 경로·필드 모양을 쓴다.
- 판단 근거: 삭제된 원주석이 명시적으로 `"취소완료 — 취소는 별도 API 필요(D-091 후속 미검증)"`라고 적어 두었다(salvage-infra.md 보존). 검증된 쓰기 경로가 `process_status` 문자열임이 확인된 이상, `status` N코드로 쓰는 이 경로는 422를 받을 가능성이 높다. 라이브 취소 시도가 없어 아직 드러나지 않았을 뿐이다.
- 관련: `Cafe24RestClient.enrich`(D-152) 덕분에 지금은 422 본문이 메시지에 담기므로, 라이브에서 실패하면 사유는 보인다.

### B-INF-3 — 11번가 XML 중복 태그 제거가 부분적이고 값 불일치를 조용히 버린다 (심각도: 낮음)
- 위치: `backend/infrastructure/.../client/elevenst/ElevenstOrderRestClient.java` — `removeDuplicateTags`
- 증상: 정규식 `(<([a-zA-Z0-9_]+)>([^<]*)</\2>)\s*<\2>[^<]*</\2>` → `$1`은 (1) 같은 태그가 **3회 이상** 반복되면 `replaceAll`의 비중첩 스캔 특성상 잔여가 남고, (2) **두 번째 값이 첫 번째와 달라도 검사 없이 버린다.**
- 판단 근거: 원주석은 "동일 태그가 2번 나옴"이라는 관측만 근거로 삼는다(예: `<ordNo>123</ordNo><ordNo>123</ordNo>`). 값이 다른 경우는 가정되지 않았는데 방어도 없다. 값이 갈리는 응답이 오면 조용히 첫 값을 채택한다.
- 성격: 현재 라이브에서 문제가 관측된 바 없음 — 잠재 결함으로만 기록.

### B-INF-4 — R2 업로드가 InputStream을 닫지 않는다 (심각도: 낮음)
- 위치: `backend/infrastructure/.../client/cloudflare/R2ImageStorageClient.java` — `uploadImages`
- 증상: `RequestBody.fromInputStream(file.inputStream(), file.size())`에 넘긴 스트림을 try-with-resources로 감싸지 않아 업로드 성공/실패 어느 쪽에서도 닫히지 않는다. 이미지가 많은 배치에서 파일 핸들이 누적될 수 있다.
- 판단 근거: `ImageUploadFile.inputStream()`이 무엇을 돌려주는지에 따라 영향이 갈리므로(메모리 기반이면 무해) 심각도를 낮게 잡았다. 확인 필요.

---

## 데드 의심 (미참조 public — 삭제하지 않고 보고만)

교리 §4에 따라 스프링 진입점·JPA·Jackson·리플렉션 의심은 삭제 금지. 아래는 `backend/{core,infrastructure,api,worker}` 전체 `*.java`(build·bin 제외)에서 참조 0건으로 확인된 **public** 멤버다.

| 대상 | 위치 | 근거 / 판단 |
|---|---|---|
| `CoupangHmacUtil.generateSignature(...)` | `client/coupang/CoupangHmacUtil.java` | **구(舊) KST 서명 방식.** salvage 기록대로 이 방식(KST·`T`/`Z` 없음 + 별도 signed-date 헤더)은 과거 **"HMAC format is invalid"**를 유발해 `generateSignatureUtc`로 대체됐다. 참조 0건. 삭제 유력 후보지만 public static이라 보고만. |
| `CoupangHmacUtil.generateDatetime()` | 같은 파일 | 위와 같은 KST `yyMMddHHmmss` 포맷을 돌려주는 헬퍼. 참조 0건. `generateSignature`와 한 세트로 함께 정리 대상. |
| `CoupangDataMapper.extractMergedHtmlDescription(JsonNode)` | `client/coupang/mapper/CoupangDataMapper.java` | 참조 0건. 같은 클래스의 `buildIdentifiers`/`buildRawData`/`getStock`은 `CoupangMarketClient`에서 사용 중이라 클래스 자체는 살아 있다. |
| `CoupangDataMapper.getPrice(JsonNode)` | 같은 파일 | 참조 0건(`getStock`만 사용됨). |

**오탐으로 판정해 제외한 것**: `QueryDslConfig.jpaQueryFactory()`(`@Bean`), 그리고 클래스 단위로 참조 0건이 잡힌 `CoupangOrderApiClient`·`ElevenstOrderApiClient`·`SmartStoreOrderApiClient`·`CoupangCategoryResolver`·`GsiExpressScraperAdapter`·`MfdsBannedIngredientClient`·`NaverKeywordToolClient`·`NaverShoppingSearchClient`·`OpenCodeZenTextClient`·`ScraplingIherbClient`·`ScraplingSourcingClient`·`DashboardRepositoryImpl`·`ProductReaderImpl`·`ProductWriterImpl`·`ProductJpaRepository` 전부 — **포트 인터페이스로 주입되는 정상 DI 패턴**이다(서베이 §①이 지적한 오탐 유형과 동일). 삭제하지 않았다.

**private 미사용 멤버: 0건.** 전 파일의 private 메서드·필드를 메서드 참조(`this::name`) 포함해 검사했고 미사용은 없었다. `*Properties` 4종의 private 필드는 `@Getter/@Setter + @ConfigurationProperties`로 살아 있다. `CoupangHmacUtil`·`SmartStoreDispatchResult`의 private 생성자는 유틸 클래스 인스턴스화 방지용(의도).

**주석처리된 코드 블록: 0건.** 주석 전량 삭제 과정에서 코드 형태의 주석 블록은 발견되지 않았다(후보로 잡힌 5줄은 전부 설명 주석이었고 salvage로 보존).

---

## 구조 변경 백로그 (이번 캠페인 범위 밖 — 파일 이동 필요)

서베이 §③(a)가 지적한 마켓별 패키지 구조 불일치를 infrastructure 담당자로서 재확인했다. **이번에 재배치하지 않았다.**

1. **`client/` 서브패키지의 의미가 마켓마다 다르다.** coupang·elevenst·smartstore는 `client/`에 저수준 HTTP 래퍼만 두고 포트 구현체(`XxxOrderApiClient`)를 패키지 루트에 두는데, **cafe24만 `client/Cafe24OrderApiClient.java`로 포트 구현체까지 `client/` 안에 넣었다.** 옛 지도 D-1이 지적한 `elevenst` 이중구조와 같은 종류의 불일치가 cafe24에서 재발한 형태.
2. **coupang만 `dto/`가 있고 그마저 루트와 섞여 있다.** `CoupangAcceptOrdersRequest`·`CoupangCancelOrderResponse`·`CoupangInvoiceResponse`는 루트, `CategoryMetaResult`·`CoupangProductPayload`는 `dto/`. elevenst·smartstore·cafe24는 `dto/` 패키지 자체가 없다.
3. **cafe24만 `config/` 패키지가 없다.** `Cafe24OAuthTokenClient`/`Cafe24OAuthTokenHttpClient`/`Cafe24TokenManager`가 전부 루트에 있다.
4. **elevenst만 `component/`가 없다** — 카테고리 해석기 부재 때문인데, 이건 버그가 아니라 문서화된 의도적 제약(11번가는 자동 카테고리 해석기가 없어 상품 자동등록을 상시 거부).
5. **계층 위반(참고)**: `api/controller/Cafe24AuthController`가 core를 건너뛰고 `infrastructure`의 `Cafe24TokenManager`·`Cafe24RestClient`를 직접 import한다. 내 담당 범위 밖(api 모듈)이지만 infrastructure 쪽 진입점이 그 대상이라 함께 기록한다 — core에 OAuth 트리거 포트 신설이 필요하다.

## 이번 캠페인에서 의도적으로 남긴 것

- **`SmartStoreOrderApiClient`의 "번호 매기기 주석"(1. 2. 3. …)이 전부 사라졌다.** 이 파일은 주석 밀도가 압도적으로 높았고(단계 번호 + 한 줄 설명), 내용은 전부 "다음 줄이 무엇을 하는지"라 교리 §1의 삭제 대상이었다. 다만 그 결과 `fetchOrders`의 **2단계 구조(상태목록 GET → 상세 일괄 POST)** 가 코드만으로는 덜 드러난다 — salvage-infra.md에 구조를 보존해 뒀고, 메서드 추출로 단계를 드러내는 것은 **행위 변경 위험이 있어 이번 범위 밖**으로 남긴다.
- **`OrderSyncScheduler.syncEsmplusOrders()` 이름 문제**(서베이 §③(d))는 worker 모듈 소관이라 손대지 않았다.
