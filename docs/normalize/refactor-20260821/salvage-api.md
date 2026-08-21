# salvage-api — api 모듈 주석 제거 전 보존 기록

대상: `backend/api/src/{main,test}`. 코드로 표현 불가한 "왜"(마켓 API 함정·멱등성·순서 제약·사용자 결정·계약)를 파일:메서드와 함께 보존한다.

---

## main/java/com/sbshop/agent/api/ApiApplication.java

- **클래스**: 2026-07-17 api·worker 두 JVM을 단일 앱으로 병합. worker가 담당하던 스케줄링을 이 앱이 인수했기 때문에 `@EnableScheduling`이 여기 붙는다. worker의 스케줄러·이메일 수집·내부 트리거 빈은 worker 라이브러리 모듈에서 `com.sbshop.agent` 전역 스캔(`scanBasePackages`)으로 로드된다 — 스캔 범위를 좁히면 worker 빈이 통째로 사라진다.

## main/java/com/sbshop/agent/api/config/AsyncConfig.java

- **클래스 (중요 — 삭제 금지 근거)**: 현재 빈이 하나도 없는 빈 껍데기다. `productBatchExecutor` 빈은 D-011로 core `AsyncConfig`에 이전됐다(소비자 `BatchPriceStockService`가 core에 있고 호출자가 api·worker 양쪽이라, 빈이 core에 있어야 두 컨텍스트 모두에서 한정자가 해소된다). 이 클래스를 남겨두는 이유는 D-009에서 부여한 빈 이름 `apiAsyncConfig`와 그 회귀 테스트(`AsyncConfigBeanNameConflictTest`)를 보존하기 위함이다. 정리 후보로 원장에 등재돼 있으나 core `AsyncConfig`와 simple name이 겹치는 상태 자체는 의도된 것.

## main/java/com/sbshop/agent/api/config/OrphanedBatchRecoveryRunner.java

- **클래스 / `recoverOnStartup()`** (F-BATCH-2): 배치는 api JVM에서 실행되고 배포가 api를 재시작하므로, 냉기동 시점엔 진행 중인 배치가 존재할 수 없다. 따라서 부팅 때 남아 있는 PENDING은 전부 이전(죽은) 실행의 고아이며, 방치하면 배치 요약이 영원히 미완료로 남는다. worker가 아니라 api에 두는 이유는 배치 실행 주체가 api이고 worker와의 부팅 경쟁을 피하기 위함. `ApplicationReadyEvent`로 부팅 1회만 실행하며 스케줄러/실시간 경로에서는 호출하지 않는다.

## main/java/com/sbshop/agent/api/controller/ActionLogController.java

- **`getActionLogs()` — 응답 형태 계약**: 응답은 평면 배열(JSON array)로 유지해야 한다. 프론트 `actionLogApi.getActionLogs`가 `res.data`를 배열로 직접 소비하므로 Spring `Page` 엔벌로프로 바꾸면 프론트가 깨진다.
- **`getActionLogs()` — F-MISC-1**: `page`(0-base)는 옵셔널. 프론트가 이 파라미터를 보내지 않으므로 기본 `page=0`이 기존 동작과 동일(비파괴 추가).
- **`DEFAULT_LIMIT`/`MAX_LIMIT` — F-MISC-2**: limit 상하한·page 하한 방어를 컨트롤러 계약에서 명시적으로 수행하고 정규화된 값만 서비스로 전달한다. 서비스도 동일 방어를 유지하는 것은 의도된 다중 방어이지 중복이 아니다.

## main/java/com/sbshop/agent/api/controller/BatchController.java

- **`startAndLog(...)` (SP-11 / F-BATCH-3)**: 4개 트리거 엔드포인트의 공통 골격(startBatch 등록 → 그 batchId로 ActionLog STARTED 기록 → batchId 반환)이다. 로그 메시지가 batchId를 포함해야 해서 `messageBuilder` 함수로 지연 조립한다 — 문자열을 미리 만들면 batchId를 못 넣는다.
- **`startAndLog(...)` (D-089)**: 배치 시작을 전 클라이언트에 SSE 방송하는 이유는, 배치를 개시하지 않은 다른 브라우저도 진행바를 공유해야 하기 때문이다.
- **`crawlAndUpdate(...)` (F-BATCH-4)**: `productIds`가 null/빈이면 그대로 두면 NPE(500)나 빈 배치가 조용히 생성된다 → 진입부 400 거부.
- **`manualUpdateAll(...)` (F-BATCH-A2/SP-3)**: `commands`는 `productIds`와 **index 위치로 매핑**되므로 두 리스트 길이가 반드시 일치해야 한다. 불일치 시 서비스 안에서 IndexOutOfBounds(500)가 터지므로 진입부에서 400으로 거부한다.
- **`updateBySupplier(...)` (F-BATCH-B2) — 프론트 계약**: 0건 케이스도 정상 케이스와 **동일 키셋 `{batchId, count, message}`**를 반환한다. `Map.of`는 null을 못 담으므로 batchId는 빈 문자열 `""`로 채운다. 프론트(BatchUpdatePage)가 `if (data.batchId)`로 분기하므로 `""`는 falsy → batchId 부재로 안전 처리되어 진행현황 폴링을 시작하지 않는다. **`""`를 null이나 다른 값으로 바꾸면 프론트 분기가 깨진다.**
- **`getBatchStatus(...)` (F-BATCH-S3)**: `status` 쿼리 파라미터는 선택. 미지정 시 기존 계약대로 전 행을 배열로 반환해야 한다(프론트 클라이언트 테이블 비파괴).
- **`getBatchSummary(...)`**: 폴링용 경량 집계(전체 행 대신 count 쿼리). 상세 조회용 `/status/{batchId}`와 역할이 다르며 둘 다 필요하다.

## main/java/com/sbshop/agent/api/controller/Cafe24AuthController.java

- **`checkStatus()` — 설계 의도**: 리프레시 토큰의 '존재'가 아니라 '유효성'을 검증한다. 실제 Cafe24 API를 가벼운 read로 한 번 호출해서 성공하면 연동 정상, 실패(401 등)면 재인증 필요로 판정한다(만료 시 tokenManager가 자동 갱신을 시도한다).
- **`checkStatus()` / `checkOrderScope()` (F-CAFE-2) — HTTP 시맨틱 계약**: '토큰 만료/무효·권한 없음'은 *정상 상태 결과*이므로 반드시 `200 + connected=false`로 표면화한다(프론트가 body를 읽어 표시). 반면 진짜 서버/인프라 오류(Cafe24 미도달·타임아웃·5xx)는 정상 상태인 양 200으로 감싸지 말고 **전파**해서 5xx가 되게 한다. `RuntimeException`은 `GlobalExceptionHandler.handleGeneral`을 타고 500이 되며, `IllegalStateException`은 400이라 이 용도에 부적합하다.
- **`isAuthOrPermissionIssue(...)` (F-CAFE-2 분류기)**: 보수적으로 판정한다 — 인증/권한 신호(401·403·invalid_grant·재인증 등)가 **양성**일 때만 정상 상태로 취급하고, 그 외(연결 실패·타임아웃·5xx)는 전파한다. 정상 만료는 항상 이 신호를 동반하므로(토큰매니저·RestClient가 붙임) 만료를 non-200으로 잘못 바꾸지 않는다.
- **`fullMessage(...)`**: 예외 체인 전체의 메시지를 합쳐서 본다 — 원인이 wrapping으로 은폐되면 인증 신호를 놓쳐 오분류된다.
- **`checkOrderScope()`**: 주문 조회 권한(`mall.read_order`) 확인용. G마켓/옥션 주문을 Cafe24 경유로 가져오려면 필수 스코프다. 권한(스코프) 문제와 그 외 오류를 구분해 표시하는 것은 오표기 방지 목적.
- **`exchangeCodeForToken(...)` (F-CAFE-12)**: `/issue-token`과 `/auth/callback` 두 엔드포인트가 공유하는 인가코드→토큰 교환 공통 로직. 활동로그 유무·응답 형태 등 두 엔드포인트의 **비대칭은 의도된 것**이며 호출부에 남아 있다(특성화: `Cafe24AuthControllerTokenExchangeTest`).
- **`extractCode(...)`**: 전체 리다이렉트 URL을 통째로 붙여넣어도 `code` 파라미터만 뽑아내도록 설계됨(사용자 편의).
- **`handleCafe24AuthCode(...)`**: (레거시) 브라우저 주소창 직접 입력용 콜백. 신규 UI는 `POST /issue-token`을 쓴다.

## main/java/com/sbshop/agent/api/controller/OrderController.java

- **삭제된 엔드포인트에 대한 사용자 결정 (2026-07-14)**: 주문 삭제 엔드포인트 `DELETE /{id}`는 **의도적으로 제거**됐다. 물리삭제는 복구 불가이고 연관데이터 고아를 유발하므로 운영 정책상 지원하지 않는다. 다시 추가하지 말 것.
- **`bulkResultStatus(...)` (SP-3)**: 실패가 하나라도 있으면 FAILED, 전건 성공이면 SUCCESS. **부분성공도 FAILED로 표면화**한다(성공N/실패M 요지는 메시지에 별도 기재). 일괄 발주확인·취소·발송이 무조건 SUCCESS를 남기던 결함(F-ORD-9/17/30)의 교정.
- **`confirmOrder(...)` (F-ORD-5) / `cancelOrder(...)` (F-ORD-15)**: 실패 경로에서도 주문을 조회해 marketType을 채운다(조회 자체가 실패할 때만 null 유지). 실패 시 재throw는 기존 에러 응답 계약을 보존하기 위한 의도된 동작이다.
- **일괄 경로의 marketType이 null인 것은 의도**: 일괄 발주확인/취소/발송은 다마켓 혼재라 단일 마켓으로 해석할 수 없다. 단일 마켓으로 해석 가능한 성공 경로(라인아이템 유니패스/소싱/배송 수정, SP-6)만 실제 마켓을 채운다.
- **`requireNonNegative(...)` (R6/F-S4)**: 소싱 금액 음수 검증. null(미변경)·0(무상 소싱/무물류비)은 정상값으로 통과시켜 과잉거부하지 않고, 음수만 `IllegalArgumentException`(→400)으로 거부한다. 음수 금액이 마켓/정산 데이터로 전파되지 않도록 진입부에서 차단하는 것이 목적(F-PROD-8/23·F-PSRC-11의 `signum()<0` 패턴과 일관).

## main/java/com/sbshop/agent/api/controller/OrderSyncController.java

- **동기화 트리거 4종(쿠팡·스마트스토어·11번가·G마켓/옥션) (D-087) — 활동로그 규율**: 동기화 서비스는 `@Async`라 트리거 직후에는 결과가 미확정이다. 컨트롤러가 디스패치 직후 SUCCESS를 남기면 실제 결과와 무관하게 항상 SUCCESS가 되므로, **컨트롤러는 STARTED만 남기고 SUCCESS는 절대 남기지 않는다.** 완료(SUCCESS/FAILED)는 `SyncCompletedEvent` → `ActionLogSyncListener`가 기록한다. 동기 디스패치 예외 시에만 FAILED를 남긴다(이 경우 async 본문·이벤트가 실행되지 않기 때문, F-SYNC-3).
- **`syncCoupangSettlement(...)` (D-087)**: 정산은 `SyncCompletedEvent`를 발행하지 **않는다**. 완료 기록은 `CoupangOrderSyncService.syncCoupangSettlement` 서비스 내부가 담당한다. 컨트롤러는 STARTED만 남긴다.
- **`syncGmarketAuction(...)` — 아키텍처 결정**: G마켓/옥션 동기화는 Selenium(ESM+) 대신 **Cafe24 주문 API**로 선회했다(`order_place_id=gmarket/auction`).
- **`previewCafe24Orders(...)`**: 진단용. Cafe24 주문 API 원시 응답 프리뷰(최근 7일 first page) — 파싱 검증·구조 확인 목적.
- **`getCafe24Carriers(...)`**: 진단용. 몰 등록 택배사 목록(`shipping_company_code` 확인용) — 송장 등록 택배사 코드 매핑 검증에 쓴다.
- **`getSyncStatus()` (F-SYNC-24)**: 서비스 내부클래스(`SyncStatusService.SyncStatus`)를 직접 노출하지 않고 응답 DTO로 미러해 계약을 보존한다. **`LinkedHashMap`을 유지해야 한다** — `getAllStatuses`가 LinkedHashMap을 반환하고 마켓 순서가 프론트 표시 순서이므로 일반 `HashMap`으로 바꾸면 순서가 깨진다.

## main/java/com/sbshop/agent/api/controller/ProductController.java

- **`MAX_CRAWL_IMAGES` (F-PROD-22)**: 크롤로 수집한 소스이미지 다운로드 개수 상한. 소스 페이지가 제공하는 이미지 수는 사용자 입력이 아니라 **제어 불가**하므로, 전량 거부(사용자 작업 좌절)가 아니라 상한 초과분 절단 + 로그로 처리한다.
- **`getProducts(...)` (F-PROD-1)**: 마켓 필터와 키워드가 **둘 다** 오면 배타 처리(keyword 무시)가 아니라 **AND로 결합**한다. 과거 배타 처리가 버그였다.
- **`getProducts(...)` / `loadMarketRegistrations(...)` (D-047)**: 페이지 상품 전체의 마켓 등록정보를 `findByProductIdIn`으로 **한 번에** 배치 조회한다. row별 `findByProductId`(N+1)로 되돌리면 성능 회귀 — 회귀 테스트가 개별 조회 0회를 단언한다.
- **`updatePriceStock(...)` (D-060/F-PROD-8)**: 자사 DB 갱신 + 연동 마켓 가격/재고 반영 결과(성공/스킵/실패 마켓)를 반환한다. 음수 가격은 마켓에 전파되기 전에 진입부 400으로 차단.
- **`updatePriceStock(...)` (F-PROD-7)**: `soldOut`을 **nullable 그대로** 전달해야 한다. null이면 재고상태 미변경 — 기본값으로 치환하면 판매재개가 잘못 전파된다.
- **`uploadPreparedImages(...)` (F-PROD-15/F-PROD-20)**: 이미지 등록 3경로(multipart 리사이즈 / URL 다운로드 / 크롤 다운로드)의 공통 뒷단(저장→응답 조립→활동로그). 각 경로의 **로그 타입·메시지 프리픽스는 호출부가 넘긴 값을 그대로 사용**해 기존 로그 계약을 유지한다.
- **`crawlAndUploadSourceImages(...)` — 로그 정확히 1회 규율**: 크롤·다운로드·저장 어느 단계에서 실패하든 `SOURCE_IMAGE_CRAWL` FAILED 로그를 **정확히 한 번만** 남긴다. `uploadPreparedImages`의 실패 로그와 겹치지 않도록 crawl-and-upload 전용 실패 프리픽스로 위임하는 구조다.
- **`crawlSourceImageUrls(...)` (F-PROD-19)**: 크롤 두 경로(`GET /images/crawl`, `POST /images/crawl-and-upload`)의 공통 앞단. 소싱 URL 부재와 이미지 목록을 `CrawlResult`로 **구분해** 반환하고, 각 경로의 빈-결과 로그/응답은 호출부가 처리한다.
- **`crawlSourceImages(...)` (D-078)**: 빈결과(소싱 URL 없음/스크랩 null)도 "왜 비었는지"가 사용자에게 보이도록 결과를 기록한다. 마켓 무관(소싱 크롤)이므로 marketType은 null.
- **`sanitizeCrawlUrls(...)` (F-PROD-18)**: http(s) 형식만 통과, 중복은 **순서를 보존하며** 제거(LinkedHashSet).
- **`updateProduct(...)` (F-PROD-23)**: 전체수정에 금액·수량 음수 검증이 전무했다 → 진입부 400 거부. null은 미변경이므로 통과.
- **`deleteProduct(...)` (F-PROD-27/28) — 이중기록 방지**: 완전 삭제는 연동 마켓 리스팅까지 삭제하고 삭제/스킵/실패 리포트를 바디로 반환한다. best-effort(C) 정책이라 일부 마켓 실패도 Product는 삭제되므로 **항상 200 + 리포트**다. ActionLog(PRODUCT_DELETE, 삭제/스킵/실패 마켓 + marketItemId 포함)는 **오케스트레이터(`deleteProduct` 유스케이스)가 기록**하므로 컨트롤러가 또 기록하면 이중기록이 된다. 컨트롤러는 상품 미존재(404) 등 오케스트레이션 진입 **전** 실패만 FAILED로 기록한다.
- **`buildMarketDetailMessage(...)` (D-077)**: "{prefix} | 쿠팡 {번호} 성공, 스마트스토어 {번호} 실패(사유), G마켓 스킵" 형태. 마켓 라벨은 한글(`MarketType.getLabel()`), 상품번호는 `extractMarketCode()`(없으면 생략). 실패 사유는 **50자로 절단**한다(전체 message 1000자 절단은 `ActionLogService.truncate`가 별도로 처리).
- **`buildMarketBadgeStates(...)` — 운영 실측 근거 (2026-08-14) ★중요**: 등록 완료 판정은 `is_synced`가 아니라 **"마켓이 돌려준 식별자를 가졌는가"**(`MarketRegistration.hasIdentifiers()`)로 한다. 레거시 임포트 행은 실제로 정상 등록돼 있어도 `is_synced=false`라, is_synced로 판정하면 배지 절반이 거짓 미완료 경고로 뜬다(운영 실측: is_synced=false인 등록행 2,594건이 **전부** 식별자를 갖고 있었다).
- **`buildMarketBadgeStates(...)` — CAFE24 키 노출 이유**: CAFE24는 자신도 배지 키로 내보낸다. 프론트가 G마켓/옥션 배지의 **선행조건(카페24 등록 여부)**을 판정해야 하기 때문이다. G마켓/옥션(ESM)은 Cafe24 경유 연동이라, 링크는 Cafe24 등록행에 백필된 식별자에서 파생한다.
- **`prepareImageFiles(...)` (F-PROD-12) / by-url 경로 (F-PROD-16)**: 개별 이미지 리사이즈·다운로드 실패를 조용히 드롭하지 않고 성공 파일과 실패 항목(파일명·사유)을 함께 집계해 응답의 `imagesFailed`로 표면화한다.

## main/java/com/sbshop/agent/api/controller/ProductSourcingController.java

- **`MAX_SOURCING_URLS` (F-PSRC-5)**: iHerb 소싱 URL 한 요청당 상한. 크롤은 URL당 외부 HTTP 왕복이라 무제한 목록은 장시간 점유·부하 위험 → 상한으로 차단.
- **`IHERB_URL_PATTERN` (F-PSRC-5) ★크롤러 결합**: 패턴이 요구하는 것은 (1) http(s) 스킴, (2) 호스트가 `iherb.com` 또는 그 서브도메인, (3) **크롤러 `IherbScraperClient.extractProductId`가 ID를 뽑을 수 있는 경로 형태** — `/product/{숫자}` 또는 `/pr/{이름}/{숫자}`. **이 패턴이 없으면 크롤이 조용히 실패한다.** 즉 이 정규식은 임의의 검증이 아니라 크롤러 구현과 묶여 있으므로, 크롤러의 ID 추출 규칙이 바뀌면 함께 바꿔야 한다.
- **`crawlIherb(...)` (F-PSRC-1)**: `urls`가 null/빈이면 UseCase에서 NPE(500)가 나기 전에 진입부 400 거부. STARTED 로그만 남기고 실패하는 것을 방지하는 목적.
- **`normalizeSourcingUrls(...)`**: 순서를 보존(LinkedHashSet)하고 **중복 제거 후**에 개수 상한을 산정한다(순서가 반대면 중복 때문에 정상 요청이 거부된다).
- **`crawlIherb(...)` (F-PSRC-2)**: 실패 URL은 조용히 누락하지 않고 응답에 포함하며 로그에도 성공/실패 건수를 남긴다. 전건 실패도 200으로 실패 내역을 담아 표면화한다.
- **`bulkCreate(...)` (F-PSRC-7/11)**: `requests`가 null이면 진입부 `.stream()` NPE(500, 로그도 없음) 대신 400 거부. 빈 목록도 거부(처리 대상 없음). 금액(`costPrice`) 음수는 데이터 오염이므로 거부.
- **`publishToMarket(...)` — 결함 B (사용자 결정 D-093 연동)**: 등록가 산정 파라미터(마진율·쿠폰율·최소마진)를 **선택적 바디**로 받는다. 원래는 정기 재가격 배치가 나중에 바로잡아 줄 것으로 가정했으나 그 배치가 D-093 사용자 결정으로 비활성이라, 프론트 다이얼로그가 등록 시점에 값을 직접 반영한다. 바디가 없거나 필드가 비면 종전 동작(오버라이드 없음)과 동일 — 기존 호출부를 깨지 않는다.
- **`publishToMarket(...)`**: 등록 직후 링크 식별자가 확보됐으면 URL까지 내려보내, 프론트가 목록 재조회 없이 배지를 바로 링크로 바꾼다.

## main/java/com/sbshop/agent/api/controller/ProductSyncController.java

- **`INTERNAL_API_TOKEN` 가드 (F-MISC-7)**: 공유시크릿 헤더 가드. **토큰 미설정 시 가드 비활성**(무파손, 프론트 옵트인)이 의도된 동작이다. 활성 시 시크릿 헤더 불일치/누락은 동기화 트리거 **전에** 403으로 차단.
- **`syncStock()` (F-MISC-8/9)**: 대상 선정 + 크롤을 관리되는 `@Async(syncTaskExecutor)`로 위임한다. 과거의 원시 `new Thread`(스레드 고갈·예외 유실)를 제거한 것이므로 되돌리지 말 것. 성공/실패는 서비스가 ActionLog로 기록한다.
- **`syncStock()` (F-MISC-10)**: 응답 메시지는 실제 동작(NEW/PREPARING 대상)과 일치해야 한다 — 과거 메시지가 실제 대상과 달랐다.

## main/java/com/sbshop/agent/api/controller/SourcingDiscoveryController.java

- **클래스**: 발굴 실행은 브라우저 렌더 크롤이 수 분~수십 분 걸려 **비동기**다. 요청은 즉시 202로 돌려주고 진행 상황은 활동로그·후보 목록 갱신으로 확인한다.
- **`runDiscovery()` ★스프링 프록시 함정**: `SourcingDiscoveryRunner.runAsync()`는 **반드시 여기(다른 빈)에서** 호출해야 `@Async` 프록시를 탄다. runner 내부에서 부르면 self-invocation이라 동기 실행되고 이 HTTP 요청이 수 분간 블록된다.
- **`runDiscovery()` — 중복 실행 가드**: 중복 실행은 iHerb에 불필요한 부하를 주고 서로의 결과를 덮어쓴다.
- **`getCustomsBlocked()`**: 통관 차단 목록은 "왜 이 상품이 추천에 없나"를 사용자가 확인하는 경로다.

## main/java/com/sbshop/agent/api/controller/SseNotificationController.java

- **`subscribe()`**: SSE 연결 타임아웃 24시간. 연결 종료·타임아웃·오류 세 콜백 모두에서 목록에서 제거해야 누수가 없다. 초기 이벤트 발송에 실패하면 즉시 제거한다.
- **`broadcast(...)`**: 등록된 모든 클라이언트에 동일 이벤트를 발송하고, **발송 실패한 클라이언트는 목록에서 제거**한다(죽은 emitter 누적 방지).
- **`onBatchStarted(...)` (D-089) — 프론트 계약**: payload는 **batchId 그 자체**여야 한다. 프론트가 별도 파싱 없이 `startTracking(batchId)`에 바로 넣어 쓰기 때문이다. JSON으로 감싸면 프론트가 깨진다.

## main/java/com/sbshop/agent/api/controller/SupplierController.java · MarketCredentialController.java

- **`createSupplier(...)` (F-SUP-3) / `createCurrency(...)` (F-SUP-UC-5)**: 공급사·통화는 마켓 무관이므로 활동로그 `marketType=null`이 의도된 값이다.
- **`MarketCredentialController` `@CrossOrigin(origins = "*")`**: 로컬 Vite 프론트엔드 개발용.

## main/java/com/sbshop/agent/api/controller/MarketRegistrationController.java

- **`marketPlusHandoff(...)` ★마켓 제약 + 정직성 원칙**: G마켓·옥션은 **상품등록 API가 없어 자동 등록이 불가능**하다. 사용자가 마켓플러스에서 직접 전송하도록 필요한 정보(상품코드·URL·안내)만 돌려준다. **여기서 아무것도 저장하지 않는 것이 핵심** — 사용자가 실제로 전송했는지 서버는 알 수 없고, 모르는 것을 '등록됨'으로 기록하면 배지가 거짓말을 한다.

## main/java/com/sbshop/agent/api/service/SourcingDiscoveryRunner.java

- **클래스 ★별도 빈이어야 하는 이유 (실측 근거)**: `@Async`는 스프링 프록시로 동작하므로 같은 빈 안에서 호출하면(self-invocation) 프록시를 우회해 **그냥 동기 실행**된다. 실측: 그렇게 했더니 발굴이 HTTP 워커 스레드 `nio-8080-exec-*`에서 돌아 `POST /discovery/run`이 응답 없이 타임아웃됐다. **별도 빈으로 뺀 것만으로는 부족하고, 프록시를 타려면 반드시 다른 빈에서 호출해야 한다.**
- **`tryStart()`**: 중복 실행 가드만 잡고 **실행하지 않는다**. 여기서 `runAsync()`를 부르면 위의 self-invocation 함정에 그대로 빠진다. 실행은 호출측(컨트롤러)이 `runAsync()`를 따로 불러야 한다. 반환값 true=실행 권한 획득, false=이미 실행 중.
- **`abort()`**: 비동기 제출 실패 등으로 실행을 못 시작했을 때 가드를 되돌린다 — 없으면 플래그가 영구히 켜진 채로 남아 이후 발굴이 전부 거부된다.

## main/java/com/sbshop/agent/api/exception/GlobalExceptionHandler.java

- **`handleTypeMismatch(...)` (SP-7/F-CRED-4)**: 잘못된 enum·타입 경로변수/파라미터 바인딩 실패는 사용자 입력 오류이므로 500이 아니라 **400**이어야 한다. 전용 핸들러가 없으면 일반 `Exception` 핸들러로 떨어져 500이 된다.
- **`handleNotFound(...)` (R1/F-PROD-5 등)**: 미존재 리소스는 400/500이 아니라 **404**. 입력오류(`IllegalArgumentException`→400)와 구분한다.
- **SSE 경로 처리**: SSE 스트림 요청 중 오류는 JSON 변환이 불가하므로 로그만 남기고 빈 응답을 반환한다.

## main/java/com/sbshop/agent/api/security/SecurityConfig.java

- **관리자 계정**: 기본 `admin/admin`. 환경변수 `ADMIN_USERNAME`/`ADMIN_PASSWORD`로 재정의 가능.
- **매처 순서 제약 ★**: 마켓 크레덴셜(시크릿 평문 포함)은 관리자 인증(HTTP Basic) 필수 — 무인증 공개 시 시크릿·리프레시토큰이 노출된다. **이 매처는 반드시 permitAll 매처보다 앞에 와야 한다** (Spring Security는 첫 매치가 이긴다).
- **`/internal/**` permitAll 이유**: 내부 트리거(이메일 수집·상품동기화)는 `InternalAccessGuard` 토큰이 보호한다. Spring Security 도입 후 `authenticated()`에 걸려 403 회귀가 났기 때문에, 컨트롤러 가드로 위임하도록 통과시킨다. 무인증 노출이 아니라 **가드 주체가 다른 것**.

## main/java/com/sbshop/agent/api/dto/ (응답 DTO 계약)

경계 DTO들의 공통 원칙: 도메인 엔티티/VO를 API 응답으로 직접 노출하지 않되, **현재 직렬화되는 JSON 형태를 1:1로 미러**해 프론트 계약을 보존한다. 필드 추가·순서 변경은 프론트 계약 변경이다.

- **`OrderResponse` / `OrderLineItemResponse` (SP-5, F-ORD-7·16·24·28)**: 엔티티의 **파생 getter**(`getMarketSpecificDataMap`/`getCafe24OrderId`, `isProgressed`)도 현재 JSON에 포함되므로 DTO가 그대로 미러해야 한다. 검증: `OrderResponseContractTest`가 엔티티 직렬화 트리와의 동일성을 단언한다.
- **`OrderLineItemResponse.shipmentId`**: 묶음배송·다품목 주문 모델 1단계 신설 컬럼 미러(설계: `docs/superpowers/specs/2026-08-05-bundle-shipment-order-model-design.md`). 1단계에서는 배선 전이라 항상 null이며, 2단계 이후 같은 shipmentId끼리 그리드에서 묶어 보여주는 데 쓴다.
- **`OrderIdsRequest` (F-ORD-11/20)**: JSON 계약 `{"orderIds":[1,2,...]}` — 기존 Map 바인딩과 동일 필드명/형태를 유지해야 한다. confirm/batch·cancel/batch 공용.
- **`ProcessStatusResponse` (F-BATCH-S1)**: 과거 `/products/batch/status/{batchId}`가 도메인 엔티티 `ProcessStatus`를 직접 직렬화했다. BaseEntity `@Getter`로 노출되던 필드까지 동일 이름으로 담고, enum은 종전 직렬화 형태(`name()`)와 동일하게 문자열로 매핑한다.
- **`MarketRegistrationResponse` (F-MREG-4)**: 원시 JSON 식별자(`marketIdentifiers`·`marketDetailedInfo`)는 **`@JsonRawValue`로 raw JSON을 그대로 방출**해야 종전과 같다(문자열로 이스케이프되면 프론트가 깨진다). 엔티티 getter가 적용하던 유효성 폴백(`"{}"`)도 getter 경유로 보존한다.
- **`SyncStatusResponse` (F-SYNC-24)**: 프론트 `SyncStatus` 인터페이스(marketType·status·lastSyncAt·errorMessage)와 동일 필드로 미러.
- **`SupplierResponse` (F-SUP-1) ★**: LAZY `@ManyToOne currency`는 응답에 **노출하지 않는다**(지연로딩 유출 차단). 스칼라 필드만 미러.
- **`CurrencyResponse` (F-SUP-LC-1)**: `currencyCode`·`exchangeRate` 현재 직렬화 형태 미러.
- **`ProductDetailResponse` (F-PROD-6)**: 도메인 VO(PriceInfo/LogisticsInfo/ProductSpec/SourcingInfo)를 직접 노출하지 않고 API 소유 중첩 record로 래핑한다. **목적: 도메인 VO에 필드가 추가돼도 API 계약에 자동으로 새지 않도록 매핑 지점을 이 DTO에 고정.** 각 중첩 record는 VO의 필드명·순서를 보존한다(priceInfo: costPrice·exchangeRate·deliveryFee·marginRate·salePrice / logisticsInfo: stock·weight·bundleQuantity / productSpec: barcode·capacity·measureUnit / sourcingInfo: vendor·sourceUrl·manufacturer·origin·hsCode).
- **`ImageUploadResponse` (D-049 반려 재수정) ★계약 의미**: 이 DTO가 본문에 실려 반환된다는 것 **자체가** 자사 저장(R2/DB)이 성공했음을 의미한다 — 저장 단계 실패는 `updateImagesAndHtml`에서 예외로 전파되어 4xx/5xx가 되고 본문이 생성되지 않는다. 그래서 `storageUpdated`와 마켓별 재게시 결과를 분리해 전달한다. 라이브 마켓 쓰기의 부분 실패(`failed`)가 조용히 삼켜지지 않게 하는 것이 이 계약의 존재 이유. `succeeded`/`skipped`는 `market=MarketType.name()`(프론트 키)+`label`=한글 표기, `failed.error`=마켓 API가 반환한 사유, `imagesFailed.ref`=원본 URL 또는 파일명. 완전 성공이면 `imagesFailed`는 빈 배열.
- **`MarketBadgeState` ★프론트 계약**: 맵에 **키가 없으면 미등록**이다(클릭하면 등록). 키가 있으면 등록된 것이고 `status`로 SYNCED(등록 완료)/PENDING(등록행은 있으나 동기화 미완료)을 가른다. **실패(FAILED)는 여기 담지 않는다** — `sb_market_registration`에 실패를 저장하는 컬럼이 없고, 등록 실패는 등록행을 남기지 않거나 PENDING으로 남긴다. 클릭 실패는 화면 세션 상태로만 표시한다. `url`은 링크 식별자를 아직 확보하지 못했으면 null.
- **`MarketPublishResponse`**: 프론트는 이 값만으로 **목록 재조회 없이** 배지를 갱신한다. `status` 어휘는 `MarketBadgeState`와 동일해야 한다.
- **`MarketPlusHandoffResponse`**: 프론트가 이것만으로 사용자를 마켓플러스로 데려갈 수 있어야 한다. `cafe24ProductCode`는 마켓플러스 목록에서 **상품코드 완전일치 검색**에 쓰는 값이고, `marketplusUrl`은 미판매 상품 목록 URL(`https://mp.cafe24.com/mp/product/front/noSaleAll`), `guide`는 사용자에게 그대로 보여줄 안내 문구다.
- **`ManualUpdateRequest` (F-BATCH-M1) ★데이터 오염 이력**: productId·price·stock을 병렬 배열이 아니라 **쌍 리스트(items)**로 받는다. 병렬 배열 index 매핑은 순서가 어긋나면 엉뚱한 상품에 값이 적용되는 데이터 오염을 유발했다. 병렬 배열로 되돌리지 말 것.
- **`BulkProductCreateResponse` (F-PSRC-6)**: 성공 항목(요청 index + 생성 productId + sbCode)과 실패 항목(요청 index + 식별자 + 사유)을 함께 반환해 **요청↔결과 매핑**을 가능하게 한다.
- **`IherbSourcingResponse` (F-PSRC-2)**: 전건 실패도 200으로 실패 내역을 담아 표면화한다(조용히 누락 금지).
- **`SourcingDtos` — 한 파일로 묶은 이유**: 전부 이 기능 전용 얕은 매핑이라, 파일당 20줄짜리 record가 10개 넘게 흩어지는 것보다 한곳에서 계약을 보는 편이 읽기 쉽다는 판단. `Candidate.scoreBreakdown`은 점수 근거 JSON 원문(프론트가 "왜 추천됐는지"를 펼쳐 보여줌), `customsReason`은 통관 판정 사유(REVIEW면 사용자가 읽고 판단해야 함). 검수 수정 요청의 **null 필드는 "변경 없음"**(부분 수정)이다.

## test/ — 테스트가 지키는 계약 (왜 이 단언이 존재하는가)

- **`AsyncConfigBeanNameConflictTest` (D-009 회귀)**: `core.config.AsyncConfig`와 `api.config.AsyncConfig`는 둘 다 `@Configuration`이고 단순명이 같아 기본 빈 이름 `asyncConfig`로 충돌한다(`ConflictingBeanDefinitionException`) → API 컨텍스트 시작 불가. 빈 이름 충돌은 **스캔 시점**에 발생하므로 refresh 없이 빈 정의만 확인한다(core.config의 `@EnableJpaAuditing` 등 인프라 초기화를 유발하지 않기 위함).
- **`ElevenstRestClientBeanConflictTest` (D-001 회귀, D-010 리네임 반영)**: elevenst 패키지에 용도가 다른 두 REST 클라이언트가 있다(`ElevenstOrderRestClient`: 주문 API GET 전용 / `ElevenstMarketRestClient`: 상품 API GET·POST·PUT). 과거 둘 다 단순명 `ElevenstRestClient`라 빈 이름 충돌로 API 컨텍스트가 시작하지 못했다. D-010에서 역할이 드러나는 이름으로 리네임해 근본 원인을 제거했다. 목 `MarketCredentialRepository`를 등록하는 이유는 상품-마켓 클라이언트가 자격증명 단일소스로 DB를 쓰기 때문(이 테스트 목적은 빈 이름 충돌 검증뿐).
- **`ApiContextLoadSmokeTest` (D-013)**: 운영 `application.yml`로 실 PostgreSQL(testcontainers)에서 컨텍스트가 로드되는지 검증. 스키마 수동 관리 체제이므로 **테스트 한정**으로 `hibernate.ddl-auto=create-drop`을 주입해 엔티티 매핑에서 스키마를 만든다(**운영은 `ddl-auto: none` 불변**). 검증 요지 두 가지: (1) 운영 yml + 엔티티 스캔이 실 Postgres에서 컨텍스트를 완주하는지, (2) `Cafe24TokenManager`의 `@PostConstruct`가 **비어 있는** `sb_market_credential` 조회를 예외 없이 견디는지(빈 DB 기동 내성). 외부 클라이언트 빈(R2 `S3Client` 등)은 더미 자격증명으로 즉시생성만 통과시킨다(실 호출 없음). `market_specific_data`가 `Order.java columnDefinition="TEXT"` 파생으로 text 타입에 수렴하는지 확인하는 단언은 구 Flyway V5가 보장하던 불변식을 스키마 생성이 유지하는지 보는 것(D-005 정합성 흡수).
- **`BatchControllerTriggerCharacterizationTest` (SP-11, F-BATCH-3/B3)**: 4개 트리거 엔드포인트의 jobType·ActionLog 상수·STARTED 상태·로그 메시지·응답 body를 고정해 골격 통합 리팩토링이 동작을 바꾸지 않았음을 증명하는 안전망. F-BATCH-B2 배경: by-supplier의 정상 케이스와 0건 케이스가 서로 다른 응답 키셋을 가져(정상={batchId,count} / 0건={message}) 클라이언트가 두 형태를 분기해야 했다 — 이 비대칭 제거가 목적.
- **`Cafe24AuthControllerStatusTest` (R1/F-CAFE-2)**: `GET /status`의 HTTP 시맨틱 특성화. 위 `Cafe24AuthController` 항목의 계약을 고정한다.
- **`Cafe24AuthControllerTokenExchangeTest` (SP-11/F-CAFE-12)**: 두 엔드포인트가 code 추출 + `issueInitialToken` 호출을 공유하되 **비대칭(활동로그 유무·응답 형태)이 보존**됨을 고정한다. 비대칭 1: `/issue-token`만 활동로그를 남긴다(성공/실패 모두).
- **`OrderSyncControllerActionLogTest` (D-087)**: 위 `OrderSyncController` 항목의 STARTED-only 규율을 고정.
- **`OrderSyncControllerPreviewContractTest` (F-SYNC-16) / `ProductSyncControllerContractTest` (F-MISC-11) / `ProductDetailResponseContractTest` / `OrderResponseContractTest` / `ResponseDtoContractTest`**: 전부 **JSON 계약 특성화**다. Spring Boot 웹 계층 **기본 ObjectMapper를 복제**(JSR-310 ISO, enum→name(), 타임스탬프 비활성)해 직렬화 트리가 고정 형태와 정확히 같은지 본다. 타입 시그니처를 바꿔도 이 트리가 깨지면 프론트 계약이 바뀐 것이므로 실패해야 한다. `preview`/`carriers`의 원시 `JsonNode` 페이로드는 외부 응답에 따라 가변이므로 **record 화하지 않고 그대로 실려야** 한다.
- **`OrderResponseContractTest` — 6단계 반영**: `shipmentBoxId`는 주문에서 사라졌다 — 배송박스번호는 배송(`sb_shipment`)이 갖는다.
- **`ProductControllerMarketMapTest` — 운영 실측(2026-08-14)**: `is_synced=false`인 등록행 2,594건이 전부 식별자를 갖고 있었다. is_synced로 판정하면 정상 등록된 상품 절반이 미완료 경고를 달게 된다. 또한 N+1 제거(배치 조회 1회·개별 조회 0회)를 단언한다.
- **`BadEnumBodyAlreadyBadRequestTest` (SP-7 확정 근거)**: 본문에서 `MarketType.valueOf(...)`를 호출하는 경로변수(String 파라미터)는 `IllegalArgumentException`을 던지므로 기존 핸들러로 **이미 400**이다(F-PSRC-12는 실제로 400). 원장의 "잘못된 marketType → 500" 주장 중 일부가 사실이 아님을 고정한 테스트. 실제 500이었던 것은 enum을 직접 경로변수 타입으로 받는 F-CRED-4뿐(`EnumPathVariableMismatchTest`).
- **`SupplierControllerCurrencyGuardTest` (SP-9)**: 통화/공급사 가드 로직(F-SUP-UC-1/2/3)은 `SupplierService`로 이동했고 **동작 보존 증거는 core의 `SupplierServiceTest`에 이관**되어 있다. 이 테스트는 컨트롤러가 요청 record를 커맨드로 매핑해 서비스에 위임만 하는지(얇은 컨트롤러)를 본다.
- **`ProductControllerInputValidationTest` — 프로젝트 규약**: 컨트롤러 진입부에서 `IllegalArgumentException`을 던지면 `GlobalExceptionHandler`가 400으로 매핑한다. 검증 실패 시 usecase는 호출되지 않아야 한다.
- **`ProductControllerImagePartialFailureTest`**: 3장 중 1장 실패 시 "성공 2장 / 실패 1장(사유)"이 응답에 표면화되고 전량 성공 시 실패 목록이 비는지 단언. multipart 케이스는 1·2장은 정상 JPEG, 3장은 이미지가 아닌 바이트로 만들어 Thumbnails 리사이즈를 실제로 실패시킨다.
- **`ProductControllerImageUploadTest` (D-049 반려 사유)**: 컨트롤러가 `MarketRepublishResult`를 버리고 Void를 반환해 일부 마켓 재게시가 실패해도 사용자는 "성공"으로만 인지했다 — 라이브 마켓 쓰기 실패가 조용히 삼켜지는 문제. 빈 파일 리스트 케이스는 `prepareImageFiles`가 빈 목록을 반환(Thumbnails 미실행)하므로 배선 계약만 검증한다.
- **`BatchControllerSupplierValidationTest` (Phase 1 파생결함)**: by-supplier 배치에서 `supplierCode`가 null/blank이면 `toUpperCase()` NPE로 500이 났다 → 400으로 표면화.
- **`ProductSourcingControllerPriceOverrideTest` (결함 B)**: 프론트가 실제로 값을 보내는 것은 별도 태스크지만 **서버는 지금부터 받을 수 있어야 한다**는 전제로 작성됨.
