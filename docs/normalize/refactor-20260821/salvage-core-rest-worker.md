# 보존 기록 — core(order/product/sourcing 제외) + worker

리팩토링 교리 §1에 따라 주석을 전량 제거하기 전에, **코드로 표현할 수 없는 제약·사용자 결정·마켓 API 함정**만 여기 옮겨 적는다.
(단순 설명 주석 · 이력 서술 · Javadoc 요약은 보존 대상이 아니라 그냥 삭제했다.)

---

## 1. 사용자 결정 (재활성/변경 시 반드시 사용자 확인)

### worker/scheduler/BatchScheduler — 정기 재가격 스케줄러 비활성화 (D-093, 2026-07-21)
- **결정**: 정기 자동 재가격 배치를 **끈다**. 수동 배치 업데이트만 사용한다.
- **사유**: 이 배치가 하드코딩 파라미터(margin 15 / coupon 20 / minMargin 5000)로 매일 05:00 전 상품을 재가격해, 사용자가 수동 배치로 설정한 판매가정책(예: 10/15/3500)을 몇 시간 만에 덮어써 왔다.
- **재활성 조건**: 하드코딩 상수 대신 **정책 저장소를 읽도록 고도화한 뒤** `@Scheduled(cron = "0 0 5 * * ?", zone = "Asia/Seoul")`를 `crawlAndUpdatePriceStock()`에 복원할 것.
- 삭제한 비활성 코드: `// @Scheduled(cron = "0 0 5 * * ?", zone = "Asia/Seoul")` (BatchScheduler.java:26)

### worker/service/EmailFetcherService#convertToKrw — 달러 확인메일 근사 환산 (2026-07-28)
- 달러 표기 주문의 원화 청구액은 카드사 환율로 정해져 메일에 없다. **근사값이라도 넣는다**가 사용자 결정. 정확한 값은 수동 편집으로 덮어쓴다.

---

## 2. 실측으로 도출된 상수 (근거 없이 바꾸면 안 되는 값)

### core/config/EmailAccountProperties#usdKrwRate — 기본값 1473
카드 해외결제 수수료 포함 **실효환율**. 달러·원화가 모두 확인된 운영 주문 4건에서 도출(1469.41~1478.41, 편차 ±0.3%):
`$48.00→70,743 · $32.40→47,609 · $31.91→46,923 · $30.61→45,254`. 환율이 움직이면 env `IHERB_USD_KRW_RATE`로 조정.

### core/config/MarketRegistrationDefaults — 11번가 주소 코드 (D-092, 라이브 검증)
- `addrSeqOut=5` (미국 출고지, `outsideYnOut=Y`) / `addrSeqIn=3` (국내 반품지, `outsideYnIn=N`) — 11번가 주소조회 API 실측값.
- **함정**: `dlvCnAreaCd`는 '배송가능지역'(01=전국) 코드지 **주소코드가 아니다**. 혼동 금지.
- `originDetailCode=1405`(미국 원산지 상세코드)도 D-092 라이브 검증값.
- 쿠팡 `returnCenterCode=1000519746` / `outboundShippingPlaceCode=1206157`은 기존 `CoupangProductPayload` 하드코딩을 승계한 **실계정 값**.
- 스마트스토어 주소록 ID와 Cafe24 분류번호는 여기 두지 않는다 — 마켓 API로 자동 조회(`MarketAccountResourcePort` / `Cafe24CategoryResolver`)하며, 설정이 비어 있는 게 정상이다.

### worker/service/OrderEmailParser#MIN_PLAUSIBLE_KRW — 원화 하한
실재 가능한 iHerb 주문 최소 금액. 실측 주문 범위 2만~7만원대. 숫자가 태그 경계로 쪼개져 평탄화되면(`"₩31,441"` → `"31 ,441"`) 앞 토막만 잡혀 31 같은 값이 나온다. **잘못된 값은 멱등 가드 때문에 영구 고착**되므로 의심 값은 주입하지 않고 버린다.
**하한 검사는 원화에만 적용한다 — `$48.00`은 정상 값이다.**

---

## 3. 마켓 API 함정 / 외부 계약

### core/domain/market/MarketRegistration#extractDeleteCode — 쿠팡만 다르다
삭제(`seller-products`)는 `sellerProductId`를 요구하는데 `extractMarketCode()`는 가격/재고용 `vendorItemId`를 우선한다. **삭제 경로에 vendorItemId를 넘기면 쿠팡이 오류를 낸다.** 쿠팡만 `sellerProductId`를 직접 추출. 없으면 null → 오케스트레이터가 실패로 수집하고 DB 삭제는 best-effort로 진행.

### core/domain/market/MarketRegistration#extractMarketCode — 마켓별 식별자 키 (D-052)
```
COUPANG               : vendorItemId → sellerProductId
SMART_STORE           : originProductNo → channelProductNo
ELEVEN_STREET         : elevenstId → prdNo
CAFE24                : product_no → product_code
GMARKET/AUCTION(ESM+) : goodsNo → itemNo → goodsCode
```
쿠팡 전용 키만 읽으면 나머지 마켓은 항상 null→'미확인' 폴백이 된다.

### core/domain/market/MarketRegistration#hasMarketIdentifier — `is_synced`를 쓰면 안 되는 이유
레거시 임포트 행 다수가 실제로는 마켓에 정상 등록돼 있는데도 `is_synced=false`로 남아 있다 (운영 실측 2026-08-14: PENDING 2,594건 전부가 식별자 보유, 식별자 없는 PENDING은 0건). 반면 `MarketRegistrationTxService.savePending`이 외부 게시 **전에** 만드는 미완료 행은 identifiers가 정확히 `"{}"`다 — 그래서 "식별자 없음"이 "게시를 시작했으나 끝내지 못함"의 정확한 신호다.

### core/domain/market/MarketRegistration — (product_id, market_type) 유니크 제약 (F-PSRC-13 / R3)
`savePending`의 `findByProductIdAndMarketType` 재사용은 **순차 재호출에만 멱등**이다. 두 트랜잭션이 동시에 "행 없음"을 관측하면 둘 다 insert하는 경쟁이 남으므로 DB 유니크 제약으로 하드 차단한다. 제약을 지우면 동시 재게시가 중복행을 만든다.

### core/domain/market/client/MarketClient — default 메서드 계약
- `publish(product, context)` / `publish(..., product)`: 기본 구현은 기존 경로로 위임한다 — **어댑터를 하나씩 옮겨도 나머지가 깨지지 않게 하는 장치**다.
- `deleteListing`: 기본 구현이 미지원 예외를 던진다 — 각 마켓 어댑터가 반드시 override.
- `fetchAllLinkIdentifiers`: 기본 `null` = **미지원 신호**. 백필 서비스는 null이면 단건/청크 경로로 폴백한다. 빈 맵과 의미가 다르다.
- `removeSellerImmediateDiscount` (D-096): 마켓별 가격이 이미 각 수수료에 맞게 산정되므로 별도 즉시할인이 겹치면 **이중할인 손해**가 난다. 기본 no-op, 스토어만 override.

### worker/controller/MarketTrackingBackfillController — 마켓 API 상한 (2026-08-08 실측)
한 번에 넓은 구간을 부르면 **Cafe24는 조회 범위 3개월 상한(422), 쿠팡은 레이트리밋(429)**에 걸린다. 구간 분할·페이싱은 `MarketTrackingBackfillService`의 책임이고, 컨트롤러는 기간 상한(`MAX_DAYS`)으로 실수(예: days=100000)를 막는다.

### worker/service/EmailFetcherService — IMAP 함정
- **비ASCII 검색어 금지**: `sourcing_order_no`는 사용자 편집 필드라 한글("재고") 등이 들어올 수 있다. 그대로 `SubjectTerm`에 넣으면 charset 미지정으로 서버가 `BAD Could not parse command`를 던져 **그 계정의 검색 루프 전체가 예외로 끊긴다**. ASCII일 때만 서버 검색에 쓴다.
- **Gmail `\All` 폴더**: 전체보관함이 INBOX·라벨·보관함의 상위집합이라 그 한 폴더만 검색하면 충분하다(30분 주기 유지의 핵심). 폴더명은 로케일마다 다르므로("[Gmail]/All Mail" vs "[Gmail]/전체보관함") **이름이 아니라 IMAP SPECIAL-USE 속성으로 판별**한다.
- **제외 폴더**: 보낸편지함·임시보관함에는 iHerb 메일이 있을 수 없다(계정당 2~3폴더 절감). **스팸·휴지통은 제외하면 안 된다** — 진짜 확인메일이 거기 들어간다.
- **별칭 도메인 매칭**: 소싱 계정 `tonyworld@hanmail.net`이 설정 계정 `tonyworld@daum.net`과 문자열 완전일치하지 않아 9개 계정 전부로 팬아웃됐다(검색 계획의 약 67%). local-part가 같고 **해석된 IMAP host가 같을 때만** 같은 메일함으로 본다. local-part만 같고 제공자가 다르면 남남일 수 있으므로 매칭하지 않는다.
- **`text/html` 파트를 버리면 안 된다** (D-112-2): HTML 단독 발송 메일에서 본문이 빈 문자열이 되어 확인메일을 찾고도 금액을 영구 누락한다(younzara@nate.com 8건). multipart는 plain을 앞, html을 뒤에 붙인다 — 패턴이 첫 매칭을 채택하므로 순서가 곧 우선순위다.

### worker/service/OrderEmailParser#extractTotalAmount — Matcher 소비 버그 재발 금지
과거 구현은 판정용 `find()`가 매칭 위치를 소비한 뒤 같은 Matcher로 다시 `find()`를 호출해 **"첫 매칭을 버리고 두 번째 매칭"**을 금액으로 읽었다 — 실측에서 70,743원 주문이 48로 기록됐다. 패턴은 **우선순위 순, 첫 매칭 채택**이며 앞선 패턴이 맞으면 뒤는 보지 않는다.
- 총액 패턴은 앞이 더 명시적, 뒤로 갈수록 일반적이라 오매칭 여지가 크다 — **순서를 바꾸지 말 것**.
- `"총 주문"`은 페이코 결제 메일(`"결제 유형: 페이코 총 주문: ₩40,418"`)의 총액 라벨이다.
- 통화기호를 group(1), 금액을 group(2)로 잡는다 — iHerb는 계정 설정에 따라 원화(₩45,254)로도 달러($48.00)로도 표기하므로 **기호를 반드시 확인**해야 한다.
- 주문 확인 제목: `"주문이 확인되었습니다"` / `"결제가 처리되었습니다"` 둘 다 받되 **`"결제 대기 중"`은 제외** — 결제 확정 전이라 금액이 바뀔 수 있다. 주문번호는 제목 어디에 있어도 잡는다(문구 앞·뒤 양쪽 표기 존재).

### 마켓 OAuth 토큰 — Cafe24 (D-103)
`core/application/market/port/Cafe24TokenRefreshPort`, `worker/scheduler/MarketTokenScheduler`:
Cafe24 리프레시 토큰은 **유효기간 2주**이며 refresh 호출 때마다 새 토큰으로 회전·연장된다. 갱신이 주문 동기화 트래픽에만 의존하면(온디맨드) **2주 이상 API 호출 공백 시 리프레시 토큰이 만료되어 재인증 외 복구가 불가능**해진다. 그래서 트래픽과 독립적으로 매일 1회 강제 회전한다. 리프레시 토큰이 없으면 조용히 스킵하고, refresh 실패 예외는 삼켜 스케줄러가 중단되지 않게 한다.

---

## 4. 동시성·멱등성 조건

### core/application/process/ProcessStatusService — 배치 가드가 in-JVM으로 충분한 근거
배치는 api JVM 단일 인스턴스에서만 실행되고 배포는 api를 재시작하므로 동시 2인스턴스가 아니다. 따라서 교차 JVM advisory lock 없이 in-JVM 상태로 충분하다.
- **가드 범위는 jobType별**: 같은 jobType이 진행 중이면 거부(IllegalStateException→400), 서로 다른 jobType은 동시 허용(전면 "한 번에 하나"는 과제약).
- **트리거~완료 전체를 덮는다**: `startBatch`(controller 스레드)에서 획득하고, 실제 배치는 `@Async`라 분리되므로 `BatchCompletedEvent` 리스너(`BatchGuardReleaseListener`) → `releaseBatch`에서 해제한다. 해제는 batchId 기준 **멱등**이라 중복/미등록 이벤트에도 안전.
- **시딩 실패 시 즉시 해제 필수**: 배치가 `@Async`로 뜨지 못했으므로 `BatchCompletedEvent`도 발행되지 않는다 → 해제하지 않으면 가드가 **영구 잠김**이 된다.

### core/application/process/ProcessStatusService#getBatchStatus — 404 판정은 전체 행 count로
미존재 판정을 **필터 결과**로 하면 "FAILED 없음(정상 배치)"과 "미존재 배치"를 혼동한다. 배치는 상품별 최소 1행을 심으므로 **전체 행이 0이면 미존재 batchId**이고, 필터 결과가 비면 빈 목록(200)을 반환한다. `getBatchSummary`도 동일 — `total==0`만 404이고 진행 중 배치(total>0)는 200 진행률을 유지해야 폴링이 끊기지 않는다(F-BATCH-SM1).

### core/application/process/ProcessStatusService#recoverStalePending — 부팅 시 1회만
부팅 시점엔 진행 중 배치가 없으므로(배포=api 재시작) 그때 남은 PENDING은 전부 이전 실행의 고아다. 방치하면 `getBatchSummary`가 완료 판정을 못 한다(F-BATCH-2). **부팅 훅에서만 호출** — 런타임에 부르면 진행 중 배치를 FAILED로 죽인다.

### core/application/sync/SyncStatusService#tryMarkRunning — 원자적 클레임 (F-SYNC-17)
정산 동기화는 워커 스케줄러 + api 수동으로 **교차 JVM 트리거**가 가능하므로 in-JVM AtomicBoolean으로는 부족하다. DB를 단일 원본으로 조건부 UPDATE(rows-affected)로 원자성을 확보한다 — **H2·PostgreSQL 모두 동작하는 이식성 있는 방식**(`ON CONFLICT` 등 벤더 구문 금지).
순서: ① non-RUNNING row를 조건부 UPDATE → 1건이면 클레임 성공 ② 0건이면 row 없음 또는 이미 RUNNING(존재+RUNNING이면 false) ③ row 없으면 RUNNING insert → true. **동시 double-insert는 `market_type` 유니크 제약이 한쪽을 예외로 막으므로, 예외 발생 시 상대가 이미 클레임한 것으로 보고 false.** 해제는 `markCompleted`/`markFailed`.

### worker/service/EmailFetcherService — 재진입 가드 (F-MISC-18)
`EmailFetchController`(수동)와 `OrderSyncScheduler`(cron 0/30)가 **같은 JVM에서** `fetchAndProcessEmails()`를 동시에 호출하면 같은 발송메일을 이중 처리해 마켓에 중복 송장이 나간다.
- **`AtomicBoolean`이어야 한다 — `ReentrantLock`은 동일 스레드 재진입을 허용하므로 부적합.**
- 반환값 계약: 본처리를 실제로 수행했으면 true, 가드로 스킵했으면 false. 계정 미설정·처리대상 없음은 "정상 실행(처리할 것이 없었을 뿐)"이므로 **true**. `/internal/email/fetch`가 이 값을 응답에 반영한다(F-MISC-20).

### worker/service/EmailFetcherService#fetchAndProcessEmails — `@Transactional` 금지 (2026-07-24)
계정별 IMAP 접속(각 최대 30s)과 마켓 API 호출 등 **느린 네트워크 I/O**를 다수 수행한다. 전체를 `@Transactional`로 감싸면 그 시간 동안 DB 커넥션을 붙잡아 풀이 커넥션을 닫고 `"Unable to rollback against JDBC Connection"`으로 실패한다. 읽기는 각 리포지토리 호출이, 쓰기는 각 `save()`가 자체 트랜잭션으로 원자적이다.

### worker/service/EmailFetcherService — 확인메일 실구매가 주입 조건
확인메일의 총 결제 금액은 iHerb 주문 **1건 전체**의 금액이다. 한 주문번호가 여러 라인아이템에 걸리면 총액을 배분할 근거가 메일에 없고, 총액을 양쪽에 넣으면 `sourcing_amount`가 라인아이템별로 합산되는 순수익 계산에서 **원가가 중복 계상**된다 → 자동 주입하지 않고 수동 입력에 맡긴다.
- 이미 실구매가가 있으면 스킵(멱등성).
- 실패 로그는 **주문번호당 1회**만 남긴다(JVM 단위) — 30분마다 같은 건이 쌓이는 것을 막는다. 배포 후 한 번 더 기록되어 수정이 먹혔는지 확인할 수 있다.

### worker/scheduler/SourcingScheduler — 실행 순서·런타임 스위치
- **성분 동기화(02:30)를 후보 발굴(03:00)보다 먼저** 돌린다. 통관 게이트가 최신 목록으로 판정해야 새로 지정된 차단 성분이 그날 발굴분부터 걸린다.
- 실행 여부는 `sb_sourcing_config.schedule_enabled`로 **배포 없이** 끌 수 있어야 한다(iHerb 차단·API 한도 소진 등 긴급 상황 대비).
- 크론 표현식은 설정 테이블에도 있지만 `@Scheduled`는 정적이라 코드는 프로퍼티를 쓴다. **시각 변경은 환경변수 수정 + 재배포**가 필요하고, 설정 테이블 값은 화면 표시·문서용이다.
- 회차 겹침 방지 가드(전 회차가 아직 도는데 다음 회차 시작).

---

## 5. 송장 진실 모델 (D-121 / D-123 / D-133 / D-147 / D-148)

### `trackingSentToMarket` 플래그로 "이미 동기화됨"을 판정하면 안 된다 (D-147)
이 플래그는 **전송이 실패해도 참으로 남을 수 있다** — 2026-08-07 라이브에서 거짓 성공(D-145)이 플래그를 참으로 찍어 놓았고, 그래서 이메일 파이프라인이 전송을 아예 시도하지 않고 스킵했다. 시도하지 않으니 마켓의 영구 거부를 만나지 못하고, 화면은 "곧 자동으로 반영됨"이라 잘못 안내했다.
**진실은 배송에 기록된 마켓 보유 송장(`marketTrackingNo`)이다 (D-148).**

### 송장은 상태와 무관하게 기록한다 (D-121)
종전에는 PREPARING일 때만 송장을 기록하고 그 외 상태(NEW·DELIVERED 등)는 통째로 스킵해, 배송완료로 넘어간 주문의 송장이 **영원히 비어 있었다**(옥션 실사례).
- **로컬 기록**: 우리에게 실값이 없으면 상태 무관하게 채운다. 배송상태는 건드리지 않으므로 마켓 진실을 훼손하지 않는다.
- **마켓 전송**: 마켓이 받아주는 상태에서만 시도한다. PREPARING은 초기등록(`shipOrder`) 경로, DISPATCHED/SHIPPED는 마켓이 이미 송장 보유 → 수정(`updateTracking`, `invoiceAlreadyExists=true`) 경로. NEW·DELIVERED는 마켓이 거부하므로 **전송하지 않고 로컬 기록만** 남긴다.
- 이메일 택배사가 없거나 미지원(ETC)로 매핑되면 **기존 택배사를 유지**한다.

### 출처(`trackingSource`) 승격 — 값이 같아도 EMAIL로 올린다
출처는 "무엇이 이 값을 확인했나"다. 마켓이 먼저 알려준 송장과 이메일 송장의 **값이 같아 쓰지 않고 지나가더라도**, 이메일이 이 송장을 진짜라고 확인해 준 사실은 남긴다 — 그러지 않으면 진짜 송장이 영영 ✍(진위 불명)로 표시된다. 이 기능에서 가장 놓치기 쉬운 경로다(`sameTracking` 분기).

### 영구 거부(terminal) 종결 마킹 (D-E6 / D-123)
마켓이 영구 거부하면 재전송은 무의미하다 — 30분마다 같은 거부를 받아낼 뿐이다. 종결로 마킹해 재시도 루프를 끊고(실송장은 이미 DB 기록됨), 실제 성공과 구분되도록 감사 로그(ActionLog)를 남긴다. 사람이 판매자센터에서 고치면 **동기화가 표시를 스스로 끈다**(D-148).
- **D-123: 감사 로그의 마켓 타입을 하드코딩하지 말 것.** 종전에 `"COUPANG"` 하드코딩으로 11번가·Cafe24 종결 건까지 쿠팡으로 기록돼 원인 추적이 어긋났다. 조회 실패 시에만 UNKNOWN(로깅 때문에 본 처리를 깨뜨리지 않는다).
- 일시 실패는 **미마킹** → 다음 사이클 재시도.

### 송장 쓰기 통로 단일화 (D-133)
송장 쓰기는 `LineItemShippingWriter` 통로만 쓴다 — 배송이 붙어 있으면 **배송이 단일 원본**이다. 2단계에서 발송처리 단위가 배송이 되면 배송이 모르는 송장은 마켓에 나가지 않는다.
- **테스트 규약**: EmailFetcher 계열 테스트는 이 통로에 **진짜 객체**를 끼운다. `@InjectMocks`가 목을 넣거나 null로 남기면 라인아이템 쓰기 자체가 사라져, 검증이 통과해도 아무것도 증명하지 못한다. (해당 테스트들의 라인아이템은 `shipment_id`가 null이라 통로가 배송을 건드리지 않는다 — 동작 동일성이 곧 회귀 증거.)

---

## 6. 응답 계약 / 보안 전제

### core/application/sync/SyncMarketKeys — 키 문자열이 곧 응답 계약
`sb_market_sync_status.market_type` 값은 기존 스케줄러가 쓰던 문자열과 **동일해야** `/orders/sync/status` 응답 계약이 유지된다. 서비스(core)와 스케줄러(worker)가 같은 상수를 공유하는 이유다.

### core/application/market/dto/MarketCredentialDto — 시크릿 평문 노출의 전제 (2026-07-24 개정)
이 DTO는 시크릿 평문(accessKey·secretKey·refreshToken)을 응답에 담는다. **안전성은 이 DTO가 아니라 엔드포인트 인증이 보장한다** — `/api/v1/market-credentials/**`가 `SecurityConfig`에서 관리자 HTTP Basic으로 보호되기 때문이다.
**인증(authenticated() 매처)을 제거하면 즉시 노출 결함(F-CRED-1·7)이 재발한다.** `hasXxx` 불리언은 하위호환용으로 함께 유지.

### core/application/market/MarketCredentialService#save — 빈 시크릿은 기존 값 유지 (F-CRED-8)
프론트 폼이 마스킹된 응답을 그대로 되보내는 시나리오가 있으므로, **저장 시 비어 있는 시크릿 필드(accessKey·secretKey)는 기존 값을 유지**한다. 그러지 않으면 빈 값 제출로 저장된 시크릿이 지워진다. 새 값이 들어온 경우에만 갱신. 식별자·리다이렉트는 마스킹 대상이 아니라 그대로 반영.

### core/config/InternalAccessGuard — 옵트인 가드 (F-MISC-17·7)
`INTERNAL_API_TOKEN` **미설정(빈 값)이면 가드 비활성 → 모든 요청 통과**(기존 동작 유지, 무파손). 토큰 설정 시에만 헤더 값 정확 일치를 요구한다. 이 프로젝트는 인증 프레임워크가 없고 CORS가 개방(*)이라 "포트 미노출" 관례에만 의존하던 내부 트리거를 최소한으로 보호하는 장치다.
servlet 의존을 두지 않기 위해 **헤더 추출은 각 컨트롤러가 하고 여기서는 순수 문자열 비교만** 한다(core는 web starter 미포함 — 모듈 경계).

### core/config/AsyncConfig#productBatchExecutor — core에 있어야 하는 이유
소비자(`BatchPriceStockService`)가 core에 있고 호출자가 **api(컨트롤러)와 worker(일일 cron) 양쪽**이므로, 두 실행 컨텍스트 모두에서 `@Async("productBatchExecutor")` 한정자가 해소되려면 빈이 core에 정의돼야 한다. api로 되돌리면 worker 경로에서 한정자 미해소로 깨진다.

### core/application/fee/MarketFeeService — 정산액은 한 번만 곱한다
`정산액 = 금액 × (1 - 수수료율/100)`. 수수료율은 `sb_fee_policy`(FeePolicy)에서 마켓별 조회, 행이 없으면 `SettlementPolicy.defaultFeeRate`로 폴백.
**종전엔 flat 0.89를 sync·ship 두 곳에서 곱해 이중 차감**됐다 — sync 1회 적용으로 바로잡은 것이므로 ship 경로에 다시 넣지 말 것.

### core/application/actionlog/ActionLogService — 기록 실패는 본업을 막지 않는다
저장 실패는 삼켜 로깅만 한다. `message`는 컬럼 길이(1000) 초과 방지를 위해 절단한다.
페이지네이션 방어(F-MISC-1/2): `page<0 → 0`, `size<=0 → 기본 100`, `size` 상한 500. 응답은 평면 리스트(프론트 계약 유지).

### core/application/dashboard/DashboardService — 축은 합집합으로 만든다
축(x)은 `bucketRange`(빈 구간 0채움)와 **실제 주문의 KST 버킷키의 합집합**으로 구성한다. naive 경계(bucketRange)와 KST 주문키(bucketKey)의 **9시간 스큐** 때문에 마지막 날 UTC 꼬리 주문이 다음 KST 버킷으로 가더라도 축에 포함되어 누락되지 않는다. `orders`가 `TreeMap`인 것도 버킷키 오름차순 보장 목적.
`orderDate`는 **zone 없는 UTC 벽시계값**(KST 변환 대상)이다.

### core/application/dashboard/DashboardBucketing — KST·월요일 주·달력 월
zone 없는 UTC 벽시계값을 KST 날짜로 본 뒤 unit 버킷 시작일로 내린다. 구간 경계(start/end)는 naive 날짜 부분으로 버킷을 결정한다(대시보드 쿼리 파라미터 용도).

### worker/scheduler/OrderSyncScheduler — 상태 기록 주체
**EMAIL만 스케줄러가 직접 기록**한다(sync 서비스가 아니라 `EmailFetcherService`를 직접 호출하므로). 수동 트리거(`EmailFetchController`)도 동일 키 `SyncMarketKeys.EMAIL`을 써 상태 계약을 일관되게 유지한다(F-MISC-19).
**나머지 마켓은 각 sync 서비스가 자기 async 스레드 안에서 직접 기록한다(F-SYNC-2) — 스케줄러는 호출만 한다.** 스케줄러에서 중복 기록하면 async 스레드의 실제 상태를 덮어쓴다.

### core/domain/supplier/repository — 정렬 계약
- `SupplierRepository`: 목록은 소프트삭제(ARCHIVED/DELETED) 제외 **ACTIVE만**(F-SUP-2), `supplierCode` 오름차순 안정 정렬(F-SUP-4).
- `CurrencyRepository`: `currencyCode` 오름차순 안정 정렬(F-SUP-LC-2).
- 중복 코드는 DB unique 예외에 의존하지 말고 **사전검증으로 400**(F-SUP-CS-2 / F-SUP-UC-1). 통화는 생성 전용 — 이미 존재하면 거부(기존 환율 불변), 환율 변경은 별도 경로.

### core/domain/process/repository/ProcessStatusRepository — OOM 예방
batchId 목록은 **DB에서 distinct** 처리한다(전 행 findAll 후 메모리 distinct 금지 — 이력 누적 시 OOM, F-BATCH-ST1). 최신 배치가 앞에 오도록 batchId별 `max(startedAt)` 내림차순. 서비스는 이 순서를 **재정렬 없이 그대로 통과**시킨다(F-BATCH-ST2).

### core/domain/fee/repository/FeePolicyRepository
현재는 마켓 단위 1행을 전제하며 **첫 행의 요율**을 사용한다.

---

## 7. 운영 호출 방법 (내부 트리거, nginx 미노출)

```bash
# 이메일 IMAP 수집·송장 처리 즉시 1회
docker exec projects-sbshop-api-1 curl -s -X POST localhost:8080/internal/email/fetch

# 마켓 링크 식별자 백필 (수천 건 × 마켓 API, 수분~십수분 — 백그라운드)
docker exec projects-sbshop-api-1 curl -s -X POST 'localhost:8080/internal/backfill/market-link-ids?limit=0'

# 마켓 보유 송장 백필 (기본 120일, 비동기 — 진행은 [백필] 로그로 확인)
docker exec projects-sbshop-api-1 curl -s -X POST 'localhost:8080/internal/backfill/market-tracking?days=120'

# 레거시 라인아이템 배송 생성·연결 (마켓 API 미호출 — 동기, 즉시 결과 반환)
docker exec projects-sbshop-api-1 curl -s -X POST localhost:8080/internal/backfill/legacy-shipments

# 스마트스토어 판매자 즉시할인 제거 (D-096)
#   소규모 dryRun 확인 (동기, 결과 즉시 반환)
curl -s -X POST 'localhost:8080/internal/smartstore/remove-seller-discount?productIds=277,245,3006&dryRun=true'
#   전체 실제 제거 (비동기 + 재진입 가드, 로그로 진행 확인)
curl -s -X POST 'localhost:8080/internal/smartstore/remove-seller-discount?dryRun=false'
```

`MarketLinkBackfillController`는 서비스의 `@Async` 진입점을 호출한다(별도 빈이라 프록시 경유 — self-invocation 문제 없음). 완료 콜백에서 재진입 가드를 해제한다.

---

## 8. 도메인 상수 의미

### core/domain/actionlog/ActionLogConstants (D-076)
- 값은 `ActionLog.actionType` 컬럼(length=50)에 저장되므로 **50자를 넘지 않는다**.
- 프론트 `ProcessStatusPage.tsx`의 **한글 라벨 맵이 이 값과 매칭**된다 — 값을 바꾸면 프론트 라벨이 깨진다.
- 마켓 동기화(`{MARKET}_SYNC`)는 `ActionLogSyncListener`가 완료를 기록하므로 **여기 상수화 대상에서 제외**한다(이중기록 방지).
- `SOURCING_AMOUNT_AUTO_FILL`(D-115): iHerb가 제목·총액 라벨을 바꾸면 조용히 누락되므로 실패를 화면(활동 로그)에 노출하기 위한 액션이다.

### core/domain/market/MarketRegistration — 스마트스토어 slug
공개 상품 URL용 스토어 slug는 **현재 단일 스토어 운영 전제로 상수**다. 멀티스토어가 되면 설정으로 빼야 한다.
마켓 상품 페이지 URL 규칙:
```
쿠팡     : products/{productId}?vendorItemId={vendorItemId}   (productId 필수, vendorItemId는 부가)
스토어   : smartstore.naver.com/{slug}/products/{channelProductNo}
11번가   : 11st.co.kr/products/{prdNo}
G마켓/옥션: Cafe24 등록행에 백필된 gmarket_goodsNo / auction_goodsNo (ESM=Cafe24 경유 연동)
```

### core/domain/common/exception/ResourceNotFoundException
입력값은 유효하나 대상이 없는 경우 → **HTTP 404**. 잘못된 입력(400)을 나타내는 `IllegalArgumentException`과 구분하기 위해 별도 예외로 둔다.

### core/domain/market/TokenRefreshLock
프로세스 간 상호배제 포트 — 동일 key에 대해 **한 번에 하나의 호출만** action을 실행하도록 보장한다(다중 JVM 포함).

### core/domain/common/RootCauseExtractor
예외 체인의 최심(root) 원인 메시지 추출. **순환(getCause()가 자기 자신) 방어** 필수. 최심 원인의 메시지가 없으면 null.

### core/domain/market/MarketRegistration#markSyncFailed
`isSynced=false`로 내려 "직전 동기화 미성공"을 나타낸다 — **변경없음이어도 다음 배치에서 재시도되도록 하는 신호**(Cafe24 변경감지 스킵과 연동).

### core/domain/market/MarketRegistration#enrichIdentifier (D-046)
발행 시 `sellerProductId`만 저장되고 `vendorItemId`를 채우는 write-path가 없던 구조적 공백을 메우는 진입점. **기존 JSON을 보존하며 단일 키를 병합**하고, 값이 비면 no-op.

### core/domain/sync/MarketSyncStatus
`BaseEntity`에 이미 `status(RecordStatus)`가 있으므로 동기화 상태 필드는 **`syncStatus`(컬럼 `sync_status`)**로 둔다 — 이름 충돌 회피.

### core/application/process/BatchSummary
`done = success + failed`, `percent = total==0 ? 0 : round(done*100/total)`. 전체 행을 폴링으로 내려보내는 대신 count 쿼리로 산출한다.
