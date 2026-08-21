# salvage — infrastructure 모듈 (2026-08-21 리팩토링 캠페인)

주석 전량 삭제 전, 코드로 표현 불가한 "왜"(마켓 API 함정·멱등성·순서 제약·운영 사고 이력)를 파일:메서드와 함께 보존한다.
범위: `backend/infrastructure/src/{main,test}`.

## lock/

### `PostgresAdvisoryTokenRefreshLock` (클래스 Javadoc / `LOCK_TX_TIMEOUT_MS` / `runExclusively`)
- Postgres 트랜잭션 범위 advisory lock으로 임계 구역을 **프로세스 간** 직렬화한다. 락은 트랜잭션 커밋/롤백 시 자동 해제되고 다른 프로세스는 같은 key에서 블록된다. `action` 내부의 JPA 저장이 이 트랜잭션에 참여하므로 **refresh + 영속화가 원자적**이다 — `@Transactional` 없이 쓰면 원자성이 깨진다.
- `LOCK_TX_TIMEOUT_MS = 15000`: 락 트랜잭션이 외부 HTTP(토큰 refresh)를 감싸므로, HTTP가 멈춰도 락·커넥션을 무한 점유하지 않도록 트랜잭션을 DB 레벨에서 시간 제한한다(설계의 "무한 대기 방지" 강제).
- `runExclusively`의 두 `SET LOCAL`: `statement_timeout`은 락 획득 대기(및 이후 SQL)의 상한, `idle_in_transaction_session_timeout`은 HTTP refresh 중 idle 상태로 락을 무한 점유하는 것을 방지(초과 시 tx abort → 락·커넥션 해제). **둘 다 필요** — 하나만 두면 각각 다른 정지 모드를 놓친다.

## repository/order/

### `OrderRepositoryImpl` (필드 `shipmentRepository` / `searchOrderGrid`)
- D-148: 화면이 "마켓이 아는 송장"과 실제 송장의 **불일치**를 판정하려면 배송(Shipment) 계층이 필요하다 — 그래서 이 리포지토리가 `ShipmentRepository`까지 의존한다.
- `searchOrderGrid`의 shipmentId 일괄 조회: 라인아이템마다 조회하면 **N+1**이므로 shipmentId를 모아 한 번에 읽는다. 라인아이템 루프 안에서 `shipmentRepository`를 부르지 말 것.

### `OrderRepositoryImpl.dateBetween`
- F-ORD-2: 기간 필터는 **한쪽만 주어져도 그 경계는 적용한다**(시작만 → goe, 끝만 → loe). 둘 다 없을 때만 필터 미적용. 회귀 테스트: `OrderRepositoryImplDateFilterTest`.

### `OrderRepositoryImpl.shippingStatusIn` / `purchaseStatusIn` / `stockStatusExists` / `vendorExists`
- 이 필터들이 `JPAExpressions.exists()` 서브쿼리인 이유: 배송·구매 상태와 재고·소싱벤더는 **라인아이템** 소속인데 결과 단위는 **주문**이다. 조인으로 풀면 주문이 라인아이템 수만큼 중복되어 페이징이 깨진다.
- `stockStatusExists`는 enum 자체가 아니라 `stringValue()`로 비교한다(조건이 문자열 리스트로 들어옴).

## client/cafe24/

### `Cafe24MarketClient.publish(Product, MarketPublishContext)`
- **유령 상품 함정.** 종전 구현은 진열 분류가 없으면 `log.warn`만 남기고 그대로 등록했다 → 어느 진열에도 걸리지 않아 **고객이 볼 수 없는 상품**이 조용히 생겼는데 호출자에겐 등록 성공으로 보고됐다. 지금은 컨텍스트에 분류가 없으면 `Cafe24CategoryResolver`로 자동 매칭을 시도하고, 그래도 못 구하면 **등록을 거부**한다.
- `display="T"` / `selling="T"`를 켜지 않으면 등록만 되고 쇼핑몰에 보이지 않는다.
- Cafe24는 **미지원 필드를 보내면 422로 거절**한다 — 확신 있는 필드만 채울 것.

### `Cafe24MarketClient.resolveCategoryOrThrow`
- 리졸버는 이름 매칭 실패 시 **가장 낮은 번호의 분류로 폴백**하며 `isResolved()`는 여전히 true, `confident()`만 false다. 최저 번호는 "전체상품" 같은 포괄적 루트 분류일 가능성이 높다 — 이걸 성공으로 치면 "카테고리를 구할 수 없으면 등록 거부" 사용자 결정이 무력화된다. **`isResolved() && confident()` 둘 다일 때만 성공**으로 인정한다. 같은 신호를 소싱 초안 경로(`MarketDraftBuilder`)도 차단 사유로 다룬다.

### `Cafe24MarketClient.syncPriceAndStock` / `deleteFromMarket`
- `supply_quantity`는 **항상 ≥1**로 보낸다(품절은 `selling="F"`로 표현).
- `selling` 분기는 라이브 검증이 아직 안 된 신규 경로.
- 두 메서드 모두 **실패 시 예외를 전파**한다 — 상위 오케스트레이터가 '실패 마켓'으로 수집(best-effort)한다. 삼키면 안 된다.
- `deleteFromMarket`의 `marketItemId`는 Cafe24 **product_no**다.

### `Cafe24OAuthTokenClient.exchange` 파라미터 매핑 (자격증명 필드가 이름과 어긋난다)
- `mallId` ← `MarketCredential.clientId`
- `clientId` ← `MarketCredential.accessKey` (Basic auth 사용자)
- `clientSecret` ← `MarketCredential.secretKey`
- `formPayload` = `grant_type=...` x-www-form-urlencoded 본문

### `Cafe24TokenManager`
- `CAFE24_TOKEN_LOCK_KEY`는 **모든 프로세스 공통 상수**여야 advisory lock이 성립한다.
- `init()`: **startup 강제 refresh는 폐지됐다** — 불필요한 refresh_token 회전이 2 JVM 경쟁을 유발했다.
- `getValidAccessToken()`: 락 획득 **후 재조회(double-check)** 가 핵심이다. 다른 프로세스가 이미 갱신했으면 HTTP를 생략한다. 이 double-check를 제거하면 스레드 수만큼 exchange가 발생한다 — `Cafe24TokenManagerConcurrencyTest`가 이 계약을 고정한다.
- `refreshProactively()` (D-103): 트래픽과 독립적으로 리프레시 토큰을 **선제 회전**해 2주 시한 만료를 막는다. access token 유효 여부와 무관하게 refresh를 강제한다. 과거 startup 강제 refresh는 2 JVM 경쟁 때문에 폐지됐으나 **현재 단일 JVM + advisory lock 하에서는 안전**하다. 실패는 삼켜 호출 스케줄러를 보호한다.
- `persist()`: **Cafe24가 refresh_token을 생략(null)해서 응답할 수 있다** — 그때 기존 값을 보존해야 한다. null로 덮어쓰면 재인증행.

### `Cafe24OrderApiClient`
- 주문 조회에는 **`mall.read_order` scope**가 필요하다 — 토큰 재발급 시 scope에 포함돼야 한다.
- D-151: **이미 배송건이 있는 주문은 신규 등록(POST)이 422로 거부된다** — 그 배송건을 수정(PUT)해야 한다.
- 배송상태 처리 API(`PUT /admin/orders`): **쓰기는 `process_status` 문자열**을 쓴다(읽기의 `order_status` N코드와 별개). `prepare`=배송준비중(발주확인), `prepareproduct`=상품준비중, `hold`=배송보류, `unhold`=배송보류해제. 취소완료는 별도 API 필요(D-091 후속 미검증).
- D-091 발주확인: Cafe24 스펙은 **경로에 id 없는** `PUT /admin/orders`에 `requests` 배열로 `{order_id, process_status}` 전송. 라인아이템 단위(`order_item_code`)는 sbshop이 품주코드를 미보존하므로 생략 → 주문 전체에 적용된다.
- 쿼리 값의 공백은 `+`가 아니라 **`%20`**으로 보낸다(일부 서버가 `+`를 공백으로 해석하지 않음).

### `Cafe24RestClient.enrich`
- D-152: 종전에는 GET에만 적용돼 POST/PUT/DELETE 실패가 "Cafe24 API POST 호출 실패"로만 남았다. 그 탓에 **G마켓 2건의 실제 사유(422 You cannot change to that order state)가 한 달간 불명**이었다 — 액션로그·화면은 최상위 메시지만 보기 때문이다. **네 동사 모두 같은 규율**을 따라야 한다. 본문 스니펫 길이 제한은 시크릿 유출 대비.

### `Cafe24CategoryResolver`
- 자사몰 분류번호는 쇼핑몰마다 임의로 만든 값이라 외부에서 알 수 없다 → `GET /admin/categories`로 실제 목록을 한 번 읽어 캐시하고 **이름으로 매칭**한다.
- 매칭은 **세부 분류부터** 맞춰야 한다 — 대분류로 먼저 맞추면 항상 "전체상품"류에 걸린다.
- 폴백은 **번호 오름차순 최저값**으로 고정(폴백이 항상 같은 분류가 되도록), `confident=false` 표시.
- 설정(`market.cafe24.default-category-no`)으로 고정하면 조회하지 않는다.

## client/cloudflare/

### `R2Config.s3Client` / `R2ImageStorageClient`
- **D-020: `@Lazy` 필수.** R2 자격증명이 없으면(운영 미설정) 즉시 생성 시 `AwsBasicCredentials`가 blank로 실패해 **스프링 컨텍스트 기동 자체를 막는다.** 실제 사용 시점(이미지 업로드)까지 생성을 지연한다. 업로드를 호출하지 않는 다른 기능은 기동·동작에 영향받지 않고, 업로드를 실제 호출하면 조용한 no-op이 아니라 명확한 예외로 실패한다(`R2ImageStorageClientBlankCredentialsTest`가 고정).

### `ImageDownloadService`
- `ImageDownloadClient`의 단일 구현체(D-004 통합). **크롤링 대상 사이트의 Cloudflare 봇 차단을 우회해야 하므로 브라우저 User-Agent 헤더를 실어 OkHttp로 내려받는다** — UA를 빼면 전건 실패한다(`ImageDownloadServiceCharacterizationTest`가 고정).
- F-PROD-16(D-092) `downloadAndConvertWithFailures`: 개별 URL 실패를 로그만 남기고 **드롭하지 않고** 실패 항목(URL·사유)으로 수집해 성공 파일과 함께 반환한다.

## client/coupang/

### `CoupangMarketClient.publish`
- 컨텍스트가 비어 있으면 카테고리를 자동 예측하고 태그를 규칙 생성한다. 컨텍스트가 있으면 **사용자가 검수한 값이 이긴다** — 검수 화면에서 고친 카테고리·판매가·키워드가 반영되지 않으면 검수 자체가 무의미하다.

### `CoupangMarketClient.syncPriceAndStock`
- 쿠팡은 **vendorItemId 단위 전용 엔드포인트**로 가격/재고/판매상태를 반영한다(저장된 rawData 불필요). price/quantity는 **경로 파라미터**, 바디 없음(HMAC는 method+path에 서명).
- **411 함정: JDK HttpClient가 무바디 PUT에 Content-Length를 안 보내 Akamai가 411을 반환한다** → Coupang이 무시하는 **빈 JSON 객체 `{}`를 바디로 보내 Content-Length를 강제**한다. 이 관습을 지우면 전건 411.
- quantity는 항상 ≥1. 판매상태 분기는 라이브 검증 필요한 신규 경로.
- 자격증명 검증은 `CoupangRestClient`가 DB 우선(env 폴백)으로 수행 — 미설정 시 명확한 예외 전파.

### `CoupangMarketClient.deleteFromMarket` (F-PROD-27/28)
- 리스팅 삭제는 `DELETE seller-products/{sellerProductId}`. `marketItemId`는 publish가 반환·저장하는 **sellerProductId**(상품 단위 안정 식별자, `extractMarketItem`·`syncImagesAndHtml`이 쓰는 것과 동일)다. **vendorItemId는 가격/재고/판매상태 전용이라 상품 삭제에 쓰지 않는다.** 주문이력 등으로 하드삭제가 거부되면 REST 오류가 예외로 표면화되고 오케스트레이터가 best-effort로 수집한다.

### `CoupangMarketClient.fetchProductId` / `fetchLinkIdentifier`
- 백필용: `sourceIdentifier(=sellerProductId)`로 **공개 링크식별자 `productId`**를 `seller-products` GET에서 조회한다(상품페이지 링크용). 없으면 `Optional.empty()` — throw 금지(best-effort).
- 응답 형태: `{ code, message, data: { sellerProductId, productId, items:[...], ... } }`.

### `CoupangMarketClient.syncImagesAndHtml`
- 저장된 `rawData`가 `{}`(가격/재고만 있고 items 없음)인 경우가 잦다 → **items가 없으면 전체 상품 페이로드를 GET으로 다시 받아** 작업 대상 rawData로 쓴다.
- **D-092 경로 함정:** 쿠팡 상품수정(승인필요)은 **id 없는 base에 PUT**(sellerProductId는 바디에). publish POST 경로와 동일하다. **id 포함 경로 `.../seller-products/{id}`는 GET/DELETE 전용 → PUT 시 404 PRECONDITION_FAILED.**
- **`requested=true`를 넣어야** 저장 후 자동 판매승인요청까지 진행된다(문서: false면 임시저장에 머묾).
- 별도 `/approvals`(상품 승인 요청) API는 **임시저장 상품 전용**이라 승인상품 편집엔 부적합 → 제거됨. 편집 경로에서 호출하면 안 된다.

### `CoupangRestClient`
- **자격증명 단일 소스**: 사용자가 Settings에서 관리하는 DB(`sb_market_credential`) 우선, 비어 있으면 env var(`CoupangProperties`) 폴백(기존 배포 호환).
- **HMAC 서명 함정**: 검증된 주문 클라이언트와 **동일한 UTC 서명**(`yyMMdd'T'HHmmss'Z'`)을 써야 한다. signed-date는 CEA Authorization에 내장돼 **별도 헤더가 불필요**하다. 과거의 `generateSignature`(KST·T/Z 없음) + 별도 signed-date 헤더 조합은 **"HMAC format is invalid"**를 유발했다.

### `CoupangCategoryResolver`
- `CoupangCategoryPredictor`는 `Product` 엔티티를 받아 신규 등록 경로에서만 쓸 수 있다. 초안 단계에는 아직 `Product`가 없으므로 **문자열로 받는 포트를 따로** 둔 것 — 중복이 아니다.
- 안전 카테고리 화이트리스트(=해외직구 판매 가능 확인된 목록) 밖으로 예측되면 폴백 카테고리를 쓰되 `confident=false`로 표시해 사람이 확인하게 한다 — **카테고리가 틀리면 등록은 되지만 노출이 안 되거나 심사에서 반려된다.**

### `CoupangInvoiceResponse`
- **항목 결과가 없으면 봉투 성공으로 간주**한다. 봉투(`code=200`)만 보던 종전 판정은 항목 거부(`succeed=false`)를 성공으로 오인했다(`CoupangInvoiceResponseTest`가 고정).

### `CoupangOrderApiClient`
- Rate limit 보호를 위한 호출 간 지연이 들어 있다.
- **D-041: HTTP 오류(403 등)를 삼켜 "성공 0건"으로 위장하지 않고 상태코드를 담아 전파**한다 → 어댑터가 전량 실패를 감지해 SYNC_FAILED로 이어지게 한다.
- `fetchReturnRequests` (D-097): v4 `returnRequests`, `searchType=timeFrame`, `createdAt` 기준 [from,to]. **구간 폭 제한(≈7일)** 이 있어 7일 창으로 분할하고 `nextToken`으로 페이지를 순회한다. status는 미지정(전 상태) — 어댑터가 `RETURNS_COMPLETED`만 필터링한다.
- **`URI.create`로 그대로 전송한다(템플릿 인코딩 회피)** — `createdAt`의 콜론이 서명 대상 쿼리와 **글자 그대로 일치**해야 HMAC가 맞는다.

### `CoupangProductPayload`
- 판매자 계정 고정값(출고지 `outboundShippingPlaceCode`·반품지 `returnCenterCode`·연락처)은 원래 이 클래스에 하드코딩돼 있었고, `MarketRegistrationDefaults`로 옮기면서 **값은 동일하게 유지**해 기존 등록 동작을 바꾸지 않았다. 내부 기본값 상수는 설정을 주입하지 않는 기존 호출부 호환용이다.
- **구매대행(`AGENT_BUY`)** 표기 — 해외 구매대행 상품임을 쿠팡에 명시해야 한다.

## client/customs/

### `GsiExpressScraperAdapter`
- 요청 페이로드 `chk_data`의 행 포맷: **`이름/통관고유부호/핸드폰번호/우편번호`**.
- **수취인명으로 검사하되, 주문자명이 다르면 별도 행으로 추가**한다 — 둘 중 하나라도 통관되면 VALID.
- 응답 행 매칭은 **이름과 통관고유부호(PCCC)를 모두** 비교해야 특정 주문을 정확히 식별한다.
- 에러 메시지 패턴 우선순위: **납세의무자명/개인통관고유부호(1순위) > 전화번호(2순위) > 우편번호(3순위)**. 기타 불일치 에러는 통관번호 불일치로 처리.
- 누적 규칙: 새 상태의 **우선순위가 더 높을 때만 갱신**한다. 행 누적 우선순위 `VALID(3) > VALID_PHONE_MISMATCH(2) > INVALID(1) > PENDING(0)`, 최종 상태 우선순위 `VALID(4) > INVALID_PHONE(3) > INVALID_ZIPCODE(2) > INVALID_PCCC(1) > PENDING(0)`.

### `MfdsBannedIngredientClient`
- 공공데이터포털(data.go.kr 15132686)의 같은 데이터는 **서비스키 발급이 필요**한 반면, 식품안전나라 포털이 화면에서 쓰는 이 JSON 엔드포인트는 **인증 없이** 전량을 준다(2026-07 실측 314건). **자격증명 없이도 통관 게이트가 도는 것이 중요**해 이쪽을 1차 원천으로 쓴다.
- 응답 필드: `raw_irdnt_nm`(한글 원료명), `raw_irdnt_eng_nm`(영문명), `appn_rels_dvs`(**Y=지정(차단중) / N=해제**), `appn_dt`/`appn_rsn`(지정일·사유), `rels_dt`/`rels_rsn`(해제일·사유).
- **원문 오타 그대로: 성공 응답의 `resultStat` 값이 `"seccess"`다.** 오타를 고치면 전건 실패한다.
- 전량이 300여 건이라 한 번에 받는다(상한을 크게 잡음).
- **해제(N)인데 해제일이 없으면** 날짜 비교로 "차단중"이 되어버린다 → **과거 날짜로 고정**해 확실히 해제 처리한다.
- 이 원천은 기타명칭(별칭)을 주지 않는다 — 별칭 보강은 `IngredientAliasSeed` 담당(단, 서베이 §① 기준 현재 미참조 데드 후보).

## client/demand/

### `NaverKeywordToolClient`
- 인증: `X-Signature = Base64(HmacSHA256(secretKey, timestamp + "." + method + "." + path))`. **서명 대상 경로에 쿼리스트링을 포함하면 401이 난다 — 경로만 넣어야 한다.**
- **키워드도구는 공백을 허용하지 않는다** — 붙여서 보내야 매칭된다.
- **검색량이 10회 미만이면 API가 숫자 대신 `"< 10"` 문자열**을 준다. `asInt()`는 이걸 0으로 만들어 "소량이라도 검색되는 키워드"를 검색량 0으로 죽인다 — 전용 파싱이 필요하다.
- 자격증명이 없으면 `isEnabled()`가 false이고 스코어링이 검색량 가중치를 빼고 정규화한다(키 없이도 파이프라인은 돈다).

### `NaverShoppingSearchClient`
- **쿠팡·네이버쇼핑 검색 페이지는 스크래핑이 막혀 있다(각각 403/418, StealthyFetcher도 실패)** — 국내 수요 신호는 이 공식 API가 **유일한 경로**다.
- `total`=경쟁강도, 최저 `lprice`=국내 최저가. 표본 수는 40(API 상한 100이지만 40이면 충분하고 빠르다).
- **0원·비정상 저가는 표본에서 뺀다** — 옵션 미끼상품이 최저가를 왜곡한다.
- 신호 하나가 없다고 후보를 버리지 않는다 — 로그만 남기고 빈 값으로 진행.

## client/elevenst/

### `ElevenstMarketClient.publish`
- **가짜 ID 금지.** 종전 구현은 등록 실패에도 `"11ST-{sbCode}"` 가짜 ID를 만들어 SYNCED로 저장했고, 존재하지 않는 상품에 대한 이후 가격·재고 동기화가 매번 실패했다. **실패는 실패로 표면화**한다(SP-A 원칙).
- 전시 카테고리(`dispCtgrNo`): 검수된 값이 있으면 그것을 쓰고, **없으면 등록 자체를 거부**한다. 빈 `dispCtgrNo`를 보내면 11번가가 카테고리 오류로 거절하므로 태그를 생략하고 진행해도 결국 마켓이 거절할 뿐 — 여기서 먼저 실패를 표면화한다. **11번가는 `Cafe24CategoryResolver` 같은 자동 카테고리 해석기가 없어서** 자동 해석 시도 없이 곧장 거부한다.
- **출고지·반품지는 주소 시퀀스코드 `addrSeqOut`/`addrSeqIn`이다.** `dlvCnAreaCd`(배송가능지역 01=전국)와 혼동하면 값이 있어도 **"출고지 주소를 확인해주세요"로 거절**된다 — D-092에서 실제로 부딪힌 벽. 판매자 실계정 값은 출고지 5(미국), 반품지 3(국내).
- **해외구매대행 표기 누락 시** 국내 배송 상품으로 오인돼 클레임 사유가 된다.
- D-092에서 확인된 "등록 후 필수 승격" 필드들은 빈값이면 상품수정이 거부되므로 기본값으로 채운다: 판매방식 고정가, 일반배송상품, 원산지=상세설명 참조, 미성년자 구매가능, 과세상품, 업체배송, 배송가능지역 전국, 배송방법 택배, 발송택배사 CJ대한통운, AS안내(필수·빈값 불가), 반품/교환 안내(필수), 배송비 무료, 묶음배송 불가, 결제방법 선결제, 제주/도서산간 추가배송비. (전체 덮어쓰기 방식의 한계 — 견고한 해법은 전체 상품상세조회 round-trip.)
- 상품정보제공고시가 비면 **전자상거래법 위반이자 11번가 심사 반려 사유**라, 값이 없으면 "상세설명 참조"로 채운다.

### `ElevenstMarketClient.syncPriceAndStock`
- **11번가는 수량 개념이 없다** — 판매상태(soldOut 기준)로만 처리한다.

### `ElevenstMarketClient.deleteFromMarket`
- `DELETE /rest/prodservices/product/{prdNo}` (marketItemId = elevenstId = prdNo). 주문이력 등으로 거부되면 오류 응답이 예외로 표면화된다(best-effort 오케스트레이터가 수집).

### `ElevenstMarketClient.syncImagesAndHtml` / `injectPromotedRequiredFields`
- **D-092: 11번가 상품수정은 전체 XML 덮어쓰기**라 옵션·카테고리·인증·고시를 보존해야 한다 → **신규상품조회 `/rest/prodmarketservice/prodmarket/{prdNo}`로 "현재 전체 전문"을 GET**한 뒤 이미지/상세HTML만 정규식 치환하고 승격 필드를 주입해 PUT한다. (`buildProductXml` 재구성·`productinfo -997` 경로는 폐기.)
- 상세HTML의 **esmplus `http` → `https`** 치환은 SK Planet 방화벽 409 방지용이다.
- **조회 응답의 메타태그(`message`/`validateMsg`/`nResult`)는 수정 PUT 파서 에러를 유발하므로 반드시 제거**한다.
- 인코딩은 **EUC-KR 강제**.
- 원산지는 **기존 태그 제거 후 해외(미국) 재주입** — 태그명이 유사하므로 **긴 것부터 제거**해야 한다.
- 성공 판정: `<resultCode>200</resultCode>`(일반) 또는 `210`(신규). 그 외는 실패.

### `ElevenstMarketRestClient` vs `ElevenstOrderRestClient` (이름이 비슷한 두 클래스)
- `client/ElevenstMarketRestClient` = **상품 API**(GET/POST/PUT, 원시 문자열 XML, `ElevenstProperties` 키 주입).
- `ElevenstOrderRestClient`(루트) = **주문 API**(GET 전용, XML/EUC-KR 응답 처리 포함).
- 자격증명 단일 소스: DB(`sb_market_credential` ELEVEN_STREET.accessKey) 우선, 없으면 env(`ElevenstProperties`) 폴백.

### `ElevenstOrderRestClient.parse*`
- EUC-KR 바이트를 디코딩 → **중복 태그 제거**(11번가 XML 응답 특성: 동일 태그가 2번 나온다. 예 `<ordNo>123</ordNo><ordNo>123</ordNo>` → 1개) → **XML 선언의 인코딩을 UTF-8로 변경**(실제 바이트는 UTF-8로 변환됨) 순서로 처리한다. 이 순서를 바꾸면 파싱이 깨진다.

### `ElevenstOrderApiClient`
- `orderlist`는 **`orderlistalladdr`(주소 포함 상세)와 다른 API**다. 응답이 **ordPrdSeq별 행**으로 온다.
- **D-043: 조회 실패(HTTP/네트워크/파싱)를 삼켜 빈 반환하지 말고 전파**한다 → 어댑터가 전량 실패를 감지한다.
- `result_code` 확인 후 `ns2:orders > ns2:order` 구조에서 주문을 추출한다.

## client/llm/

### `OpenCodeZenTextClient`
- `POST https://opencode.ai/zen/v1/chat/completions` (OpenAI 호환). **유료 API를 쓰지 않는다는 제약**에 맞춰 무료 모델만 쓴다.
- 모델 선택은 실측 기반(동일 프롬프트, 한글 상품명 + 키워드 10개 JSON 강제):
  - `nemotron-3-ultra-free` — 완전한 JSON, completion 185토큰 → **주력**
  - `ling-3.0-flash-free` — 완전한 JSON, 1,108토큰(추론 747) → **폴백**
  - `deepseek-v4-flash-free` — 빈 content → 미사용
  - `big-pickle` — 2,000토큰 초과·미완성 → 미사용
- 앞 모델이 실패하면 다음으로 넘어가고, 전부 실패하면 빈 `Optional` — 호출측이 규칙 기반으로 폴백해 파이프라인이 멈추지 않는다.
- **max tokens를 넉넉히** 준다 — 무료 모델은 추론 토큰을 많이 써서 잘리면 JSON이 깨진다.
- **User-Agent를 명시**한다. Zen 앞단이 일부 클라이언트 UA를 403으로 막는다(python-urllib 실측). JDK 기본 UA는 현재 통과하지만 **기본값이 바뀌면 조용히 전건 실패**로 돌아선다.
- 일부 모델은 본문을 **`reasoning_content`로만** 내보낸다 — `content`가 비면 거기도 봐야 한다.
- 무료 모델은 코드펜스(```json)나 앞뒤 설명을 붙이는 일이 잦아 문자열 전체 파싱이 실패한다 → **첫 `{`부터 마지막 `}`까지 잘라** 파싱한다.

## client/smartstore/

### `SmartstoreMarketClient.publish` / `autoContext` / `mergeWithAuto`
- 컨텍스트 없는 기존 경로(수동 등록)도 **같은 완전한 payload**를 쓴다. 종전의 6필드 payload로는 커머스API 필수필드를 못 채워 **어차피 등록이 안 됐다.**
- **검수 컨텍스트가 이긴다.** 비어 있는 칸만 `autoContext` 값으로 채운다. 부분 컨텍스트(예: 판매가만 담긴 등록 경로)로 들어오면 빈 칸을 채우지 않는 한 카테고리·주소록·A/S 같은 필수필드가 비어 등록이 거절된다.
- `mergeWithAuto`의 조기반환 가드(`hasCategory() && !extraFields().isEmpty()`)가 옳은 이유는 **`MarketRequiredFieldValidator.validateSmartstore`가 카테고리 + extraFields 7개 키를 모두 요구**해 `MarketDraft.isValid`를 통과시키기 때문이다 — **정확성이 다른 파일의 불변식에 기대고 있다.** 그 필수필드 목록이 줄어들면 검수 경로에서도 리졸버가 호출돼 검수값을 덮어쓸 수 있다(`SmartstoreMarketClientPublishMergeTest`가 고정).
- 응답의 `originProductNo`뿐 아니라 **`smartstoreChannelProductNo`도 함께 저장**한다 — 상품 그리드의 스토어 링크가 채널 상품번호를 쓰는데, 없으면 나중에 전체 페이지 스캔으로 백필해야 한다(`MarketLinkIdentifierBackfillService`). 스키마 버전에 따라 `originProduct` 아래에 오는 경우가 있다.

### `SmartstoreMarketClient.syncPriceAndStock`
- **가격표시제(2026-04-29 필수)**: GET 스냅샷에 `unitCapacity`가 없으면 **400**. 상품 용량·단위로 채운다. 상품 없이 호출되는 경로(단건 수정 등)는 `unitCapacity` 없이 기존 동작.
- **스마트스토어는 재고 0이면 API가 자동으로 OUTOFSTOCK(품절) 처리하고 `statusType`은 무시**한다. `OUTOFSTOCK`은 수정 API에서 직접 지정 불가(400)라 **재고 0으로 품절을 표현**한다.
- 판매중 복귀 시 **마켓 잠금상태(SUSPENSION/PROHIBITION)는 보존**하고 그 외에만 SALE로 되돌린다.

### `SmartstoreMarketClient.deleteFromMarket`
- `marketItemId`는 등록 시 저장한 **originProductNo**(publish가 반환한 identifiers의 `originProductNo`). 원상품 삭제 API `DELETE /v2/products/origin-products/{no}` 호출. 하드삭제가 거부되면 예외 표면화(best-effort 수집 대상).

### `SmartstoreMarketClient.removeImmediateDiscount` (D-096)
- **판매자 즉시할인(`customerBenefit.immediateDiscountPolicy`)만 제거하고 다른 혜택(적립 등)은 보존**한다. origin-product를 GET → 즉시할인 정책 확인 → (dryRun 아니면) **그 키만 제거 후 PUT**. 저수수료 마켓에 마켓별 가격을 이미 낮게 산정하므로 즉시할인이 겹치면 이중할인 손해가 난다.

### `SmartstoreMarketClient.syncImagesAndHtml`
- **D-092: 네이버는 외부 URL(R2)을 대표이미지로 거부한다("올바른 이미지 파일이 아닙니다")** → 네이버 이미지 서버에 직접 업로드하고 반환된 네이버 URL로 등록한다. 실패 시 외부 URL(hostedImages)로 폴백. 확장자가 없으면 `.jpg` 힌트를 붙여야 네이버 이미지 검증을 통과한다.
- **커머스API 이미지 스키마: `originProduct.images.representativeImage.url`(오브젝트), `originProduct.images.optionalImages = [{url}, ...]`.** 최상위 문자열 `representativeImage`/`optionalImages`는 **Naver가 조용히 무시**하므로 반드시 `images` 하위 오브젝트여야 한다. 기존 `images` 오브젝트의 다른 필드는 보존하고 representative/optional만 덮어쓴다. 단일 이미지일 때는 이전에 남아 있던 `optionalImages`를 제거해 대표만 남긴다.
- **D-092: 해외 상품 관부가세 필수** — `originProduct.detailAttribute.customsTaxType` 미설정 시 수정이 거부되므로 `INCLUDED`로 채운다.
- **D-092: 수동 이스케이프 제거** — Map 값은 Jackson이 직렬화 시 이스케이프하므로 직접 이스케이프하면 **이중 이스케이프(HTML 손상)**가 된다.
- `currentRawData` 미러도 스키마와 일관되게 `{url}` 오브젝트로 저장한다.

### `SmartstoreMarketClient.scanAllChannelProductNos` / `fetchChannelProductNo`
- **`/v1/products/search`는 `originProductNos` 필터를 무시하고 전체를 반환한다(라이브 확인됨)** → 특정 번호 필터 대신 **전체 페이지를 훑는다.** total 수천 건이라도 `size=500`이면 몇 페이지로 끝나 429 없이 완주한다. 페이지 간 지연은 `throttleMs`로 제어하고 무한루프 방지 안전 상한이 있다.
- `channelProducts` 배열에서 **STOREFARM 채널**의 `channelProductNo`를 고른다(없으면 첫 채널, 그마저 없으면 null).

### `SmartstoreMarketClient.unitCapacity` (가격표시제)
- 2026-04-29~ 필수. 용량·단위가 있으면 `unitPriceYn=true` + `totalCapacityValue`/`indicationUnit`/`unitCapacity`(기준), 없으면 `unitPriceYn=false`(필수필드 누락 400만 회피). 단위가격 자체는 네이버가 판매가/용량으로 산정한다.
- `MeasureUnit` → 네이버 `indicationUnit` 허용값(g·kg·ml·L·개·정·캡슐·포 …). **MG/OZ/LB(무게 환산 필요)·UNKNOWN 등은 값 오표시 방지를 위해 미표시(false)** 로 둔다.

### `SmartstoreRestClient`
- 자격증명 단일 소스: DB(`sb_market_credential` SMART_STORE) 우선, 없으면 env(`SmartstoreProperties`) 폴백.
- **토큰 만료 시각을 반드시 추적한다.** Naver 커머스 토큰은 ~3시간 후 만료되는데, 과거엔 `accessToken`이 null일 때만 재발급해 **만료된 토큰을 계속 재사용 → 재시작 전까지 상품 API가 401 GW.AUTHN**을 반환했다. `expires_in(초) - 60초 버퍼`로 만료 시각을 계산하고, 응답에 없으면 3시간 기본.
- **401(GW.AUTHN)이면 토큰을 무효화하고 1회 재발급 재시도**한다.
- 토큰 발급의 **timestamp는 반드시 epoch 밀리초(13자리)**. epoch 초(10자리)를 보내면 Naver Commerce가 유효창 밖으로 판정해 400 "timestamp 유효시간 만료"로 실패한다(`SmartstoreRestClientTest`가 고정).
- **D-092 이미지 업로드**: `POST /v1/product-images/upload` (multipart, field=`imageFiles`), 응답 `{"images":[{"url":...}]}`.

### `SmartstoreAddressBookResolver`
- `originProduct.deliveryInfo.claimDeliveryInfo`는 `shippingAddressId`(출고지)/`returnAddressId`(반품지)를 요구하는데 이 값은 **판매자 계정 주소록의 일련번호라 계정마다 다르다.** 주소록 API로 한 번 읽어 캐시한다(주소록은 거의 바뀌지 않는다).
- 조회 경로가 커머스API 버전에 따라 둘 중 하나 — **순서대로 시도**한다: `GET /v1/seller/addressbooks-for-page?page=1&size=100` → `GET /v1/seller/addressbooks`.
- **주소 유형 코드 표기가 흔들린다(`RELEASE`/`REFUND`/`REFUND_OR_EXCHANGE`)** → 정확 일치가 아니라 **접두 매칭**으로 고른다. 못 찾으면 대표 주소나 첫 항목으로 폴백.
- **둘 다 못 찾았으면 캐시하지 않는다** — 일시적 API 장애를 영구 결측으로 굳히면 안 된다.
- **반품지가 따로 없으면 출고지를 쓴다**(국내 반품지를 별도로 두지 않는 계정이 있다).
- 설정으로 직접 지정하면 조회하지 않는다(자동 조회가 틀렸을 때의 탈출구).

### `SmartstoreCategoryResolver`
- `GET /v1/categories?categoryName=…`로 검색해 **리프만** 고른다. `leafCategoryId`는 등록 필수필드이고 **리프가 아닌 카테고리를 넣으면 등록이 거절**된다.
- 검색어는 카테고리 힌트의 **마지막 마디**를 우선 쓴다 — 대분류로 검색하면 리프가 아닌 결과만 잔뜩 나온다. `"비타민/미네랄"`처럼 슬래시가 있으면 앞부분이 더 잘 걸린다. 후보 순서: 힌트 마지막 마디 → 힌트 전체 마디 → 상품명 첫 토큰.
- **`last=true` 필터가 무시되는 경우가 있어 응답에서도 리프 여부를 다시 확인**한다.
- 실패 시 설정된 기본 카테고리로 폴백하되 `confident=false`로 표시.

### `SmartstoreProductPayloadBuilder`
- 기존 구현은 `originProduct`에 6필드(productName·salePrice·stockQuantity·productCode·detailContent·images)만 담았다. 공식 스펙상 **`statusType`·`detailAttribute`와 `smartstoreChannelProduct` 전체가 REQUIRED**이고 `leafCategoryId`·`stockQuantity`는 등록 시 필수라 **그 payload로는 애초에 등록이 성립하지 않았다.**
- `smartstoreChannelProduct` 블록이 없으면 **채널 상품이 만들어지지 않아 스토어에 노출되지 않는다.**
- 추가 이미지는 **최대 9장**.
- 재고 실수량을 추적하지 않는다(판매중/품절 이분법) — 기존 `Product` 규약과 동일.
- 구매대행은 주문 후 해외 배송이라 **반품 안내를 별도로** 남긴다. 해외 배송이라 **출고까지 여유**를 둔다(쿠팡 `outboundShippingTimeDay=3`과 동일 가정).
- **별도 인증 대상이 아님을 명시하지 않으면 심사에서 반려**된다.
- 상품정보제공고시: 값이 비어 있으면 "상세설명 참조"로 채운다 — **빈 문자열을 보내면 마켓이 거절**한다. 고시 유형 코드는 건강기능식품/가공식품 상수로 고정.
- 계정 종속값(주소록 ID·A/S 전화)은 `MarketPublishContext.extraFields()`로 들어온다. 비어 있으면 초안 검증에서 걸러졌어야 하지만 **방어적으로 여기서도 확인해 빈 값으로 마켓에 보내지 않는다** — 400 응답보다 우리 예외 메시지가 원인을 잘 설명한다.
- **주소록 ID는 숫자로 보내야 한다**(문자열이면 커머스API가 거절).

### `SmartStoreDispatchResult` (D-145)
- **스마트스토어 발송 API는 HTTP 200 본문 안에 실패를 담아 준다:**
  ```
  {"data":{"successProductOrderIds":[],
           "failProductOrderInfos":[{"productOrderId":"…","code":"9999",
                                     "message":"주문상태 및 클레임상태를 확인하세요"}]}}
  ```
- 종전에는 **최상위 `code`만 검사**해 이 응답이 "전송 완료"로 기록됐다. 그 거짓 성공이 `trackingSentToMarket=true`까지 찍어 **마켓에 없는 송장을 있다고 표시하고 다음 동기화가 마켓 값으로 되돌렸다**(2026-08-07 실측: 교정 11:34 → 원복 11:38).
- **모르는 형태는 실패로 위조하지 않는다.** 판정 근거(성공/실패 목록·최상위 코드)가 응답에 없으면 **통과**시킨다 — 마켓이 응답 형태를 바꿨을 때 멀쩡한 전송을 실패로 만들지 않기 위해서다.
- 종전 형태(최상위 `code`가 채워진 오류 응답, 인증 실패 등)도 계속 지원한다.

### `SmartStoreOrderApiClient`
- **D-043: HTTP 오류를 삼켜 빈 반환("성공 0건")하지 말고 상태코드를 담아 전파**한다 → 어댑터가 전량 실패를 감지한다. 조회 실패(파싱/네트워크)도 마찬가지.
- 주문 조회는 **2단계**다: (Step 1) 변경된 주문 상태 목록 GET → (Step 2) 거기서 뽑은 `productOrderIds`로 상세 일괄 POST.
- **발송 처리 payload의 시각은 밀리초 3자리가 필수**다(`yyyy-MM-dd'T'HH:mm:ss.SSS+09:00`).
- **D-145**: 발송 응답은 `data.failProductOrderInfos`까지 검사한다(위 `SmartStoreDispatchResult` 참조).
- **D-150: 실패 사유를 메시지에 남긴다.** 래핑하며 버리면 상위의 **영구 거부 분류가 문구를 찾지 못해 마켓의 영구 거부가 일시 실패로 분류**된다 — 30분마다 같은 거부를 받아내고 수동수정 표시도 서지 않는다.
- 토큰 발급 서명: `BCrypt.hashpw(clientId + "_" + timestamp, secret)` → Base64. `timestamp`는 epoch **밀리초**.

## client/sourcing/

### `IherbScraperClient`
- 벤더 라우팅용(`StockCrawlerRouter`) — **iHerb가 기본 크롤러**다.
- 가격(costPrice) 우선순위: **`discountPriceAmount` > `listPriceAmount` > `price.discount` > `price.listPrice`**. 루트 레벨 필드를 우선하고 `price` 객체는 이전 버전 API 호환 폴백.
- 재고 수량이 응답에 없으면 **가용성 기반으로 충분한 수량을 추정**한다.
- **`null` 반환 = 크롤 결과 없음**(상품 ID 추출 실패·차단·응답 파싱 실패 등). 조용히 누락시키면 안 된다.
- URL 패턴: `/pr/{name}/{id}` — 이름과 ID 사이에 `/`가 있어야 한다.
- **iHerb 현행 API는 `partNumber` + `imageIndices`(정수 배열)로 cloudinary URL을 조합**한다. 구 필드 `imageGroups`/`mainImage`는 API 변경으로 제거되어 **항상 0장이었다.** partNumber는 `"-"` 앞 첫 segment. 최대 5장(메인1+추가4).
- `parseProductInfo`는 **단위테스트용으로 package-private**으로 열어 뒀다(`IherbScraperClientParseTest`).

### `ScraplingIherbClient`
- 기존 `IherbScraperClient`가 쓰는 `catalog.app.iherb.com` JSON API는 **Cloudflare 챌린지로 403**을 반환한다(2026-07 실측). 사이드카(Python Scrapling)의 브라우저 페처는 통과한다.
- **타임아웃이 크다**: 발굴은 (카테고리 수 × 페이지 수)만큼 브라우저 렌더를 돌린다 — 4카테고리 × 3페이지 = 12회 렌더 = 수 분. **스케줄러/비동기 경로에서만 호출**할 것. 타임아웃은 렌더 횟수에 비례해 산정한다(너무 짧으면 정상 크롤이 중단되고, 무제한이면 스레드를 붙잡는다).
- **HTTP/1.1 고정 필수**: 기본 HTTP/2 협상 시 uvicorn(HTTP/1.1 전용)에 **POST 본문이 유실돼 422**가 난다.
- 사이드카 자체가 죽었을 때 **후보 0건을 "인기 상품 없음"으로 오인하지 않도록** 사유를 올린다.

### `ScraplingSourcingClient` (Fortnum & Mason)
- F&M은 **JS 렌더링 + Cloudflare** 페이지라 JVM HttpClient로는 못 가져온다. 스크래퍼가 브라우저로 렌더해 가격(£)·재고·원가(원, 배대지 배송비 + 환율 반영)를 계산해 준다.
- **결과 분기(스크래퍼 status)**:
  - `ok` → 정상 재고/원가 반영
  - `not_found` → 링크 소멸(404): **품절 처리(가격 미변경)**, `sourceGone=true` 신호로 배치가 재고만 내린다
  - `blocked`/`error` → **예외를 던져 배치가 실패로 기록**(재고/가격 미변경). **Cloudflare 차단을 오품절로 만들지 않기 위한 규율.**
- **재고 판별 불가(`inStock` 누락/null)면 오품절 방지를 위해 스킵**(예외 → 배치 실패 기록).
- `costPrice`=상품원가(**묶음수량 곱 대상**), `shippingCost`=배송비(**주문당 1회 가산**).
- **HTTP/1.1 고정 필수**(위와 같은 uvicorn 422 이유).

## repository/order/

### `OrderLineItemRepositoryImpl.iherbWithPurchaseOrderNo` (공통 전제)
- **iHerb 소싱 + 구매주문번호 보유** — 이메일 검색의 공통 전제 조건.

### `OrderLineItemRepositoryImpl` — 실구매가 미기록 조건
- 배송상태를 **참조하지 않는다**. `0 이하도 미기록`으로 본다 — `EmailFetcherService`의 멱등 가드(`amount > 0`이면 스킵)와 **같은 경계**여야 한다.
- 기존 `findIherbItemsNeedingEmailProcessing`은 배송 큐 전용이라 배송이 끝난 주문이 확인메일 검색 대상에서 영구 제외됐다 — **실구매가 조회는 별도 조건이어야 한다.**
- 일부 조건 메서드는 **테스트 접근을 위해 package-private**이다.

### `OrderLineItemRepositoryImpl` — 발송메일 송장 큐 (D-144)
- 조건은 **"종결 전 상태면 전부"**다. 종전에는 `trackingSentToMarket`이 아닌 건만 담았는데, 그 플래그는 "마켓이 송장을 갖고 있다"는 뜻(D-129)이라 **마켓에서 유입된 가송장도 참으로 만든다.** 라이브에서 무관한 두 주문(쿠팡·스토어)에 같은 번호 `363092185283`이 붙어 있었고 그 번호는 메일함 어디에도 없었다. 큐에서 빠지면 **그 주문번호로 메일을 검색조차 하지 않으므로** 진짜 발송메일이 도착해도 영영 교정되지 않는다(실측 활성 16건 중 12건이 DB 송장 ≠ 발송메일 송장).
- **iHerb 주문번호가 있다 = iHerb 발송메일이 송장의 진실.** 마켓이 무엇을 갖고 있든 그것은 교정 대상이지 게이트가 아니다. 같은 송장이면 `EmailFetcherService.processIherbShipment`가 스킵하므로 재처리 비용도 없다.
- **종결(DELIVERED·CANCELED·RETURNED·EXCHANGED)은 담지 않는다** — 배송이 끝난 뒤의 송장 교정은 마켓이 받아주지 않고, 과거 기록을 바꾸는 일이라 사람의 판단이 필요하다.

## 테스트 — 회귀 가드의 의도 (테스트 이름만으로는 복원 불가한 것)

| 테스트 | 고정하는 계약 |
|---|---|
| `Cafe24MarketClientCategoryTest` | 저신뢰 폴백(`confident()==false`)도 "못 구했다"에 포함 — 고신뢰 결과만 통과. `Product.create()`가 조립하는 productName은 관심사가 아니라 브랜드만 검증하고 나머지는 `any()`. |
| `Cafe24OrderApiClientStatusTest` | `PUT /admin/orders`(경로에 id 없음), `body.requests[0]={order_id, process_status:"prepare"}` |
| `Cafe24RestClientErrorMessageTest` | D-152. **실제 HTTP 경로를 타야 의미가 있어 JDK 내장 서버로 422를 돌려준다** — 모킹으로 대체하면 가드가 무의미해진다. |
| `Cafe24TokenManagerConcurrencyTest` | 모든 스레드를 `CyclicBarrier`로 pre-lock 검사 통과 지점까지 모은 뒤 `ReentrantLock`으로 직렬화한다. 첫 refresh 이전에 N개 스레드 전부가 락 안에 있으므로 **재-refresh를 막는 것은 오직 '락 내부 double-check'뿐**이다. double-check가 제거되면 `exchangeCalls == 12`(스레드 수)를 관측해 실패한다. |
| `Cafe24TokenManagerFailFastTest` | D-075(CF24-2). 토큰이 없을 때 조용히 null을 반환하면 상위가 `"Bearer null"`로 호출해 401이 나고 원인이 "Cafe24 API 호출 실패"로 은폐된다 → **그 자리에서 재인증 필요 예외로 fail-fast.** |
| `Cafe24TokenManagerTest` | refresh_token 생략(null) 응답 시 **기존 RT가 유지**되어야 한다(null 덮어쓰기 금지). D-103 선제갱신은 access token이 유효해도 강제 refresh하고, refresh token이 없으면 예외 없이 리턴하며, 실패해도 예외가 전파되지 않아야 한다(스케줄러 보호). |
| `ImageDownloadServiceCharacterizationTest` | D-004 통합 전후 동작 불변. **브라우저 UA 전송이 이 구현체의 존재 이유**이므로 회귀 방지 대상으로 명시 고정. |
| `R2ImageStorageClientBlankCredentialsTest` | D-020. blank 자격증명에서 업로드는 **조용한 no-op이 아니라 예외**. `ObjectProvider`로 지연 해석하는 것이 스프링 `@Lazy` 결선과 동일 동작임을 흉내낸다. |
| `CoupangMarketClientFetchProductIdTest` | `productId` 부재·blank 입력·예외 시 **best-effort로 `Optional.empty()`(throw 금지)**. `objectMapper`는 `@Mock`이며 `readTree`를 실제 `ObjectMapper`로 위임(ImagesTest 패턴 미러). |
| `CoupangMarketClientImagesTest` | `marketItemId(=sellerProductId)`가 권위 경로 식별자. rawData에 items가 없으면 GET으로 전체 페이로드 선조회. **별도 승인요청 API는 임시저장 전용 → 편집 경로에선 호출하지 않는다.** |
| `CoupangMarketClientSoldOutTest` | `quantities` + `sales/stop`은 **항상** 호출된다. |
| `ElevenstMarketClientPublishTest` | 출고지·반품지가 **주소 시퀀스코드**로 나가는지가 핵심. 배송가능지역(`dlvCnAreaCd`)도 함께 나가지만 주소코드를 **대체하지 않는다.** 카테고리 없으면 즉시 거부, 가짜 ID 생성 금지. |
| `ElevenstMarketClientRepresentativeImageTest` | 신규상품조회 GET → 이미지/HTML 치환 + 승격 필드 주입 → 수정 PUT 라운드트립. 판매자 실계정 주소코드로 교체(기존 99/88 제거), 조회 메타태그 제거(파서 에러 방지). |
| `SmartstoreRestClientTest` | timestamp가 **13자리 epoch 밀리초**(>1e12)여야 한다. `BCrypt.hashpw`가 유효 salt를 요구해 실제 bcrypt salt를 secret으로 쓰고, `private final restClient` 필드를 `MockRestServiceServer` 바인딩 인스턴스로 **리플렉션 교체**한다. |
| `SmartstoreMarketClient*Test` 다수 | `categoryResolver`/`addressBookResolver`/`payloadBuilder`는 **신규 등록(publish) 전용 협력자** — 이 테스트들이 검증하는 경로에서는 호출되지 않아야 한다(`verifyNoInteractions` 의도). |
| `SmartstoreMarketClientFetchChannelTest` | `/v1/products/search` 요청·응답 스키마는 **라이브 검증 필요(리스크 최고 가정)**. search가 `originProductNos` 필터를 무시하므로 전체를 페이지로 훑고, page1 `last=false` → page2 `last=true`로 2회 요청. |
| `SmartstoreMarketClientPublishMergeTest` | 위 `mergeWithAuto` 항목 참조 — 정확성이 `MarketRequiredFieldValidator`의 불변식에 기대고 있어 병합 로직을 직접 고정한다. D-094 산정가(부분 컨텍스트 값)가 auto 값으로 덮이면 안 된다. |
| `SmartstoreMarketClientSoldOutTest` | 재고 0 → API 자동 품절. **수정 API에서 무효인 `OUTOFSTOCK`을 직접 지정하지 않는다.** |
| `IherbScraperClientParseTest` | 구 필드만 있으면 `partNumber`가 없으므로 `imageLinks`는 **비어야** 한다. |
| `OrderLineItemRepositoryImplShipmentQueueTest` | D-144 — 위 리포지토리 항목 참조. 교정 자체는 `EmailFetcherService.processIherbShipment`에 이미 있고 **큐에만 들어오면 된다.** |
| `OrderRepositoryImplDateFilterTest` | F-ORD-2 — 한쪽만 와도 그 경계는 적용. |
