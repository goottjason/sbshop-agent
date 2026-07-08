# Defect Ledger — sbshop-agent

> 초판 작성: 2026-07-07  
> 진단 사이클: 사이클 1 (Phase 1)  
> 작성자: defect-scout

---

## 요약 표

| 지표 | 값 |
|------|-----|
| 총 결함 수 | 13 |
| P0 (빌드/시작 불가) | 2 |
| P1 (기능 불능) | 1 |
| P2 (오동작/아키텍처 리스크) | 5 |
| P3 (품질/부채) | 5 |

> 2026-07-07 갱신(tdd-fixer, 사이클 1 수정 중): 신규 D-009(AsyncConfig 2차 P0 빈 충돌)·D-010(Elevenst 클래스명 중복)·D-011(BatchPriceStockService bare @Async)·D-012(Coupang 탐색 테스트 잔재)·D-013(api 테스트 datasource/Flyway 순환 depends-on) 추가. D-001·D-003·D-009·D-006·D-012 수정완료(검증대기), D-001~D-006 qa 검증통과.
>
> 2026-07-07 사이클 1 종료(리더): **검증통과 5건**(D-001·D-003·D-006·D-009·D-012 — P0 2건 전부 해소), 보류 1건(D-002 오판 — 스케줄러는 실제 활성. 위 P1 카운트는 기록 보존 목적). 잔여 미해결 7건(D-004·D-005·D-007·D-008·D-010·D-011·D-013).
>
> 2026-07-07 사이클 9 갱신(리더, 운영 가동 이후 사용자 신고 3클러스터 병렬 진단): **신규 17건 등재 D-022~D-038**. 사용자 신고 3대 증상 직접원인 확정 — D-022(동기화 finally 이중이벤트 에러은폐)·D-027(쿠팡 오취소)·D-034(상품 cellRenderer HTML 이스케이프). P0 1(D-022)·P1 5(D-023·D-027·D-028·D-029·D-034)·P2 6·P3 5. 클러스터 C(주문상태)의 핵심은 "취소/반품/교환 전환을 동기화에 반영 못하는 구조적 공백"(쿠팡·11번가·ESM+). 클러스터 D(상품)는 UI 뼈대 방치 — 백엔드/API 완비, 상세모달·이미지변경 미배선.
>
> 2026-07-07 사이클 4 갱신(fixer-c4, D-005+D-013 통합 중대 배치·사용자 승인): **D-005·D-013 수정완료(검증대기)**. testcontainers-PostgreSQL 도입, api 순환 depends-on 해소(defer 제거), after-migrate.sql → V5 흡수(멱등). **D-013은 실 Postgres Red 재현으로 P0 확정**(운영 기동 차단). 배치 중 파생: **신규 D-015**(V1~V4 빈 DB 비자족 — V2 shipping_fee)·**D-016**(sb_market_credential/sb_market_registration 마이그레이션 부재), 둘 다 범위 밖 후보로 기록. Flyway 의존성 공백(`flyway-database-postgresql`)도 교정. 총 결함 16건(D-001~D-016).

## 최우선 수정 권고 3건 (사이클 2 대상 — 리더 갱신)

1. **D-013 (P2)** — api 테스트 datasource/Flyway 미구성: 현재 api 모듈에 @SpringBootTest 실기동 검증이 0인 상태. 통합 테스트 확보의 선결 과제 (D-005와 연계 처리 권장).
2. **D-004 (P2)** — 이미지 다운로더 3벌 통합: 구조 변경 배치. D-010과 함께 처리 가능.
3. **D-005 (P2)** — after-migrate.sql ↔ Flyway 중복 정리: 스키마 이력 단일화 (중대 등급 — 사용자 승인 필요).

---

## 결함 레코드

### D-001: ElevenstRestClient 중복 빈 충돌 → API 모듈 시작 불가

- 심각도: P0 (빌드/데이터 손상 — 앱 컨텍스트 시작 불가)
- 리스크 등급: 중대
- 위치:
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/ElevenstRestClient.java:23`
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/client/ElevenstRestClient.java:17`
- 증상: `@SpringBootTest` 컨텍스트 로딩 시 `ConflictingBeanDefinitionException` 발생. `CoupangApiExplorationTest`·`CoupangDebugTest` 모두 context load failure로 실패. 동일 패키지 스캔 경로를 쓰는 운영 `ApiApplication`도 시작 불가.
- 재현: `cd backend && ./gradlew :api:test` → `ConflictingBeanDefinitionException: Annotation-specified bean name 'elevenstRestClient' for bean class [...client.ElevenstRestClient] conflicts with [...elevenst.ElevenstRestClient]`
- 원인(확인): 두 클래스가 모두 `@Component`이고 기본 빈 이름 `elevenstRestClient`로 동일하게 등록됨. elevenst 패키지에 GET 전용 구버전, elevenst/client 패키지에 GET/POST/PUT + ElevenstProperties 주입 신버전이 공존. 레거시 병합 시 정리되지 않은 중복.
- 수정(2026-07-07): 두 클래스 모두 실사용처 존재(구버전←`ElevenstOrderApiClient` 주문 API, 신버전←`ElevenstMarketClient` 상품 API). 주입은 전부 타입 기반이므로 삭제/병합 없이 빈 이름만 분리 — 구버전 `@Component("elevenstOrderRestClient")`, 신버전 `@Component("elevenstMarketRestClient")`. 회귀 테스트: `api/src/test/.../ElevenstRestClientBeanConflictTest`. 클래스 단순명 동일(`ElevenstRestClient`)로 인한 혼동은 구조 개선 후보 → [[D-010]].
- 검증(2026-07-07, qa-verifier): 회귀 테스트 `ElevenstRestClientBeanConflictTest` Green(tests=1, failures=0), `:infrastructure:compileJava` 정상, 두 소비처 타입 주입·시그니처 유지, 문자열 빈 이름 참조 0건, 실제 elevenst 충돌 예외 전무. 전체 `:api:test`의 잔여 실패 3건은 전부 별개 원인(D-009 asyncConfig ×2, D-006 실API ×1)이며 D-001 유발 회귀 아님. 판정서: `_workspace/verify/D-001_verdict.md`.
- 상태: 검증통과 (범위: elevenstRestClient 충돌 해소. 영향모듈 전체 `:api:test` 그린은 [[D-009]] 해소 전제 — 커밋 게이트 조건)
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과(범위 한정, D-009 전제)

---

### D-002: OrderSyncScheduler 6개 스케줄 전부 비활성화

- 심각도: P1 (기능 불능)
- 리스크 등급: 표준
- 위치: `backend/worker/src/main/java/com/sbshop/agent/worker/scheduler/OrderSyncScheduler.java:37, 52, 67, 82, 97, 112`
- 증상: 이메일(IMAP), 쿠팡, ESM+(G마켓/옥션), 스마트스토어, 11번가, 쿠팡 정산 동기화 6개 메서드가 `// TODO: 리팩토링 완료 후 활성화` 주석으로 실질적 cron 어노테이션은 존재하나 실행 의도가 보류 상태. 통관 상태 동기화(`syncCustomsStatus`, line 127)만 활성화되어 있음.
- 재현: 앱 기동 후 30분 이상 대기해도 `IMAP 이메일 주문 동기화 시작...` 로그 미출력. `syncStatusService` 상태가 초기값에서 변경되지 않음.
- 원인(확인): `@Scheduled` 어노테이션이 선언되어 있어 Spring은 해당 메서드를 스케줄에 등록하지만, TODO 주석 자체는 코드 실행에 영향을 주지 않음. 즉 어노테이션은 살아있고 메서드는 실행됨. 재확인 필요 — 어노테이션이 제거된 게 아니라 주석만 남은 것이므로 실제 스케줄은 돌고 있을 가능성 있음. 소스코드 확인: 37번 라인의 `@Scheduled(cron = "0 0/30 * * * ?")` 어노테이션은 그대로 존재하고, `// TODO` 는 같은 줄 주석임. 따라서 **스케줄은 실제로 등록되어 있음**. TODO 주석이 오해를 유발하는 부채 수준이나, 기능 자체는 비활성화 아님. 스킬의 "비활성 기능" 패턴이 실제로는 코드 수준에서 해소된 상태. → 상태를 `보류`로 수정.
- 상태: 보류 (TODO 주석만 존재, 어노테이션·메서드 본체 모두 정상. 기능 비활성화 아님 — 오판)
- 이력: 2026-07-07 발견 → 2026-07-07 보류 (소스 재확인 결과 @Scheduled 어노테이션 정상 존재)

---

### D-003: api/AsyncConfig.productBatchExecutor() @Bean 어노테이션 누락

- 심각도: P2 (오동작)
- 리스크 등급: 표준
- 위치: `backend/api/src/main/java/com/sbshop/agent/api/config/AsyncConfig.java:13`
- 증상: `productBatchExecutor()` 메서드가 `@Bean` 없이 일반 메서드로 선언됨 → Spring 컨테이너에 빈으로 등록되지 않음. 비동기 처리 시 `product-batch-` 스레드가 아닌 기본 executor 또는 core 모듈의 `syncTaskExecutor`가 사용될 수 있음.
- 재현: `ApplicationContext.getBean("productBatchExecutor")` 시 `NoSuchBeanDefinitionException` 발생 예상. 또는 `@Async("productBatchExecutor")`로 주석된 메서드 존재 시 실행 오류.
- 원인(확인): `api/config/AsyncConfig.java` 13번 라인 — `public Executor productBatchExecutor()` 선언에 `@Bean` 없음. core 모듈 `AsyncConfig`(line 14)에는 `@Bean(name = "syncTaskExecutor")`가 정상 선언됨. api 모듈 AsyncConfig는 `@Configuration`만 있고 실제 빈 등록 메서드가 없는 껍데기 클래스가 됨.
- 수정(2026-07-07): `productBatchExecutor()`에 `@Bean(name = "productBatchExecutor")` 부여. 회귀 테스트: `api/src/test/.../config/ProductBatchExecutorBeanTest` (격리 컨텍스트에서 빈 등록 검증, Red=NoSuchBeanDefinitionException → Green).
- 파생 발견(범위 외, 후보): 원장 재현란은 `@Async("productBatchExecutor")` 참조 메서드를 가정했으나 **전수 검색 결과 그런 참조는 없음**. 의도된 소비자로 보이는 `core/.../product/BatchPriceStockService`의 배치 메서드 3곳(line 38·81·123)은 **한정자 없는 `@Async`**를 사용 → 빈을 등록해도 이름으로 자동 연결되지 않음. 빈이 2개 이상(syncTaskExecutor 등)이면 bare `@Async`는 전용 풀을 못 고르고 폴백. 즉 "product-batch- 스레드로 실제 처리" 목표는 `@Async("productBatchExecutor")` 한정자 지정이 추가로 필요 → 별도 결함 후보 [[D-011]]. D-003 자체(빈 등록)는 완료.
- 검증(2026-07-07, qa-verifier): `ProductBatchExecutorBeanTest` Green(tests=1, failures=0), `:core:test` 통과, D-009 수정과 동일 파일 공존 무충돌, 참조처 정합. 전용 풀 미배선은 [[D-011]]로 정당 분리(범위 밖). 판정서: `_workspace/verify/D-003_verdict.md`.
- 상태: 검증통과
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과

---

### D-004: ImageDownloadService — ImageDownloadClient 인터페이스 우회 중복 구현

- 심각도: P2 (오동작/아키텍처 리스크)
- 리스크 등급: 표준
- 위치:
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cloudflare/ImageDownloadService.java:18`
  - `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java:54`
- 증상: `ProductController`가 `ImageDownloadClient` 인터페이스를 거치지 않고 `ImageDownloadService`를 직접 주입받아 사용. `ImageDownloadService`는 `ImageDownloadClient`를 구현하지 않으므로 DI 계층 설계 위반. 동일한 `downloadAndConvert()` 로직이 `ImageDownloader`(RestTemplate)와 `ImageDownloadService`(OkHttpClient) 두 곳에 중복 구현됨.
- 재현: `ProductController.java:54` — `private final ImageDownloadService imageDownloadService;` (infrastructure 클래스 직접 참조). `ImageDownloadClient` 인터페이스(`core/domain/product/client/ImageDownloadClient.java`)를 구현하지 않은 `ImageDownloadService`가 API 계층에서 사용됨.
- 원인(추정): Cloudflare 이미지 차단 우회(User-Agent 헤더) 필요로 OkHttpClient 버전을 별도 작성했으나, 기존 `ImageDownloader`를 교체하지 않고 추가 생성. 레거시 병합 시 정리 누락.
- 수정(2026-07-07, 사이클 2 구조 배치): 두 소비처(ProductController·ProductCreateUseCase) 모두 `downloadAndConvert`만 호출·인터페이스 `download`/`downloadAll` 외부 소비처 0 확인. OkHttp+UA 구현 `ImageDownloadService`가 `ImageDownloadClient`를 구현하도록 변경(`downloadAndConvert` 본문 무변경 — @Override만; 계약 이행용 `download`/`downloadAll` 추가), `ProductController`를 인터페이스 주입으로 전환(계층 위반 해소), 잉여 RestTemplate 구현 `ImageDownloader` 삭제(사용처 0). 구조 변경이므로 특성화 테스트 `infrastructure/src/test/.../cloudflare/ImageDownloadServiceCharacterizationTest`(JDK HttpServer, 신규 의존성 0)로 출력 계약·User-Agent 전송·all-fail 예외를 통합 전후 그린으로 고정. 유일한 관측 변화: ProductCreateUseCase 다운로드 경로가 RestTemplate→OkHttp+UA(출력 계약 동일, User-Agent 추가만 — 리더 승인 방향). `:infrastructure:test :core:test :api:test` 전건 그린, worker 컴파일 무결. 수정 요지: `_workspace/fixes/D-004_fix.md`.
- 검증(2026-07-07, qa-verifier): **PASS**. 특성화 테스트 `ImageDownloadServiceCharacterizationTest` 실행 XML `tests=3 skipped=0 failures=0 errors=0`(User-Agent `Mozilla/5.0`+`Accept: image/*`·출력계약·all-fail 예외 고정). 경계면: `implements ImageDownloadClient` 정확히 1벌(ImageDownloadService), `ProductController`가 core 포트 주입(계층 위반 해소), 두 소비처 `downloadAndConvert`만 호출(신규 download/downloadAll 런타임 미도달), `ImageDownloader` 코드 참조 0. `downloadAndConvert` 본문 바이트 무변경 → 헤더 유실 회귀 없음. 전체 `./gradlew test` BUILD SUCCESSFUL. spotless 실측: D-004 3파일도 미준수이나 주변 하우스 스타일 정합(전역 [[D-014]], D-004 고유 결함 아님). 판정서: `_workspace/verify/D-004_verdict.md`.
- 상태: 검증통과
- 이력: 2026-07-07 발견 → 2026-07-07 수정중(사이클 2) → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과

---

### D-005: after-migrate.sql — Flyway 외부 스키마 변경이 V4 마이그레이션과 중복

- 심각도: P2 (오동작/데이터 리스크)
- 리스크 등급: 표준
- 위치: `backend/api/src/main/resources/after-migrate.sql`
- 증상: 앱 시작 시마다 `ALTER TABLE TYPE` DDL 재실행. `V4__product_vo_and_new_tables.sql`과 동일한 DDL(source_images/hosted_images jsonb 변환, detail_html text, delivery_fee/stock_status/restock_date 컬럼, sb_currency/sb_supplier/sb_process_status 테이블)이 after-migrate.sql에도 존재. `sb_order.market_specific_data VARCHAR(50000)` 타입 변경은 after-migrate.sql에만 존재하고 Flyway 마이그레이션에는 없음.
- 재현: 앱 기동 로그에서 `after-migrate.sql` 실행 확인. 반복 실행 시 `ALTER TABLE TYPE` 재실행으로 PostgreSQL에 불필요한 잠금 발생 가능.
- 원인(확인): Flyway 마이그레이션(V4)이 신규 추가된 시점에 after-migrate.sql을 정리하지 않음. `sb_order.market_specific_data` 컬럼은 Flyway 히스토리에 없어 마이그레이션 이력 추적 불가 상태.
- 수정(2026-07-07, 사이클 4 fixer-c4, D-013 통합·사용자 승인 / V5 설계 리더 확정): after-migrate.sql 항목별 대조 결과 V4와 중복이 아닌 유일 항목은 **`sb_order.market_specific_data`** 뿐(나머지 9개 문 — jsonb 변환×2·detail_html·신규 컬럼×3·신규 테이블×3 — 전부 V4가 커버, fix.md 대조표). 이를 신규 **`V5__absorb_after_migrate_market_specific_data.sql`**(infrastructure/db/migration)로 이전. **타입은 TEXT로 정규화**(엔티티 `Order.java`의 `@Column(columnDefinition = "TEXT")`와 정합; 구 after-migrate의 varchar(50000)은 무손실로 TEXT 수렴): `ADD COLUMN IF NOT EXISTS market_specific_data TEXT` + `DO $$ BEGIN ALTER COLUMN ... TYPE TEXT; END $$`(멱등 가드). `after-migrate.sql` 삭제(`git rm`), api `application.yml`의 `sql.init`(mode/schema-locations) 블록 제거. **실측 발견**: 구 after-migrate는 `market_specific_data`를 ADD 없이 `DO $$ ... EXCEPTION WHEN OTHERS THEN NULL`로 ALTER만 시도 → 컬럼이 없으면 조용히 no-op이라 실제로 컬럼을 보장하지 못했다(V5가 이 결함을 교정). 또한 after-migrate 전체를 V4 뒤에 재적용하면 jsonb 재변환부가 `invalid input syntax for type json`으로 **실패**(이미 jsonb인 컬럼을 `''`와 비교) → 구 after-migrate는 V4 이후 정상 실행 불가였음(제거의 추가 근거). 멱등 검증: `MigrationIdempotencyTest`(실 PostgreSQL/testcontainers, 3케이스) — 컬럼 부재(운영 V1~V4 재현·빈 DB)엔 TEXT 신규 생성, varchar(50000) 기존 존재 시 TEXT 수렴, 재적용 멱등. 수정 요지: `_workspace/fixes/D-005_fix.md`.
- 검증(2026-07-07, verifier-c4): **검증통과(PASS)** — 안정 상태 기준. 델타 완전성 재대조: after-migrate 8효과 중 7개는 V4 동일 존재, 유일 델타 market_specific_data만 V5 흡수(중복 0). 멱등 3경로(부재/빈DB/varchar 선존재) 모두 최종 `data_type='text'` 수렴 실증(`MigrationIdempotencyTest` 3/0/0). 타입 TEXT ↔ 엔티티 `Order.java:83 columnDefinition="TEXT"` 정합. 전체 백엔드 `test spotlessCheck --rerun-tasks` BUILD SUCCESSFUL(tests=31/0/0). **검증 무결성 주의**: fixer-c4가 "수정완료" 통보 후에도 편집 지속(V5→TEXT 전환 중), 초기 관측 flaky 실패는 mid-edit 아티팩트로 폐기·동결 상태 재검증. **미검증**: 운영 DB 실제 market_specific_data 상태 접근 불가(배포 시 실측 권고); 진짜 빈 DB V1~V5 전체 체인은 D-015로 미검증. 판정서: `_workspace/verify/D-005_verdict.md`.
- 상태: 검증통과 (사이클 4 verifier-c4 — V5 흡수·3경로 TEXT 수렴 멱등, 동결 상태 실증) — 이후 사이클 7에서 Flyway 자체 제거됨(사용자 결정: 스키마 수동 관리). V5 마이그레이션·`MigrationIdempotencyTest`·`pre_flyway_baseline.sql` 삭제. `market_specific_data` 컬럼은 운영 DB에 이미 존재(`Order.java` TEXT 매핑 유지).
- 이력: 2026-07-07 발견 → 2026-07-07 수정중(사이클 4, D-013 통합) → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과(verifier-c4) → 2026-07-07 Flyway 전면 제거로 V5 삭제(사이클 7 fixer-c7)

---

### D-006: SmartStoreApiExplorerTest 하드코딩 인증정보 + 실제 외부 API 호출

- 심각도: P2 (오동작 + 보안)
- 리스크 등급: 표준
- 위치: `backend/api/src/test/java/com/sbshop/agent/api/SmartStoreApiExplorerTest.java:18-19`
- 증상: `clientId = "1l5fRuKFzyNJGQF3AP27AE"`, `clientSecret = "$2a$04$24Vxb0j6X3HK.ZVUA43Wk."` 하드코딩. 실제 네이버 커머스 API 엔드포인트 호출 → HTTP 403 Forbidden 으로 테스트 실패. CI 환경에서 항상 실패하며, 인증정보가 git 히스토리에 노출됨.
- 재현: `cd backend && ./gradlew :api:test --tests "com.sbshop.agent.api.SmartStoreApiExplorerTest"` → `SmartStoreApiExplorerTest > exploreSmartStoreApi() FAILED: org.springframework.web.client.HttpClientErrorException$Forbidden at SmartStoreApiExplorerTest.java:46`
- 원인(확인): 탐색용 테스트가 그대로 잔류. `@SpringBootTest` 없이 실제 외부 API를 직접 호출하는 구조. 인증정보 만료 또는 권한 부족으로 403.
- 수정(2026-07-07): 파일 삭제(`git rm backend/api/src/test/.../SmartStoreApiExplorerTest.java`). 회귀 테스트가 아니라 결함 그 자체이므로 삭제가 정답(@Disabled 금지 규칙의 예외 — 리더 지시). 하드코딩 인증정보(`1l5fRuKFzyNJGQF3AP27AE`, `$2a$04$...`)가 다른 소스·설정에 재사용되지 않음을 전수 검색으로 확인(0건). 삭제 후 `:api:compileTestJava` 정상, `SmartStoreApiExplorer` 참조 0건.
- 파생 발견(범위 외, 후보): 같은 성격의 `CoupangApiExplorationTest`·`CoupangDebugTest`도 `@SpringBootTest` + 실 Coupang API 호출(`coupangOrderApiClient.fetchOrders`) + assertion 없는 `System.out.println` 탐색 코드. 하드코딩 인증정보는 없고 DB(`MarketCredentialRepository`)에서 읽으나, 실 외부 API 호출·라이브 datasource 의존으로 CI에서 항상 실패 → 삭제 권장. 신규 후보 [[D-012]].
- 검증(2026-07-07, qa-verifier): 하드코딩 인증정보 문자열 작업트리 0건, `SmartStoreApiExplorer` 참조 0건, 파일 삭제(staged) 확인, `:api:compileTestJava` BUILD SUCCESSFUL. 동종 Coupang 탐색 테스트 실 API 호출 잔존 → [[D-012]]. **보안 경고: 노출 인증정보가 git 히스토리에 잔존 → 네이버 커머스 콘솔에서 회전(재발급) 필요(수동 조치, 코드 범위 밖)**. 판정서: `_workspace/verify/D-006_verdict.md`.
- 상태: 검증통과
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과

---

### D-007: 프론트엔드 lint 59 errors

- 심각도: P3 (품질/부채)
- 리스크 등급: 경량
- 위치: `frontend/src/` 다수 파일
  - `frontend/src/pages/OrderGrid.tsx` — `@typescript-eslint/no-explicit-any` 다수, `@typescript-eslint/no-unused-vars` (line 1164, 1182), `react-hooks/incompatible-library` (line 1144)
  - `frontend/src/pages/Settings.tsx:20` — `react-hooks/set-state-in-effect`
  - `frontend/src/pages/ProductPage.tsx:14` — `@typescript-eslint/no-unused-vars`
  - `frontend/src/pages/ProductRegisterPage.tsx:2` — `@typescript-eslint/no-unused-vars`
- 증상: `npm run lint` 결과 59 errors, 2 warnings. `npm run build`는 통과.
- 재현: `cd frontend && npm run lint` → `✖ 61 problems (59 errors, 2 warnings)`
- 원인(확인): 타입 정의 없이 `any` 사용, 선언 후 미사용 변수, Effect 내 직접 setState 호출.
- 게이트 정정(2026-07-07, fixer-c5): 프론트 `npx tsc --noEmit`(루트 tsconfig.json `files:[]` references-only)은 **아무 소스도 검사 안 하는 헛-그린**, `npm run build`(vite/esbuild)도 타입체크 안 함 → 실제 타입 게이트는 `tsc --noEmit -p tsconfig.app.json`(리더가 하네스 게이트 커맨드 교정). 착수 전부터 tsc-app 8 errors 존재.
- 수정(2026-07-07, 사이클 5 fixer-c5): **행위 불변 우선(타입 계층만 변경 → 런타임 JS 소거 불변)**. 기준선(D-008 삭제 반영 후) 58 problems(56 errors, 2 warnings) → 결과 **6 problems(4 errors, 2 warnings)**, 에러 56→4(−52). 처리 —
  - 미사용 전량 제거: Table.tsx className 6건(전달 호출처 0 확인 후 구조분해 제거), ProductPage `loading`(`[, setLoading]`), ProductRegisterPage `InputNumber` import, OrderGrid 미사용 함수 `MarketFilter` 삭제(내부 any 동반 해소)·`catch (error)`×6/`catch (e)`×3 → bare `catch`.
  - no-explicit-any: orderApi.ts 10건(marketRegistration→unknown, 미소비 반환 Promise<any>→unknown, updateData→Record<string,unknown>) + OrderGrid 다수(불필요 `as any` 캐스트 제거 `row.order?.id`·`row.original.product`, 색상맵 3건·tanstack meta 2건·getCommonLabel `c`·mutation updates·handleUpdate value를 사용형태/DTO 기반 타입으로, axios catch 2건 `unknown`+shape 캐스트, ProcessingModal/selectedLineItem/open*Modal lineItem→`OrderGridDto`). **DTO 정합 보강**: `OrderLineItemDto.sourcingData`에 `sourcingVendor?: string` 추가 — 근거 백엔드 `core/domain/order/vo/SourcingData.java:30` 실재 필드(추측 아님).
  - tsc-app: 착수 8 errors 중 미사용 3건(MarketFilter·loading·InputNumber) 해소, **신규 0건** → 5 errors 잔존(전부 착수 전 기존: ORDER_COLUMNS/PRODUCT_COLUMNS implicit any[] ×4 + [[D-017]] updateOrderLineItem). `npm run build` exit 0. 수정 요지: `_workspace/fixes/D-007_fix.md`.
- 잔여 lint 4 errors + 2 warnings (하위항목 — 로직 재구성 필요분, 리더 지시대로 미강행):
  - `OrderGrid.tsx:18`·`:690` no-explicit-any(`onSubmit`/`handleModalSubmit` data): 모달 제출 페이로드가 소싱/배송 이질 union인데 판별자가 페이로드 밖 `modalMode`라 discriminated-union 내로잉 불성립 → 정밀 타입화는 모달 상태 리팩토링 필요(제출 경로 행위 영향). **권장**: modalMode를 payload에 태그로 편입해 discriminated union 구성 후 두 API 분기 내로잉.
  - `OrderGrid.tsx:38`·`Settings.tsx:20` react-hooks/set-state-in-effect: effect 내 동기 setState(폼 상태 seeding). **권장**: `key`-리마운트+`useState` 이니셜라이저 또는 렌더 파생/ref 가드(폼 리셋 타이밍 행위 민감 → 수동 검증 동반 필요).
  - `OrderGrid.tsx:1096` exhaustive-deps(warning), `:1098` incompatible-library(warning): 각각 memo 의존성/React Compiler-tanstack 호환 이슈 — 행위 민감·라이브러리 이슈로 보류.
- 검증(2026-07-07, verifier-c5): 3게이트 실측 — lint **6 problems(4 errors, 2 warnings)**(에러 56→4), 실 게이트 `tsc --noEmit -p tsconfig.app.json` **5 errors**(전부 착수 기준선 8건의 부분집합, 미사용 3건 해소, **신규 0=무회귀**; tsc 라인 시프트 535→489 등은 상위 삭제 결과·동일 에러), `npm run build` EXIT 0 ✓ built. 백엔드 diff 0건. **행위 불변 혼입 검사(핵심)**: 전 변경 타입 계층 확인 — `as any` 캐스트 제거·`unknown` 대체(stricter가 tsc-app 신규 0=소비처 무파손 실증)·bare catch 9건(error 미참조)·axios catch 캐스트(옵셔널 체이닝 보존, 런타임 동일)·MarketFilter 삭제(참조 0)·Table className(전달 호출처 0, OrderGrid 사용 블록 전수 부재). 경계면 대조: `sourcingVendor?: string` 추가는 백엔드 `SourcingData.java:31 private String sourcingVendor` 실재 + `OrderController`가 도메인 `OrderLineItem` 직접 직렬화(`ResponseEntity<OrderLineItem>`)로 서빙 확정 — 추측 아님, 승인. 잔여 6건 실 lint 출력과 fix.md 기록 정확 일치(침묵 생략 없음). 파생 관측: 배송 모달 `carrier` vs 백엔드 `shippingCarrier` 필드명 불일치 잠복 이슈(범위 밖, 트리아지 권함). 판정서: `_workspace/verify/D-007_verdict.md`.
- 상태: 검증통과 (잔여 lint 6건은 로직 재구성 필요분으로 보류 기록 — 커밋 게이트 판단은 리더)
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기, 사이클 5 fixer-c5) → 2026-07-07 검증통과(verifier-c5)

---

### D-008: orderApi.ts 미호출 함수 3건 — 대응 백엔드 엔드포인트 없음

- 심각도: P3 (품질/부채)
- 리스크 등급: 경량
- 위치: `frontend/src/api/orderApi.ts:207` (`purchaseItem`), `frontend/src/api/orderApi.ts:218` (`shipItem`), `frontend/src/api/orderApi.ts:227` (`updateTracking`)
- 증상: 세 함수가 프론트엔드 내 어디서도 import/호출되지 않음. 백엔드 `OrderController`에 대응 엔드포인트(`/purchase`, `/ship` on line-items, `/tracking`)가 존재하지 않음.
- 재현: `grep -rn "purchaseItem\|shipItem\|updateTracking" frontend/src --include="*.tsx" --include="*.ts"` — orderApi.ts 정의 외 0건. 백엔드: `grep -rn "line-items.*purchase\|line-items.*ship\|line-items.*tracking" backend/api/src` — 0건.
- 원인(추정): 미구현 기능 계획을 위한 placeholder로 추가됐으나 기능 구현이 이루어지지 않은 채 잔류. 의도적 보류인지 미완성인지 불분명 — 미확인.
- 수정(2026-07-07, 사이클 5 fixer-c5): 재현 재확인(프론트 참조 0건 + 백엔드 `OrderController` 전체 매핑 대조로 `/line-items/{id}/purchase`·`/line-items/{id}/ship`·`/line-items/{id}/tracking` 부재 확정 — 컨트롤러 `@PostMapping("/ship")`는 배치 `/api/v1/orders/ship`으로 프론트 `shipOrders` 소비, 라인아이템 ship 아님) 후 `orderApi.ts`의 세 함수(주석 포함) 삭제. 미완성 기능 부활은 범위 밖. 성공 기준(프론트 테스트 러너 부재): `npx tsc --noEmit` EXIT 0 + `npm run build` 성공 + 삭제 후 재-grep 0건 — 전부 그린. 다른 export/import 무변경(`updateShippingInfo`·`shipOrders`는 실 엔드포인트·호출처 있어 존치). 수정 요지: `_workspace/fixes/D-008_fix.md`.
- 검증(2026-07-07, verifier-c5): `git diff HEAD`로 세 함수 29줄 정확 삭제 확인(주변 로직 무변경, 행위 불변 혼입 없음). 타입체크 게이트 정정 — 루트 `tsc --noEmit`은 `files: []` references-only라 헛-그린이므로 실 게이트 `tsc --noEmit -p tsconfig.app.json` 실측: 8에러 검출되나 전부 OrderGrid/ProductPage/ProductRegisterPage 소재, orderApi.ts 0건(D-008 삭제가 app-tsc 에러 추가 안 함). `npm run build` ✓ built. 삭제 후 재-grep 0건. 경계면 대조: 삭제 3함수의 호출 URL(`/line-items/{id}/purchase` POST, `/line-items/{id}/ship` POST, `/line-items/{id}/tracking` PUT) 모두 `OrderController` 전체 매핑에 부재 확정(`/ship`은 배치 엔드포인트, `/line-items/{id}/shipping`은 PATCH — 불일치). 존치 `updateShippingInfo`·`shipOrders`는 실 호출처 있어 오삭제 없음. 백엔드 diff 0건(무접촉 준수). 판정서: `_workspace/verify/D-008_verdict.md`.
- 상태: 검증통과
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기, 사이클 5 fixer-c5) → 2026-07-07 검증통과(verifier-c5)

---

### D-009: AsyncConfig 중복 빈 이름 충돌 → API 모듈 시작 불가 (D-001에 가려져 있던 2차 P0)

- 심각도: P0 (앱 컨텍스트 시작 불가)
- 리스크 등급: 중대
- 위치:
  - `backend/core/src/main/java/com/sbshop/agent/core/config/AsyncConfig.java`
  - `backend/api/src/main/java/com/sbshop/agent/api/config/AsyncConfig.java`
- 증상: `@SpringBootTest` 컨텍스트 로딩 시 `ConflictingBeanDefinitionException: Annotation-specified bean name 'asyncConfig' for bean class [com.sbshop.agent.core.config.AsyncConfig] conflicts with existing, non-compatible bean definition of same name and class [com.sbshop.agent.api.config.AsyncConfig]`. D-001(ElevenstRestClient) 충돌을 해소하자 스캔이 더 진행되며 드러난 **동일 성격의 2차 빈 이름 충돌**. D-001 수정만으로는 API 컨텍스트가 여전히 시작하지 못함.
- 재현: `cd backend && ./gradlew :api:test --tests "com.sbshop.agent.api.CoupangApiExplorationTest"` → 위 예외로 context load failure. (D-001 수정 이후 상태)
- 원인(확인): `core.config.AsyncConfig`와 `api.config.AsyncConfig`가 둘 다 `@Configuration`이고 클래스 단순명이 같아 기본 빈 이름 `asyncConfig`로 동일 등록됨. D-003(api AsyncConfig의 `productBatchExecutor()` `@Bean` 누락)과 같은 파일을 다루나 **별개의 결함**: D-003은 빈 등록 누락, D-009는 빈 이름 충돌.
- 제안 수정: D-001과 동일 패턴 — 최소 변경으로 한쪽에 명시적 빈 이름 부여(예: `@Configuration("apiAsyncConfig")`). 두 AsyncConfig의 실질적 통합(중복 정의 제거)은 구조 변경으로 별도 취급.
- 수정(2026-07-07, 리더 배치 확장 승인): api `AsyncConfig`에 `@Configuration("apiAsyncConfig")` 부여(core는 기본 `asyncConfig` 유지). D-003과 커밋 단위 분리. 회귀 테스트: `api/src/test/.../config/AsyncConfigBeanNameConflictTest` (두 config 패키지 동시 스캔, refresh 없이 빈 정의 등록 확인 — JPA 초기화 회피). Red=ConflictingBeanDefinitionException → Green. 두 P0 빈 충돌(D-001·D-009) 해소 후 `CoupangApiExplorationTest`의 실패 원인이 `ConflictingBeanDefinitionException` → `entityManagerFactory`/`flyway` `BeanCreationException`(테스트 DB 미설정, 별개 인프라 이슈)으로 바뀜을 확인 = 빈 이름 충돌 완전 해소.
- 검증(2026-07-07, qa-verifier): `AsyncConfigBeanNameConflictTest` Green(tests=1, failures=0), `:core:test` 통과. 전체 `:api:test`에서 `ConflictingBeanDefinitionException` 완전 소멸(0건) — 두 P0 빈 충돌 해소 실측. 경계면 정합: api→`apiAsyncConfig`/core→`asyncConfig`, core 무변경, 문자열 참조 0, 주문 동기화 `@Async("syncTaskExecutor")` 정상. 판정서: `_workspace/verify/D-009_verdict.md`.
- 잔여 관측(신규 후보, 리더 트리아지 필요): 빈 충돌 소멸 후 `CoupangApiExplorationTest`·`CoupangDebugTest`의 `@SpringBootTest`가 `BeanCreationException: Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`(테스트 datasource/Flyway 미구성)로 여전히 로드 실패 → 전체 `:api:test` 그린의 잔여 블로커. D-009 회귀 아님. [[D-005]] 연관 가능성.
- 상태: 검증통과 (범위: asyncConfig 충돌 해소. 전체 `:api:test` 그린은 위 flyway 인프라 이슈 해소 전제 — 커밋 게이트 조건)
- 이력: 2026-07-07 발견 (tdd-fixer, D-001 수정 중 파생 발견) → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과(범위 한정)

---

### D-010: ElevenstRestClient 클래스 단순명 중복 (구조 개선 후보)

- 심각도: P3 (품질/부채)
- 리스크 등급: 경량
- 위치:
  - `backend/infrastructure/.../client/elevenst/ElevenstRestClient.java`
  - `backend/infrastructure/.../client/elevenst/client/ElevenstRestClient.java`
- 증상: 서로 다른 두 클래스가 동일 단순명 `ElevenstRestClient`를 사용 → 빈 이름 충돌(D-001)의 근본 원인이자 코드 가독성 저하. D-001은 명시적 빈 이름으로 충돌만 해소했고, 클래스명 자체의 혼동은 남아있음.
- 제안 수정: 신버전을 용도에 맞게 리네임(예: `ElevenstApiHttpClient`)하고 import 갱신. 구조 변경(Tidy First)이므로 행위 수정과 분리해 별도 커밋.
- 수정(2026-07-07, 사이클 2 구조 배치): 양쪽 다 역할 드러나는 이름으로 리네임 — `elevenst.ElevenstRestClient`→`ElevenstOrderRestClient`(주문 API), `elevenst.client.ElevenstRestClient`→`ElevenstMarketRestClient`(상품 API). 리더 지시가 두 빈 이름 정합을 명시했으므로 한쪽만이 아닌 양쪽 리네임으로 클래스명↔빈이름 일치. 리네임으로 기본 빈 이름이 D-001의 명시 한정자(`elevenstOrderRestClient`/`elevenstMarketRestClient`)와 문자열 동일해져 명시 한정자 제거(평문 `@Component`) — 빈 이름 무변경. 소비처(ElevenstOrderApiClient·ElevenstMarketClient) 주입 타입·import 갱신, D-001 회귀 테스트 FQN 갱신. 구 단순명 코드 참조 grep 0(잔존은 회귀 테스트 Javadoc 역사 설명 1건). 전체 `./gradlew test` BUILD SUCCESSFUL. 수정 요지: `_workspace/fixes/D-010_fix.md`.
- 검증(2026-07-07, qa-verifier): **부분** — 기능·동작불변·경계면 전부 PASS, 단 검증자 유발 포맷 오염 1건 정리 후 커밋 가능. 전체 `./gradlew test` BUILD SUCCESSFUL(회귀 테스트 D-010 반영본 포함). 빈 이름 정합: 두 신규 클래스 평문 `@Component`→기본 빈 이름 `elevenstOrderRestClient`/`elevenstMarketRestClient`로 D-001 명시 한정자와 문자열 동일, `@Qualifier`/문자열 빈 참조 0. 구 단순명 코드 참조 0(Javadoc 역사 문자열 1건만). old 삭제본 vs 신규 diff: 메서드 본문 의미 동일(차이는 Javadoc/포맷) → 행위 불변. **정리 필요(검증자 귀책, fixer 결함 아님)**: `ElevenstMarketClient.java`에 검증자의 모듈 전역 `spotlessApply` 오염(BigDecimal 미사용 import 제거·if 줄분리 등)이 정당 rename 2줄과 혼입 → `git checkout HEAD --` 후 import/필드 타입 2줄만 재적용해 `ElevenstOrderApiClient`처럼 clean rename-only로 정리 필요(tdd-fixer 요청 전송). 판정서: `_workspace/verify/D-010_verdict.md`.
- 정리 완료(2026-07-07): tdd-fixer가 `ElevenstMarketClient.java`를 HEAD 복원 후 rename 2줄만 재적용 → 검증자 유발 오염 제거 확인. 현재 diff는 import+필드 타입(비공백 4줄)로 `ElevenstOrderApiClient`와 동일한 clean rename-only. 재검증 전체 `./gradlew test` **BUILD SUCCESSFUL**. → **PASS 승격**.
- 상태: 검증통과
- 이력: 2026-07-07 발견 (tdd-fixer, D-001 수정 중 기록) → 2026-07-07 수정중(사이클 2) → 2026-07-07 수정완료(검증대기) → 2026-07-07 부분(오염 정리 대기) → 2026-07-07 검증통과(오염 정리 후)

---

### D-011: BatchPriceStockService가 productBatchExecutor를 한정자로 참조하지 않음

- 심각도: P3 (품질/부채 — 전용 스레드풀 미사용)
- 리스크 등급: 경량
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/application/product/BatchPriceStockService.java:38, 81, 123`
- 증상: 세 배치 메서드가 한정자 없는 `@Async`를 사용. D-003으로 `productBatchExecutor` 빈을 등록해도 이름으로 자동 연결되지 않아 `product-batch-` 전용 풀이 실제로 쓰이지 않음. Executor 빈이 복수면 bare `@Async`는 전용 풀을 선택하지 못하고 폴백(경고 후 SimpleAsyncTaskExecutor 등).
- 재현: `grep -rn "@Async" backend/.../BatchPriceStockService.java` → 한정자 없음. `@Async("productBatchExecutor")` 전수 검색 0건.
- 제안 수정: 세 지점을 `@Async("productBatchExecutor")`로 지정. 행위 변경이므로 실제 풀 사용을 검증하는 테스트와 함께 별도 처리.
- 수정(2026-07-07, 사이클 3 행위 배치 fixer-c3): 세 배치 메서드(`crawlAndUpdatePriceStock`·`manualUpdatePriceStock`·`manualUpdateAllFields`) `@Async`→`@Async("productBatchExecutor")`(본문·시그니처 무변경). Red 테스트: `core/src/test/.../product/BatchPriceStockAsyncPoolTest`(신규 의존성 0) — ①행위: 운영과 동일한 executor 복수 공존(`productBatchExecutor`+`syncTaskExecutor`) 재현 컨텍스트에서 배치가 `product-batch-` 스레드에서 실행됨 검증(Red=폴백 풀 접두 불일치→Green), ②리플렉션: 세 메서드 `@Async.value=="productBatchExecutor"` 검증(Red=""→Green). **한계(모듈 경계)**: 실 `productBatchExecutor` 빈은 api 모듈에 있어 core 단독 컨텍스트 로드 불가 → 테스트는 실빈 대신 동일 조건 재현 컨텍스트로 한정자 라우팅을 검증(핵심 결함 정확 재현, api 실빈 배선 자체는 D-013 인프라 확보 후 별도). `:core:test` 전체 BUILD SUCCESSFUL, 변경 2파일 spotless 위반 0. 수정 요지: `_workspace/fixes/D-011_fix.md`.
- 검증(2026-07-07, verifier-c3): **반려(FAIL)**. 한정자 추가·격리 테스트(`BatchPriceStockAsyncPoolTest` tests=2, failures=0)·`:core:test` BUILD SUCCESSFUL·worker 컴파일 정상은 확인되나, **경계면 불일치(설정↔빈, 모듈 경계)로 런타임 회귀 도입**: `productBatchExecutor` 빈은 api 모듈에만 정의(`api/config/AsyncConfig.java:14`). 그러나 이 @Async 메서드 `crawlAndUpdatePriceStock`는 worker `BatchScheduler`(매일 05시 cron, worker `@EnableScheduling`+core `@EnableAsync` 활성)가 호출하는데 **worker는 api 미의존(core+infra만)** → worker 컨텍스트에 빈 부재 → 호출 시점에 `findQualifiedExecutor("productBatchExecutor")`가 `NoSuchBeanDefinitionException` throw(스케줄러 스레드 동기). 수정 전 bare @Async는 유일 TaskExecutor(syncTaskExecutor)로 폴백해 정상 실행 → 이 변경이 worker iHerb 정기 배치를 "동작→런타임 예외"로 회귀. worker 테스트 0건으로 자동 커버리지 없음. fixer 격리 테스트는 빈을 테스트 내부 정의하므로 원리적으로 이 공백 미검출. **수정 지시**: `productBatchExecutor` @Bean을 core.AsyncConfig로 이전하고 api에서 삭제(양쪽 동시 정의 시 api 컨텍스트가 core+api 둘 다 스캔→빈 이름 중복 `ConflictingBeanDefinitionException` 재발, D-001/D-009 동종). 판정서: `_workspace/verify/D-011_verdict.md`.
- 재수정(2026-07-07, 리더 방향 확정 fixer-c3): verifier 지시대로 **`productBatchExecutor` @Bean을 api→core `AsyncConfig`로 이전(승격)** + api에서 제거. api `AsyncConfig`는 껍데기 존치(`@Configuration("apiAsyncConfig") @EnableAsync` — D-009 빈 이름·`AsyncConfigBeanNameConflictTest` 보존, 정리 후보 기록). 중복 정의 회피로 `ConflictingBeanDefinitionException` 미발생 확인(`:api:test` BUILD SUCCESSFUL). D-003 회귀 테스트 `api/.../ProductBatchExecutorBeanTest`→`core/.../config/ProductBatchExecutorBeanTest` 이전(빈 등록+전용풀 검증 의도 유지). 행위 테스트 `BatchPriceStockAsyncPoolTest`를 **실제 core `AsyncConfig`만 로드(=worker 컨텍스트 동일 조건)**하도록 재작성 — worker 경로에서 한정자 해소·`product-batch-` 라우팅 검증(core 빈 이름 일시 변경으로 Red 실측 후 원복해 가드 유효성 입증). `:core:test :api:test :worker:compile*` BUILD SUCCESSFUL(BatchPriceStockAsyncPoolTest 2/0F, core ProductBatchExecutorBeanTest 1/0F, D-009 test 1/0F 무변경), 변경 파일 spotless 위반 0. 수정 요지 갱신: `_workspace/fixes/D-011_fix.md`.
- 범위 외 후보(원장 기록): api `AsyncConfig` 빈-없는 껍데기(`apiAsyncConfig`) — D-009 보존 위해 존치, 향후 `@EnableAsync` 이설 후 클래스 제거 + D-009 회귀 테스트 동반 갱신 검토.
- 재검증(2026-07-07, verifier-c3): **검증통과(PASS)**. 반려 지시(빈 core 이전) 정확 반영으로 1차 FAIL 근본원인 해소 실측. 경계면 재검증: ①`productBatchExecutor` 정의처 core 단 하나(전수 grep, 중복 0) → worker 컨텍스트(core 스캔)에서 한정자 해소·NoSuchBeanDefinitionException 소멸, ②D-009 빈 이름 충돌 재발 없음(core=`asyncConfig`/api=`apiAsyncConfig`, `AsyncConfigBeanNameConflictTest` Green), ③이중 @EnableAsync 무해(Spring imported config 중복제거, 수정 전에도 양쪽 존재). 전체 `./gradlew test --rerun-tasks` **BUILD SUCCESSFUL**(BatchPriceStockAsyncPoolTest 2/0/0, core ProductBatchExecutorBeanTest 1/0/0, D-009·D-001 회귀 테스트 각 1/0/0). 가드 강도: `BatchPriceStockAsyncPoolTest`가 실 core AsyncConfig만 로드(=worker 대표, TestBeans는 executor 미정의)하므로 빈 부재 시 반드시 Red — 결함 본질 실제 포착(설계 검증). 미검증: api 실 @SpringBootTest end-to-end는 D-013 미해소로 별개(범위 밖). 판정서: `_workspace/verify/D-011_verdict.md`.
- 상태: 검증통과 (빈 core 이전으로 api·worker 양 컨텍스트 한정자 해소, D-009/D-001 회귀 없음, 전체 test 그린)
- 이력: 2026-07-07 발견 (tdd-fixer, D-003 수정 중 기록) → 2026-07-07 수정중(사이클 3) → 2026-07-07 수정완료(검증대기, 한정자만) → 2026-07-07 반려(verifier-c3, 경계면 불일치) → 2026-07-07 재수정 수정완료(검증대기, 빈 core 승격) → 2026-07-07 검증통과(verifier-c3, 재검증)

---

### D-012: CoupangApiExplorationTest·CoupangDebugTest — 실 API 호출 탐색용 테스트 잔재

- 심각도: P3 (품질/부채 — CI 상시 실패)
- 리스크 등급: 경량
- 위치:
  - `backend/api/src/test/java/com/sbshop/agent/api/CoupangApiExplorationTest.java`
  - `backend/api/src/test/java/com/sbshop/agent/api/CoupangDebugTest.java`
- 증상: 둘 다 `@SpringBootTest` + 실제 Coupang API 호출(`coupangOrderApiClient.fetchOrders`) + assertion 없는 `System.out.println` 탐색 코드(D-006의 SmartStore 테스트와 동일 성격). 하드코딩 인증정보는 없고 DB(`MarketCredentialRepository`)에서 읽으나, 라이브 datasource·실 외부 API 의존으로 CI에서 항상 실패(현재 테스트 datasource 미설정으로 `entityManagerFactory`/`flyway` BeanCreationException).
- 재현: `cd backend && ./gradlew :api:test --tests "com.sbshop.agent.api.CoupangApiExplorationTest"` → context/bean 생성 실패.
- 제안 수정: 삭제(D-006과 동일 판단). 또는 마켓 API를 MockWebServer로 대체한 진짜 회귀 테스트로 재작성.
- 수정(2026-07-07, 리더 배치 포함 승인): 두 파일 삭제(`git rm`). D-006과 동일 근거·처리(assertion 없는 탐색 잔재 + 실 API/라이브 DB 의존). 하드코딩 인증정보는 없음(DB 조회) — 재사용 grep 확인 대상 없음, 잔여 테스트 디렉터리 자격증명 문자열 0건. 삭제로 묻히는 인프라 이슈는 신규 [[D-013]]으로 별도 기록.
- 검증(2026-07-07, qa-verifier): 두 파일 삭제·참조 잔존 0건 확인. `:api:test --rerun-tasks` **BUILD SUCCESSFUL**(결과 파일 정확히 3개, 각 tests=1/failures=0/errors=0). 전체 모듈 `./gradlew test` **BUILD SUCCESSFUL**(api 3 + core 7, infra/worker NO-SOURCE) — 백엔드 전체 그린, 회귀 없음. 근본 인프라 이슈는 [[D-013]]으로 분리 추적. 판정서: `_workspace/verify/D-012_verdict.md`.
- 상태: 검증통과 (이로써 사이클 1 배치 5건 수정 후 커밋 게이트 `:api:test`·전체 test 그린 실측 성립)
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과

---

### D-013: api 모듈 테스트 datasource/Flyway 미구성 → @SpringBootTest 시 flyway↔entityManagerFactory 순환 depends-on

- 심각도: P2 (테스트 인프라 부재 — api 통합 테스트 작성의 선결 과제)
- 리스크 등급: 표준
- 위치: `backend/api` 테스트 구성 (`src/test/resources` 부재 — 테스트용 `application.yml`/datasource 없음), 연관: `backend/api/src/main/resources/application.yml`, `after-migrate.sql`([[D-005]])
- 증상: api 모듈에 테스트 전용 datasource/프로파일이 없어 `@SpringBootTest`가 운영 `application.yml`(PostgreSQL + Flyway)을 그대로 로드. 컨텍스트 기동 시 `BeanCreationException: Error creating bean 'flyway' ... Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`로 실패. 이 때문에 api에서 전체 컨텍스트를 띄우는 통합 테스트를 현재 작성할 수 없음(D-012의 탐색 테스트들이 실패하던 실제 원인이기도 함).
- 재현: api에 `@SpringBootTest` 테스트를 추가하고 `./gradlew :api:test` 실행 → 위 순환 depends-on 예외. (D-012 삭제 전 `CoupangApiExplorationTest`에서 실측됨.)
- 원인(확인): 테스트 classpath에 H2는 있으나(`testImplementation 'com.h2database:h2'`) 테스트용 `spring.datasource`/`spring.flyway`/프로파일 오버라이드가 없어 운영 설정이 적용됨. Flyway와 JPA(entityManagerFactory) 간 depends-on 배선이 이 환경에서 순환으로 해석됨(운영 프로파일·`after-migrate.sql` 구성과 얽힘 — D-005 연관).
- 제안 수정(범위 밖, 별도 배치): api `src/test/resources/application.yml`에 H2(또는 Testcontainers-Postgres) datasource + `spring.flyway.enabled=false`(또는 테스트 마이그레이션) + 필요한 프로파일을 구성해 컨텍스트 기동을 격리. testcontainers 도입은 신규 의존성이므로 리더 승인 필요(tdd-doctrine). 이 선결 과제 해소 전에는 api 통합/@SpringBootTest 테스트를 추가하지 말 것.
- 분석(2026-07-07, 사이클 3 fixer-c3 — 실측 4실험 후 삭제, 코드 변경 0): **수정 보류·리더 승인 요청.** 근거: 이 문제는 datasource 단일 이슈가 아니라 3중 구조 문제. ①**순환의 단독 트리거는 `spring.jpa.defer-datasource-initialization: true` + Flyway**로 격리 확인(defer=false→순환 소멸·Flyway 실행, defer=true→순환 재현). 순환은 **DB 무관**(빈 정의 시점) → 운영 Postgres 기동에도 동일 발생 개연 = 운영 기동 리스크 확인 필요(P0 승격 후보). ②**H2 불가 실측**: 순환 깬 뒤 H2(PG 호환)로 Flyway 실행 시 **V2에서 이미 실패**(V4 jsonb·after-migrate DO$$에 도달조차 못 함). ③H2+ddl-auto 우회조차 그린 아님 — **`R2Config`의 `s3Client`가 기동 즉시 생성되며 `Access key ID cannot be blank`로 실패**(외부 클라이언트 즉시-생성 빈 다수). 판정: H2 경로는 운영 스키마 검증력 상실(리더 금지) + 그린도 안 됨 → 반려. 정도=testcontainers-Postgres(신규 의존성, 승인 필요; 캐시·Docker 준비됨) + `defer` 제거·after-migrate↔Flyway 단일화(=**D-005 처리와 동일 작업, 중대·사용자 승인**) + 외부빈 목/범위축소. 상세: `_workspace/fixes/D-013_fix.md`.
- 승인 요청 항목: (1) testcontainers-Postgres 신규 의존성 도입, (2) D-005와 병합 처리(순환 해소가 defer 제거+after-migrate 단일화를 요구 → 분리 불가), (3) 운영 api 실제 기동 여부 확인(순환 DB 무관 → 운영도 실패 가능성).
- 리더 판정(2026-07-07): (1)(2)는 **중대 등급 — 사용자 승인 대상**(자율 마커로도 생략 불가). 사이클 3 마감 보고에서 사용자에게 승인 요청 예정. 그때까지 분석완료(승인대기) 유지·코드 변경 금지. (3) **운영 기동 차단 개연 — 리더 config 실측 확인**: api `application.yml`에 `defer-datasource-initialization: true` + `flyway.enabled: true` + `sql.init.mode: always` 그대로 존재, 프로파일 오버라이드 없음. D-001/D-009가 기동을 먼저 막고 있어 이 순환이 관측된 적 없었을 뿐일 개연 높음. → **P0 승격 후보**(심각도 P0-후보 표기; 실기동 재현 전이므로 단정 금지).
- 심각도(갱신): P2(테스트 인프라) + **P0 확정(운영 기동 차단 — testcontainers 실 PostgreSQL로 재현 실측)**.
- Red 실측(2026-07-07, 사이클 4 fixer-c4): testcontainers-PostgreSQL `@SpringBootTest`(`ApiContextLoadSmokeTest`)를 현재 설정 그대로 실행 → `BeanCreationException: ... Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`로 컨텍스트 로드 **실패**. DB 접속 이전 빈 와이어링 단계에서 발생(실 Postgres·더미 외부자격 무관) → **운영 기동 차단 P0 확정**(P0-후보 → P0 승격, 반증 아님).
- 수정(2026-07-07, 사이클 4 fixer-c4, 사용자 승인): ①**testcontainers 도입** — api `build.gradle`에 `org.testcontainers:junit-jupiter` + `:postgresql`(testImplementation, 최소 범위). ②**순환 해소** — api `application.yml`에서 `spring.jpa.defer-datasource-initialization: true` 제거(+ `sql.init` 블록 제거, D-005). Green 실측: `ApiContextLoadSmokeTest`가 실 Postgres에서 컨텍스트 로드 성공 + V1~V5 적용 + `market_specific_data` 존재 확인. ③**Flyway 의존성 공백 교정(신규 발견)** — Flyway 10.x는 Postgres 지원에 `flyway-database-postgresql` 모듈이 필요한데 infrastructure엔 `flyway-core`만 있어 실 Postgres에서 `Unsupported Database: PostgreSQL 16.14`로 실패 → infrastructure `build.gradle`에 `runtimeOnly 'org.flywaydb:flyway-database-postgresql'` 추가(운영·worker Flyway도 이걸 없인 못 돌던 상태였음). ④R2 `S3Client` 등 외부 클라이언트 즉시생성 빈은 테스트 프로퍼티 더미 자격증명으로 통과(운영 코드 무변경, 실 자격·실 호출 없음). 수정 요지: `_workspace/fixes/D-013_fix.md`.
- worker 확인(step 5): worker `application.yml`에는 `defer-datasource-initialization`·`sql.init` 없음 → 동일 순환 패턴 없음. worker는 infrastructure 경유로 `flyway-database-postgresql`를 함께 획득(Flyway 정상화 파급 이득).
- 검증(2026-07-07, verifier-c4): **검증통과(PASS)** — 안정 상태 기준. `ApiContextLoadSmokeTest`(@SpringBootTest, 실 Postgres/testcontainers)가 운영 yml+Flyway V1~V5로 컨텍스트 정상 로드 → 순환 depends-on 해소 실증(Red→Green). 트리거 제거가 운영 config 자체 수정(api yml `sql.init`·`defer` 제거)으로 재현 왜곡 없음. 경계면: worker yml은 원래 clean 패턴 → api 수렴 정합; 테스트 더미 자격증명 `@DynamicPropertySource`(test 스코프) 한정, 운영 빈 무변경. `:api:test --rerun-tasks` 4회 결정적 통과. **미검증**: 실 운영 api 기동 여부/프로파일 오버라이드 접근 불가(배포 시 확인 권고); `flyway-database-postgresql` 부재가 운영/worker Flyway를 막고 있었는지 로그 미확인. 판정서: `_workspace/verify/D-013_verdict.md`.
- 상태: 검증통과 (사이클 4 verifier-c4 — 순환 해소 실증, 설정↔기동 경계면 정합) — 이후 사이클 7에서 Flyway 자체 제거됨(사용자 결정). 순환 유발 축(Flyway↔entityManagerFactory)이 소멸했고, `ApiContextLoadSmokeTest`는 Flyway 의존을 걷어낸 뒤에도 실 Postgres 컨텍스트 기동 검증을 유지하도록 재작업(테스트 한정 `ddl-auto: create-drop`, 엔티티 매핑 스키마).
- 이력: 2026-07-07 발견 (tdd-fixer, D-012 수정 중 기록) → 2026-07-07 분석완료·승인대기(fixer-c3, 사이클 3) → 2026-07-07 리더 판정(사용자 승인 대상 확인 + 운영 기동 P0-후보 config 실측) → 2026-07-07 수정중(사이클 4, 사용자 승인) → 2026-07-07 Red 실측(P0 확정) → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과(verifier-c4) → 2026-07-07 Flyway 제거로 스모크 재작업(사이클 7 fixer-c7)

---

### D-014: infrastructure 모듈 spotless 포맷 위반 29건 (기존 부채 — 커밋 게이트 리스크)

- 심각도: P3 (품질/부채)
- 리스크 등급: 경량
- 위치: `backend/infrastructure/src/main/java` 다수 (`Cafe24TokenManager.java` 외 28개 파일)
- 증상: `./gradlew :infrastructure:spotlessCheck` 실행 시 `Cafe24TokenManager` 외 28개 파일에서 포맷 위반으로 BUILD FAILED. `./gradlew :infrastructure:spotlessApply`로 일괄 교정 가능하다고 spotless가 안내.
- 재현: `cd backend && ./gradlew :infrastructure:spotlessCheck` → `The following files had format violations ... Violations also present in 28 other files.`
- 원인(확인): 사이클 2와 무관한 리포지토리 전역 포맷 부채. tdd-fixer가 D-004/D-010 수정 중 표면화(내 변경 파일들은 위반 없음 — 별개). 사이클 1 커밋이 통과한 정황상 현재 커밋 게이트는 `test`만 돌리고 `spotlessCheck`는 미포함으로 추정.
- 제안 수정(범위 밖): `spotlessApply` 일괄 교정은 대량 diff를 만들므로 별도 배치로 분리. 커밋 게이트에 `spotlessCheck` 편입 여부는 리더 정책 결정 사항.
- 범위 확장 관측(2026-07-07, fixer-c3 사이클 3): infrastructure뿐 아니라 **core 모듈에도 동종 전역 위반 ~40개 파일** 존재(`:core:spotlessCheck` 실측). 커밋 게이트 `spotlessCheck` 미포함 전제와 정합. D-014 배치 시 core 포함 권고.
- 상태: 발견 (tdd-fixer, 사이클 2 D-004 수정 중 기록만; core 위반 사이클 3 추가 관측)
- 이력: 2026-07-07 발견 (tdd-fixer, 사이클 2 구조 배치 중 파생 기록) → 2026-07-07 core 전역 위반 관측 추가(fixer-c3)

---

### D-015: Flyway 마이그레이션 V1~V4가 빈 DB에서 자족적이지 않음 (pre-Flyway 베이스라인 가정)

- 심각도: P2 (신규 환경 배포/재구축 불가 리스크)
- 리스크 등급: 표준
- 위치: `backend/infrastructure/src/main/resources/db/migration/V1__init_schema.sql`, `V2__move_shipping_fee_to_logistics_cost.sql`
- 증상: 진짜 빈 DB에 Flyway V1부터 적용하면 **V2에서 실패** — `UPDATE sb_order_line_item SET logistics_cost = shipping_fee`가 참조하는 `shipping_fee` 컬럼을 V1이 생성하지 않는다(`sb_order_line_item`에 없음). V1은 `CREATE TABLE IF NOT EXISTS`라 운영의 pre-Flyway 기존 테이블엔 no-op이었고, `shipping_fee`는 Flyway 도입 이전 스키마(ddl-auto 시대)에만 존재했다. 즉 마이그레이션은 "기존 운영 스키마에 대한 델타"로만 작성되어 from-scratch 재현이 불가능.
- 재현(2026-07-07, fixer-c4 실측): testcontainers 빈 Postgres에 V1→V5 순차 적용 시 `ERROR: column "shipping_fee" does not exist` (Script V2 failed). git 전수: `shipping_fee`는 V1의 어느 버전에도 없었음(0건).
- 원인(확인): Flyway가 기존 운영 DB에 사후 도입되며 baseline 개념 없이 델타 스크립트만 축적됨. 운영 정합상 V1~V4 수정은 checksum 위험(운영에 이미 적용됐다는 전제) → 수정 시 `baseline` 전략·V0 재구성 등 별도 설계 필요.
- 임시 대응(테스트): `ApiContextLoadSmokeTest`/`MigrationIdempotencyTest`는 pre-Flyway 베이스라인(`api/src/test/resources/legacy/pre_flyway_baseline.sql`)을 먼저 세팅해 운영 경로를 재현.
- 상태: 무효화 (2026-07-07 사용자 결정: Flyway 제거·스키마 수동 관리) — 마이그레이션 자족성 자체가 무의미해짐. 사이클 7에서 Flyway 런타임·의존성·V1~V5 마이그레이션·`pre_flyway_baseline.sql` 전면 제거. 운영 DB는 데이터가 채워진 살아있는 원본으로 사용자가 직접 스키마 관리. from-scratch 재구축은 엔티티 매핑(테스트 한정 `ddl-auto: create-drop`으로 실증)으로 대체.
- 이력: 2026-07-07 발견 (fixer-c4) → 2026-07-07 무효화 (사이클 7 fixer-c7, 사용자 Flyway 제거 결정)

---

### D-017: OrderGrid `updateOrderLineItem` 미정의 → 라인아이템 인라인 편집 시 ReferenceError

- 심각도: P2 (기능 불능 — 라인아이템 필드 인라인 편집 경로 깨짐)
- 리스크 등급: 표준
- 위치: `frontend/src/pages/OrderGrid.tsx:535` (`lineItemMutation`의 `mutationFn`이 `updateOrderLineItem(id, updates)` 호출)
- 증상: `updateOrderLineItem`가 호출되지만 `orderApi`에서 import되지도(9행 import 목록에 없음), 파일 내 정의되지도 않음. `tsc -p tsconfig.app.json`에서 `error TS2304: Cannot find name 'updateOrderLineItem'`. `vite build`(esbuild)는 타입체크를 하지 않아 빌드는 통과하나, 런타임에 `updateOrderLineItem`은 undefined → 호출 시 ReferenceError.
- 도달 경로(실측): 세 UI 입력이 `handleUpdate(..., 'lineItem.*', ...)`를 호출 → `lineItemMutation.mutate` → `updateOrderLineItem`:
  - `OrderGrid.tsx:1063` 소싱금액(`lineItem.sourcingAmount`, onBlur)
  - `OrderGrid.tsx:1067` 물류비(`lineItem.logisticsCost`, onBlur)
  - `OrderGrid.tsx:1100` 유니패스 완료 체크박스(`lineItem.isUnipassDone`, onChange)
  - → 사용자가 이 세 필드를 편집(blur/toggle)하면 mutation 실행 시점에 예외.
- 재현: `cd frontend && npx tsc --noEmit -p tsconfig.app.json` → `src/pages/OrderGrid.tsx(535,68): error TS2304`. (런타임 재현: 위 세 입력 편집 → 콘솔 ReferenceError·상태 미저장.)
- 원인(추정): 백엔드 라인아이템 수정 엔드포인트는 `PATCH /api/v1/orders/line-items/{lineItemId}`(OrderController:126)로 존재. 프론트에 대응 `updateOrderLineItem` API 래퍼가 있어야 하나 orderApi.ts에 미구현/누락. 인접 래퍼(`updateSourcingInfo`·`updateShippingInfo`)만 있고 범용 라인아이템 patch 래퍼는 누락된 상태. — 미확인(백엔드 엔드포인트 대조까지만 확인).
- 수정(2026-07-07, 사이클 6 fixer-c6): `orderApi.ts`에 `updateOrderLineItem(id, {isUnipassDone?})` 래퍼 신규(`PATCH /line-items/{id}`, `OrderLineItemUpdateRequest`=isUnipassDone only에 정확히 한정), OrderGrid import. **계약 대조 결과 3경로가 서로 다른 DTO 소속**: 백엔드 `OrderLineItemUpdateRequest`는 isUnipassDone만 보유 → sourcingAmount/logisticsCost를 `/line-items/{id}`로 보내면 Jackson이 무성 폐기(데이터 손실). 따라서 `handleUpdate`의 lineItem 분기를 필드별 라우팅으로 교체 — isUnipassDone→`updateOrderLineItem`(`/line-items/{id}`), sourcingAmount·logisticsCost→기존 `updateSourcingInfo`(`/line-items/{id}/sourcing`, `SourcingUpdateRequest`가 두 필드 보유, `toSourcingData` null-보존 부분갱신 확인). 대조표 3경로 전부 DTO 필드 매칭 성립(fix.md). 성공기준: tsc-app TS2304 해소 5→4(신규 0), lint 6 무회귀, build ✓. 수정 요지: `_workspace/fixes/D-017_fix.md`.
- 범위 밖 후보(원장 기록): 인라인 소싱금액/물류비 편집을 `/sourcing`으로 보내면 백엔드 상태전이 로직(PREPARING 시 sourcingOrderNo 필수·PREPARING→PURCHASED 전이) 경유. 뮤테이션에 onError 토스트 부재로 가드 예외가 무성 실패(기존 패턴).
- 검증(2026-07-07, 사이클 6 verifier-c6): **PASS**. 게이트 실측 — tsc-app 5→4(TS2304 소멸, 신규 0), lint 6 무회귀, build ✓, 백엔드 diff 0. 경계면 교차 비교: 3경로 각각 대상 DTO에 필드 존재 확정(sourcingAmount·logisticsCost→`SourcingUpdateRequest`, isUnipassDone→`OrderLineItemUpdateRequest`). null-보존 주장 실코드 검증 — `SourcingUpdateCommand.toSourcingData(existing)`가 `existing.toBuilder()` 기반 non-null만 덮어씀 + 서비스가 기존 SourcingData 전달 확인(부분갱신 정합). 미검증: 런타임 실행(테스트 러너·DB 부재 → fix.md 수동절차로 갈음). 판정서: `_workspace/verify/D-017_verdict.md`.
- 상태: 검증통과 (사이클 6 verifier-c6)
- 이력: 2026-07-07 발견 (fixer-c5) → 2026-07-07 리더 P2 승인·다음 배치 TDD 처리 지시 → 2026-07-07 수정완료(사이클 6 fixer-c6) → 2026-07-07 검증통과(verifier-c6)

---

### D-016: 엔티티 테이블 sb_market_credential·sb_market_registration에 대응 마이그레이션 부재

- 심각도: P2 (신규 환경 앱 기동 불가 — 기존 스키마 의존)
- 리스크 등급: 표준
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/domain/market/MarketCredential.java`, `MarketRegistration.java` (대응 마이그레이션 없음)
- 증상: 두 엔티티(`sb_market_credential`·`sb_market_registration`)에 대한 `CREATE TABLE` 마이그레이션이 V1~V5 어디에도 없다. `ddl-auto: none`이므로 Hibernate도 생성하지 않음 → 이 테이블들은 pre-Flyway 베이스라인에만 존재. 특히 `Cafe24TokenManager` `@PostConstruct`가 기동 시 `sb_market_credential`을 조회하므로, 빈 DB에 Flyway만으로 구축하면 `ERROR: relation "sb_market_credential" does not exist`로 컨텍스트 기동 실패.
- 재현(2026-07-07, fixer-c4 실측): testcontainers 빈 Postgres + Flyway V1~V5 후 `@SpringBootTest` 기동 → 위 오류. 두 테이블을 테스트 베이스라인에 seed하면 해소.
- 원인(확인): D-015와 동일 뿌리 — 마이그레이션이 델타로만 작성되어 엔티티 전체 스키마를 커버하지 않음. `@Table` 9개 중 7개만 마이그레이션이 생성(누락: sb_market_credential, sb_market_registration).
- 제안(범위 밖): 두 테이블의 `CREATE TABLE IF NOT EXISTS` 마이그레이션(예: V6)을 엔티티 매핑에서 도출해 추가. D-015와 묶어 "마이그레이션 자족성 확보" 배치로 처리 권고.
- 상태: 무효화 (2026-07-07 사용자 결정: Flyway 제거·스키마 수동 관리) — 마이그레이션 부재 자체가 무의미해짐. 사이클 7에서 Flyway 전면 제거, 스키마는 사용자가 직접 관리. `sb_market_credential`·`sb_market_registration`은 운영 DB에 이미 존재하며, 테스트는 엔티티 매핑 기반 `ddl-auto: create-drop`으로 생성해 `Cafe24TokenManager @PostConstruct`의 빈 조회 내성을 `ApiContextLoadSmokeTest`가 실증.
- 이력: 2026-07-07 발견 (fixer-c4) → 2026-07-07 무효화 (사이클 7 fixer-c7, 사용자 Flyway 제거 결정)

---

### D-018: 배송 모달 carrier ↔ 백엔드 shippingCarrier 필드명 불일치 → 저장 시 택배사 누락 가능

- 심각도: P2 (오동작 — 배송 정보 부분 저장)
- 리스크 등급: 표준
- 위치: `frontend/src/pages/OrderGrid.tsx` 배송 모달 (carrier 전송) ↔ `PATCH /api/v1/orders/line-items/{id}/shipping` 요청 DTO (`shippingCarrier` 기대)
- 증상: 프론트가 `carrier` 키로 전송하나 백엔드 DTO 필드는 `shippingCarrier` — Jackson이 미매칭 키를 무시하므로 택배사 값이 null로 저장될 개연.
- 재현: 배송 모달에서 택배사 입력 후 저장 → DB shipping_carrier 미반영 확인 (실측 재현은 수정 배치에서 수행).
- 원인(추정): 레거시 병합 시 프론트/백엔드 필드명 리네임 불일치. — 미확인(양측 코드 grep 대조까지 확인, 런타임 재현 전).
- 수정(2026-07-07, 사이클 6 fixer-c6): `OrderGrid.tsx` 단일. **요청 전 필드 전수 대조로 2겹 불일치 확인**: ①키 이름 `carrier`→`shippingCarrier`(`onSubmit` 페이로드 정합). ②**enum 값 불일치(추가 발견)** — 모달 `<select>` 값(`DHL/FedEx/UPS/USPS/EMS/CJ/LOTTE/POST`)이 백엔드 `ShippingCarrier` enum 상수(`CJ_LOGISTICS/HANJIN/KOREA_POST/LOTTE_LOGISTICS/HYUNDAI_LOGISTICS/ROCKET/ETC`)와 불일치. Jackson 기본 enum 역직렬화는 상수명 정확 일치 요구(`fromMarketCode`는 `@JsonCreator` 아님) → 키만 고치면 ETC 외 전부 HTTP 400. select 옵션을 enum 값/라벨로 교체·carrier 초기값 `DHL`→`CJ_LOGISTICS`(2곳). `trackingNo`는 이미 일치. 백엔드 무변경. 성공기준: tsc-app 4(신규 0)·lint 6 무회귀·build ✓. 수정 요지: `_workspace/fixes/D-018_fix.md`.
- 범위 밖 후보(원장 기록): 국제 택배사(DHL/FedEx/UPS/USPS/EMS) 옵션은 백엔드 `ShippingCarrier` enum이 표현 불가하여 제거됨. 국제배송 지원이 실제 요구라면 백엔드 enum 확충 필요(제품 결정+백엔드 변경) — 별개 결함 후보.
- 검증(2026-07-07, 사이클 6 verifier-c6): **PASS**. 게이트 실측 — tsc-app 4(신규 0), lint 6 무회귀, build ✓, 백엔드 diff 0. 경계면 전수 대조: 키 `shippingCarrier`·필드 `trackingNo` 정합, 모달 select 옵션 7개 = `ShippingCarrier` enum 상수 7개 정확 1:1 일치(누락·초과 없음), 기본값 `CJ_LOGISTICS` 유효. 미검증: 런타임 실행(DB 부재 → fix.md 수동절차로 갈음). 국제택배사 소거는 백엔드 enum 표현 불가에 따른 정당한 스코프 사안(반려 아님, 별개 결함 후보). 판정서: `_workspace/verify/D-018_verdict.md`.
- 상태: 검증통과 (사이클 6 verifier-c6)
- 이력: 2026-07-07 발견 → 2026-07-07 리더 등재 (D-017과 함께 경계면 배치 후보) → 2026-07-07 수정완료(사이클 6 fixer-c6) → 2026-07-07 검증통과(verifier-c6)

---

### D-020: R2 자격증명 부재 시 S3Client 빈 즉시생성 실패 → api·worker 컨텍스트 기동 차단

- 심각도: P1 (기능 불능 — 앱 부팅 차단)
- 리스크 등급: 표준
- 위치:
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cloudflare/config/R2Config.java:20` (`s3Client()` 빈)
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cloudflare/R2ImageStorageClient.java:23` (`S3Client` 즉시 주입 — 유일 소비자)
  - 트리거: `api/src/main/resources/application.yml:31-32` (`access-key: ${CLOUDFLARE_R2_ACCESS_KEY:}` 기본값 빈 문자열)
- 증상(운영 실측 — 리더 확인): 운영 서버 `.env`에 `CLOUDFLARE_R2_*` 미설정 상태. 이때 `R2Config.s3Client()`가 기동 시 즉시 `AwsBasicCredentials.create(accessKey, secretKey)`를 호출 → 자격증명이 blank(빈 문자열)이면 `NullPointerException("Access key ID cannot be blank")` → api·worker 모두 스프링 컨텍스트 기동 실패. `ImageStorageClient`를 주입받는 `ProductCreateUseCase`·`ProductManageUseCase`가 eager 싱글턴이므로 `R2ImageStorageClient`→`S3Client`가 기동 시 즉시 생성됨.
- 재현: 운영 yml 그대로(R2 env 미설정) `@SpringBootTest` 기동 → 컨텍스트 로드 실패. 기존 `ApiContextLoadSmokeTest`는 더미 자격증명(`test-access-key` 등)을 주입해 통과하므로 이 blank 케이스를 못 잡음 → blank-creds 변형 테스트 신규 필요.
- 원인(확인): `s3Client()`가 non-lazy 싱글턴이라 Spring이 기동 시 pre-instantiate → 생성자 본문에서 자격증명 검증에 걸림. 업로드 기능은 실제 호출 시에만 자격증명이 필요한데 부팅 시점에 강하게 결합됨.
- 수정(2026-07-07, 사이클 8 fixer-c8): `S3Client` 빈 생성을 사용 시점으로 지연. ①`R2Config.s3Client()`에 `@Lazy` 추가(Spring pre-instantiate 회피). ②유일 소비자 `R2ImageStorageClient`의 `S3Client` 필드 주입을 `ObjectProvider<S3Client>`로 교체, `uploadImages()` 진입 시 `getObject()`로 해석. `@Lazy` 주입 지점 방식은 이 모듈에 `lombok.config`가 없어 `@RequiredArgsConstructor`가 필드 `@Lazy`를 생성자 파라미터로 전파 못 함 → build-wide 설정 신설(파급 큼) 대신 두 파일 국한 `ObjectProvider` 선택. 계약: 부팅은 blank 자격증명에서도 완주(업로드 외 기능 정상), 업로드 실제 호출 시 AWS SDK 검증 예외가 호출자에 전파(조용한 no-op 아님). Red: `ApiContextLoadWithBlankR2CredentialsTest`(수정 전 NPE로 컨텍스트 로드 실패 확인). 계약 고정: `R2ImageStorageClientBlankCredentialsTest`(업로드 호출 시 예외 전파). 검증: `:api:test --tests ApiContextLoad*`·`:infrastructure:test` PASS, 전체 `test`·`spotlessCheck` BUILD SUCCESSFUL. 기존 `ApiContextLoadSmokeTest`(더미-creds 정상 경로)와 공존. worker도 동일 infrastructure 빈 공유로 함께 해소. 수정 요지: `_workspace/fixes/D-020_fix.md`.
- 검증(2026-07-07, 사이클 8 verifier-c8): **PASS**. Red→Green 독립 재현 — 격리 git 워크트리(HEAD, 수정 전 코드)에 신규 blank 테스트만 복사해 실행 시 `UnsatisfiedDependency→BeanInstantiation(s3Client)→NPE "Access key ID cannot be blank"`로 컨텍스트 로드 FAILED(부팅 차단 재현), 수정 후 primary에서 동일 테스트 PASS. 지연 완전성: `S3Client` 프로덕션 주입 지점 전수 grep = `R2ImageStorageClient` 단 1곳 → `ObjectProvider`화로 eager 소비 체인(`ProductCreateUseCase`/`ProductManageUseCase` @Service → R2ImageStorageClient → S3Client) 차단 완결(`@Bean @Lazy`만으로는 불충분한 리스크를 소비자측 ObjectProvider가 정확히 해소). 실패 모드: 계약 테스트로 blank creds 업로드 시 `RuntimeException` 전파(no-op 아님) 확인. 회귀: 기존 `ApiContextLoadSmokeTest`(더미-creds) PASS, 전체 `test`+`spotlessCheck`(읽기전용) BUILD SUCCESSFUL. 미검증: 실 R2 자격증명 업로드 E2E(하네스 범위 밖 — 재배포 후 수동 확인 필요), worker 런타임 부팅(worker 컨텍스트 테스트 부재 — 동일 빈 공유 정적 확인까지). 판정서: `_workspace/verify/D-020_verdict.md`.
- 상태: 검증통과 (사이클 8 verifier-c8)
- 이력: 2026-07-07 발견 (운영 실측, 리더 확인) → 2026-07-07 수정중 (사이클 8 fixer-c8) → 2026-07-07 수정완료(검증대기) (사이클 8 fixer-c8) → 2026-07-07 검증통과 (사이클 8 verifier-c8)

---

### D-021: Product.detailHtml @Lob 매핑 → 실 PostgreSQL에서 주문/상품 조회 전체 500

- 심각도: P1 (기능 불능 — orders/products API 전멸)
- 리스크 등급: 경량 (1파일·가역 — 리더 직접 처리)
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/domain/product/Product.java:83` (@Lob)
- 증상: 운영 실측(2026-07-07 첫 기동 후) — `Could not extract column [6] from JDBC ResultSet [Bad value for type long : <img ...]`. PostgreSQL에서 @Lob String을 Hibernate가 Large Object(OID)로 취급, text 컬럼을 getLong()으로 읽다 실패.
- 재현: ProductDetailHtmlReadTest (testcontainers 실 Postgres, text 컬럼 + HTML 데이터 → 엔티티 로드). H2로는 재현 불가 (@Lob을 CLOB 텍스트로 처리 — H2≠PG 함정 실증 사례).
- 원인(확인): @Lob 제거로 해소 (columnDefinition="text" 유지 — 매핑 동작 동일, OID 해석만 제거).
- 상태: 검증통과 (Red→Green 실측 + 전체 게이트 그린. 내부 QA: 경량 등급 — 리더 직접, 재현 테스트가 게이트)
- 이력: 2026-07-07 운영 E2E 중 발견 → 2026-07-07 리더 직접 TDD 수정 → 검증통과

---

### D-019: 운영 DB 9개 테이블 공통 감사 컬럼 aa_ 접두사 드리프트 (수동 DDL로 해소)

- 심각도: P0 (기동 차단 — 4번째 층)
- 리스크 등급: 중대 (운영 스키마 — 사용자 승인 후 적용)
- 위치: 운영 sbshop DB 전 테이블의 aa_created_at/aa_updated_at/aa_status (경위 불명 이관 잔재 — 사용자는 직접 붙인 적 없음 확인)
- 처리(2026-07-07): 27개 컬럼 리네임 DDL 단일 트랜잭션 적용 (데이터 무손실), 잔여 aa_ 0건 확인. 사용자 사전 승인.
- 상태: 검증통과 (적용 후 기동 성공 실측)
- 이력: 2026-07-07 운영 E2E 중 발견 → 사용자 승인 → 수동 DDL 적용 → 해소

---

## 사이클 9 신규 결함 (운영 가동 이후 사용자 신고 — 3 클러스터 병렬 진단, 2026-07-07)

> 출처: `_workspace/scout_sync.md`(동기화·액션), `_workspace/scout_status.md`(주문상태 매핑), `_workspace/scout_product.md`(상품페이지). 사용자 신고 3대 증상 = D-022(동기화 무반응)·D-027~D-029(취소 오표시)·D-034~D-036(상품페이지 렌더/기능). 리스크등급은 리더 판정 기입.

### D-022: 마켓 동기화 실패 시 finally 블록이 SYNC_COMPLETED(success=true) 재발행 → 에러 은폐

- 심각도: P0 (기능 불능 체감 — 동기화 실패를 사용자가 인지 불가)
- 리스크 등급: 표준 (4 서비스 동일 패턴·행위 수정, 스키마/스케줄러/계약 무변경)
- 위치:
  - `backend/core/.../order/service/SmartStoreOrderSyncService.java:75-82`
  - `backend/core/.../order/service/CoupangOrderSyncService.java:75-82`
  - `backend/core/.../order/service/ElevenstOrderSyncService.java:61-67`
  - `backend/core/.../order/service/EsmplusOrderSyncService.java:61-67`
  - 연계: `SseNotificationController`, `frontend/.../OrderGrid.tsx:510-525`
- 증상: 동기화 버튼 클릭 → "로딩 및 동기화 중..." → 몇 초 뒤 에러·데이터 없이 사라짐 (사용자 신고 직접 원인).
- 재현: 자격증명 미설정 마켓 동기화 버튼 클릭 → catch가 `SyncCompletedEvent(success=false)` 발행 직후 finally가 무조건 `SyncCompletedEvent(success=true)` 재발행 → SSE로 SYNC_FAILED(토스트 깜박) 후 SYNC_COMPLETED(로딩 정상종료·refetch) 순차 도달.
- 원인(확인): 4개 서비스 `finally` 블록이 예외 여부 무관하게 항상 성공 이벤트 발행.
- 제안 수정: 성공 플래그(try 말미 set) 도입 → finally에서 성공 시에만 SYNC_COMPLETED 발행. 또는 성공 경로 말미에서만 발행하고 finally는 isSyncing 리셋만.
- 수정(2026-07-07, 사이클 9 fixer-c9): 4개 서비스에 `boolean success` 플래그 도입, finally에서 `if(success)`일 때만 2-arg `SYNC_COMPLETED(success=true)` 발행. isSyncing.set(false)는 성공/실패 무관 finally 유지. SSE 계약(SseNotificationController isSuccess() 분기) 무변경. Red: `OrderSyncEventEmissionTest`(실패 4 + 성공 회귀 1) 수정 전 4 fail → 후 5 pass.
- 검증(2026-07-07, verifier-c9): **PASS**. `OrderSyncEventEmissionTest` 5/5 Green, `:core:test`·전체 `./gradlew test` BUILD SUCCESSFUL. 4서비스 실패경로 success 이벤트 도달 불가·성공경로 1회 발행·isSyncing 무조건 리셋 코드 확인. 미검증: 실 SSE 스트림 브라우저 E2E(범위 밖). 판정서 `_workspace/verify/D-022_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-023: SSE 연결 실패 시 isSyncing 영구 고착 (로딩 스피너 무한)

- 심각도: P1 (기능 불능 — SSE 불통 시 로딩 오버레이 영구)
- 리스크 등급: 표준
- 위치: `frontend/src/pages/OrderGrid.tsx:543-605`(sync handler success path — setIsSyncing(false) 없음), `:510-525`(EventSource onerror 없음)
- 증상: SSE 단절 상태에서 동기화 클릭 → 로딩 무한. 원인: isSyncing 리셋을 SSE 이벤트에만 의존, HTTP 응답 성공 분기·onerror에 리셋 없음.
- 제안 수정: EventSource `onerror`에서 setIsSyncing(false)+에러 토스트, HTTP 성공 분기에 timeout fallback.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — onerror가 `readyState===CLOSED && isSyncingRef.current`일 때만 해제+토스트(트랜지언트/비동기화 스퍼리어스 방지), isSyncingRef(useRef)+useEffect stale 회피, 4핸들러 30초 watchdog. 정상 SSE/catch 해제 보존. verifier-c9 **PASS**(로직 정합·D-022 SYNC_FAILED 연계 확인). 미검증: 브라우저 EventSource CLOSED 전이·watchdog 실동작(러너 부재). 판정서 `_workspace/verify/D-023_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-024: SYNC_FAILED 토스트에 ELEVEN_STREET·GMARKET 한글 라벨 미매핑

- 심각도: P3 (품질) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:521` — COUPANG/SMART_STORE만 한글 치환, 나머지 enum명 노출.
- 제안 수정: `marketLabels` Record(:413) 재사용.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — 인라인 삼항을 `marketLabels[marketType]||marketType`로 교체(11번가·G마켓/옥션 등 한글화). verifier-c9 **PASS**(COUPANG/SMART_STORE 회귀 없음). 판정서 `_workspace/verify/D-024_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-025: 선택 발송(handleShipSelected) 성공 시 toast.success 없음

- 심각도: P3 (UX) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:1122-1127` — shipOrders 성공 후 조용히 refetch만.
- 제안 수정: `toast.success('N건 발송 처리되었습니다.')`.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — handleShipSelected 성공경로(try 내, refetch 뒤) toast.success 추가, 건수=중복제거 orderIds.length. verifier-c9 **PASS**(성공경로만·실패 catch 유지). 판정서 `_workspace/verify/D-025_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-026: 선택 주문 거부(handleCancelOrders) 성공 시 toast.success 없음

- 심각도: P3 (UX) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:673-680` — cancelOrder 성공 후 조용히 refetch만.
- 제안 수정: `toast.success('N건 취소(거부) 처리되었습니다.')`.
- 참고(scout): 선택 확인/거부/발송 백엔드 계약(`/confirm/batch`·`/{id}/cancel`·`/ship`)·DTO는 전부 프론트와 일치(계약 결함 아님). "선택 확인" 버튼은 선택 행이 모두 NEW일 때만 활성(UX 가이드 부재이나 결함 아님).
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — handleCancelOrders 성공경로 toast.success 추가, confirm 취소 시 조기 return으로 오발행 없음. verifier-c9 **PASS**. 판정서 `_workspace/verify/D-026_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-027: 쿠팡 detectCancellations — RETURNED·EXCHANGED를 terminal에서 누락 → 반품/교환 주문을 오취소 (사용자 신고 "취소 아닌데 취소됨" 직접 원인)

- 심각도: P1 (오동작 — 실 주문상태 오표시)
- 리스크 등급: 표준 (라이브 주문 상태에 영향 — 회귀 게이트 필수)
- 위치: `backend/core/.../order/adapter/CoupangOrderAdapter.java:481-492`
- 증상: 반품/교환 처리 중 쿠팡 주문이 취소 아닌데 "취소됨" 표시.
- 재현: DB에 RETURNED 주문 A → 동기화(fetchOrders는 ACCEPT/INSTRUCT/DEPARTURE/DELIVERING/FINAL_DELIVERY/NONE_TRACKING만 조회, RETURNED 미포함) → detectCancellations의 hasNonTerminal이 RETURNED를 non-terminal로 판정(terminal=CANCELED,DELIVERED뿐) → API 응답에 없으니 CANCELED로 덮어씀.
- 원인(확인): terminal 판정에 RETURNED·EXCHANGED 누락.
- 제안 수정: hasNonTerminal의 terminal 집합에 RETURNED·EXCHANGED 추가(1줄). Red: RETURNED 주문이 API 미응답 시 CANCELED로 안 바뀜을 재현.
- 수정(2026-07-07, 사이클 9 fixer-c9): non-terminal 판정을 `isNonTerminal` 헬퍼로 추출, terminal 집합에 RETURNED·EXCHANGED 추가. Red: `CoupangDetectCancellationsTest`(RETURNED/EXCHANGED 미취소 2 + NEW 취소·DELIVERED 미취소 회귀 2) 수정 전 2 fail → 후 4 pass.
- 검증(2026-07-07, verifier-c9): **PASS**. `CoupangDetectCancellationsTest` 4/4 Green. RETURNED/EXCHANGED 미취소 확인 + 정당 취소(NEW/PREPARING/SHIPPED 소멸) 회귀 유지, terminal 집합 ↔ ShippingStatus enum 정합. 미검증: 쿠팡 실 API가 반품/교환을 미조회하는 전제(범위 밖). 판정서 `_workspace/verify/D-027_verdict.md`.
- 상태: 검증통과 (사이클 9 — "취소 아닌데 취소됨" 쿠팡 직접원인 해소)

---

### D-028: 11번가 취소/반품/교환 동기화 전무 — postSyncProcess 빈 메서드

- 심각도: P1 (오동작 — 취소 주문이 NEW/PREPARING 영구 잔류)
- 리스크 등급: 표준
- 위치: `backend/core/.../order/service/ElevenstOrderSyncService.java:208`(빈 postSyncProcess), `.../adapter/ElevenstOrderAdapter.java:58-107`(complete/packaging/shipping/dlvcompleted 4상태만 조회)
- 증상: 11번가 주문이 취소/반품돼도 DB에 이전 상태로 영구 잔류. 쿠팡 detectCancellations 대응 로직 부재.
- 제안 수정: 쿠팡 detectCancellations 패턴을 11번가에 적용(terminal에 RETURNED·EXCHANGED 포함). **주의: 라이브 주문을 CANCELED로 마킹 — 오취소 방지 로직 정확성 검증 필수.**
- 수정(2026-07-07, 사이클 9 fixer-c9): 11번가 어댑터가 detectCancellations 훅을 미보유 → `ElevenstOrderSyncService.postSyncProcess`에 쿠팡 정본 `isNonTerminal`(terminal=CANCELED·DELIVERED·RETURNED·EXCHANGED) 이식. API 미응답 기존 주문 중 non-terminal을 CANCELED 처리, 주문일 30일 범위 밖 스킵(쿠팡 정본 동일 가드). Red: `ElevenstDetectCancellationsTest`(NEW 소멸 취소 1 + RETURNED/EXCHANGED/DELIVERED 오취소방지 회귀 3).
- 검증(2026-07-07, verifier-c9): **PASS**(라이브 리스크 심사 통과). isNonTerminal이 쿠팡 정본과 문자 동일 → RETURNED/EXCHANGED 오취소 방지·NEW 소멸 정당취소 유지, 30일 스킵 정당, 상태 갱신만·신규생성 없음. `ElevenstDetectCancellationsTest` 4/4. 미검증: 11번가 실 API가 취소를 미조회하는 전제(진단 근거·범위 밖). 판정서 `_workspace/verify/D-028_verdict.md`.
- 상태: 검증통과 (사이클 9 — 11번가 취소 오표시 해소)

---

### D-029: ESM+ 취소·교환 주문 null 반환 → 기존 DB 주문 상태 미갱신

- 심각도: P1 (오동작 — 취소/교환이 이전 상태로 고착)
- 리스크 등급: 표준
- 위치: `backend/core/.../order/adapter/EsmplusOrderAdapter.java:183-185`, `backend/infrastructure/.../esmplus/EsmplusOrderApiPortImpl.java:677-679`, 연계 `EsmplusOrderSyncService`
- 증상: status가 CANCELED/EXCHANGED면 `return null`로 필터 → processOrders 미전달 → 기존 주문(NEW/PREPARING) 미갱신.
- 원인(확인): 취소/교환을 단순 필터링(detectCancellations 부재). 참고: ESM+ API가 2010~2070 취소코드를 리스트 반환하는지는 API 문서 확인 필요이나, 반환되더라도 현재 코드가 DB 미반영은 확정.
- 제안 수정: 취소/교환도 기존 주문 있으면 상태 업데이트하도록 변경, 또는 detectCancellations 패턴 적용.
- 수정(2026-07-07, 사이클 9 fixer-c9): 실 라이브 경로 = infra `EsmplusOrderApiPortImpl.parseSingleOrder`(Selenium 스크레이프, 상태 필터 없이 취소/교환 in-band 반환) → **case① null 필터 제거**로 커버. `if(CANCELED||EXCHANGED) return null`을 core·infra 양쪽 제거 → processOrders→updateExistingOrder가 기존 주문 상태 갱신. Red: `EsmplusParseSingleOrderTest`(취소 2010/교환 2050 포함 + 정상 회귀). core 파서는 미사용 중복(dead)이나 드리프트 방지로 동일 수정.
- 검증(2026-07-07, verifier-c9): **PASS**. 호출그래프로 라이브 경로(core.fetchOrders→infra parseSingleOrder:646) 확인, infra에도 동일 적용. `EsmplusParseSingleOrderTest` 4/4. **비차단 관찰**: DB에 없던 취소/교환 주문은 processOrders else 분기로 CANCELED/EXCHANGED 신규 레코드 생성 — 데이터 오염 아닌 실 마켓상태 반영(기존 계약). 업무상 원치 않으면 [[D-039]]. 미검증: infra parseSingleOrder는 private+Selenium이라 core 파서와 정적 교차비교로 갈음. 판정서 `_workspace/verify/D-029_verdict.md`.
- 상태: 검증통과 (사이클 9 — ESM+ 취소/교환 미갱신 해소)

---

### D-030: 쿠팡 NONE_TRACKING → UNKNOWN 오매핑 (SHIPPED여야 함)

- 심각도: P2 (오동작) · 리스크 등급: 경량
- 위치: `backend/core/.../order/mapper/CoupangStatusMapper.java:30-44` (case 없음 → default UNKNOWN), `CoupangOrderAdapter.java:66`(조회 대상엔 포함)
- 증상: 운송장 미등록 배송중 쿠팡 주문이 "알수없음" 표시.
- 제안 수정: `case "NONE_TRACKING" -> ShippingStatus.SHIPPED;` 추가.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9가 mapBasicStatus에 NONE_TRACKING→SHIPPED 추가, `CoupangStatusMapperTest` 2/2. verifier-c9 PASS(기존 매핑 회귀 없음, statuses 배열에 NONE_TRACKING 실재 정합). 판정서 `_workspace/verify/D-030_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-031: 11번가 parseOrderDetailElement status SHIPPED 하드코딩

- 심각도: P2 (잠재 오동작 — 현재 호출처 없음) · 리스크 등급: 경량
- 위치: `backend/core/.../order/adapter/ElevenstOrderAdapter.java:264` — `.status(ShippingStatus.SHIPPED)` 고정.
- 증상: fetchOrderDetail 반환 status가 항상 SHIPPED. 현재 미호출이나 향후 통합 시 오매핑.
- 제안 수정: 실제 상태 파싱 또는 메서드 용도 명확화(SHIPPED 전용이면 주석/이름).
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 실측 — fetchOrderDetail(11번가) 호출처 0건(latent), 소스 엔드포인트에 배송상태 필드 없음 → 근거없는 `.status(SHIPPED)` 제거(미설정 null=enrichment no-clobber). parseShippingElement의 배송중 SHIPPED는 무변경. `ElevenstOrderDetailStatusTest` 1/1. verifier-c9 PASS(null이 toShippingData null-guard로 안전). 판정서 `_workspace/verify/D-031_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-032: ESM+ 문자열 폴백 조건 논리 오류 (DEAD CODE)

- 심각도: P2 (오동작 — 미인식 코드 폴백 무력화) · 리스크 등급: 경량
- 위치: `backend/core/.../order/adapter/EsmplusOrderAdapter.java:175-179`, `backend/infrastructure/.../esmplus/EsmplusOrderApiPortImpl.java:668-675`
- 원인(확인): `if (status==NEW && deliveryStatusCode!=1010)` — NEW는 1010에서만 반환되므로 논리 모순 = DEAD CODE. 의도는 `if (status==UNKNOWN)` (미인식 코드 시 문자열 폴백).
- 제안 수정: 조건을 `status==UNKNOWN`으로, 폴백 비교도 `!=UNKNOWN`으로 정정.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9가 core·infra 양쪽 `if(status==NEW && code!=1010)`→`if(status==UNKNOWN)`, 내부 비교 `!=NEW`→`!=UNKNOWN`으로 교정. Red: `EsmplusParseSingleOrderTest`(미인식 9999+"배송중"→SHIPPED 폴백). verifier-c9 PASS(폴백 작동, 정상 1040→SHIPPED 회귀 없음). 판정서 `_workspace/verify/D-032_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-033: 프론트 동기화 상태 dot에서 AUCTION 누락

- 심각도: P3 (표시 누락) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:413-421`(marketLabels), `:1166`(렌더)
- 제안 수정: `AUCTION` 키 추가 또는 GMARKET에 통합 표시(ESM+ 공유 서비스).
- 판정(2026-07-07, 사이클 9 — **무변경 정당**): fixer-c9·verifier-c9 백엔드 발행 키 직접 실측 — `OrderSyncScheduler` markRunning/Completed/Failed 인자는 EMAIL·COUPANG·GMARKET·SMART_STORE·ELEVEN_STREET·COUPANG_SETTLEMENT·CUSTOMS 7종으로 **AUCTION 미발행**(ESM+가 G마켓·옥션을 GMARKET 단일 키 처리). 프론트 dot은 `Object.entries(marketLabels)`이고 GMARKET='G마켓/옥션'로 이미 포괄. AUCTION 키 추가 시 syncStatuses['AUCTION'] 영구 undefined→회색 오해 dot 발생 → **코드 무변경이 정답**. verifier-c9 실측 일치 확인.
- 상태: 검증통과 (사이클 9 — 무변경 결정, 실측 근거)

---

### D-034: 상품관리 페이지 ag-Grid cellRenderer가 HTML 문자열 반환 → 텍스트로 이스케이프 (사용자 신고 "`<img>` `<button>` 텍스트 노출" 직접 원인)

- 심각도: P1 (기능 불능 — 화면 렌더 깨짐)
- 리스크 등급: 표준 (단일 파일이나 화면 핵심)
- 위치: `frontend/src/pages/ProductPage.tsx:39-41`(이미지 컬럼), `:48-53`(관리 컬럼)
- 증상: ag-Grid v32에서 cellRenderer가 문자열 반환 시 textContent 처리 → `<img>`/`<button>` 태그가 글자로 노출.
- 제안 수정: JSX 반환 React 셀 렌더러로 교체(이미지 `<img>`, 관리 버튼 onClick).
- 수정(2026-07-07, 사이클 9 fixer-c9, D-037 동반): 두 cellRenderer를 JSX 반환으로 교체(이미지 `<img>`, 관리 버튼 `onClick`→openPriceStockModal). `window.__editProduct` 전역등록+onGridReady prop 전면 제거([[D-037]]). 가격/재고 모달 기존 로직 보존. 상세모달(D-035)·이미지변경(D-036)은 범위 밖 미착수.
- 검증(2026-07-07, verifier-c9): **PASS**. `tsc -p tsconfig.app.json` ProductPage 신규 에러 0(잔존 4건 전부 OrderGrid.tsx 기준선 any), `npm run build` EXIT 0, `__editProduct` grep 0건. 미검증: 브라우저 실제 렌더(범위 밖 — 수동 확인 권고). 판정서 `_workspace/verify/D-034_verdict.md`.
- 상태: 검증통과 (사이클 9 — 상품페이지 raw HTML 노출 직접원인 해소)

---

### D-035: 상품 상세 모달 미구현 (상품명 클릭 핸들러·모달 컴포넌트 부재)

- 심각도: P2 (기능 불능 — 상세 조회 불가)
- 리스크 등급: 표준 (신규 UI 구축, 백엔드 계약 완비)
- 위치: `frontend/src/pages/ProductPage.tsx:44`(상품명 컬럼 — onCellClicked 없음)
- 증상: 상품명 클릭 무반응. `fetchProductDetail`(`productApi.ts:42`) 정의됐으나 미호출, 상세 모달 없음.
- 제안 수정: 상품명 클릭 → `fetchProductDetail(id)` → `ProductDetail`(id/코드/가격/물류/스펙/소싱/이미지/detailHtml) 표시 모달 구축.
- 수정(2026-07-07, 사이클 9 fixer-c9, 미배선 UI 구축): 상품명 onCellClicked → fetchProductDetail → antd Descriptions 섹션화(기본/가격/물류/스펙/소싱/메모), 로딩 Spin·실패 토스트. detailHtml은 **sandbox iframe(`sandbox=""` + srcDoc)** 으로 스크립트 차단 렌더(XSS 방지, dangerouslySetInnerHTML 0건).
- 검증(2026-07-07, verifier-c9): **PASS**. 경계면 대조 — 프론트 `ProductDetail` ↔ 백엔드 `ProductDetailResponse` 최상위·중첩 VO 필드명 전부 정합(PriceInfo/LogisticsInfo/ProductSpec/SourcingInfo). detailHtml XSS 안전(빈 sandbox 스크립트 차단), sourceUrl rel="noopener". tsc ProductPage 신규에러 0, build EXIT 0. 미검증: 실 JSON 직렬화·모달 렌더 브라우저 런타임(러너 부재). 판정서 `_workspace/verify/D-035_verdict.md`.
- 상태: 검증통과 (사이클 9 — 상품 상세 조회 기능 배선)

---

### D-036: 상품 이미지 변경 UI 미구현 (API 완비·UI 부재)

- 심각도: P2 (기능 불능) · 리스크 등급: 표준
- 위치: `frontend/src/pages/ProductPage.tsx`(UI 없음), API `productApi.ts:48-55`, 백엔드 `ProductController.java:94-114`(`PUT /images`·`/images/by-url` 구현 존재)
- 증상: 이미지 변경 UI(파일 업로드/URL 입력/변경 버튼) 부재.
- 제안 수정: 상세 모달 내 파일 업로드+URL 입력 섹션 → `uploadImages`/`uploadImagesByUrl` 연결. **주의: R2 자격증명 미설정 시 업로드 실패([[D-020]]) — 코드 결함 아닌 환경 이슈로 구분, 명확한 에러 표시.**
- 수정(2026-07-07, 사이클 9 fixer-c9): 상세 모달 내 이미지 섹션 — hostedImages/sourceImages 썸네일(Image.PreviewGroup) + ①파일 업로드(FormData 'images' 멀티 append → uploadImages), ②URL 등록(TextArea → uploadImagesByUrl), ③crawlSourceImages(자동등록 아닌 TextArea 채움→검토 후 등록). 성공 시 refetch. **D-020 대응**: 업로드 실패 시 "이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요" 명확 토스트(조용한 실패 금지), uploading 중복클릭 방지.
- 검증(2026-07-07, verifier-c9): **PASS**. 백엔드 파트 키 정합 — ①FormData 'images' ↔ `@RequestPart("images") List<MultipartFile>`, ②URL 배열 ↔ `@RequestBody List<String>`, ③crawl ↔ `GET List<String>`. 크롤 자동등록 아님(검토 후 등록). R2 미설정 시 의도된 에러 토스트 경로 확인(환경이슈 구분). tsc 신규에러 0, build EXIT 0. 미검증: 멀티파트/R2 저장/크롤 실동작 실API 런타임(범위 밖). 판정서 `_workspace/verify/D-036_verdict.md`.
- 상태: 검증통과 (사이클 9 — 이미지 변경 기능 배선)

---

### D-037: window.__editProduct 전역 함수 패턴 (CSP 위험·정리 없음)

- 심각도: P3 (부채) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProductPage.tsx:52`(onclick 문자열), `:57-59`(window 전역 등록)
- 제안 수정: D-034(JSX 셀 렌더러)와 동시에 전역 함수 패턴 제거 → React 이벤트로.
- 수정·검증(2026-07-07, 사이클 9): [[D-034]]와 함께 처리 — `window.__editProduct` 전역등록·인라인 onclick 전면 제거, React onClick 핸들러로 대체. `__editProduct` grep 0건(verifier-c9).
- 상태: 검증통과 (사이클 9, D-034 동반)

---

### D-038: BatchUpdatePage by-supplier 빈 응답 계약 불일치 → undefined 노출

- 심각도: P2 (오동작 — 빈 결과 시) · 리스크 등급: 경량
- 위치: `frontend/src/pages/BatchUpdatePage.tsx:26`, `backend/api/.../controller/BatchController.java:89`
- 증상: 해당 소싱업체 상품 없을 때 백엔드가 `{message}`만 반환(batchId·count 없음) → 프론트가 "배치 시작: undefined개 상품 (batchId: undefined)" 표시.
- 제안 수정: 프론트에서 batchId 유무 분기, 또는 백엔드 empty 케이스를 명시 응답으로.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — 백엔드 무변경, `BatchUpdatePage.tsx`에서 `res.data`를 {batchId?,count?,message?}로 보고 batchId 있으면 성공메시지·없으면 `message.info`로 백엔드 안내 표시. verifier-c9 **PASS**(빈응답 {message} 계약 정합, 정상응답·manual 모드 회귀 없음). 판정서 `_workspace/verify/D-038_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-039: (후보) ESM+ 취소/교환 주문이 DB에 없으면 CANCELED/EXCHANGED 신규 레코드 생성

- 심각도: 미정(업무 결정 필요) · 리스크 등급: 경량
- 위치: `EsmplusOrderApiPortImpl.parseSingleOrder`(취소/교환 in-band 반환) → `processOrders` else 분기 `createNewOrder`
- 관찰(verifier-c9, D-029 검증 중): D-029로 null 필터를 제거하자, 과거 동기화된 적 없는 취소/교환 ESM+ 주문이 조회되면 신규 CANCELED/EXCHANGED 주문으로 생성됨. 데이터 오염은 아니고 실 마켓 상태 반영이며 모든 상태에 동일 적용되는 기존 processOrders 계약. 다만 "취소된 주문을 신규로 만드는" 것이 업무상 불필요하면 skip 조건 추가 검토.
- 제안(범위 밖): 신규 주문 생성 시 terminal 상태(CANCELED/EXCHANGED)면 skip하거나, 취소는 기존 주문 갱신만 허용. 업무 요구 확인 후 결정.
- **사용자 결정(2026-07-08)**: "기존 동기화된 주문이 취소되면 그 행이 취소로 바뀌는 게 자연스럽다. 없던 취소 주문이 신규 레코드로 생성되면 중복 표시" → **absent + CANCELED/EXCHANGED는 신규 생성 건너뜀**(기존 주문만 취소로 갱신).
- 수정(2026-07-08, fixer-d39): `EsmplusOrderSyncService.processOrders` else(미존재) 분기 진입 직후 `dto.getStatus()`가 CANCELED/EXCHANGED면 상세조회·생성 없이 continue(로그). RETURNED·기존 update·비terminal 생성 경로 무변경(D-029 이전 거동 보존). Red: `EsmplusOrderSyncTerminalSkipTest` 4케이스(①absent+CANCELED→save/fetchOrderDetail never ②absent+EXCHANGED→동일 ③existing+CANCELED→save 갱신유지 ④absent+NEW→생성유지) 수정 전 ①② 실패 → 후 4/4.
- 검증(2026-07-08, 리더 직접): diff 정확(1파일, else 진입 즉시 skip), `:core:test` BUILD SUCCESSFUL, 테스트 4케이스가 실제 가드(never/verify)로 결함 포착. 경량-표준 등급 리더 검증.
- 상태: 검증통과 (사이클 9 후속 — 사용자 결정 반영, 중복 레코드 방지)

---

### D-040: (후보/구조) core EsmplusOrderAdapter parseSingleOrder/parseOrdersFromJson 미사용 중복(dead)

- 심각도: P3 (부채) · 리스크 등급: 경량
- 위치: `backend/core/.../order/adapter/EsmplusOrderAdapter.java` (parseSingleOrder/parseOrdersFromJson)
- 관찰(fixer-c9, D-029 수정 중): 실 라이브 경로는 infra `EsmplusOrderApiPortImpl`이고 core 어댑터의 파서는 테스트 외 호출처 없는 중복. D-029에서 드리프트 방지 위해 양쪽 동일 수정했으나, 구조적으로는 중복 제거 대상.
- 제안(범위 밖, 구조 배치): 사용처 재확인 후 core 중복 파서 제거 또는 infra와 단일화. 행위/구조 분리 위해 별도 배치.
- 상태: 후보 (사이클 9 fixer-c9 관찰 — 구조 정리 후보)

---

### 후보 기록 (사이클 8 운영 정착 중 관찰)

- **Cafe24 refresh token 만료**: 기동 시 invalid_grant (비치명 — 로그만). Cafe24 개발자센터에서 토큰 재발급 후 sb_market_credential 갱신 필요 (사용자 조치).
- **market-credentials API가 accessKey/secretKey 평문 노출**: 응답에 자격증명 원문 포함. 사용자의 "보안 비중요" 방침상 후보 기록만 (공개망 노출 시 마스킹 필요).
- **nginx 컨테이너 IP 캐시**: sbshop 컨테이너 재생성 시 nginx reload 필요 (`docker exec projects-nginx-1 nginx -s reload`) — 배포 절차에 포함 권장.

### 사이클 9 배포 기록 (2026-07-08)

- **R2 자격증명 활성화 (D-020/D-036 연계 완료)**: buying-agent `application.yml`의 R2 키를 서버 `~/projects/.env`에 `CLOUDFLARE_R2_*`로 추가. **핵심 발견**: 서버 `~/projects/docker-compose.yml`(레포와 별개 파일)의 sbshop-api 서비스가 **구 변수명 `R2_ACCESS_KEY_ID` 등**을 쓰고 있어 현재 앱이 읽는 `CLOUDFLARE_R2_*`와 불일치 → `.env`만으론 컨테이너 미전달이었음. 서버 compose sbshop-api `environment`에 `CLOUDFLARE_R2_*` 5줄 매핑 추가(백업: `docker-compose.yml.bak-c9`). 재배포 후 컨테이너 env 주입·부팅 무에러(blank creds 예외 소멸) 확인. **주의: 서버 compose는 git 미추적 — 이 변경은 서버에만 존재**(레포 docker-compose.yml엔 이미 CLOUDFLARE_R2 매핑 있음, 서버가 뒤처져 있었음).
- **worker 동거 확인**: sbshop-api 컨테이너가 api(:8080) + worker(:8081) 둘 다 기동 → sbshop-api 재빌드가 스케줄 동기화(worker) 수정도 함께 반영. 별도 worker 서비스 없음.
- **배포 결과**: git pull(D-022~D-039 전체) → compose up --build(api·frontend) → nginx reload. 외부 `/sbshop-agent` 200, orders/products API 200(실데이터), api·worker 부팅 안정.
- **잔존(사용자 조치)**: Cafe24 refresh token 만료(invalid_grant) — 재배포 후에도 로그 에러(비치명). Cafe24 개발자센터 토큰 재발급 후 `sb_market_credential` 갱신 필요.

### 사이클 9 HTTPS 설정 (2026-07-08, 사용자 요청)

- **증상**: 사용자가 `https://168.107.31.154/sbshop-agent` 접속 불가 신고("서버 다운?"). **진단: 서버 정상**(컨테이너 전부 up, http 200) — nginx가 80만 리슨하고 **443/TLS 미설정**이라 https 연결거부(000)였음. docker-compose는 443 매핑하나 nginx conf에 ssl 블록·인증서 없었음.
- **처리(사용자 결정: 자체서명)**: 서버 `~/projects/nginx/conf.d/`에 자체서명 인증서 생성(`selfsigned.crt/.key`, CN·SAN=`IP:168.107.31.154`, 825일). `default.conf`(백업 `default.conf.bak-c9`) server 블록에 `listen 443 ssl` + ssl_certificate 2줄 추가(80·443 동일 location 서빙). `nginx -t` 통과 후 reload. 검증: https 200(루트·products API), http 무회귀 200, can-agent 200.
- **주의**: 자체서명이라 브라우저가 "연결이 비공개가 아닙니다/주의 요함" 경고 → 사용자가 "고급 → 계속 진행" 클릭 필요(기능은 정상). 도메인 확보 시 Let's Encrypt로 정식 인증서 전환 가능. 이 변경도 서버 nginx conf(git 미추적)에만 존재.
