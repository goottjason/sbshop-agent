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

## 사이클 10 (운영 라이브 진단: 동기화 무반응·액션로그, 2026-07-08)

> 라이브 진단(리더 직접, 실서버 SSH): 동기화 버튼 눌러도 그리드 비어있음 → 실제로는 마켓 API가 실패하는데 **어댑터가 오류를 삼켜 "성공 0건"으로 보고**. 근본은 외부(자격증명/IP 허용목록)이나, 코드가 실패를 은폐. 서버 아웃바운드 IP=168.107.31.154(쿠팡/11번가/스마트스토어 허용목록 등록 대상 — 사용자 조치). 자격증명 실측: COUPANG SET/SET(그러나 403 FORBIDDEN=IP 미허용 유력), SMART_STORE access-key EMPTY, ELEVEN_STREET secret-key EMPTY, GMARKET SET/SET(Selenium).

### D-041: 마켓 주문 동기화 어댑터가 API 오류(403 등)를 삼켜 "성공 0건"으로 보고

- 심각도: P1 (오동작 — 실패를 성공으로 위장, 사용자 무반응 체감)
- 리스크 등급: 표준
- 위치: `backend/core/.../order/adapter/CoupangOrderAdapter.java:72-104`(status별 `catch(Exception)` 후 log만·빈 리스트 반환), 동종 패턴 타 어댑터 점검
- 증상(라이브 실측): 쿠팡 동기화 → API 전 status **403 FORBIDDEN** → 어댑터가 삼켜 `result` 빈 채 반환 → 서비스 `[COUPANG] 동기화 완료: 0건 처리`·success=true → SSE SYNC_COMPLETED → 프론트 에러 없이 빈 그리드. D-022(finally 이중이벤트)는 해소됐으나 그 위층(어댑터 삼킴)이 남아 실패가 서비스 catch에 도달 못 함.
- 원인(확인): fetchOrders가 API 실패를 예외로 전파하지 않고 빈 결과로 흡수. "진짜 0건"과 "오류로 0건"을 구분 못 함.
- 제안 수정: fetchOrders에서 status별 실패를 집계 → 전량 실패(성공 0·오류≥1)면 HTTP 상태 포함 설명적 예외 throw(서비스 catch→SYNC_FAILED→에러 토스트). 부분 성공은 유지. HTTP 403 등 상태코드를 메시지에 노출("403 — IP 허용목록/자격증명 확인"). 타 마켓 어댑터 동일 패턴 점검.
- 상태: 검증통과 (qa-verifier, 2026-07-08 — `_workspace/verify/D-041_verdict.md`). 전량실패→예외전파(403 포함)·진짜0건 무예외·정상/부분실패 정합, 서비스 catch→SYNC_FAILED 경로 정합, CoupangOrderFetchFailureTest 4/4 그린. ⚠커밋 전 트리 동결 후 재게이트 필요(검증 중 D-043 라이브 편집 감지 — 판정서 참조).

### D-042: 진행 현황 사용자 액션 로그 부재 (요청 기능)

- 심각도: 기능 요청 · 리스크 등급: 표준 (신규 테이블·엔드포인트·UI)
- 사용자 요청: "진행 현황에서 사용자가 누른 모든 행동을 로그로 — 쿠팡 동기화 눌렀으면 눌렀다는 것 한 줄 + 성공/실패."
- 현황: `SyncStatusService`는 마켓별 최신 상태만 인메모리 보관(이력 없음·재시작 소실). `ProcessStatus`(sb_process_status)는 상품 배치 전용. 시간순 액션 로그 없음.
- 제안 구축: 영속 `ActionLog` 엔티티(`sb_action_log`: BaseEntity[id/status/created_at/updated_at] + action_type·market_type·action_status(STARTED/SUCCESS/FAILED)·message) + `ActionLogService.record()` + `@EventListener(SyncCompletedEvent)`로 SUCCESS/FAILED 기록 + 컨트롤러에서 STARTED 기록 + `GET /api/v1/action-logs?limit=N` + 프론트 진행현황에 "활동 로그" 섹션(시간·액션·마켓·상태·메시지). 스키마는 수동 DDL(서버). D-041과 결합 시 실패가 로그에 명확히 남음.
- 상태: 검증통과 (qa-verifier, 2026-07-08 — `_workspace/verify/D-042_verdict.md`). **DDL↔엔티티 완전 일치**(id/status/action_type/market_type/action_status/message/created_at/updated_at 컬럼명·타입·nullable 대조 — 서버 DDL 적용 안전), SyncCompletedEvent getter명 정확, GET 최근순·limit clamp, 프론트 DTO 계약 일치, ProcessStatusPage 무회귀. ServiceTest/SyncListenerTest 그린·api:test 빈주입 확인·프론트 tsc0/build0. ⚠서버 `sb_action_log` 수동 DDL 생성 필요(미생성 시 record 예외안전 삼킴).

---

### D-043: 11번가·스마트스토어·ESM+ 어댑터도 동일 오류 삼킴 + 자격증명 빈문자열 미검증

- 심각도: P1 (오동작 — 액션 로그에 거짓 SUCCESS 기록 유발) · 리스크 등급: 표준
- 위치: 각 마켓 클라이언트/포트impl fetch + `{Elevenst,SmartStore,Esmplus}OrderAdapter.fetchOrders`(D-041의 쿠팡과 동일 2층 삼킴), `{...}OrderSyncService.loadAndValidateCredential`(null만 검사·빈문자열 통과)
- 근거(fixer-c10 점검 + 리더 실측): 동일 삼킴 패턴이 11번가·스마트스토어·ESM+ 전부에 존재. 또한 loadAndValidateCredential이 `access_key==null`만 보고 **빈 문자열은 통과** → 스마트스토어(access-key EMPTY)·11번가(secret EMPTY)가 API까지 가서 실패·삼켜짐. 결과적으로 D-042 액션 로그가 이 3마켓에 거짓 SUCCESS(0건) 기록 → 로그 신뢰성 훼손.
- 제안 수정: D-041의 쿠팡 패턴을 3개 마켓 클라이언트+어댑터에 이식(전량 실패 throw). loadAndValidateCredential을 빈문자열도 불완전으로 처리(clear 메시지 "○○ 자격증명 불완전"). 각 마켓 Red 테스트.
- 상태: 검증통과 (qa-verifier, 2026-07-08 — `_workspace/verify/D-043_verdict.md`). 3마켓 어댑터+클라이언트 전량실패 예외전파(삼킴 2층 해소)·**정상0건 오탐 없음**(11번가 result_code≠0 업무분기·스마트스토어 빈배열 try내 반환 보존)·부분실패 result+warn·자격증명 `!hasText` fast-fail(불완전 이벤트, success 없음)·ESM+ masterId/스크래핑 예외전파. 신규 10 케이스 그린. **전체 클린 게이트 `./gradlew test`(전 모듈) BUILD SUCCESSFUL·프론트 tsc0/build0** — 사이클 10(D-041·D-042·D-043) 일괄 커밋 게이트 통과. 쿠팡 서비스 무접촉. 잔여: 11번가 result_code≠0(HTTP200 업무오류) 및 쿠팡 서비스 빈문자열 검증은 후속 권고(판정서 참조).

### D-044: ESM+(G마켓/옥션) Selenium이 컨테이너에 chromedriver/Chrome 부재로 동작 불가

- 심각도: P1 (기능 불능 — ESM+ 동기화 전무) · 리스크 등급: 표준 (Docker 이미지·기동 스크립트)
- 위치: `Dockerfile.backend`(런타임 `eclipse-temurin:21-jre`에 Chrome 미설치), `start.sh`, `infrastructure/.../esmplus/EsmplusScraper.java`(`new ChromeDriver(options)` — Selenium Manager 자동 다운로드 의존)
- 증상(라이브 실측): ESM+ 동기화 → 액션 로그 `FAILED "Unable to obtain: chromedriver, error Command failed"`. 자격증명(masterId/password) 정상, 크롤링 드라이버 자체가 없음. 로컬 PC엔 크롬 있었으나 서버 컨테이너엔 없음.
- 원인(확인): 런타임 이미지에 Chrome/chromedriver 부재 + Selenium 4.16이 Selenium Manager로 런타임 다운로드 시도하나 실패.
- 진단 여정(2026-07-08, 리더 직접): ①서버가 **ARM64**(Oracle Ampere, 커널 6.17) — Google Chrome ARM64 미제공(amd64 .deb 의존성 충돌). ②Debian bookworm `chromium`+`chromium-driver`(arm64, 버전일치 150) 설치는 되나 **Chromium이 SIGTRAP(exit 133)으로 크래시** — 플래그(headless old/new·single-process)·seccomp=unconfined 무관하게 재현(이 Ampere 커널과 비호환). ③해법 전환: **`seleniarm/standalone-chromium`(ARM64 지원 이미지, Chromium 124)는 이 호스트에서 세션 생성 성공 실측** → 별도 Selenium 컨테이너 + 앱이 RemoteWebDriver로 접속.
- 수정(2026-07-08, 리더+fixer-c11): 앱 이미지 chromium 설치 시도 전량 롤백(Dockerfile/start.sh 원복). 신규 `EsmplusDriverFactory.newDriver(options)` — `SELENIUM_REMOTE_URL` 있으면 `RemoteWebDriver(url, options)`, 없으면 `ChromeDriver`(로컬 개발 보존). `EsmplusOrderApiPortImpl`·`EsmplusScraper`의 ChromeDriver 타입을 공통 상위 `RemoteWebDriver`로 넓히고 생성부를 팩토리로 교체. 서버 compose에 `sbshop-selenium`(seleniarm/standalone-chromium, shm 2g) 서비스 + sbshop-api에 `SELENIUM_REMOTE_URL=http://sbshop-selenium:4444/wd/hub` env 추가(백업 `docker-compose.yml.bak-c11`). `:infrastructure:compileJava :api:compileJava :infrastructure:test` BUILD OK.
- 검증(2026-07-08, 리더 라이브): **PASS(드라이버 인프라 한정)**. 재배포 후 ESM+ 동기화 → **원격 Selenium 그리드에서 세션 생성 성공**(chrome 124, Session ID 로그 실측) → 로그인 페이지 접속·로그인 제출·쿠키 6개 획득까지 실행. chromedriver "Unable to obtain"/SIGTRAP 크래시 **완전 소멸**. 이후 `#innerIFrame` 미발견으로 실패하나 이는 드라이버가 아닌 **ESM+ 로그인 성공 여부/페이지 구조** 문제 → [[D-045]]로 분리.
- 상태: 검증통과 (사이클 10 후속 — chromedriver/드라이버 인프라 해소, 원격 그리드 세션 실증)

### D-045: ESM+ 로그인 후 주문 iframe(#innerIFrame) 미발견 → 스크래핑 실패 (근본원인 심화)

- 심각도: P1 (ESM+ 동기화 여전히 불가 — 단 원인이 드라이버가 아님) · 리스크 등급: 표준
- 위치: `EsmplusOrderApiPortImpl.loginAndCreateDriver:439-487`(로그인 후 `:482` `driver.findElement(By.id("innerIFrame"))`), `createLoggedInDetailDriver:292-318`(동일 패턴), `EsmplusOrderSyncService.loadAndValidateCredential:77-86`, `EsmplusScraper.java`(진단 전용, 미사용 실경로), `frontend/src/pages/Settings.tsx:59-60,253-315`
- 증상(라이브): 원격 그리드로 로그인 페이지 접속·제출·쿠키 6개 획득 후 주문 페이지(`/Home/v2/order-integration`)에서 `#innerIFrame` NoSuchElement.
- **(a) 로그인 성공 판별 로직 — 부재(확인)**: `loginAndCreateDriver`(439-487)는 로그인 버튼 클릭(466) 후 `Thread.sleep(5000)`(469)만 하고 **URL도 페이지 요소도 검증하지 않은 채** 곧바로 주문 페이지로 이동(478)한다. 로그인 실패(잘못된 비밀번호 등)여도 ESM+ 로그인 페이지는 보통 같은 화면에 에러 메시지만 띄우고 예외를 던지지 않으므로, 이 코드는 실패를 성공으로 오인하고 계속 진행한다. `createLoggedInDetailDriver`(292-318)도 동일 — try 블록 내 모든 예외를 뭉뚱그려 "[ESM+] 로그인 실패"로만 던져(316) 로그인 자체의 성공/실패와 후속 단계(요소 못 찾음)의 실패를 구분하지 못한다. 대조: 같은 패키지의 **`EsmplusScraper.loginAndScrapeOrders`(디버그 전용, 아래 참조)는 로그인 후 `currentUrl`(63-64)과 주문 페이지 URL(78-79)을 로깅**하고 iframe 목록을 `querySelectorAll('iframe')`로 열거(86-89)한 뒤 `findElement`를 시도한다 — 즉 진단 도구는 이미 "URL/iframe 존재를 먼저 확인해야 한다"는 패턴을 구현해뒀으나, 실제 동기화 경로(`loginAndCreateDriver`)에는 역이식되지 않았다.
- **(b) `#innerIFrame` 미발견의 코드상 원인 후보(우선순위순, 미확정)**:
  1. **로그인 실패(자격증명 무효/불완전) — 유력.** `loadAndValidateCredential`(77-86)이 `access_key`(masterId)의 `hasText`만 검사하고(82-84) **`secret_key`(password)는 전혀 검증하지 않는다.** password가 빈 문자열이어도 그대로 Selenium까지 전달되어 로그인폼에 빈 값이 입력되고, 로그인은 조용히 실패한 채 (a)의 무검증 흐름을 타고 주문 페이지로 진행 → iframe 없음.
  2. **고정 `Thread.sleep` 타이밍 경합.** 로그인 후 5초(469), 주문 페이지 이동 후 5초(479) 고정 대기 — 네트워크 지연·서버 부하 시 페이지 렌더가 늦으면 `findElement`(482, WebDriverWait 미사용 raw 호출)가 요소 생성 전에 실행돼 NoSuchElement. `idInput`/`esmTab` 탐색(449-457)은 `WebDriverWait`로 감쌌으면서 iframe 탐색(482)만 감싸지 않은 비일관성.
  3. **추가 인증 단계(캡차/2FA/기기인증).** ESM+ 로그인이 신규 IP(서버 168.107.31.154, D-041 배포기록)에서 추가 보안 절차를 요구할 가능성 — 코드로 확인 불가, 라이브 스크린샷/HTML 덤프 필요.
  4. **페이지 DOM 변경.** ESM+가 `#innerIFrame` id를 다른 값으로 바꿨을 가능성 — 코드만으로는 배제 불가.
- **(c) 자격증명 출처(확인)**: `EsmplusOrderSyncService.loadAndValidateCredential:78` — `credentialRepository.findByMarketType(MarketType.GMARKET)`로 **`MarketType.GMARKET` 행 단 하나만** 조회. `EsmplusOrderAdapter.getMarketType()`도 `GMARKET`만 반환하고 `credential.getAccessKey()/getSecretKey()`를 `masterId/password`로 그대로 `EsmplusOrderApiPortImpl.fetchOrders`에 전달한다. 검증은 masterId(accessKey)의 `hasText`뿐(82-84) — secretKey(password) 공백/오류는 Selenium 로그인 단계까지 가서야(그것도 무검증으로) 실패한다.
- **(d) "Auction 저장 에러" 및 G마켓/옥션 분리 UI — 구조적 불일치(확인)**: `Settings.tsx`는 GMARKET(59)·AUCTION(60) 탭을 완전히 독립된 마켓 연동처럼 노출한다 — 각 탭이 별도 `formData`(활성탭 기준 `credentials.find`)를 갖고 `PUT /api/v1/market-credentials/{marketType}`로 **서로 다른 DB row**(`sb_market_credential`, marketType unique)에 저장한다(253-315). AUCTION 탭 안내문(289-290) "옥션도 G마켓과 동일한 ESM+ 플랫폼을 사용합니다. G마켓과 동일한 마스터 ID/비밀번호를 입력하세요"는 사용자에게 별도 입력을 요구하는 것처럼 읽히지만, **백엔드 동기화 경로는 AUCTION 행을 영구히 읽지 않는다**((c) 참조 — GMARKET 하드코딩). 즉 사용자가 AUCTION 탭에 자격증명을 입력·저장해도(저장 API 자체는 marketType에 특별한 검증/제약이 없어 성공함 — 명시적 "저장 에러"를 일으킬 코드 경로는 발견 못함, 추정) 그 값은 어디에도 소비되지 않는다. 응답 파싱 단계의 `siteId==1 → AUCTION`(`parseSingleOrder:665`)은 **로그인 자격증명과 무관하게 API 응답 안에서 마켓을 구분**하는 로직일 뿐 — 사용자가 이해하는 "옥션 자격증명"과는 다른 층위다. **"Auction 저장 에러" 신고는 실제 HTTP 실패가 아니라, 저장은 성공하나 효과가 전혀 없는 것(동기화가 AUCTION 행을 참조하지 않음)에 대한 사용자 오인일 가능성이 높다(추정 — 라이브 재현 없이 코드만으로는 확정 불가).**
- 제안 수정(우선순위순): ①`loadAndValidateCredential`에 secretKey `hasText` 검증 추가(masterId와 동일하게 fast-fail). ②`loginAndCreateDriver`에 로그인 성공 판별 단계 추가 — 로그인 후 URL이 `signin.esmplus.com`에 여전히 머물거나 에러 요소가 있으면 명확한 "ESM+ 로그인 실패(자격증명 확인)" 예외로 즉시 중단(현재의 무검증 진행 제거). ③`#innerIFrame` 탐색을 `WebDriverWait`로 감싸 고정 sleep 경합 제거, 타임아웃 시 페이지 HTML 스냅샷을 로그로 남겨 (b)-3/4 후속 진단 가능하게. ④Settings.tsx AUCTION 탭에 "이 값은 저장되지만 동기화에는 사용되지 않습니다(G마켓 계정 통합 사용)" 명시 경고 또는 AUCTION 탭 자체 제거·GMARKET 탭에 흡수 — UX 정정은 백엔드 구조(단일 계정)를 바꿀지, UI를 바꿀지 업무 결정 필요.
- 리스크·미확인 가정(라이브·자격증명 관련 — 운영 실행 없이 코드레벨까지만 확인): 실제 masterId/password 유효성은 라이브 로그인 시도 없이는 확정 불가(추정만). (b)-3(추가 인증)·(b)-4(DOM 변경)도 코드로 배제 불가 — 라이브 재현(HTML 덤프/스크린샷) 필요. 수정 검증 시 실 ESM+ 계정으로 로그인 성공 여부를 반드시 확인해야 함(가짜 그린 위험 — 코드는 "덜 무모하게 실패"하게만 바뀌고 근본 원인은 라이브에서만 확정됨).
- 수정(2026-07-10, 사이클 12 tdd-fixer): 3스코프 — ①`EsmplusOrderSyncService.loadAndValidateCredential:81-89`(core, 태스크 지시문의 infra 경로는 오기)에 secretKey(비밀번호) `hasText` 검증 추가 → 빈/공백 비밀번호로 Selenium 로그인이 조용히 실패("성공 0건" 위장)하던 것을 스크래핑 이전 fast-fail(`"ESM+ 크레덴셜 불완전: 비밀번호(secret-key) 확인"`). **유력 원인.** ②`EsmplusOrderApiPortImpl.loginAndCreateDriver`: 로그인 클릭+sleep 직후 로그인 성공 판별(WebDriverWait로 `signin.esmplus.com` 이탈 확인, 실패 시 `"ESM+ 로그인 실패(자격증명/추가인증 확인)"` 예외) + `#innerIFrame` 탐색을 raw findElement→`WebDriverWait` 교체(미발견 시 htmlSnapshot 로깅 + `"ESM+ 주문 iframe 없음... 페이지 구조 변경 의심"` 구분 예외). 헬퍼 `safeCurrentUrl`/`pageHtmlSnapshot` 추가. `EsmplusDriverFactory`·원격 그리드·드라이버 생성 방식 무접촉. ③`Settings.tsx` AUCTION 탭 제거·GMARKET 탭에 흡수(라벨 "G마켓·옥션 (ESM+ 단일 로그인)")로 무효과 입력 방지(백엔드 GMARKET 행 소비·스키마 무변경). Red: `MarketCredentialValidationTest` esmplus_emptySecret/blankSecret 2건(수정 전 빈 secretKey가 success 이벤트 발행하던 것 실증) Red→Green. 회귀 조정: `EsmplusOrderSyncTerminalSkipTest.stubCredential`에 getSecretKey 스텁 추가. 게이트: `:infrastructure:test :core:test` BUILD SUCCESSFUL, 프론트 `tsc -p tsconfig.app.json`(Settings 신규0)·`npm run build` EXIT0. **근본해결 미확정 — 라이브 검증 필수**(자격증명 유효성·추가 인증·DOM 변경·로그인 성공판별 휴리스틱은 실 ESM+ 계정 라이브에서만 확정). 수정 요지: `_workspace/fixes/recon_D045.md`.
- 상태: 검증통과(코드)·라이브 근본원인 미확정 (재정합 사이클 — qa PASS 2026-07-10 `_workspace/verify/recon_D045_D050_D051.md`: secretKey 검증·로그인 성공판별·iframe WebDriverWait·Settings 단일화 코드 정합, 기존 `EsmplusOrderSyncTerminalSkipTest` 스텁변경은 D-039 보호 무손상 확인. **iframe 미발견 진짜 원인(자격증명 유효성·추가인증·DOM·로그인판별 휴리스틱)은 실 ESM+ 계정 라이브 검증 전까지 미확정 — 사용자 수동 검증 필수**)
- 진단강화(2026-07-10, 사이클 12 리더): 라이브에서 (a) 로그인 성공판별이 실제로 발화(로그인 페이지 잔류 시 예외) 확인됨 → 이제 실패 **원인**(자격증명 오류 vs 캡차/추가인증 vs 셀렉터 드리프트)을 다음 실행에서 확정하기 위해 로그인 실패 catch 블록에 `pageHtmlSnapshot`(앞2000자) + `loginFailureHint(html)`(캡차/보안문자/recaptcha 흔적 감지 → 신규 IP 추가인증 가능성 안내, 미감지 → 자격증명 오류 가능성 안내)를 로그·예외메시지에 포함. 코드만으로 원인 단정 불가 — 다음 라이브 로그의 힌트/HTML로 확정. 헬퍼 `loginFailureHint` 추가, 로그인·iframe 흐름 로직 무변경.
- 이력: 2026-07-10 발견(사이클 11 심화) → 2026-07-10 수정완료 → 2026-07-10 검증통과(코드, 라이브 미확정) → 2026-07-10 진단강화(사이클 12, 실패원인 확정용 HTML/캡차 힌트 로깅)

### D-046: 쿠팡 주문↔상품 매핑 끊김 — 발행 시 sellerProductId 저장 vs 주문 매칭 시 vendorItemId 조회 불일치 (사용자 신고 (1))

- 심각도: P1 (쿠팡 주문 그리드 상품정보 공백) · 리스크 등급: 표준
- 근본원인: 발행 시 `marketIdentifiers`에 `sellerProductId`만 저장되고 `vendorItemId` write-path 부재. 주문 매칭 `resolveProductId`는 vendorItemId LIKE 조회 → 상시 미스매치 → `OrderLineItem.productId=null`. (원격 origin/main base에도 미해소 — 확인.)
- 수정(재정합 재적용): 주문 응답에 이미 있던 sellerProductId를 `MarketOrderDto`에 실어, `resolveProductId`가 vendorItemId 직접매칭 실패 시 sellerProductId로 `MarketRegistration` 역조회 후 `enrichIdentifier()`로 vendorItemId 보강(최초 동기화 자기치유, 추가 API 호출 없음). 변경: `CoupangOrderSyncService`·`MarketRegistration`·`MarketOrderDto`·`CoupangOrderAdapter` + 테스트 `CoupangOrderProductMappingTest`·`MarketRegistrationEnrichIdentifierTest`.
- 상태: 검증통과 (재정합 — `:core:test :infrastructure:test :api:test --rerun-tasks` BUILD SUCCESSFUL 2026-07-10)
- 이력: 2026-07-10 재정합 사이클 — 구 로컬 브랜치(cycle10-local-20260710) D-041 재적용, **원격 D-041(=마켓 오류표면화)과 충돌 방지 위해 D-046으로 번호 재부여**(코드 인라인 주석·커밋 동기화).

### D-047: 상품관리 그리드 — 마켓별 연동코드 컬럼·바로가기 링크 부재 (사용자 신고 (5))

- 심각도: P2 · 리스크 등급: 표준
- 근본원인: 백엔드 `ProductListResponse.marketRegistrations` 데이터는 있으나 프론트 타입·컬럼 미소비. `getMarketMap` N+1.
- 수정(재정합 재적용): `productApi.ts` 타입 추가, `ProductPage.tsx` 마켓 컬럼 6종+URL 링크(폴백값은 '미확인' 배지), `ProductController.getMarketMap` N+1→`findByProductIdIn` 배치조회. 마켓 URL 패턴 best-guess(스토어/카페24 미생성) — 미해결 이관. 변경: `ProductController`·`MarketRegistrationRepository`·`productApi.ts`·`ProductPage.tsx` + `ProductControllerMarketMapTest`.
- 상태: 검증통과 (재정합 — 게이트 BUILD SUCCESSFUL·프론트 tsc0/build0 2026-07-10)
- 이력: 2026-07-10 재정합 — 구 로컬 D-045 재적용·**D-047 재부여**(원격 D-045=ESM+ iframe과 무관).

### D-048: 상품관리 그리드 — ag-Grid 클라이언트사이드 페이징으로 51번째 이후 미노출 (사용자 신고 (6))

- 심각도: P1 · 리스크 등급: 표준
- 근본원인: `pagination={true}` + `rowModelType` 미지정(clientSide)으로 최초 50건만 보유, 백엔드 서버 페이징 미연결.
- 수정(재정합 재적용): ag-Grid 내장 pagination 비활성 + 하단 antd `Pagination`, `onChange→loadData(page-1,size,keyword)` 서버 재호출(total=totalElements, 검색어 유지). 변경: `ProductPage.tsx`.
- 상태: 검증통과 (재정합 — 프론트 tsc0/build0 2026-07-10)
- 이력: 2026-07-10 재정합 — 구 로컬 D-046 재적용·**D-048 재부여**.

### D-049: 상품 상세모달 소스이미지 크롤 버튼 — 비-iHerb 무음실패 + 마켓 재게시 미배선 (사용자 신고 (7))

- 심각도: P2 · 리스크 등급: 표준
- 근본원인: 크롤러 iHerb 전용(비-iHerb 무음 빈응답), `MarketClient.syncImagesAndHtml`(4마켓 구현 완비)이 호출부 0건.
- 수정(재정합 재적용): (사용자 결정) 비-iHerb 벤더 크롤버튼 비활성+안내. 이미지/HTML 수정 후 `ProductManageUseCase.republishToMarkets`로 연동 마켓 `syncImagesAndHtml` 자동 호출(GMARKET/AUCTION은 클라이언트 부재 스킵, 마켓별 try/catch 격리, 자사DB/성공마켓은 마켓실패와 무관 커밋). 부분실패를 `ImageUploadResponse`(synced/skipped/failed)로 표면화(`message.warning`). 변경: `ProductController`·`ProductManageUseCase`·`ImageUploadResponse`(신규)·`productApi.ts`·`ProductPage.tsx` + `ProductManageUseCaseRepublishTest`·`ProductControllerImageUploadTest`.
- 상태: 검증통과 (재정합 — `:core:test :api:test` BUILD SUCCESSFUL·프론트 tsc0/build0 2026-07-10). ⚠라이브 마켓 쓰기 — 첫 실행 시 부분실패 안내 실측 권장.
- 이력: 2026-07-10 재정합 — 구 로컬 D-047(반려→재수정 포함) 재적용·**D-049 재부여**.

### 후보 기록 (사이클 8 운영 정착 중 관찰)

- **Cafe24 refresh token 만료**: 기동 시 invalid_grant (비치명 — 로그만). Cafe24 개발자센터에서 토큰 재발급 후 sb_market_credential 갱신 필요 (사용자 조치).
- **market-credentials API가 accessKey/secretKey 평문 노출**: 응답에 자격증명 원문 포함. 사용자의 "보안 비중요" 방침상 후보 기록만 (공개망 노출 시 마스킹 필요).
- **nginx 컨테이너 IP 캐시**: sbshop 컨테이너 재생성 시 nginx reload 필요 (`docker exec projects-nginx-1 nginx -s reload`) — 배포 절차에 포함 권장.

### 사이클 10 배포 기록 (2026-07-08)

- **sb_action_log 수동 DDL 적용**: 서버 postgres에 `CREATE TABLE sb_action_log`(엔티티 정합 — verifier-c10 대조) + `idx_action_log_created_at`. `docker exec -i`로 적용(-i 없으면 stdin 미전달 함정).
- **재배포**: git pull(D-041/042/043) → compose up --build(api·frontend) → nginx reload. api 기동 안정.
- **라이브 검증(핵심)**: 쿠팡 동기화 트리거 → 액션 로그에 `STARTED "쿠팡 동기화 요청"` → `FAILED "동기화 실패: 쿠팡 API HTTP 오류: 403 FORBIDDEN — IP 허용목록/자격증명 확인"` 기록 실증. **더 이상 "성공 0건" 위장 없음.** 외부 https app·action-logs API 200.
- **사용자 조치 리마인더**: 쿠팡·11번가·스마트스토어 API 허용목록에 서버 아웃바운드 IP **168.107.31.154** 등록(기존 로컬 PC IP 대체) + 스마트스토어 access-key·11번가 secret-key 자격증명 채우기. 그러면 즉시 정상 적재.
- **후속 후보**: 11번가 result_code≠0(HTTP200+업무오류코드)는 정상0건과 구분 위해 미변경 — 오류코드 사전확보 시 코드별 throw. 쿠팡 서비스 loadAndValidateCredential 빈문자열 검증(일관성, 현재 자격증명 SET이라 무영향).

### 사이클 9 배포 기록 (2026-07-08)

- **R2 자격증명 활성화 (D-020/D-036 연계 완료)**: buying-agent `application.yml`의 R2 키를 서버 `~/projects/.env`에 `CLOUDFLARE_R2_*`로 추가. **핵심 발견**: 서버 `~/projects/docker-compose.yml`(레포와 별개 파일)의 sbshop-api 서비스가 **구 변수명 `R2_ACCESS_KEY_ID` 등**을 쓰고 있어 현재 앱이 읽는 `CLOUDFLARE_R2_*`와 불일치 → `.env`만으론 컨테이너 미전달이었음. 서버 compose sbshop-api `environment`에 `CLOUDFLARE_R2_*` 5줄 매핑 추가(백업: `docker-compose.yml.bak-c9`). 재배포 후 컨테이너 env 주입·부팅 무에러(blank creds 예외 소멸) 확인. **주의: 서버 compose는 git 미추적 — 이 변경은 서버에만 존재**(레포 docker-compose.yml엔 이미 CLOUDFLARE_R2 매핑 있음, 서버가 뒤처져 있었음).
- **worker 동거 확인**: sbshop-api 컨테이너가 api(:8080) + worker(:8081) 둘 다 기동 → sbshop-api 재빌드가 스케줄 동기화(worker) 수정도 함께 반영. 별도 worker 서비스 없음.
- **배포 결과**: git pull(D-022~D-039 전체) → compose up --build(api·frontend) → nginx reload. 외부 `/sbshop-agent` 200, orders/products API 200(실데이터), api·worker 부팅 안정.
- **잔존(사용자 조치)**: Cafe24 refresh token 만료(invalid_grant) — 재배포 후에도 로그 에러(비치명). Cafe24 개발자센터 토큰 재발급 후 `sb_market_credential` 갱신 필요.

### 사이클 9 HTTPS 설정 (2026-07-08, 사용자 요청)

- **증상**: 사용자가 `https://168.107.31.154/sbshop-agent` 접속 불가 신고("서버 다운?"). **진단: 서버 정상**(컨테이너 전부 up, http 200) — nginx가 80만 리슨하고 **443/TLS 미설정**이라 https 연결거부(000)였음. docker-compose는 443 매핑하나 nginx conf에 ssl 블록·인증서 없었음.
- **처리(사용자 결정: 자체서명)**: 서버 `~/projects/nginx/conf.d/`에 자체서명 인증서 생성(`selfsigned.crt/.key`, CN·SAN=`IP:168.107.31.154`, 825일). `default.conf`(백업 `default.conf.bak-c9`) server 블록에 `listen 443 ssl` + ssl_certificate 2줄 추가(80·443 동일 location 서빙). `nginx -t` 통과 후 reload. 검증: https 200(루트·products API), http 무회귀 200, can-agent 200.
- **주의**: 자체서명이라 브라우저가 "연결이 비공개가 아닙니다/주의 요함" 경고 → 사용자가 "고급 → 계속 진행" 클릭 필요(기능은 정상). 도메인 확보 시 Let's Encrypt로 정식 인증서 전환 가능. 이 변경도 서버 nginx conf(git 미추적)에만 존재.

---

## 사이클 11 (D-045 근본원인 심화 + 액션로그 UX 잔여요구 진단, 2026-07-10, defect-scout — 코드 무수정)

> 컨텍스트: 원격 origin/main이 2026-07-08 사이클 10(D-041~D-045: 동기화 오류표면화·액션로그·ESM+ Selenium 인프라)을 배포한 위에서, 사용자 잔여 신고 3건 진단. 빌드 베이스라인 확인: `cd backend && ./gradlew compileJava compileTestJava` **BUILD SUCCESSFUL**(경고 0, P0 없음) — 정적 진단으로 진행.

D-045(위 항목)를 근본원인·수정방향으로 심화 갱신함(상태 후보→발견). 아래는 신규 2건.

### D-050: 활동 로그 actionType/marketType가 Enum 원문(영문 코드)으로 노출

- 심각도: P3 (품질/가독성 — 기능 불능 아님, 비개발자 사용자 판독 곤란) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProcessStatusPage.tsx:83`(`{ title: '액션', dataIndex: 'actionType', width: 180 }` — render 없음, 원문 그대로 렌더), `:84-85`(`marketType`도 `v || '-'`만 적용, 라벨화 없음)
- 증상: 활동 로그 테이블 "액션" 컬럼에 `COUPANG_SYNC`/`SMART_STORE_SYNC`/`ELEVEN_STREET_SYNC`/`GMARKET_SYNC` 같은 내부 코드가 그대로 표시되고, "마켓" 컬럼도 `COUPANG`/`SMART_STORE`/`ELEVEN_STREET`/`GMARKET` 원문이 표시됨.
- 원인(확인): 백엔드 `ActionLog` 엔티티(`backend/core/.../domain/actionlog/ActionLog.java:29-34`)는 `actionType`·`marketType`을 **Java enum이 아닌 순수 `String` 컬럼**으로 저장한다(`action_type varchar(50)`, `market_type varchar(30)`). `actionType` 값은 실제로는 자유문자열이지만 관례상 `{MarketType.name()}_SYNC` 패턴으로 생성됨 — 확인된 발생처: `ActionLogSyncListener.java:24-25`(`String actionType = marketType + "_SYNC"`, 동기화 성공/실패 이벤트) + `OrderSyncController.java:55,79,103,123`(STARTED 기록 시 하드코딩 리터럴 `"COUPANG_SYNC"`/`"SMART_STORE_SYNC"`/`"ELEVEN_STREET_SYNC"`/`"GMARKET_SYNC"`, marketType은 각각 `"COUPANG"`/`"SMART_STORE"`/`"ELEVEN_STREET"`/`"GMARKET"`). `ActionLogResponse.from()`(`backend/api/.../dto/actionlog/ActionLogResponse.java:17-25`)도 문자열을 그대로 통과시킬 뿐 라벨 변환 없음. **백엔드에 CommonCode/라벨 테이블·API 없음**(레포 전체에서 "라벨"/"CommonCode" 관련 인프라 grep 결과 전무) — 이 프로젝트의 기존 관례는 프론트 로컬 맵이다: `frontend/src/pages/OrderGrid.tsx:416-421`의 `marketLabels: Record<string,string>`(`COUPANG: '쿠팡'` 등, `:524-525`에서 토스트 메시지에 사용)가 이미 동일한 목적의 로컬 매핑 선례로 존재.
- 제안 수정 방향: 백엔드 변경 불필요(actionType이 자유문자열 관례라 enum화는 별도 리스크 있는 구조 변경) — `ProcessStatusPage.tsx`에 `OrderGrid.tsx:416` 선례를 따라 로컬 `actionTypeLabels`/`marketTypeLabels: Record<string,string>` 맵 추가 후 `render`로 라벨 치환(미매핑 값은 원문 fallback, 신규 마켓 추가 시 자동 깨지지 않게). actionType은 `{marketLabel}_SYNC` 관례가 안정적이므로 marketTypeLabels를 먼저 두고 `actionType.replace('_SYNC','')`로 마켓코드 추출 → 라벨 조합("쿠팡 동기화") 방식도 가능(단, 향후 SYNC 외 actionType 추가 시 깨짐 — 명시적 actionTypeLabels 맵이 더 안전). 두 맵을 `OrderGrid.tsx`와 공유 모듈(`src/constants/marketLabels.ts` 등)로 추출하면 중복도 해소.
- 재현: 코드 경로 추적(위) — 브라우저 재현은 액션 로그 1건 이상 존재 시 `/process-status` 진행현황 페이지에서 즉시 육안 확인 가능(런타임 실행 없이 코드상 100% 확정, ellipsis/render 부재는 정적 사실).
- 상태: 검증통과 (재정합 사이클 — qa PASS 2026-07-10 `_workspace/verify/recon_D045_D050_D051.md`). `ProcessStatusPage.tsx`에 `marketTypeLabels` 맵(OrderGrid.tsx:416 이식) + `renderMarketType`/`renderActionType`(`_SYNC` 접미 → `{마켓라벨} 동기화` 조합, 미매칭 원문 폴백) 추가, actionType/marketType 컬럼에 render 연결. 게이트: tsc -p tsconfig.app.json 신규 에러 0(OrderGrid 4건 베이스라인) + npm run build EXIT 0. 요약: `_workspace/fixes/recon_D050_D051.md`.

### D-051: 활동 로그 message 컬럼이 ellipsis 잘림 + 전체보기 부재

- 심각도: P3 (품질 — 정보 손실 아님, 확인 곤란) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProcessStatusPage.tsx:88`(`{ title: '메시지', dataIndex: 'message', ellipsis: true }` — Modal/팝오버 없음, 행 클릭 핸들러 없음)
- 증상: "메시지" 컬럼이 antd `ellipsis: true`로 컬럼 폭(고정폭 미지정 → 나머지 flex 공간)에서 말줄임 처리되고, 전체 텍스트를 볼 방법(모달/팝오버/툴팁 expand)이 없음.
- 원인(확인): 백엔드가 저장하는 message는 **JSON이 아닌 순수 한글/영문 자유 텍스트**다. `ActionLog.java:43`(`@Column(name="message", length=1000)`, 주석 "예: '동기화 요청', '동기화 실패: 403 ...'`) — 실제 값 확인: `ActionLogSyncListener.java:28`(성공 시 `"동기화 성공"` 고정), `:31-32`(실패 시 `"동기화 실패: " + reason`, reason은 `SyncCompletedEvent.getErrorMessage()` — D-041/D-043에서 확인된 예로 `"쿠팡 API HTTP 오류: 403 FORBIDDEN — IP 허용목록/자격증명 확인"` 등 체이닝된 예외 메시지 텍스트, ESM+ 경로는 `EsmplusOrderApiPortImpl.fetchOrders:53`에서 `"ESM+ 주문 조회 실패: " + e.getMessage()`처럼 중첩 예외 메시지가 누적돼 길어질 수 있음). `OrderSyncController.java:55` 등 STARTED 메시지는 짧은 고정 문구("쿠팡 동기화 요청"). 컬럼 자체는 DB에서 최대 1000자까지 저장 가능하므로, 중첩 예외(RuntimeException wrapping)가 쌓이는 실패 케이스는 화면 폭보다 훨씬 길어질 소지가 큼.
- 제안 수정 방향: message가 JSON이 아니므로 별도 JSON 포맷터는 불필요 — 행 클릭(`onRow`) 또는 메시지 셀 클릭 시 antd `Modal.info`/커스텀 모달로 `white-space: pre-wrap` 처리된 전체 텍스트 표시(길이 제한 없이). 실패 메시지가 향후 구조화될 가능성(예: 스택트레이스 포함) 대비, 모달에 `<pre>` 또는 코드블록 스타일 적용 권장(가독성 확보, JSON 파싱 시도는 불필요 — 실패해도 원문 그대로 표시하는 방어적 렌더).
- 재현: 코드 경로 추적(위) — `ellipsis: true` 단독 사용은 antd 기본 동작상 hover 시 title 툴팁만 제공하고 클릭 확장 기능은 없음(정적 사실, antd Table 컬럼 스펙 확인). 브라우저 재현은 message 길이가 컬럼 폭을 넘는 액션 로그(예: D-041/D-043류 403 오류 메시지) 존재 시 즉시 확인 가능.
- 상태: 검증통과 (재정합 사이클 — qa PASS 2026-07-10 `_workspace/verify/recon_D045_D050_D051.md`). `ProcessStatusPage.tsx` message 컬럼에 `ellipsis: true` 유지 + 클릭 가능한 span 진입점(render) 추가, `messageModal` 상태 + antd `Modal`(`<pre>` white-space:pre-wrap/word-break/max-height:60vh)로 전체 텍스트 표시. import에 `Modal` 추가. JSON 파서 불필요(순수 텍스트, 방어적 원문 렌더). 게이트: tsc 신규 에러 0 + npm run build EXIT 0(modal 청크 번들 확인). 요약: `_workspace/fixes/recon_D050_D051.md`.

### D-052: 상품 그리드 마켓별 연동코드가 쿠팡만 표시·나머지 '미확인' (사용자 신고 후속)

- 심각도: P1 (스토어·11번가·카페24·ESM+ 연동코드 상시 '미확인' — 마켓 상품 바로가기 무력화) · 리스크 등급: 표준
- 위치: `ProductController.buildMarketMap:172`(`reg.extractVendorItemId()`), `MarketRegistration.extractVendorItemId:99`(쿠팡 전용 `vendorItemId` 키만 읽음), `frontend/src/pages/ProductPage.tsx:40-71`(`renderMarketCell` — code===productId 폴백 시 '미확인' 배지)
- 근본원인: `buildMarketMap`이 **모든 마켓**의 코드를 쿠팡 전용 `extractVendorItemId()`(marketIdentifiers의 `vendorItemId` 키)로 읽었다. 스마트스토어(`originProductNo`)·11번가(`elevenstId`)·카페24(`product_no`)·ESM+(`goodsNo`)는 해당 키가 없어 항상 null→`productId` 폴백→프론트에서 code===row.id로 '미확인' 배지. 스크린샷 실증(쿠팡 코드 정상, 스토어/11번가/카페24 전부 '미확인'). 각 마켓 클라이언트 저장 키 확인: Coupang `sellerProductId`/`vendorItemId`, Elevenst `elevenstId`, Smartstore `originProductNo`, Cafe24 `product_no`/`product_code`.
- 수정(2026-07-10, 사이클 12 리더): `MarketRegistration.extractMarketCode()` 신설 — marketType별 실제 키 분기(COUPANG=vendorItemId→sellerProductId, SMART_STORE=originProductNo→channelProductNo, ELEVEN_STREET=elevenstId→prdNo, CAFE24=product_no→product_code, GMARKET/AUCTION=goodsNo→itemNo→goodsCode). `buildMarketMap`이 이를 사용(코드 없으면 기존대로 productId 폴백→'미확인'). 프론트 '미확인' 툴팁을 D-046 한정 문구→"해당 마켓 연동정보에 상품코드 키 없음"으로 일반화. Red: `MarketRegistrationExtractMarketCodeTest`(스토어 코드가 vendorItemId 조회로는 null이나 extractMarketCode로는 OP123 반환하는 회귀 재현 포함). 게이트: `:core:test :api:test :infrastructure:test --rerun-tasks` BUILD SUCCESSFUL, 프론트 tsc(신규0, 기존 D-007 OrderGrid 4건만)·build EXIT0.
- 미확정 가정: ESM+(GMARKET/AUCTION)의 실제 저장 키가 `goodsNo`인지는 상품 발행 write-path 미확인(라이브 데이터로 확인 필요) — 후보 키 3종(goodsNo/itemNo/goodsCode) 순차 조회로 방어. 스토어/11번가/카페24는 클라이언트 저장 키 코드상 확인.
- 라이브 발견(2026-07-10, 사용자 협조 DB 조회): `sb_market_registration` 마켓별 건수 = CAFE24 3185·SMART_STORE 3183·ELEVEN_STREET 2286·COUPANG 1261, **GMARKET·AUCTION 0건**. 전체 11개 테이블 중 마켓코드 저장처는 이 테이블뿐(레거시 매핑 테이블 없음), `sb_product`에도 마켓코드 컬럼 없음. 코드상 ESM+ 상품 연동 경로 부재(발행 클라이언트 4개뿐, ESM+는 주문 스크래핑 전용). **결론: 지마켓/옥션은 "매핑 실패"가 아니라 "데이터 부재"** — 사용자 가설(카페24 호스팅 원본 market_registration 테이블 마이그레이션 누락)이 유력, 원본 확인 후 임포트 스크립트 필요. D-052 코드수정은 데이터가 존재하는 4마켓엔 유효.
- 상태: 수정완료(검증대기 — 리더 게이트 통과. 4마켓 라이브 표시 확인 대기 / GMARKET·AUCTION은 데이터 부재로 별도 임포트 트랙)
- 이력: 2026-07-10 발견·수정(사이클 12) → 2026-07-10 라이브 진단(지마켓/옥션 데이터 부재 확정)

### 사이클 11 요약 (defect-scout)

- 신규 결함: 2건(D-050·D-051, 둘 다 P3/경량). 기존 D-045 심화 갱신 1건(상태 후보→발견, 근본원인 4갈래 원인후보·자격증명 경로·GMARKET/AUCTION 구조 불일치 확정).
- 심각도 분포(사이클 11 신규+심화분): P1 1건(D-045, 심화) · P3 2건(D-050·D-051).
- 최우선 수정 권고 3건:
  1. **D-045 (P1)** — ESM+ 동기화 여전히 불가한 상태의 핵심 원인. 우선 `loadAndValidateCredential`에 secretKey 검증 추가(비용 최소, 즉시 가능) + 로그인 성공 판별 단계 추가(라이브 확정 전제). 코드 수정만으로 근본 해결을 단정할 수 없음 — 실 계정 라이브 로그인 검증 필수.
  2. **D-050 (P3)** — 로컬 라벨 맵 추가만으로 해소 가능한 저리스크·저비용 개선(선례 `OrderGrid.tsx:416` 존재), 사용자 체감 개선 크기 대비 수정 비용 낮음.
  3. **D-051 (P3)** — 모달 추가만으로 해소, D-050과 동일 파일(`ProcessStatusPage.tsx`) 동시 처리 시 효율적.
- ESM+(D-045) 관련 미확인 가정 재강조: 실 자격증명 유효성·추가 인증 단계·ESM+ 페이지 DOM 변경 여부는 코드 정적 분석만으로 확정 불가 — 수정 후 반드시 라이브(실 ESM+ 계정) 검증 필요, 가짜 그린 위험 큼.

---

## 사이클 13 (사용자 신고 4건 — 시간대·상품정보·호버성능·상품그리드 UX, 2026-07-11, 리더 직접)

> 컨텍스트: 사용자 UI 신고 4건을 병렬 진단(Explore ×2: 주문-상품 매핑, 시간대) 후 리더가 프론트 전용으로 수정. 전 항목 프론트엔드 변경(백엔드 무변경) → 회귀 위험 최소, 경량 등급. 게이트: `tsc -p tsconfig.app.json` 신규 에러 0(기존 OrderGrid ORDER/PRODUCT_COLUMNS 4건도 함께 해소해 **완전 clean**) + `npm run build` EXIT 0.

### D-053: 진행 현황 시간이 UTC(미국시간처럼)로 표시

- 심각도: P2 (판독 오류 — 기능 정상) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProcessStatusPage.tsx:117`(활동로그 createdAt), `:112`(배치상태 startedAt, render 부재로 원문 노출)
- 근본원인: 백엔드 시간 필드가 모두 zone 없는 `LocalDateTime`(`BaseEntity.createdAt`, `ProcessStatus.startedAt` 등) + 컨테이너 TZ=UTC → JSON에 zone 정보 없이 `"2026-07-09T04:22:00"` 형태로 직렬화. 프론트 `new Date(v).toLocaleString('ko-KR')`가 이를 (브라우저 로컬 존으로) 파싱하지만 값 자체가 UTC 벽시계라 9시간 이른 시각 표시. **주의**: `spring.jackson.time-zone`은 `LocalDateTime` 직렬화에 영향 없음(존 없는 타입) — 백엔드 설정만으론 미해결.
- 수정: `frontend/src/utils/datetime.ts` 신설 — `toKstDate()`(존 없는 문자열을 UTC로 간주 `+Z`), `formatKst()`(ko-KR·Asia/Seoul 강제). ProcessStatusPage의 createdAt·startedAt 컬럼 render에 `formatKst` 연결. 기존/신규 데이터 모두 일괄 교정, 브라우저 존과 무관.
- 범위 판단: **통합주문관리 주문일시(orderDate)는 의도적으로 미변경** — Coupang 등 한국 마켓 API가 KST를 반환하고 `CoupangOrderAdapter.java:198`에서 `LocalDateTime.parse`로 naive KST 저장하므로 이미 정확. UTC 변환 적용 시 오히려 +9h 어긋남(서버감사시간 UTC와 마켓주문시간 KST의 혼재 의미 구분).
- 상태: 검증통과 (리더 게이트: tsc clean + build EXIT0)

### D-054: 통합주문관리 상품정보(상품명/영문명) 미표시

- 심각도: P1 (상품 식별 정보 상시 공란) · 리스크 등급: 경량
- 위치: `frontend/src/api/orderApi.ts:51-57`(ProductDto), `frontend/src/pages/OrderGrid.tsx:958,962,956`
- 근본원인: 상품코드(`product.sbCode`)는 정상 표시되므로 `product`는 null이 아님(백엔드 조인 정상). 실제 원인은 **프론트 타입/접근 경로 오류** — 백엔드 `Product` 엔티티의 `productName`/`originalName`은 **flat String**인데 `ProductDto`가 `productName: {productName, originalName}` 중첩 객체로 잘못 정의됨. OrderGrid가 `product.productName.productName`으로 접근해 `undefined`. 소싱 URL도 실제 필드는 `SourcingInfo.sourceUrl`인데 `sourcingInfo.url`로 접근.
- 수정: `ProductDto`를 flat(`productName?: string; originalName?: string; sourcingInfo?: {sourceUrl?: string}`)으로 교정. OrderGrid 상품정보 셀 접근 경로를 `product.productName`(등록상품명 행1)·`product.originalName`(영문명 행2)·`sourcingInfo.sourceUrl`(링크)로 수정.
- 참고(별개 트랙): 동기화 시 MarketRegistration 매핑 실패로 `OrderLineItem.productId`가 null이 되는 케이스(`CoupangOrderSyncService.resolveProductId`)는 데이터 레벨 이슈로 별도 — 본 증상(상품코드는 뜨는데 이름만 공란)의 원인은 아님.
- 상태: 검증통과 (리더 게이트: tsc clean + build EXIT0)

### D-055: 통합주문관리 행 호버 음영 지연

- 심각도: P2 (체감 성능 — 마우스 호버 시 회색 음영 지연) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:387`(hoveredOrderId state), `:1282`(행별 isHovered/bgCol)
- 근본원인: 호버를 React state(`hoveredOrderId`)로 처리 → 매 `onMouseEnter`마다 전체 그리드(주문 500건×최대 3행 = 수백~수천 행) 리렌더·전 셀 `flexRender` 재실행. 이 리렌더 비용이 음영 표시 지연으로 체감됨. 추가로 TableRow 내장 per-row 핸들러가 `background=transparent`로 리셋하는 부작용.
- 수정: `hoveredOrderId` state 제거 → ref + 이벤트 위임(DOM 클래스 토글) 방식. 스크롤 컨테이너에 `onMouseOver`(위임)·`onMouseLeave` 부착, 다른 주문 그룹 진입 시에만 `querySelectorAll('tr[data-order-id="..."]')`로 `.og-row-hover` 클래스 토글(같은 그룹이면 early-return → per-pixel DOM 조회 없음). CSS `.og-row-hover > td { background:#f1f5f9 !important }`로 frozen 셀 인라인 배경까지 즉시 덮음. 각 TableRow에 `data-order-id` 부여, 내장 핸들러는 `onMouseEnter/Leave={undefined}`로 무력화. 리렌더 완전 제거 → 즉시 음영.
- 상태: 검증통과 (리더 게이트: tsc clean + build EXIT0)

### D-056: 상품관리 그리드 — G마켓/옥션 컬럼 제거 + 디자인 개선 + 가격/재고 버튼

- 심각도: P3 (UX/디자인) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProductPage.tsx`
- 요구/원인: (1) G마켓·옥션은 마켓 상품코드가 없음(D-052 라이브 확정: `sb_market_registration` GMARKET·AUCTION 0건) → 상시 공란 컬럼. (2) ag-grid 오버라이드가 `.ag-theme-alpine`에만 적용(`index.css:191`)돼 실제 사용 테마 `.ag-theme-quartz`엔 미적용 → 기본 스타일로 촌스러움. (3) `가격/재고` 버튼이 무스타일 raw `<button>`.
- 수정: (1) `MARKET_COLUMNS`에서 GMARKET·AUCTION 제거(쿠팡·스토어·11번가·카페24만). (2) `.sb-product-grid`로 스코프한 quartz 토큰 오버라이드(폰트·헤더·행 hover·테두리·라운드·accent) + 헤더/툴바 카드화 + 제목·건수 표시 + 이미지 셀 라운드/플레이스홀더 + 판매가/재고 우측정렬. (3) `가격/재고`를 `.sb-pricestock-btn`(primary 아웃라인→hover 채움)으로 교체. 타 그리드 영향 없도록 클래스 스코프.
- 부수: 기존 `OrderGrid.tsx`의 `ORDER_COLUMNS`/`PRODUCT_COLUMNS` 암묵 any[] 타입 에러 4건(베이스라인)도 `: string[]` 명시로 해소 → 타입 게이트 완전 clean.
- 상태: 검증통과 (리더 게이트: tsc clean + build EXIT0)

### 사이클 13 요약

- 신규 결함 4건(D-053 P2 · D-054 P1 · D-055 P2 · D-056 P3), 전부 프론트 전용·경량. 백엔드 무변경 → 회귀 게이트는 프론트 tsc+build로 충분(전 항목 통과, 타입 게이트 완전 clean 달성).
- 핵심 판단: 시간대 수정 범위를 서버감사시간(UTC)에 국한하고 마켓주문시간(KST)은 제외 — 의미 혼재 방지. 호버는 React state→DOM 위임으로 리렌더 제거.

---

## 사이클 14 (재고현황 새로고침 무반응 신고, 2026-07-11, 리더 직접 — 부분 재실행)

### D-057: 통합주문관리 재고현황 새로고침 버튼 무반응(체감)

- 심각도: P2 (기능 오작동 아님 — 피드백 부재로 무동작 체감) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:653` `handleSyncProductStock`
- 진단(경계면 전 구간 확인): 클릭 발화 정상(통관정보 새로고침 `:916`과 완전 동일한 SVG onClick 패턴, 통관은 정상 동작). 엔드포인트 정상(`POST /api/v1/products/sync/stock` 라이브 200 `{success:true}`, `ProductSyncController:28` 즉시 성공 반환 후 `new Thread`로 백그라운드 크롤). 대상 선정 정상(`findProductIdsByShippingStatus(NEW/PREPARING)` — 그리드의 "구매준비"=`ShippingStatus.PREPARING`이므로 대상 비어있지 않음).
- 근본원인: 순수 프론트 UX 결함. 재고 동기화는 마켓 동기화(SSE `SYNC_COMPLETED`로 완료 시 갱신)와 달리 **완료 신호가 없는 백그라운드 크롤(수 초~수 분)**인데, 핸들러가 (1) 성공 토스트 없이 (2) 고정 3초 후 refetch만 수행 → 크롤 미완 상태로 refetch돼 재고 값 무변화 + 명시적 피드백 없음 → 사용자에겐 "눌러도 반응 없음"으로 체감.
- 수정(2026-07-11): `handleSyncProductStock`에 ① 시작 즉시 `toast.info`("재고 동기화를 시작했습니다 …") 명시 피드백, ② 3초 후 refetch+오버레이 해제(기존) 유지, ③ 15초 지연 refetch 추가로 느린 크롤 결과 반영. 오버레이 장시간 고착(그리드 블로킹) 회피. 백엔드 무변경.
- 상태: 검증통과 (리더 게이트: tsc -p tsconfig.app.json clean + npm run build EXIT0)
- 미해결(후속): 재고 크롤 완료 이벤트가 없어 갱신 타이밍은 여전히 추정치(15초). 근본 해결은 재고 동기화에도 마켓 동기화처럼 완료 SSE/폴링(배치 진행상태 API 연동)을 붙이는 것 — 별도 개선 트랙(P3).

---

## 사이클 15 (푸시 전 전수 QA — 대시보드/ETC/마켓전파, 2026-07-11, 리더 직접 + 5에이전트 병렬조사)

> 사용자 요청: 모든 메뉴·버튼·액션 전수 조사 후 수정·푸시. 5개 도메인 조사 에이전트(대시보드/OrderGrid/이메일자동추적/마켓전파/기타페이지) 병렬 투입 → 리더가 상충·핵심 재확인. 산출: `docs/normalize/qa-checklist-20260711.md`(전수 체크리스트). 안전 결함 2건 수정(D-058·D-059), 중대 마켓전파 갭 2건 보고(D-060·D-061).

### D-058: 배송정보 미입력 시 택배사가 'ETC'로 표시

- 심각도: P2 (오표기 — 미입력이 '기타'로 보임) · 리스크 등급: 경량
- 위치: `backend/core/.../domain/order/enums/ShippingCarrier.java:27` `fromMarketCode`
- 근본원인: `code == null`이면 null 반환하나 **빈 문자열("")/공백은 switch default 분기→ETC("기타")**로 매핑. 마켓(쿠팡/스마트스토어 어댑터가 `fromMarketCode(deliveryCompanyName/Code)` 호출)이 미배송 주문에 빈 택배사를 주면 ETC가 저장돼 그리드에 "ETC" 표시. 그리드 렌더 자체는 빈 값→'-'로 정상(`OrderGrid.tsx:1061`)이므로, 원인은 저장된 값이 실제 'ETC'라는 것.
- 수정(2026-07-11): `if (code == null || code.isBlank()) return null;` — 빈/공백도 '택배사 없음'(null)으로 처리. 미배송이면 빈칸(`-`). 회귀 테스트 `ShippingCarrierTest`(빈문자열→null, DHL→ETC, 알려진 코드 매핑) 추가.
- 상태: 검증통과 (`:core:test` ShippingCarrierTest PASS, 프론트 무변경)
- 참고: 이미 저장된 기존 'ETC' 데이터는 이 수정으로 자동 교정되지 않음(신규 동기화분부터 적용). 필요 시 일괄 UPDATE 별도.

### D-059: 대시보드 지표가 하드코딩 0 (실데이터 미연동)

- 심각도: P2 (핵심 현황 화면 무기능) · 리스크 등급: 표준
- 위치: `frontend/src/pages/Dashboard.tsx`(전체 하드코딩), 백엔드 통관필터 부재
- 근본원인: Dashboard.tsx가 useState/useEffect/fetch 전무 — 3개 카드 모두 `0` 리터럴. '통관 오류/대기'는 `OrderSearchCondition`에 통관상태 필터가 없어 집계 불가였음.
- 수정(2026-07-11): (백엔드) `OrderSearchCondition`에 `List<CustomsStatus> customsStatuses` 추가(쿼리파라미터 자동 바인딩), `OrderRepositoryImpl`에 `customsStatusIn()`(order.customsData.customsStatus.in) 헬퍼를 content·count 쿼리 where에 추가. (프론트) `orderApi.fetchOrderCount(filters)` 경량 카운트 헬퍼(size=1, totalElements) 추가, Dashboard를 7개 지표(전체·미발주·구매준비·배송진행중·배송완료·통관오류대기·배송처리대기)로 재구성 + 병렬 조회 + 새로고침 버튼.
- 상태: 검증통과 (`:infrastructure:test`·`:api:test` BUILD SUCCESSFUL, 프론트 tsc clean + build EXIT0)

### D-060: 상품 가격/재고 수정이 어느 마켓에도 동기화되지 않음 (호출 경로 부재)

- 심각도: P1 (사용자 기대 기능 미동작 — item 4) · 리스크 등급: **중대(마켓 API 계약·다도메인)** → 요승인
- 위치: `ProductManageUseCase.updatePriceStock:42`(DB만 저장), `BatchPriceStockService`(3개 메서드 모두 DB만), MarketClient.syncPriceAndStock(마켓별 구현 편차)
- 근본원인: 단건/배치 가격재고 수정 경로 어디에서도 `MarketClient.syncPriceAndStock()`를 호출하지 않음. 게다가 마켓별 구현도 편차: 스마트스토어·11번가는 실제 API 호출, **쿠팡·카페24는 로컬 Map만 패치(실 API 호출 없음)**, G마켓/옥션은 MarketClient 자체 부재.
- 제안: (1) `updatePriceStock`/배치 이후 상품의 MarketRegistration을 순회하며 `syncPriceAndStock` 호출하는 경로 신설, (2) 쿠팡·카페24 클라이언트의 실 API 호출 구현, (3) 실패 표면화(부분 실패 마켓 반환). 마켓 API 계약을 실제로 건드리므로 사용자 승인 + 라이브 검증 필수.
- 상태: 발견(보고) — 미수정. 중대 등급이라 별도 배치·승인 후 진행.

### D-061: 배송 마켓 전파 갭 (배송정보 수정 미전파 + ESM+/카페24 미구현)

- 심각도: P1~P2 · 리스크 등급: **중대(마켓 API 계약)** → 요승인
- 위치: `OrderController.updateShippingInfo`(DB만), `EsmplusOrderAdapter.shipOrder:71`(log.warn 스텁), Cafe24 OrderAdapter 부재
- 근본원인: (A) 배송정보 "수정"(updateShippingInfo)은 DB만 갱신하고 마켓 미전파 — 단, 배송"처리"(shipOrders)와 이메일 자동추적 경로는 쿠팡·스토어·11번가 3마켓에 송장 전파함. (B) ESM+(G마켓/옥션)은 shipOrder가 로그만, 카페24는 OrderAdapter 자체가 없어 배송처리 시 예외.
- 제안: ESM+/카페24 배송 어댑터 구현, updateShippingInfo에도 (정책상 필요 시) 마켓 전파 연결. 중대 등급 — 승인·라이브 검증 필수.
- 상태: 발견(보고) — 미수정.

### 사이클 15 요약

- 조사: 5에이전트 병렬 전수 감사, 전수 체크리스트 산출. OrderGrid 22액션·기타 페이지 대부분 계약 정상(✅). iHerb 이메일 자동추적 체인 배선·가동 확인(스케줄러 활성 — 초기 FAIL 오판 정정).
- 수정: D-058(ETC·경량), D-059(대시보드·표준) — 게이트 통과.
- 보고(요승인·중대): D-060(가격재고 마켓 미동기), D-061(배송 마켓 전파 갭). 프론트 죽은코드 인벤토리(P3).
- 다음 배치 권고: D-060 우선(사용자 명시 기대) — 승인 후 마켓별 syncPriceAndStock 호출 경로 신설 + 쿠팡/카페24 실 API 구현 + 라이브 검증.

---

## 사이클 16 (D-060 수정 — 가격/재고 마켓 동기화 배선 + 쿠팡/카페24 실 API, 2026-07-11, 리더 직접, 사용자 승인)

### D-060: 상품 가격/재고 → 마켓 동기화 (수정 완료)

- 상태 전이: 발견(보고·요승인) → **검증통과(수정)** — 사용자 "승인"
- 리스크 등급: 중대(마켓 API 계약·다도메인). 사용자 승인 취득. 게이트: 회귀 통과 + 신규 TDD.
- 수정 내역:
  1. **배선(핵심)**: `ProductManageUseCase.updatePriceStock`가 DB 저장 후 `syncPriceStockToMarkets` 호출 — 상품의 MarketRegistration을 순회하며 `MarketClient.syncPriceAndStock(marketItemId, rawData, price.intValue(), stock)` 실행. 이미지 재게시(`republishToMarkets`, D-049)와 동일 규율: 클라이언트 없는 마켓(GMARKET/AUCTION) 스킵, 마켓별 try로 부분 실패 수집(롤백 없음). marketItemId는 D-052 `extractMarketCode()`(마켓별 코드) 사용. 반환 `MarketRepublishResult`(synced/skipped/failed).
  2. **쿠팡 실 API**: `CoupangMarketClient.syncPriceAndStock`가 로컬 Map만 패치하던 것을 `syncImagesAndHtml`과 동일 패턴(전체 seller-product 페이로드 PUT `/v2/providers/seller_api/apis/api/v1/marketplace/seller-products`)으로 실제 반영. items 없으면 예외 전파(실패 표면화).
  3. **카페24 실 API**: `Cafe24MarketClient.syncPriceAndStock`가 로컬 Map만 패치하던 것을 `publish`의 price/supply_quantity 필드 + `syncImagesAndHtml`의 `PUT /admin/products/{id}` 패턴으로 실제 반영.
  4. **컨트롤러/프론트**: `PUT /products/{id}/price-stock`가 `MarketRepublishResult` 반환. ProductPage가 마켓별 성공/실패를 토스트로 표면화(자사 저장 성공해도 마켓 반영 실패 조용히 삼키지 않음).
  5. **테스트**: `ProductManageUseCasePriceStockSyncTest`(마켓 순회 호출·스킵·부분실패 수집 3케이스). 게이트: `:core:test` 신규 3 PASS(기존 flaky SmartStore 1건 제외), `:infrastructure:test`·`:api:test` BUILD SUCCESSFUL, 전체 compileJava SUCCESSFUL, 프론트 tsc clean + build EXIT0.
- 실동작 마켓: 스마트스토어·11번가는 기존 실 API 구현 → 배선으로 즉시 반영. 쿠팡·카페24는 이번에 실 API 추가. GMARKET/AUCTION은 클라이언트 부재 → 스킵.
- **라이브 미검증(중요)**: 실 마켓 API 응답은 코드 정합으로만 확인, 실제 write 성공은 미검증. 배포는 안전(버튼 클릭 시에만 해당 상품 write, 자동 대량변경 없음) — 첫 상품 1건 클릭이 곧 스모크 테스트. 마켓별 실패는 토스트로 표면화됨.
- 잔여(후속): **배치 경로**(`BatchPriceStockService` 3메서드)는 여전히 DB만 — 대량 라이브 write는 단건 검증 후 별도 배선 권고. 카페24 재고는 product-level `supply_quantity`(publish 관례) 사용 — variant 재고 정확 반영은 라이브 확인 필요. D-061(배송 마켓 전파)은 미착수.

### 사이클 16 요약
- D-060 수정 완료(승인·게이트 통과). 단건 가격/재고 저장이 연동 마켓(스토어·11번가·쿠팡·카페24)에 반영되고 결과가 표면화됨.
- 다음: 단건 라이브 스모크(상품 1건) → 이상 없으면 배치 경로 배선 + D-061(배송 전파) 검토.

### D-060 라이브 검증 (2026-07-11, 리더 직접 — 무인증 라이브 환경, 사용자 허가)

- 방법: 실 서버 `PUT /products/1/price-stock`에 **현재값(40700/500) 그대로** 저장 → 값 변화 없이 마켓 동기화 경로만 실행, 마켓별 결과 관찰(상품1=쿠팡·11번가·스토어·카페24 4마켓).
- 결과: `synced=[SMART_STORE, ELEVEN_STREET]`, `failed={CAFE24, COUPANG}`.
  - ✅ **스토어·11번가: 실 API write 성공** — 배선·클라이언트 end-to-end 정상 확정.
  - 🔴 **쿠팡**: 1차 "원본데이터(items) 없음"(내 rawData 의존 접근 오류) → **vendor-items 전용 엔드포인트(`/vendor-items/{vendorItemId}/prices|quantities`)로 교정**. 2차 "Empty key" → `CoupangProperties.secretKey` 공백 = **서버 `COUPANG_ACCESS_KEY/SECRET_KEY` env var 미설정**(마켓 상품 API는 주문 API의 DB 자격증명과 별개인 env var 사용). 코드 아님·운영 config. 명확한 메시지 가드 추가.
  - 🔴 **카페24**: "401 Invalid access_token" → **기존 미해결 이슈(Cafe24 OAuth 토큰 만료, 사이클 8/9) 확정**. 코드 아님·토큰 재발급 필요.
- 결론: **D-060 코드 완성·검증**(부분 실패 표면화 포함). 스토어·11번가 라이브 동작. 쿠팡·카페24는 **자격증명 설정만 하면 즉시 동작**(코드 준비 완료). 남은 것은 운영 조치:
  1. 서버에 `COUPANG_ACCESS_KEY`, `COUPANG_SECRET_KEY`(쿠팡 마켓 상품 API 키) 설정.
  2. Cafe24 개발자센터에서 토큰 재발급 후 저장소 갱신.

### D-060 라이브 반복검증·자격증명 통합 (2026-07-11, 무인증 라이브 환경 직접 검증)

사용자 허가로 실서버에서 `PUT /products/1/price-stock`(4마켓 상품, 현재값 유지)로 반복 검증하며 실패를 순차 해소:

1. **쿠팡 4단계 관통**: (a) rawData 의존 접근 오류 → vendor-items 전용 엔드포인트, (b) "Empty key"(env 자격증명 공백) → **CoupangRestClient가 DB `sb_market_credential`(COUPANG) 우선 사용, env 폴백**으로 통합(publish·이미지·가격재고 전체가 DB 키로 동작), (c) "411 Length Required"(JDK HttpClient가 무바디 PUT에 Content-Length 미전송) → 빈 JSON `{}` 바디로 강제, (d) "HMAC format is invalid"(제품 RestClient가 KST·T/Z없는 `generateSignature` 사용) → 주문 클라이언트와 동일한 `generateSignatureUtc`(UTC `yyMMdd'T'HHmmss'Z'`)로 교체 + 중복 signed-date 헤더 제거.
2. **최종 결과**: `synced=[SMART_STORE, COUPANG, ELEVEN_STREET]` — **3마켓 실 API write 성공(라이브 확정)**. 현재값 유지로 실판매가 무변경.
3. **카페24만 잔여**: `401 Invalid access_token`. `Cafe24TokenManager`는 DB 리프레시 토큰으로 갱신하는데, 리프레시 토큰이 Cafe24에서 거부(만료/회전소진)돼 access token을 못 얻음 → "Bearer null"→invalid_token. **코드 아님 — 사용자 OAuth 재인증 필요**(Settings UI "정상 연동중"은 토큰 존재만 확인, 유효성 미검증). 재인증 경로: `Cafe24AuthController /api/admin/sync/cafe24/auth/callback` + `generateAuthorizationUrl`.
- **핵심 부산물**: 마켓 자격증명 2원화(주문=DB, 제품=env) 불일치를 쿠팡에 한해 DB 우선으로 통합. 스토어·11번가는 기존 env로 동작 중이라 미변경(향후 동일 통합 여지).
- 상태: **D-060 검증통과(3/4 마켓 라이브 확정)**. 카페24는 토큰 재인증 대기(운영·사용자).

### D-062: Cafe24 가짜 '정상 연동중' 표시 + 재인증 UX 부재 (2026-07-11, 사용자 요청)

- 심각도: P2 (오표시로 장애 은폐 — 실제 토큰 무효인데 정상으로 표기) · 리스크 등급: 표준
- 위치: `frontend/src/pages/Settings.tsx`(hasRefreshToken 기반 녹색), `Cafe24AuthController`(상태 점검·간편 발급 엔드포인트 부재)
- 근본원인: Settings가 `hasRefreshToken`(토큰 **존재** 여부만)으로 "✅ 정상 연동 중"을 표시 → 리프레시 토큰이 실제로 만료/거부돼도 녹색으로 뜸(가짜). 재인증은 콜백 URL을 브라우저 주소창에 수동 입력하는 번거로운 방식뿐.
- 수정: (백엔드) `GET /api/admin/sync/cafe24/status`가 실 Cafe24 API(`/admin/products?limit=1`)를 호출해 토큰 **실유효성**을 검증(401이면 재인증 필요). `POST /api/admin/sync/cafe24/issue-token`이 리다이렉트 code(또는 전체 URL, code 자동추출)로 리프레시 토큰 발급·저장. `Cafe24TokenManager.isRefreshTokenPresent()` 추가. (프론트) 카페24 탭이 실상태를 표시(가짜 녹색 제거)하고, 무효 시 재인증 카드(①인증 열기 →②주소/코드 붙여넣기 →발급) + 상태 새로고침 제공.
- 라이브 검증: 배포 후 `/status` = `{connected:false, "리프레시 토큰이 만료/무효입니다"}` — **기존 '정상 연동중'이 가짜였음이 실검증으로 확정**. 이제 사용자가 UI에서 재인증(OAuth 승인은 Cafe24 로그인 필요) 후 즉시 토큰 발급 가능.
- 상태: 검증통과 (게이트: infra+api test BUILD SUCCESSFUL, 프론트 tsc clean + build EXIT0, 라이브 상태 엔드포인트 실동작 확인)

### D-060 최종 완료 (2026-07-11): 4마켓 전부 라이브 동기화 확정
- Cafe24 재인증(D-062) 후 `PUT /products/1/price-stock` → `synced:[CAFE24,SMART_STORE,COUPANG,ELEVEN_STREET], failed:{}`. **가격/재고 → 4마켓 전 마켓 라이브 반영 확정.** D-060 종결.

---

## 사이클 17 (D-061 배송 마켓 전파 재진단 — 이전 감사 정정, 2026-07-11)

### D-061 정정: 배송정보 수정 → 마켓 전파는 이미 구현됨

- **이전 감사(사이클 15) 오류 정정**: "배송정보 수정(updateShippingInfo)은 DB만 저장·마켓 미전파"는 **틀렸음**. 리더 직접 확인 결과 `OrderService.updateShippingInfo:306,318`이 이미 `marketplaceShippingService.sendTrackingToMarketplace(item)`를 호출(PURCHASED→SHIPPED 및 SHIPPED 이후 수정 양쪽). 전파 경로: OrderController.updateShippingInfo → OrderService → MarketplaceShippingService(DB MarketCredential) → 마켓별 shipOrder/updateTracking.
- **실제 배송 전파 매트릭스**: 쿠팡(shipOrder+updateTracking 완전 구현), 스마트스토어·11번가(shipOrder 구현, updateTracking은 MarketOrderPort 기본값=shipOrder 재호출), ESM+(shipOrder가 log.warn 스텁), 카페24(OrderAdapter 자체 부재 → getPort 시 예외).
- **남은 실제 갭(대형·저가치)**:
  - ESM+(G마켓/옥션) 송장 등록: Selenium 스크래핑으로만 가능(정규 API 없음), 구현·유지보수 비용 중상. **게다가 ESM+는 주문 데이터 0건(D-052)** — 현재 가치 낮음.
  - 카페24 OrderAdapter: 주문 조회/배송 어댑터 전무 → **카페24 주문 동기화 자체가 없음**. 배송 전파 이전에 주문 수집부터 필요. 대형 신규 개발.
- 결론: D-061의 핵심(데이터 있는 3마켓 배송정보 수정→마켓 전파)은 **이미 구현·동작**. ESM+/카페24는 데이터 부재·대형 개발이라 즉시 착수 부적합. 라이브 검증은 실 송장이 필요해(실주문에 실송장 전송) 보류 — 코드 경로는 이메일 자동추적과 동일한 sendTrackingToMarketplace로 확인됨.
- 상태: D-061 핵심 이미구현 확인(정정). ESM+/카페24 배송은 발견(보고) 유지 — 데이터·우선순위상 후순위.

---

## 사이클 18 (사용자 요청 "1,2,3 모두 구현", 2026-07-11)

### 항목1(D-060 배치): 배치 가격/재고도 마켓 자동 반영 — 검증통과
- 구조(Tidy First): 마켓 순회 동기화 로직을 `ProductManageUseCase`→공용 `ProductMarketSyncService`로 추출, `MarketRepublishResult` 독립 레코드화(단건 회귀 없음). `ProductMarketSyncServiceTest`(순회·스킵·부분실패 3케이스).
- 행위: `BatchPriceStockService.crawlAndUpdatePriceStock`(일일 크롤)·`manualUpdatePriceStock`(수동 배치)가 DB 저장 후 마켓 반영, 진행상태 메시지에 성공/스킵/실패 표면화. 일일 가격 갱신이 마켓까지 자동 전파.
- 게이트: compileJava/TestJava 전체 SUCCESSFUL, :core:test(신규3 PASS, 기존 flaky1 제외), :api:test BUILD SUCCESSFUL.

### 항목2: 스토어·11번가 상품 클라이언트 DB 자격증명 통합 — 검증통과(라이브)
- 행위: `SmartstoreRestClient`(clientId/clientSecret)·`ElevenstMarketRestClient`(apiKey)가 쿠팡처럼 DB `sb_market_credential` 우선, env 폴백. 상품-마켓 작업 자격증명을 DB 단일소스로 일원화.
- 라이브 회귀: 배포 후 `PUT /products/1/price-stock` = `synced:[CAFE24,SMART_STORE,COUPANG,ELEVEN_STREET], failed:{}` — **DB 자격증명으로 4마켓 전부 유지(스토어·11번가 무회귀 확인)**.

### 항목3(D-061): 배송 마켓 전파 — 안전개선 + 대형갭 보고
- 행위(안전): `MarketplaceShippingService`가 배송 어댑터 없는 마켓(카페24 등)에서 `getPort` 예외로 배송정보 수정을 깨뜨리던 것을 `findPort`(Optional)로 감지해 스킵(자사 저장 유지, 크래시 방지).
- **대형 갭(미착수·데이터 부재로 검증 불가)**:
  - **ESM+(G마켓/옥션) 송장 등록**: 정규 API 없이 Selenium 스크래핑으로만 가능 → 라이브 UI 리버스엔지니어링 필요. ESM+ 주문 데이터 0건(D-052)이라 검증 불가·가치 낮음.
  - **카페24 배송/주문**: OrderAdapter 자체 부재 + **카페24 주문 동기화(fetchOrders)가 아예 없음** → 배송 전파 이전에 카페24 주문 통합(대형 신규)부터 필요. 카페24 주문 데이터도 시스템에 없음.
  - 판정: 두 건 모두 해당 마켓에 주문 데이터가 없어 무검증 대형 투기 개발 → 라이브 검증 원칙상 별도 스코프(사용자가 실제 해당 마켓 주문 운영 시 착수). 데이터 있는 3마켓(쿠팡·스토어·11번가)은 배송정보 수정→마켓 전파 이미 동작.
- 상태: 항목1·2 검증통과, 항목3 안전개선 완료·대형 어댑터는 보고(후순위).

### 사이클 18 요약
- 사용자 "1,2,3 모두 구현": 1·2 완전 구현·라이브 검증, 3은 안전개선 + 대형 신규(ESM+ Selenium·카페24 주문통합)는 데이터 부재로 별도 스코프 권고.

---

## 사이클 19 (G마켓/옥션 조회를 Cafe24 주문 API로 선회, 2026-07-11, 사용자 요청)

### D-061 후속(방향전환): ESM+ Selenium → Cafe24 주문 API

- 배경(사용자 통찰): G마켓/옥션이 Cafe24에 오픈마켓 연동돼 있어, 불안정한 Selenium 스크래핑 대신 **Cafe24 주문 API로 G마켓/옥션 주문을 안정 조회** 가능.
- 조사(2에이전트: Cafe24 API 문서 + 기존 sync 아키텍처): `GET /api/v2/admin/orders`(scope `mall.read_order`), **`order_place_id`로 마켓 구분**(gmarket/auction/coupang/…), `embed=items,receivers,buyer`, start/end_date(3개월/콜), 페이지 limit≤1000. 송장: `POST /orders/{id}/shipments`(scope `mall.write_order`). 택배사 코드는 `GET /carriers`로 조회.
- 구현: `Cafe24OrderApiPort`(core) + `Cafe24OrderApiClient`(infra, 기존 Cafe24RestClient 재사용) + `Cafe24OrderSyncService`(order_place_id→GMARKET/AUCTION 매핑, 타마켓 스킵, receivers/buyer/items 파싱, product_no로 CAFE24 마켓등록→sb상품 매핑, N/C/R/E 상태매핑). `/api/v1/orders/sync/esmplus` 엔드포인트·OrderSyncScheduler·"G마켓/옥션 동기화" 버튼을 Cafe24 방식으로 선회(ESM+ Selenium 철회). 진단용 `POST /orders/sync/cafe24/preview`(원시 응답). OAuth scope에 `mall.read_order,mall.write_order,mall.read_shipping` 추가.
- 게이트: 전체 compileJava/TestJava SUCCESSFUL, 신규 `Cafe24OrderSyncServiceTest`(gmarket 매핑·타마켓 스킵·필드 파싱) PASS, `:core:test`·`:api:test` BUILD SUCCESSFUL. 부수: 항목2 여파로 깨졌던 `ElevenstRestClientBeanConflictTest`(좁은 스캔에 MarketCredentialRepository 목 미제공)도 수정.
- **재인증 필요(사용자 액션)**: 기존 토큰은 product scope만 → 주문 조회 불가(preview 500 확인). Settings→카페24 재인증 카드(항상 노출로 변경)로 새 scope 재발급 후 라이브 검증 예정.
- 상태: 코드 완성·배포. 재인증 후 라이브 검증(preview로 실구조 확인→sync→그리드 표시) 대기.

### D-061 후속(라이브 검증 완료, 2026-07-11): Cafe24 주문 API로 G마켓/옥션 조회 성공

- 재인증 과정 실드리븐: 403 insufficient_scope(앱에 주문 scope 미등록 → 사용자가 Cafe24 개발자센터에서 전 권한 활성화) → 422 Invalid date format(start_date 시간 포함 → **날짜만 yyyy-MM-dd로 수정**) → 성공.
- **라이브 검증**: `/orders/sync/cafe24/preview` 실주문 파싱 정확(order_place_id=gmarket→GMARKET, buyer/receivers.address_full/items.product_no·product_name·quantity/order_status=N30→SHIPPED, order_date ISO+09:00→KST). status=connected(상품·주문 권한 확인). 동기화 트리거 후 통합주문관리에 **G마켓 주문 3건 실제 표시**(20260708-0000011 등).
- 부수 개선: OAuth scope 전체(app/product/collection/order/shipping RW), status가 주문 권한까지 실검증(오표기 방지), 프리뷰 rootCause 노출.
- 상태: **검증통과** — G마켓/옥션 주문 조회가 Cafe24 API로 안정 동작(Selenium 철회 완료). 송장 역전송(write_order)은 후속 트랙.

### D-061 후속(완료): G마켓/옥션 송장 역전송 Cafe24 shipments API 구현

- 구현: 배송정보 수정 → MarketplaceShippingService.sendTrackingToMarketplace → 포트(GMARKET=EsmplusOrderAdapter shipOrder 스텁 교체, AUCTION=신규 Cafe24AuctionOrderAdapter) → `Cafe24ShipmentService` → `POST /admin/orders/{order_id}/shipments`(tracking_no·shipping_company_code·status=shipping·order_item_code). marketOrderNo=Cafe24 order_id. 택배사 코드는 `GET /admin/carriers`로 몰별 조회→ShippingCarrier 라벨 매칭(미매칭 실패 표면화), order_item_code는 주문상세(embed=items)에서 획득. `MarketplaceShippingService`의 cred==null 조기종료 완화(Cafe24 배송은 마켓 자격증명 불필요→옥션 커버). 진단 `POST /orders/sync/cafe24/carriers`.
- 라이브 검증(읽기): 몰 택배사 조회 성공 — 자체배송(0001)·우체국택배(0012)·CJ대한통운(0006). 코드 매칭 정확(CJ→0006, 우체국→0012). 응답 필드 shipping_carrier_code를 방어적 다중 필드조회로 획득.
- 게이트: `Cafe24ShipmentServiceTest`(코드매칭·order_item_code·바디구성·미매칭예외) PASS, 전체 compileTestJava·:core:test·:api:test BUILD SUCCESSFUL(EsmplusParseSingleOrderTest 생성자 갱신).
- 미검증(쓰기): 실제 shipments POST는 실주문을 '배송중'으로 바꾸는 부작용이라 가짜 데이터 라이브 테스트 회피 — 첫 실 송장(실 트래킹번호로 실 G마켓 주문 배송처리)이 검증. 코드·택배사코드는 준비 완료.
- 상태: 구현 완료(읽기 검증), 실 송장 등록은 실사용 시 검증.

---

## 사이클 20 (사용자 신고 5클러스터 병렬 진단, 2026-07-11)

> 출처: `_workspace/scout_report_inventory.md`(재고), `_workspace/scout_report_customs.md`(통관), `_workspace/scout_report_status_sync.md`(상태·배송). 3 scout 병렬. 리더 코드 직접확인으로 Jackson 직렬화 가설 기각·11번가 실경로(parseShippingElement) 확정·scout의 스마트스토어 상태코드 환각(비표준) 정정.

### D-063: 재고현황 UI 표시규칙 오류 (INV-1)
- 심각도 P2 / 리스크 경량 / 상태 수정완료(검증대기)
- 증상: 재고현황 셀이 IN_STOCK/품절 무관 재입고일 행 항상 표시, 재입고일 없으면 `( - )`. 사용자 요구: 구입가능이면 부가표시 없음, 품절+재입고일 있을 때만 (날짜).
- 근본원인: `frontend/src/pages/OrderGrid.tsx:1015~1023` — 조건 미완성(IN_STOCK도 재입고행 렌더, restockDate 없으면 `( - )`).
- 수정방향: IN_STOCK→뱃지만, OUT_OF_STOCK+restockDate→품절+(날짜), OUT_OF_STOCK+무재입고일→품절만, null→`-`.
- 영향: `frontend/src/pages/OrderGrid.tsx`

### D-064: 재고 갱신시각 미표시 (INV-2)
- 심각도 P2 / 리스크 경량 / 상태 수정완료(검증대기)
- 증상: 재고현황이 언제 갱신된 데이터인지 화면에 표기 없음.
- 근본원인: `Product`(BaseEntity)에 `updatedAt` 존재하나 프론트 `ProductDto`(`frontend/src/api/orderApi.ts:46~62`)에 필드 부재 + 셀 렌더에 표시코드 부재. `timeAgo()`(`OrderGrid.tsx:430`) 재사용 가능.
- 수정방향: 백엔드 응답에 재고확인시각 노출(updatedAt 또는 전용 stockCheckedAt) → ProductDto 추가 → 셀에 상대시각 표시.
- 영향: `frontend/src/api/orderApi.ts`, `frontend/src/pages/OrderGrid.tsx`, (백엔드 DTO 직렬화 확인)

### D-065: 특정 상품 restockDate (-) 버그 (INV-3)
- 심각도 P1 / 리스크 표준 / 상태 **수정완료(검증대기)** — 코어 null-guard(b) 완료. 파싱부(a)는 라이브확인 잔여.
- 수정(b): `ProductSyncService.java:45~51`에 null-guard+IN_STOCK 의미론 적용(IN_STOCK이면 null clear 허용, 그 외 restockDate=null이면 기존값 유지). Red 테스트 `ProductSyncServiceRestockDateTest`(신규, 3건). 요약 `_workspace/fixes/D-065.md`.
- 잔여(a) 라이브확인: DB restock_date 실값 + iHerb 응답 재입고일 필드/포맷(ISO 여부) — 비ISO면 파싱부 후속 수정 필요.
- 증상: "California Gold Nutrition Magnesium Bisglycinate TRAACS 240 Capsules" 재입고일 있으나 화면 (-).
- 근본원인(리더 정정): Jackson은 Spring Boot 기본(JavaTimeModule 등록, write-dates-as-timestamps=false)→LocalDate는 ISO 문자열, **직렬화 가설 기각**. 남은 후보 2: (a) 크롤 파싱 실패(`IherbScraperClient.java:134~156` expectedAvailability/backOrderDate 미획득) (b) null 소거(`ProductSyncService.java:47` restockDate null이면 기존값 덮어씀).
- 수정방향: (b) null-guard(안전, 즉시) + (a) 라이브 확인(DB restock_date 값, iHerb 응답 필드) 필요.
- 라이브확인: `SELECT restock_date,stock_status,updated_at FROM sb_product WHERE original_name LIKE '%Magnesium Bisglycinate%'`
- 영향: `ProductSyncService.java`, `IherbScraperClient.java`

### D-066: 11번가 통관번호(개인통관고유부호) 미조회 — 배송중 경로 (CUS-2, 조숙현)
- 심각도 P2 / 리스크 표준 / 상태 수정완료(검증대기)
- 수정(fixer): `parseShippingElement`·`parseOrderDetailElement`에 psnCscUniqNo 파싱+customsClearanceNo 매핑 추가. **정정: getElementText는 태그 부재 시 null이 아니라 "" 반환** → `emptyToNull()`로 정규화(SyncService null-guard 실효화, 기존 통관번호 빈값 덮어쓰기 회귀 차단). 재현/회귀 테스트 `ElevenstShippingCustomsClearanceTest`. 게이트 `:core:test` 96 tests green. 요약 `_workspace/fixes/D-066.md`. 라이브확인: 배송중 XML에 psnCscUniqNo 태그 실제 포함 여부.
- 증상: 11번가 주문(조숙현) customsClearanceNo null.
- 근본원인(리더 확정): SyncService는 `fetchOrders`만 호출→배송중 주문은 `parseShippingElement`(`ElevenstOrderAdapter.java:387`) 경유하는데 여기 `psnCscUniqNo` 파싱 없음. `parseOrderElement`(line 320/371)엔 이미 존재. 잠복경로 `parseOrderDetailElement`(line 230, 호출 0건)도 누락.
- 수정방향: `parseShippingElement`에 `psnCscUniqNo` 파싱+`.customsClearanceNo(...)` 추가(태그 없으면 null, 안전). parseOrderDetailElement도 동일 보강.
- 영향: `ElevenstOrderAdapter.java`

### D-067: G마켓/옥션 통관번호 미조회 — Cafe24 파싱 부재 (CUS-1)
- 심각도 P2 / 리스크 중대(마켓 API 계약) / 상태 검증통과(코드) — 라이브 PCCC 필드명 확정 필요
- 검증(2026-07-11 배치B): 경계면 정합(CustomsData 빌더·Order.updateCustomsClearanceNo 실존, Order 널가드로 NPE 안전), 4테스트 Green, `:core:test` 107 전량 통과. 코드판정 PASS. **라이브검증 필요**: 후보키 7종은 방어적 추측 — 실제 Cafe24 응답 PCCC 필드명은 preview 로그로 확정. 판정서 `_workspace/verify/batchB_verdict.md`.
- 증상: G마켓/옥션 주문(Cafe24 경유) customsClearanceNo 항상 null.
- 근본원인(확정): `Cafe24OrderSyncService.createOrder/updateOrder`(line 119~170)가 buyer/receivers에서 통관번호 미추출·Order.customsData 미설정. `embed=items,receivers,buyer`로 조회는 함.
- 수정방향: Cafe24 응답의 실제 PCCC 필드명 확정 후 추출·CustomsData 매핑. **라이브확인 필요**(buyer vs receivers, 필드키). 방어적 구현+미발견시 노드키 로깅 권고.
- 영향: `Cafe24OrderSyncService.java`, `CustomsData.java`(구조 적합)

### D-068: 스마트스토어 상태 UNKNOWN 오맵핑 (STAT-1, 이명동)
- 심각도 P2 / 리스크 중대(마켓 계약·배송차단 연계) / 상태 검증통과 — 이명동 실코드 라이브 확인 잔여
- 검증(2026-07-11 배치B): 표준 누락코드만 추가·비표준 미추가 확인, 기존 매핑 7종·default(UNKNOWN+warn) 회귀 보존, 4테스트 Green, `:core:test` 107 전량 통과. 코드판정 PASS. 잔여: 이명동 실 status는 서버로그로 확정(UNKNOWN+warn 노출 유지). 판정서 `_workspace/verify/batchB_verdict.md`.
- 수정(사이클20 배치B): 표준 누락코드 `PAYMENT_WAITING`→NEW, `CANCELED_BY_NOPAYMENT`→CANCELED 추가. 기존 케이스·default(UNKNOWN+log.warn) 유지. 비표준 추측코드 미추가. Red→Green(`SmartStoreStatusMapperTest` 신규, `:core:test` BUILD SUCCESSFUL). 상세 `_workspace/fixes/D-068.md`. 잔여: 이명동 실코드는 서버로그로 라이브 확정 필요.
- 증상: 이명동 주문 "알수없음". 2차피해: `OrderService.java:294~296`이 UNKNOWN이면 배송정보 수정 400 차단.
- 근본원인: `SmartStoreStatusMapper.java:30~50` 스위치에 표준 네이버 productOrderStatus 일부 누락→default UNKNOWN. **리더 정정: scout 제시 코드(PLACE_ORDER_COMPLETED/IN_PROGRESS 등)는 비표준(환각).** 표준 누락 후보: `PAYMENT_WAITING`(→NEW), `CANCELED_BY_NOPAYMENT`(→CANCELED).
- 수정방향: 표준 누락코드 추가 + 기존 log.warn이 이명동 실코드 노출하므로 라이브 로그로 확정. UNKNOWN 해소 시 배송차단도 자동 해소.
- 라이브확인: 서버로그 `"알 수 없는 스마트스토어 주문 상태: {코드}"`
- 영향: `SmartStoreStatusMapper.java`

### D-069: 배송정보 수정 마켓 미동기화 (SYNC-1)
- 심각도 P1 / 리스크 중대 / 상태 검증통과 — (c) 실패 표면화 스코프만. (b) postSyncProcess는 별도 후속.
- 검증(2026-07-11 배치B): 반환형 전환·호출부 6곳(OrderService 4 + worker EmailFetcher 2) 전수 정합(grep 재검증), 성공 시에만 마킹·실패 미마킹 확인, 롤백 제거 의미론 확인, 4테스트 Green. 게이트 `:core:test` 107 통과 + `:worker:clean compileJava` + `:api:test` 8 통과, 회귀 0. 코드판정 PASS. 판정서 `_workspace/verify/batchB_verdict.md`.
- 수정(2026-07-11): `sendTrackingToMarketplace` void→`MarketShippingResult` 반환, port 예외를 catch해 실패결과로 표면화(예외전파·롤백 제거). 배송정보 DB 저장은 마켓 전송과 독립 커밋. 성공 시에만 `markTrackingAsSent`(실패 시 미마킹→재시도 보존). 호출부 6곳(OrderService 4 + worker EmailFetcherService 2) 정합. Red→Green 테스트 `MarketplaceShippingServiceTest`(4). 게이트 `:core:test` 전체 통과 + `:worker:compileJava`. 요약 `_workspace/fixes/D-069.md`.
- 판정: D-061 정정(전파 배선 존재)은 **코드상 맞음**(`OrderService:306,318`→sendTrackingToMarketplace). 사용자 재신고 주원인 = **D-068(UNKNOWN)로 인한 입구 400 차단**(이명동). 부차: (b) `SmartStoreOrderSyncService.postSyncProcess`(line 203) 빈 메서드 → 취소/반품 미갱신, (c) `MarketplaceShippingService`(line 86~95) 마켓 API 실패가 예외전파→트랜잭션 롤백(부분실패 미표면화).
- 수정방향: D-068 우선(이명동 해소). (c) 마켓 실패를 예외전파 대신 부분실패 결과로 표면화(D-060 패턴). (b) postSyncProcess 구현.
- 영향: `MarketplaceShippingService.java`, `SmartStoreOrderSyncService.java`, `OrderService.java`(차단로직은 정상)

### 사이클 20 배치 계획
- **배치 A(코드확정·즉시)**: D-063·D-064(프론트 재고 UI, 동일파일 순차), D-065 null-guard(백엔드), D-066(11번가 통관, 백엔드) — 파일 비겹침 병렬.
- **배치 B(라이브데이터 의존)**: D-067(Cafe24 통관, 필드명 확인), D-068(스마트스토어 상태, 실코드 확인), D-069(배송 전파 표면화). 방어적 구현+라이브검증 관문.

### 사이클 20 검증·커밋 결과 (2026-07-11)
- **배치 A (검증통과·커밋)**: D-063/D-064(재고 UI 규칙+갱신시각, 커밋 cc6e574), D-065(restockDate null-guard, 6559b5b), D-066(11번가 통관 배송중경로, 3b0de64). qa PASS(_workspace/verify/batchA_verdict.md), :core:test 96/0·tsc 0·build ✓.
- **배치 B (검증통과·사용자 승인·커밋)**: D-068(스마트스토어 표준 누락코드, f8d4577), D-067(Cafe24 통관 방어적 구현, adf85d7), D-069(배송 전파 실패 표면화, 968d64b). qa PASS(_workspace/verify/batchB_verdict.md), :core:test 107/0·:api:test 8/0·:worker compile OK. 중대 등급 사용자 승인 획득.
- 결함 상태: D-063·D-064·D-065·D-066·D-067·D-068·D-069 = **검증통과**.

### 사이클 20 라이브 확인 필요(배포 후, 사용자 액션)
1. **D-067 Cafe24 PCCC 실 필드명**: G마켓/옥션 실주문으로 `POST /orders/sync/cafe24/preview` 후 서버 debug 로그 `[CAFE24-ORDER] PCCC 미검출 ... buyerKeys=[..] receiverKeys=[..]`로 실제 통관번호 필드명 확정 → `Cafe24OrderSyncService.PCCC_KEYS`에 반영(후속). 채워지면 확정 완료.
2. **D-068 이명동 실 상태코드**: 스마트스토어 동기화 후 서버 로그 `"알 수 없는 스마트스토어 주문 상태: {코드}"` 확인 → 남은 미지 표준코드 있으면 매퍼 추가(후속).
3. **D-065 iHerb 재입고일**: `SELECT restock_date,stock_status,updated_at FROM sb_product WHERE original_name LIKE '%Magnesium Bisglycinate%'` + iHerb 응답 재입고일 필드/포맷(ISO 여부). 비ISO면 IherbScraperClient 파싱부 후속 결함.
4. **D-066 11번가 배송중 psnCscUniqNo 태그 실재 여부**: 배송중 XML 응답에 태그 포함 확인.

### 사이클 20 후속 결함 후보 (미착수)
- **D-070(후보)**: `ElevenstOrderAdapter.parseOrderElement`(line 325)도 `emptyToNull` 미적용 — complete/packaging/dlvcompleted 경로에서 psnCscUniqNo 태그 부재 시 ""가 null-guard 통과해 기존 통관번호 덮어쓸 잠복 위험(qa-verifier 배치A 발견). D-066과 동일 정규화 적용 권고. 리스크 표준.
- **D-071(후보)**: `SmartStoreOrderSyncService.postSyncProcess`(line 203) 빈 메서드 — 스마트스토어 취소/반품 동기화 전무(쿠팡 detectCancellations 패턴 미적용). 리스크 표준.
- **D-072(후보)**: D-069 배송 전송 실패를 컨트롤러/응답 DTO까지 전달해 UI 토스트로 표면화(현재 서비스 로그+상태까지만). 리스크 경량.

---

## 사이클 21 (동기화가 수기 편집을 덮어쓰는 구조적 결함, 2026-07-11, 사용자 신고)

> 출처: `_workspace/scout_report_overwrite.md`. 사용자 통찰: 통관 검증상태(대기중→정상/불일치)를 수기 처리해도 동기화가 다시 덮어씀 — 통관번호에 국한되지 않고 "우리 DB에서만 관리·외부 마켓 미전송" 필드(통관검증상태·주소·우편번호) 전반의 수기보정 보호 문제. 리더 코드 확정: updateCustomsClearanceNo 무조건 PENDING 리셋.

### D-073: 통관 검증상태 무조건 리셋 (OVW-1/OVW-3/OVW-4)
- 심각도 P1 / 리스크 표준 / 상태 수정완료(검증대기)
- 수정(사이클 21): `Order.updateCustomsClearanceNo`를 번호 실제 변경 시에만 PENDING/NONE 리셋하도록 변경, 불변이면 기존 상태/검증인 유지. Red 테스트 `OrderCustomsClearanceNoTest`(신규) 3케이스. `:core:test` 전체 통과. 요약 `_workspace/fixes/D-073.md`.
- 증상: 사용자가 통관 검증 완료(VALID/INVALID_*) 후 다음 마켓 동기화에서 같은 통관번호가 재하달되면 customsStatus=PENDING·verifiedPerson=NONE으로 리셋 → 수기 검증 소실. VALID→PENDING 되돌림이 통관 스케줄러 재검증 루프도 유발(OVW-4).
- 근본원인(확정): `Order.java:122~128` `updateCustomsClearanceNo`가 번호 변경 여부 무관 항상 PENDING/NONE 세팅. 호출부 5마켓 SyncService(SmartStore:135·Coupang:237·Elevenst:135·Esmplus:153·Cafe24:167) 모두 `if(no!=null)`만 가드 → 번호 불변인데 재호출 시 리셋. 사용자 수기 통관번호 수정 경로(OrderService:214)도 동일(OVW-3).
- 수정방향(최소안): `updateCustomsClearanceNo`를 "번호가 실제 변경된 경우에만 PENDING/NONE 리셋, 불변이면 기존 상태/검증인 유지"로 변경. Order.java 1메서드만. 5 SyncService 불변. 정책: 번호 변경 시 재검증 필요(사용자 사고모델 일치)로 채택.
- 영향: `Order.java`
- 리스크: 낮음(번호 변경 시 기존 무효화 유지).

### D-074: 주소/우편번호 수기보정 동기화 덮어쓰기 (OVW-2)
- 심각도 P2 / 리스크 표준 / 상태 수정완료(검증대기) — 수정요지 `_workspace/fixes/D-074.md`
- 증상: 사용자가 주소를 수기 보정해도 스마트스토어/11번가/ESM+ 동기화가 마켓 값으로 덮어씀. 쿠팡·Cafe24는 progressed(진행) 시 protectAddress 가드 있으나 이 3마켓엔 없음. 또 zipcode는 쿠팡·Cafe24 가드에서도 미보호(주소만 null 치환, 우편번호는 마켓 값 덮음).
- 근본원인(확정): `CoupangOrderSyncService:224`·`Cafe24OrderSyncService:150` protectAddress 존재. `SmartStore/Elevenst/EsmplusOrderSyncService.updateOrderInfoFromDto`는 lineItems 미수신 → 가드 부재, `order.update(...,dto.getAddress(),...)` 무조건. `Order.update`는 non-null이면 덮어씀. zipcode도 세트 보호 필요.
- 수정방향(표준안): 3마켓 updateOrderInfoFromDto에 lineItems 전달 + 쿠팡/Cafe24 패턴(protectAddress=progressed lineitem 존재)의 주소 보호 이식. 추가로 protectAddress 시 zipcode도 함께 null 치환(전 마켓)해 주소·우편번호 세트 보호.
- 영향: `SmartStoreOrderSyncService.java`, `ElevenstOrderSyncService.java`, `EsmplusOrderSyncService.java`, `CoupangOrderSyncService.java`, `Cafe24OrderSyncService.java`(zipcode 세트 보호)
- 리스크: 표준(5 SyncService 다파일).

### 사이클 21 배치
- D-073(Order.java, P1) + D-074(5 SyncService) 파일 비겹침 → 병렬. 둘 다 행위 변경. 커밋 분리.

### 사이클 21 검증·커밋 결과 (2026-07-11)
- **검증통과·커밋**: D-073(통관 검증상태 번호변경시에만 리셋, 커밋 33a1b4e), D-074(주소/우편번호 protectAddress 일관화+세트보호, 3fda643). qa PASS(_workspace/verify/cycle21_verdict.md) + 리더 게이트 :core:test 113/0·:api:test 8/0. 리스크 표준(자율 통과).
- 결함 상태: D-073·D-074 = 검증통과. OVW-3/OVW-4는 D-073로 자동 해소.
- 라이브 확인 권고: (a) 스마트스토어/11번가/ESM+가 통관번호를 실제 하달하는지(하달 안 하면 OVW-1 해당마켓 미발현). (b) "번호 변경 시 재검증(PENDING)" 정책이 업무규칙에 맞는지 사용자 확인.
- 미착수 후속(원장 유지): D-070(parseOrderElement emptyToNull)·D-071(스마트스토어 postSyncProcess 취소/반품)·D-072(배송실패 UI 표면화).

---

## 사이클 22 (Cafe24 동기화 실패 + 전체 액션 활동로그, 2026-07-11, 사용자 신고)

> 출처: `_workspace/scout_report_cafe24fail.md`, `_workspace/scout_report_actionlog.md`. 사용자 신고 (1) "동기화 실패: Cafe24 API 호출 실패" 버그, (2) 모든 메뉴/기능 액션에 활동로그(진행현황 성공/진행/실패) 기록.

### D-075: Cafe24 동기화 실패 원인 은폐 (CF24-1/2/3)
- 심각도 P1 / 리스크 표준 / 상태 수정완료(검증대기) — 수정요지 `_workspace/fixes/D-075.md`
- 증상: 진행현황에 "동기화 실패: Cafe24 API 호출 실패"만 표시, 원인 불명. G마켓/옥션 주문 동기화 중단.
- 근본원인(확정): `Cafe24TokenManager.getValidAccessToken()`(60~68)이 refresh 실패 시에도 예외 없이 **null 반환** → `Cafe24RestClient.get()`이 "Bearer null"로 호출 → 401 → `catch(Exception)` → `RuntimeException("Cafe24 API 호출 실패", e)`(RestClient:34) → `Cafe24OrderSyncService`(62~67) `e.getMessage()`만 SyncCompletedEvent errorMessage로 전달 → root cause(401/403/422) 은폐.
- 구분: (라이브) refresh_token 만료/scope 부족이면 사용자 재인증 필요(`GET /api/admin/sync/cafe24/status`로 판별). (코드 수정) CF24-2 getValidAccessToken null 시 즉시 IllegalStateException("재인증 필요") throw; CF24-3 실패 시 root cause chain을 errorMessage에 포함.
- 수정방향: 코드 수정으로 실패 사유를 명확히 표면화(활동로그·상태에 재인증 필요/scope/HTTP코드 노출). 실제 연결 복구는 사용자 재인증.
- 영향: `Cafe24TokenManager.java`, `Cafe24RestClient.java`, `Cafe24OrderSyncService.java`
- 라이브 확인: `GET /api/admin/sync/cafe24/status`(connected 여부·사유), 서버로그 "Cafe24 GET Error".

### D-076: 전체 사용자 액션 활동로그 커버리지 (기능 구현)
- 심각도 P2(기능 요청) / 리스크 표준 / 상태 발견
- 배경: 활동로그 인프라(ActionLog/ActionLogService.record STARTED·SUCCESS·FAILED/ActionLogSyncListener/ActionLogController/ProcessStatusPage) 존재하나 커버리지가 4개 마켓 동기화(S1~S4)뿐. 미커버 24개 엔드포인트(통관검증·재고새로고침·구매정보수정·배송정보수정·주문확인/취소/발송/삭제·상품 수정/이미지/삭제/등록/게시·소싱·배치·자격증명저장·Cafe24재인증 등).
- 설계 결정(채택): **수동 record() 방식**(surveyor 권고 후보 B) + `ActionLogConstants` 상수 클래스 신설. AOP(후보 A)는 spring-boot-starter-aop 의존성·동적 marketType·기존 SyncCompletedEvent 이중기록 복잡성으로 후순위. 동기 단건수정은 결과만(SUCCESS/FAILED), 장시간/비동기(동기화·배치·크롤)는 STARTED+결과. S1~S4는 현행 유지(STARTED+리스너 완료, 이중기록 금지).
- 인벤토리: `_workspace/scout_report_actionlog.md` §2 전수표 참조.
- 영향: `ActionLogConstants.java`(신규), api 컨트롤러 8종(OrderSyncController[S5·S6], OrderController[O1~O10], ProductController, ProductSyncController, ProductSourcingController, BatchController, MarketCredentialController, Cafe24AuthController), `frontend/src/pages/ProcessStatusPage.tsx`(actionType→한글 라벨).
- 리스크: 표준. 스키마 변경 없음(sb_action_log 기존). marketType nullable 허용.

### 사이클 22 배치
- D-075(Cafe24 client/token/sync)와 D-076(api 컨트롤러+프론트) 파일 비겹침 → 병렬. D-076은 ActionLogConstants 공유 의존으로 단일 fixer 일괄.

### 사이클 22 검증·커밋 결과 (2026-07-11)
- **검증통과·커밋**: D-075(Cafe24 실패 fail-fast+root cause, 커밋 824f24f), D-076(전체 액션 활동로그 24엔드포인트+ActionLogConstants+프론트 라벨, 368d408). 리더 직접 게이트(세션 재시작으로 팀원 소멸→리더 검증): 코드 테스트 :core/:api/:infrastructure 관련 통과, 프론트 tsc 0·build ✓. record 패턴 FAILED후 예외 재throw로 에러응답 보존 스팟체크.
- **환경 제약**: 로컬 Docker 미실행으로 testcontainers 컨텍스트 스모크 3건(ApiContextLoadSmokeTest/ApiContextLoadWithBlankR2CredentialsTest/ProductDetailHtmlReadTest) initializationError — cycle-22 코드 무관(미변경), CI/서버(Docker)에서 검증됨.
- 결함 상태: D-075·D-076 = 검증통과.
- 라이브 확인: D-075 — `GET /api/admin/sync/cafe24/status`로 connected/재인증 필요 판별, 재인증 후 preview 정상 확인. D-076 — 각 액션 클릭 후 진행현황에 STARTED/SUCCESS/FAILED 표시 확인.
- 후속(원장 유지): 배치(B1~B4) 비동기 완료 SUCCESS/FAILED 기록(2차), 활동로그 AOP 리팩토링(선택). 기존 D-070~072 + 사이클20 라이브 잔여.

---

## 사이클 23 (상품관리 액션 상세로그 + 소스이미지 크롤 무반응, 2026-07-11, 사용자 신고)

> 출처: `_workspace/scout_report_product_actions.md`. 신고 (1) 가격/재고 수정 활동로그가 "성공"만 뜸→마켓별 상세 원함, (2) 소스이미지 크롤 버튼 무반응+진행현황 미표시(이미지 교체 중요).

### D-077: 가격/재고·이미지 활동로그 마켓별 상세 미표시 (PA-1)
- 심각도 P3 / 리스크 경량 / 상태 수정완료(검증대기) — `_workspace/fixes/D-077_078.md`
- 증상: `ProductController.updatePriceStock`(:106)이 `MarketRepublishResult`(synced/skipped/failed)를 받고도 "가격/재고 수정 성공 (상품 N)"만 기록. 사용자는 "DB 성공, 쿠팡 7283748383 성공, 스마트스토어 2939395 실패(사유), 카페24 3938 성공" 형태 원함.
- 근본원인: 컨트롤러가 result 내용을 메시지에 미반영. MarketRepublishResult엔 marketItemId 없음(MarketRegistration.extractMarketCode로 조회 가능, ProductController가 이미 MarketRegistrationRepository 주입).
- 수정방향(방법 A, 최소): SUCCESS 블록에서 result.synced/skipped/failed + marketRegistrationRepository.findByProductId(id)로 마켓→상품번호 맵 조립해 "DB 저장 완료 | 쿠팡 {번호} 성공, 스마트스토어 {번호} 실패({사유50자})" 포맷. 실패사유 50자 절단, message 1000자 truncate 기존 유지. 동일 패턴 uploadImages·uploadImagesByUrl에도 적용.
- 영향: `ProductController.java`(주). MarketRepublishResult/프론트 토스트 상품번호는 방법 B(파급 큼)라 후순위.

### D-078: 소스이미지 크롤 버튼 무반응 + 활동로그 미배선 (PA-2)
- 심각도 P2 / 리스크 표준 / 상태 수정완료(검증대기) — `_workspace/fixes/D-077_078.md`
- 증상: 상세모달 "소스 이미지 크롤" 버튼 눌러도 무반응, 진행현황 미기록. 이미지 교체 작업 핵심 경로.
- 근본원인: (a) 프론트 `ProductPage.tsx:535` `disabled={d.vendor!=='IHB'}` — 비-iHerb 버튼 비활성인데 antd disabled 버튼은 Tooltip도 안 떠 "먹통"으로 체감. iHerb라도 크롤 결과 빈응답이면 피드백 없음. (b) 백엔드 `crawlSourceImages`(ProductController:156~)에 activityLog record 미배선(D-076 커버리지 누락), ActionLogConstants에 상수 없음.
- 수정방향: (프론트) 버튼 항상 클릭 가능+handleCrawl에서 비-iHerb warning·크롤 결과 피드백(N개 수집/없음/실패 토스트)·loading 유지. (백엔드) ActionLogConstants.SOURCE_IMAGE_CRAWL 추가 + crawlSourceImages에 record(성공 이미지수/빈결과/실패). 
- 영향: `frontend/src/pages/ProductPage.tsx`, `ProductController.java`, `ActionLogConstants.java`
- 참조: D-049(과거 소스이미지 크롤 비-iHerb 무음실패 수정) 후속.

### 사이클 23 배치
- D-077·D-078 모두 ProductController.java 수정 → 파일 충돌 방지 위해 단일 fixer 일괄. 행위+기능 변경.

### 사이클 23 검증·커밋 결과 (2026-07-11)
- **검증통과·커밋**: D-077(가격재고·이미지 활동로그 마켓별 상세), D-078(소스이미지 크롤 무반응+활동로그 배선). 단일 커밋 d8c7b74(ProductController 공유).
- 리더 직접 게이트: :core:test·:api:test 코드테스트 통과(신규 ProductControllerActionLogDetailTest 5건), 프론트 tsc 0·build ✓. Docker 컨텍스트 스모크 3건은 로컬 환경제약(cycle 무관).
- 리더 보완: ProcessStatusPage에 SOURCE_IMAGE_CRAWL 한글 라벨 추가(fixer 대상 외 갭).
- 결함 상태: D-077·D-078 = 검증통과.
- 라이브 확인: 가격/재고 수정 후 진행현황 마켓별 상세(상품번호 포함) 실측, iHerb 크롤→SOURCE_IMAGE_CRAWL 기록·비-iHerb 클릭→warning 표시.
- 후속: 프론트 토스트/가격재고 상품번호는 방법B(MarketRepublishResult 확장)로 후순위. 배치 비동기 완료기록·AOP·D-070~072·사이클20 라이브 잔여 유지.

### D-079: Cafe24 동기화가 내부 이행상태(PURCHASED 구매완료)를 매 사이클 되돌림 (SP-E 후속 I-1)
- 심각도 P3 / 리스크 표준 / 상태 미착수(후속) — 출처: fix-live-defects-b-c-e 최종 리뷰
- 증상: 운영자가 G마켓/옥션 라인아이템을 수기로 PURCHASED(구매완료, order=2)로 진행시켜도, 30분 주기 Cafe24 동기화가 실제코드(N10/N20)를 매핑해 PREPARING(order=1)으로 덮어 되돌림.
- 근본원인: `Cafe24OrderSyncService.updateOrder`(:191~208)가 shippingStatus를 무조건 덮어씀(단조 가드 없음). main에도 존재한 선행결함이나, d3d40dc가 N10 클로버 대상을 NEW→PREPARING으로 바꿔 체감 노출. `ShippingStatus.PURCHASED`는 Cafe24 코드 매핑이 없음(내부 전용).
- 수정방향: N계열 매핑 적용 시 `if (newStatus.getOrder() > current.getOrder())`로 상향만 허용(내부 진행 downgrade 금지), C*/R*/E*(취소·반품·교환) 전이는 예외적으로 항상 허용.
- 영향: `Cafe24OrderSyncService.java`.

### D-080: marketSpecificData JSON 파서가 순진한 문자열 split (SP-E 후속 I-2)
- 심각도 P4 / 리스크 경량 / 상태 미착수(후속) — 출처: fix-live-defects-b-c-e 최종 리뷰
- 증상: 잠재. 현재는 안전(값에 `,`/`:` 없음). 단 accept/cancel/송장의 cafe24_order_id 조회가 이 파서에 의존하게 되어, 향후 `order_place_name` 등 값에 `,`/`:`가 섞이면 파싱 붕괴→cafe24_order_id 오독→마켓 원본번호로 폴백→Cafe24에 잘못된 id 전송 위험.
- 근본원인: `Order.getMarketSpecificDataMap()`(:160~183)이 `,`·`:` split + 따옴표 strip 방식.
- 수정방향: Jackson(ObjectMapper)로 실제 JSON 파싱 교체. setMarketSpecificDataFromMap도 대칭적으로 직렬화.
- 영향: `Order.java`.

### D-081: 스마트스토어 토큰 발급 timestamp 초/ms 오류 — 가격/재고 배치 스마트스토어 100% 실패 (라이브)
- 심각도 P1 / 리스크 표준 / 상태 수정완료·배포·라이브검증 (main `a92b1b7`)
- 증상: 소싱업체별/가격·재고 배치에서 스마트스토어만 100% 실패. Naver `/oauth2/token` 400 `"timestamp 항목의 유효 시간이 만료되었습니다"` → `[Smartstore] 토큰 발급 실패` → 해당 상품 SMART_STORE 갱신 실패. (Cafe24·11번가·쿠팡은 정상.)
- 근본원인: `SmartstoreRestClient.fetchAccessToken()`(:58)이 서명·전송 timestamp를 `Instant.now().getEpochSecond()`(epoch 초, 10자리)로 생성. Naver Commerce는 **밀리초** 요구 → 초값을 ms로 해석 시 1970년대 → 유효창 밖 "만료". 라이브 로그 `timestamp=[1783909089]`(10자리)가 증거. 형제 클라이언트 `SmartStoreOrderApiClient`는 `System.currentTimeMillis()`(ms) 사용해 정상 — 두 클라이언트 단위 불일치.
- 수정: `SmartstoreRestClient.java:58` `getEpochSecond()`→`toEpochMilli()`. 같은 `timestamp` 변수가 BCrypt 서명(:59)·form 필드(:66) 양쪽에 쓰여 한 줄로 정합 유지. L103 `X-Time-Stamp` 헤더도 동일 초/ms 잠재버그라 ms로 동반 교정. TDD: revert→RED, fix→GREEN(SmartstoreRestClientTest, MockRestServiceServer, ms 13자리/≥1e12 검증).
- 라이브검증(2026-07-13 재배포 후 배치 47059d54): 스마트스토어 46/46 성공, `timestamp 만료`·`토큰 발급 실패` 0건. 이전 100% 실패 완전 해소.
- 영향: `infrastructure/.../smartstore/client/SmartstoreRestClient.java`.
- 잔여(비차단): 테스트가 private final restClient에 리플렉션 주입(RestClient.create() 하드코딩, 생성자 seam 없음) — 향후 JDK 하드닝 시 취약. 근본 개선은 RestClient 생성자 주입화(별도).
- 참고: 쿠팡 배치 부분실패는 코드 무관 외부 상태(쿠팡 판매중지 상품 판매재개 거부·stale vendorItemId not found·일시 504) — 정직 표면화(SP-F), 결함 아님.

## 2026-07-13 라이브 개선 사이클 (통합주문/배치/배송 — 배포·검증 완료)
> superpowers 흐름으로 진행, main 직접 배포. 아래 전부 수정완료·배포됨.

### D-082: 배치 진행추적 키 불일치 — 진행률 영구 PENDING
- 심각도 P2 / 상태 수정완료·배포·라이브검증 (main `43d1171`+요약엔드포인트/프론트)
- 증상: 배치 실행 후 811건 처리해도 `sb_process_status`가 전부 PENDING → 진행률 표시 불가.
- 근본원인: `ProcessStatusService.startBatch`는 productId를 product_code 키로 저장하는데, `BatchPriceStockService`의 markSuccess/markFailed는 `product.getSbCode()`로 갱신 → 키 불일치로 매칭 실패, 상태 미갱신.
- 수정: 3개 배치 메서드의 mark* 호출을 `String.valueOf(productId)`로 통일 + 경량 `GET /api/v1/products/batch/status/{batchId}/summary`(total/success/failed/pending/done/percent) + 프론트 진행바(30초 폴링·localStorage로 새로고침 복원).
- 검증: 라이브 배치 da9a3bdf done 23→31→38→42 증가, 2145/2145 SUCCESS 확인.

### D-083: 배송정보 택배사 맵핑 미흡 — ETC/원본 enum 노출 + carrier 유실
- 심각도 P3 / 상태 수정완료·배포 (main `34af0b2`·프론트 `dffb101`)
- 증상: 배송정보에 "ETC"·"HANJIN" 등 원본 문자열 노출, 쿠팡 일부 주문 carrier 유실(-).
- 근본원인: (a) 프론트 한글맵이 CJ/우체국/롯데 3개만, (b) 백엔드 `ShippingCarrier.fromMarketCode` default→ETC(주석은 null이라면서 모순), (c) `CoupangOrderAdapter` 보정패스가 현재값 ETC일 때만 복구(null 방치).
- 수정: 프론트 전체 택배사 한글맵 + ETC/미매핑/빈값→'-'; fromMarketCode default→null; 쿠팡 보정패스 null carrier도 복구.
- 전수조사(라이브): CJ103·우체국33·롯데24 정상, null 20(송장있는데 carrier null 11=쿠팡 deliveryCompanyName 빈값), ETC 2. GMARKET 중복행 없음.

### D-084: 쿠팡 송장 반영 조용한 성공 위장 + DB/마켓 비동기화
- 심각도 P2 / 상태 수정완료·배포·라이브검증 (main `c07b7cd`·`aa2e9ae`)
- 증상: 송장수정이 DB엔 저장됐는데 쿠팡엔 반영 안 됨, 화면엔 성공 토스트.
- 근본원인: (a) `CoupangInvoiceResponse.isSuccessful()`이 최상위 code만 보고 `data.responseList[].succeed`(항목별 결과)를 무시 → 쿠팡 항목거부를 성공으로 오인. (b) `updateShippingInfo`가 마켓 실패해도 DB 저장 보존(D-069) → 화면 성공.
- 수정: isSuccessful이 항목별 succeed 검사 + `failureReason()` 표면화; `updateShippingInfo`가 `isFailed()` 시 throw→@Transactional 롤백(D-069 반전, DB/마켓 싱크); 프론트 실패 토스트 + orders 재조회.
- 검증: 라이브 백정환 건 "쿠팡 송장업로드 실패: 배송진행상태가 유효하지 않습니다" 사유 표시 + DB 롤백 확인.

### D-085: 송장 초기등록/수정 판단이 우리 전송여부(trackingSentToMarket)에 의존
- 심각도 P2 / 상태 수정완료·배포 (main `3711e32`)
- 증상: 쿠팡 동기화로 유입된 송장(우리가 안 보냄) 편집 시 초기등록 API(shipOrder) 타서 "배송진행상태가 유효하지 않습니다" 거부. 백정환(플래그 false) 실패 vs 김창식(플래그 true) 성공.
- 근본원인: `MarketplaceShippingService.sendTrackingToMarketplace`가 `trackingSentToMarket`(우리 전송 여부)로 shipOrder/updateTracking 결정 → 판매자/쿠팡 직접 등록·수정을 반영 못 함.
- 수정: 시그니처 `(OrderLineItem, boolean invoiceAlreadyExists)`. 판단을 "마켓에 송장 이미 존재(편집 전 trackingNo 존재/동기화 상태)"로 → 있으면 updateTracking(수정), 진짜 최초발송만 shipOrder. 4개 호출부(updateShippingInfo 2·ship·updateTrackingInfo)+worker EmailFetcherService 2곳 관통. **주의: 시그니처 변경이 worker 호출부 누락으로 첫 배포 컴파일 실패 → hotfix `4968d24`. 이후 공유 시그니처 변경은 전 모듈 컴파일 필수.**

### D-086: 이메일 송장 교정 누락 — SHIPPED-다른송장 미처리(가짜→진짜)
- 심각도 P2 / 상태 수정완료·배포 (main `a545497`)
- 증상: 취소 방지용 가짜 송장 선입력(상태 SHIPPED) 후 이메일로 진짜 송장(다른 번호) 도착해도 반영 안 됨.
- 근본원인: `EmailFetcherService.processIherbShipment`가 `SHIPPED+동일송장`(alreadyShipped) 또는 `PURCHASED`만 처리, `SHIPPED+다른송장`은 else로 빠져 무처리.
- 수정: alreadyShipped 다음·PURCHASED 앞에 `SHIPPED이고 이메일송장≠기존` 분기 추가 → 송장/택배사 교정 + 마켓 `updateTracking`(수정, invoiceAlreadyExists=true) 전파. 미지 해외택배사(DHL/FedEx 등 mapCarrier→ETC)는 기존 택배사 유지. worker 테스트 2건.

### D-087: @Async 마켓동기화/정산 트리거의 ActionLog SUCCESS 오기록 (실패 미인지)
- 심각도 P2 / 상태 수정완료·회귀통과(전체 ./gradlew test SUCCESS) — 커밋 대기 (2차 API분석 SYNCA-1/5/9/13 + SYNCB-6 승격)
- 수정: (a) `OrderSyncController` 쿠팡·스토어·11번가·G마켓·정산 5개 엔드포인트의 디스패치 직후 `record(SUCCESS)` 제거(STARTED·catch→FAILED 유지). (b) `CoupangOrderSyncService`에 `ActionLogService` 주입, `syncCoupangSettlement` markCompleted/markFailed 지점에서 `COUPANG_SETTLEMENT_SYNC` SUCCESS/FAILED 기록(recordSettlement 헬퍼). S1~S4 완료는 기존 `SyncCompletedEvent`→`ActionLogSyncListener`에 위임.
- 테스트: `OrderSyncControllerActionLogTest` 새 계약으로 재작성(트리거는 STARTED만·SUCCESS never·동기예외 FAILED), 신규 `CoupangSettlementActionLogTest`(완료 SUCCESS·실패 FAILED·중복스킵 무기록). 기존 수동생성 테스트 2건(OrderSyncEventEmission·OrderAddressProtection) 생성자 인자 보정.
- 증상: 쿠팡·스마트스토어·11번가·G마켓 주문동기화와 쿠팡 정산동기화가 실제 실패해도 활동로그에 SUCCESS로 남거나(정산은 실패가 아예 안 남음), 운영자가 진행현황에서 실패를 인지하지 못한다.
- 근본원인: `OrderSyncController`가 STARTED 기록 후 `@Async("syncTaskExecutor")` 서비스를 호출하고, 비동기 디스패치 직후 동기 실행 흐름에서 `record(..., SUCCESS, ...)`를 남긴다(`OrderSyncController.java:64/94/123/149/204`). @Async라 즉시 반환되므로 try는 항상 성공 → **결과와 무관하게 SUCCESS**. try/catch는 백그라운드 예외를 못 본다.
  - S1~S4(주문동기화): 서비스가 성공/실패 양쪽에 `SyncCompletedEvent`를 발행하고 `ActionLogSyncListener`가 이미 정확한 `{MARKET}_SYNC` SUCCESS/FAILED를 기록한다 → 컨트롤러 SUCCESS는 **중복+가짜** 이중기록.
  - S5(`COUPANG_SETTLEMENT_SYNC`): `syncCoupangSettlement`은 `SyncCompletedEvent`를 발행하지 않아 리스너 기록이 없다 → 유일한 ActionLog가 컨트롤러의 가짜 SUCCESS. **실제 정산 실패가 어디에도 FAILED로 안 남는 진짜 BUG.**
  - 과거 F-SYNC-3(1차 분석 기반)이 "트리거 성공 기록" 의도로 이 SUCCESS를 추가했으나, 리스너 도입(D-042) 이후 중복·오기록이 됨.
- 수정계획: (a) S1~S4 컨트롤러의 즉시 `record(SUCCESS)` 제거 — STARTED와 catch→FAILED(동기 디스패치 실패용)는 유지, 완료는 리스너에 위임. (b) S5 정산은 `CoupangOrderSyncService`에 `ActionLogService` 주입, `syncCoupangSettlement`의 markCompleted/markFailed 지점에서 `COUPANG_SETTLEMENT_SYNC` SUCCESS/FAILED 기록 + 컨트롤러 가짜 SUCCESS 제거. (c) S6 통관은 동기 실행이라 정확 → 불변.
- 영향 범위: `OrderSyncController`(api) + `CoupangOrderSyncService`(core). 다마켓 관측성 계약 → 표준~중대. TDD Red(컨트롤러 SUCCESS 미기록 · 정산 서비스 완료기록) 후 수정.

### UX 개선(비결함, 동반 배포)
- 배치 업데이트 진행바(D-082 동반), 상품관리 가격/재고 stockStatus 모달 시드, 통합주문 그리드 **구매/배송정보 셀 병합(rowSpan, 배송사/송장·계정/공급처/주문#/할인 stack) + 더블클릭 통합 인라인편집(택배사+송장 한 세트 1회 저장→마켓 1회 호출) + placeholder + 수정버튼/모달 제거**, 인라인 성공/실패 토스트.

### D-088: 옥션/G마켓 신규주문(발주확인 전)이 "구매준비"로 오분류 — Cafe24 N10 매핑 오류
- 심각도 P2 (오동작 — 주문상태 오표시, 운영자 발주확인 워크플로우 혼란) / 리스크 등급 표준(마켓 주문상태 계약) / 상태 수정완료·회귀통과(전체 ./gradlew test SUCCESS)·push대기 (2026-07-20 결과서 20260720_1028)
- 라이브 확증(2026-07-20, /cafe24/preview): 주문 2566278285(cafe24_order_id 20260719-0000018, auction)의 실제 Cafe24 `order_status`=**N10**. 대조군 gmarket 4469254653=N20(발주확인 후, PREPARING 정상)·4469438260=N30(배송중, SHIPPED 정상). N10→PREPARING 오분류 100% 확정.
- 신고: 주문 2566278285(서종수, 옥션)이 실제로는 신규주문(주문확인 전)인데 시스템은 "구매준비(PREPARING)"로 표시.
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/Cafe24OrderSyncService.java:326`
- 근본원인: `mapStatus()`가 `case "N10", "N20", "N21", "N22" -> PREPARING`으로 **N10(상품준비중)을 발주확인 후 상태에 잘못 포함**. 그러나 같은 코드베이스의 발주확인 액션(`Cafe24OrderApiClient.java:67` `ACCEPT_STATUS="N20"`)이 발주확인의 *결과*를 N20으로 정의하므로 **N10=발주확인 전(신규주문)**이 논리적 필연. 옥션/G마켓은 결제완료 상태로 유입되어 Cafe24가 신규건에 N10을 부여 → NEW여야 할 건이 PREPARING으로 표시됨.
- 모순 근거: `Cafe24OrderSyncService.java:326` + `Cafe24OrderSyncServiceTest.java:167`("N10=발주확인 후" 가정)이 `Cafe24OrderApiClient`의 발주확인 타깃(N20)과 정면충돌. 사용자 신고 증상이 오분류와 정확히 일치.
- 수정계획(TDD): mapStatus에서 N10을 NEW 그룹으로 이동 — `case "N00","N02","N10" -> NEW`(발주확인 전), `case "N20","N21","N22" -> PREPARING`(발주확인 후). `Cafe24OrderSyncServiceTest`의 잘못된 기대값(line 167-168) 정정 + N10→NEW·N20→PREPARING 회귀 테스트 추가.
- 유의: `Cafe24OrderApiClient.java:66` "상태코드 라이브 검증 대상" 주석 — N20 값 잠정. 다만 (신고증상 + 발주확인 타깃) 2독립근거로 진단 신뢰 높음. 라이브 확정은 주문 2566278285의 실제 Cafe24 order_status 조회로 확인 가능(프로덕션 read 승인 필요).
- 영향: Cafe24OrderSyncService 단일 파일(core) + 테스트. 배포 후 기존 N10 상태로 저장된 옥션/G마켓 미확인 주문의 재동기화 시 자동 NEW 교정.

### D-089: 배치 업데이트 진행바가 클라이언트(브라우저)별로 격리 — 동업자 간 상호 미표시
- 심각도 P2 (오동작 — 다중 운영자 협업 시 배치 진행 상호 미가시) / 리스크 등급 표준(프론트 + SSE 이벤트 추가) / 상태 수정완료·검증중 (A안, 2026-07-20)
- 수정(2026-07-20, A안): `BatchStartedEvent`(core) 신설 → `BatchController.startBatchWithLog`(4개 엔드포인트 공통)에서 발행 → `SseNotificationController.onBatchStarted`가 `BATCH_STARTED`(payload=batchId) 전역 방송. `BatchUpdatePage`가 `/notifications/subscribe` 구독 → BATCH_STARTED 수신 시 startTracking(자기 배치는 batchIdRef로 중복 회피), BATCH_COMPLETED/FAILED 수신 시 즉시 요약 갱신. 테스트: `SseNotificationBatchTest`에 batchStartedEventName/Payload 2건 + 기존 BatchController 생성자 4곳 publisher mock 보정. 프론트 tsc/build 통과. (단일 batchId 슬롯: 최근 시작 배치가 표시됨 — 동시 다른 배치는 latest-wins.)
- 신고: "내가 배치업데이트한 것은 동업자 컴퓨터에서 안 보이고, 동업자가 한 것은 내게 안 보인다."
- 위치: `frontend/src/pages/BatchUpdatePage.tsx:5,64,77` (localStorage 단일 구동) · `backend/api/.../SseNotificationController.java`(START 이벤트 부재)
- 근본원인: 진행 카드가 `localStorage['sbshop.activeBatchId']`에 저장된 batchId로만 폴링(`startTracking`). batchId는 배치를 **개시한 브라우저에만** 반환·저장되므로 타 클라이언트/타 기기는 그 batchId를 알 수 없어 진행바 미표시. 배치 상태는 공유 DB(`ProcessStatus`)에 있고 전역 SSE 브로드캐스트(`SseNotificationController.emitters`)도 존재하나, (a) BatchUpdatePage가 SSE를 구독하지 않고, (b) SSE는 `BATCH_COMPLETED/FAILED`(완료)만 방송하고 **배치 시작(batchId 전파) 이벤트가 없어** 타 클라이언트가 진행 중 배치를 발견할 경로가 전무.
- 참고: `ProcessStatusPage`는 SSE(BATCH_COMPLETED) 구독 + DB 조회로 완료건은 공유 가시. 실시간 진행"바"만 개시 브라우저 국한.
- 수정방향(택1, TDD):
  - A(권장·실시간): 배치 시작 시 `BATCH_STARTED` SSE 이벤트(batchId 포함) 방송 + BatchUpdatePage가 `/notifications/subscribe` 구독해 수신 시 startTracking. 기존 전역 emitters 재사용.
  - B(폴링): 진행 중(pending>0) batchId 목록 엔드포인트 신설 + BatchUpdatePage 마운트/주기 폴링으로 활성 배치 자동 추적. SSE 불요, 폴링 지연 존재.
- 영향: 프론트 BatchUpdatePage + (A안) api SseNotificationController/BatchController·이벤트 1종. 백엔드 배치 실행 로직 불변.

### D-090: 옥션/G마켓 발주확인이 "AUCTION credentials not found"로 실패 — Cafe24 기반 마켓 크레덴셜 필수 오요구
- 심각도 P1 (기능 불능 — 옥션/G마켓 발주확인 전건 실패) / 리스크 등급 표준(발주확인 경로 1라인) / 상태 수정완료·검증중 (2026-07-20)
- 수정(2026-07-20): `OrderService.confirmOrder:96` `.orElseThrow(...)` → `.orElse(null)`. 회귀 테스트 `OrderServiceStateGuardTest.ConfirmOrderCafe24NoCredential`(AUCTION 크레덴셜 없이 발주확인 성공·null credential 포트 위임·NEW→PREPARING). 전체 `./gradlew test` 통과. 잔여: Cafe24 acceptOrder PUT 자체(status N20·body)는 `Cafe24OrderApiClient.java:66` "라이브 검증 대상" — 실제 발주확인 성공은 배포 후 라이브 확인 필요(크레덴셜 차단만 해소, PUT 포맷은 별개 미검증).
- 신고: 서종수 옥션 주문(196) 발주확인 시 오류. 로그: `주문 196 접수 확인 실패: AUCTION credentials not found`.
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderService.java:96`
- 근본원인: `confirmOrder`가 `credentialRepository.findByMarketType(marketType).orElseThrow(...)`로 마켓 크레덴셜을 **필수**로 요구. 그러나 옥션/G마켓 발주확인은 `Cafe24AuctionOrderAdapter/Cafe24GmarketOrderAdapter.acceptOrders`가 **credential 파라미터를 무시**하고 Cafe24 토큰(Cafe24TokenManager)으로 호출 → AUCTION/GMARKET 크레덴셜은 DB에 없음(설계상 Cafe24가 연동주체). 크레덴셜 조회에서 실제 Cafe24 호출 전에 실패.
- 노출 경위: [[D-088]] 수정 전에는 N10이 PREPARING으로 오분류돼 `hasProgressedOrEnded` 가드("이미 발주확인됨")에서 차단 → 크레덴셜 조회에 도달 못 함. D-088(N10→NEW)로 발주확인이 실행되며 잠재 버그 노출.
- 판정근거: 같은 코드베이스의 송장·취소 경로(`MarketplaceShippingService.java:73,141`, `OrderShipProcessor.java:54`)는 이미 `findByMarketType(...).orElse(null)` + "Cafe24 기반(G마켓/옥션)은 cred 없어도 포트 위임" 주석으로 올바르게 처리. **발주확인 경로만 `.orElseThrow`로 누락.**
- 수정방향(TDD): `OrderService.confirmOrder`의 크레덴셜 조회를 `.orElseThrow(...)` → `.orElse(null)`로 변경(기존 송장/취소 패턴과 일치). Cafe24 어댑터는 null 무시, 타 마켓은 어댑터 내부에서 사용(송장 경로와 동일 계약).
- 영향: `OrderService.java` 1라인 + 회귀 테스트.

### D-091: Cafe24 발주확인 PUT 포맷 오류 — 잘못된 엔드포인트·바디·상태값
- 심각도 P1 (기능 불능 — 옥션/G마켓 발주확인 API 호출 실패) / 리스크 등급 표준(마켓 API 계약·라이브 확증) / 상태 수정완료·검증중 (2026-07-20)
- 신고: 발주확인 재시도 시 "Order 196: 마켓플레이스 주문 접수 실패: Cafe24 API PUT 호출 실패". [[D-090]] 크레덴셜 차단 해소 후 실제 PUT이 노출한 후속 결함. 사용자가 Cafe24 정식 API 스펙 제공.
- 위치: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/client/Cafe24OrderApiClient.java:acceptOrder`
- 근본원인: `acceptOrder`가 `PUT /admin/orders/{id}` + `{shop_no, request:{status:"N20"}}`로 호출했으나, Cafe24 배송상태처리 스펙은 `PUT /admin/orders`(경로에 id 없음) + `{shop_no, requests:[{order_id, process_status:"prepare"}]}`. 3가지 오류: ① 엔드포인트에 id 붙임 ② `request`(객체)→`requests`(배열) ③ `status:"N20"`(읽기 order_status 코드)→`process_status:"prepare"`(쓰기 어휘). 읽기(order_status: N00/N10/N20…)와 쓰기(process_status: prepare/prepareproduct/hold/unhold)가 별개 어휘였음.
- 수정(2026-07-20, 라이브 스펙 기반): `acceptOrder`를 `PUT /admin/orders` + `requests:[{order_id, process_status:"prepare"}]`로 재작성. order_item_code(품주코드)는 sbshop 미보존이라 생략(주문 전체 적용). 회귀 테스트 `Cafe24OrderApiClientStatusTest.acceptOrderSendsPut` 스펙대로 정정. `:infrastructure:test` 통과.
- 잔여(후속 결함 후보): `cancelOrder`(CANCEL_STATUS="C40" + 동일 구 엔드포인트/바디)도 같은 포맷 오류일 가능성 높음. 단 Cafe24 취소는 process_status 어휘 밖(별도 취소/환불 API)이라 정식 스펙 확보 후 수정 필요 — 이번 스코프 제외. 실제 발주확인 성공 여부는 배포 후 라이브 확인.

### D-092: 소스이미지 크롤 재게시 — 대표이미지가 마켓에 반영 안 됨(마켓별 교차 결함)
- 심각도 P2 (오동작 — 상품 이미지/상세 재게시 불완전) / 리스크 등급 중대(마켓 API 계약·다마켓, 라이브 검증 필수) / 상태 진단완료·수정보류(라이브 확증 필요)
- 신고 매트릭스: 쿠팡·스토어=대표이미지·상세 둘 다 ✗ / G마켓=둘 다 ✓ / 옥션·11번가=대표이미지 ✗·상세 ✓.
- 진단(4에이전트 병렬 + 리더 코드확인):
  - 진입: `ProductManageUseCase.updateImagesAndHtml`→`republishToMarkets`→마켓별 `MarketClient.syncImagesAndHtml(marketItemId, currentRawData, hostedImages, newHtml)`. GMARKET/AUCTION은 marketType 클라이언트 부재로 스킵되고, 실제 반영은 CAFE24 등록행(ESM 백필 identifiers) 1개를 Cafe24MarketClient가 처리 → G마켓/옥션 공통 경로.
  - **확정 비대칭(핵심 가설)**: 작동하는 Cafe24(G마켓)는 이미지를 **base64 업로드**(`Cafe24MarketClient` `POST /admin/products/{id}/images`, image_upload_type=B, detail_image/list_image=dataUri). 실패 마켓들은 **우리 외부 hostedImage URL을 대표이미지 필드에 그대로** 전송: 쿠팡 `items[0].images[].vendorPath`(CoupangMarketClient:~269-273), 스토어 `originProduct.images.representativeImage.url`(SmartstoreMarketClient:168-172,314-332), 11번가 `prdImage01~04`(ElevenstMarketClient:117-134,165-181). 마켓이 외부 URL을 대표이미지로 안 받고 조용히 무시 → 대표이미지 ✗. 상세 HTML은 외부 `<img>` URL 렌더되어 ✓.
  - 마켓별 부가:
    - 스토어 상세 ✗: `SmartstoreMarketClient:175` `newDetailHtml.replace("\"","\\\"").replace("\n","")` 수동 이스케이프 후 Jackson이 재이스케이프 → **이중 이스케이프**로 detailContent 손상(코드 확정 버그). currentRawData 미러도 최상위 representativeImage로 스키마 불일치(183-188, 미러 한정 2차).
    - 쿠팡 둘 다 ✗: items[0].images/contents를 rawData PUT + `/approvals`. 외부 vendorPath는 쿠팡이 받을 수도 있어(추정) 승인요청·상태전이 등 별개 원인 가능 — 라이브 응답 확인 필요.
    - 11번가 대표 ✗: prdImage01에 외부 URL. (부차: 대표이미지 PUT은 "동일" 응답을 성공 처리 안 함 124-127 vs 상세 145 — 비대칭, 단 신규 이미지엔 무관.)
    - 옥션 대표 ✗: Cafe24 base64 업로드가 옥션 오픈마켓 연동으로 전파 안 되는 ESM 설정(코드 밖) 가능 — 라이브 확인 필요.
- **publish↔resync 대조 결과(2026-07-20, 리더 확정 — 앞선 가설 반증)**: 3개 실패 마켓 모두 publish()와 syncImagesAndHtml()이 **동일한 외부 호스팅 URL**을 이미지에 사용(스토어·쿠팡은 이미지/이스케이프 코드까지 동일). ∴ "외부 URL 미수용"·"detailContent 이중 이스케이프"는 **근본원인 아님**(등록이 같은 코드로 작동). 진짜 차이는 **CREATE(publish=새 페이로드 POST, 작동) vs UPDATE(resync=GET 전문→필드수정→PUT, 실패)** 라운드트립.
  - 스토어: `publish:43,48`과 `syncImagesAndHtml:172,175`이 `applyImages`·동일 이스케이프 공유 → CREATE만 되고 PUT UPDATE는 안 되는 Naver 의미 차이(추정, 라이브 필요).
  - 쿠팡: `publish:56-60`(POST typed payload) vs `syncImagesAndHtml:265-273`(GET rawData.firstItem.images 교체 후 통째 PUT+/approvals). 이미지 ID 없는 신규 이미지 무시/별도처리 가능(추정).
  - 11번가: `buildProductXml:prdImage01`(POST) vs `applyRepresentativeImages`(productinfo **GET 응답 XML**에 정규식 `<prdImage0N>` 치환 후 PUT). **GET 응답 스키마에 prdImage 태그 없으면 regex no-op**(가장 구체적·검증가능 — productinfo GET 실응답 스키마 확인 필요).
- 다음 단계: 마켓별 UPDATE-이미지 API 정확한 계약 확증 필요(라이브 GET 실응답 스키마 or API 문서). 등록은 되므로 resync를 delete+republish 또는 마켓 전용 이미지수정 엔드포인트로 전환하는 근본 수정 후보. 확증 전 배포 금지(중대·다마켓, 작동중 G마켓 회귀 위험).
- 상태: 진단 refined·미수정. 마켓 1개씩 라이브 확증→TDD 수정 권장(11번가 productinfo 스키마부터).
- **계측 배포(2026-07-20)**: 4개 `syncImagesAndHtml`(쿠팡·스토어·11번가·Cafe24)에 읽기전용 `[D092]` 로깅 추가 — GET 응답 스키마·PUT/POST 요청 바디·응답(현재 다 버려지던 값). 동작 무변경(로깅만), 진단 후 제거 예정.
- **★라이브 확정 근본원인(2026-07-20, [D092] 로그)★**:
  - **쿠팡**: `PUT /v2/providers/seller_api/apis/api/v1/marketplace/seller-products/{id}` → **404 PRECONDITION_FAILED "No matched http method ... did you mean GET,DELETE?"**. 상품수정 PUT을 GET/DELETE 전용 경로(id 포함)에 보냄. 수정: **id 없는 `PUT .../seller-products`**(sellerProductId는 바디)로 변경. `/approvals`는 별개 확인.
  - **스토어**: `PUT /v2/products/origin-products/{id}` → **400 BAD_REQUEST "올바른 이미지 파일이 아닙니다."**. Naver가 외부 R2 URL(representativeImage.url)을 거부 → **Naver 이미지업로드 API로 먼저 업로드 후 Naver URL 사용** 필요. 이미지 거부로 PUT 전체 실패 → 상세도 collateral 실패(이미지 고치면 상세도 통과). GET 스키마·images 스키마·detailContent는 정상.
  - **11번가**: `GET /rest/prodservices/productinfo/{prdNo}` → **`<AuthMessage><resultCode>-997</resultCode>등록된 API 정보가 존재하지 않습니다`**(인증에러, 상품데이터 아님). applyRepresentativeImages가 에러XML에 no-op(prdImage 포함=false)→PUT에 에러XML 전송→11번가 JAXBException. **에러가드(`ERROR`/`resultCode>500`)가 -997·JAXBException 미포착→"재게시 완료" 가짜성공 오기록**. 상세설명 POST(updateProductDetailCont)는 resultCode 000 진짜성공. 수정: (a) productinfo GET 엔드포인트/인증 교정(-997 해소), (b) 에러가드에 AuthMessage/-997/JAXBException 추가(가짜성공 차단).
  - **옥션(Cafe24)**: description PUT resp(len~3900)·이미지 POST resp(len 491) 정상 반환 = Cafe24 API 성공. 옥션 대표이미지 미전파는 Cafe24 상품→ESM 옥션 연동 설정(코드 밖). G마켓은 정상.
- 수정 스코프: 쿠팡(엔드포인트 교정, 확신 높음)·11번가(productinfo GET + 에러가드)·스토어(Naver 이미지업로드 신규, 큼). 옥션은 코드밖. 각 TDD 후 계측 제거.
- **수정 1차(2026-07-20, 쿠팡+11번가 TDD)**:
  - 쿠팡: `syncImagesAndHtml` 상품수정 PUT을 id 없는 base(`.../seller-products`)로, 승인은 `.../seller-products/{id}/approvals` 유지(GET/DELETE는 id경로 유지). `CoupangMarketClientImagesTest` 수정 PUT 어서션을 no-id로 정정.
  - 11번가: GET을 `productinfo`→`product`(‑997 해소 시도), GET/PUT 에러가드에 AuthMessage/‑997/JAXBException/`!<Product>` 추가(가짜성공 차단). `ElevenstMarketClient*Test` mock 경로 product로 정정 + 신규 `throwsWhenGetReturnsAuthError`.
  - 계측 [D092] 유지(라이브 재검증용). 전체 `./gradlew test` 통과. 스토어(Naver 업로드)·옥션(ESM)은 다음 라운드/코드밖.

### D-092 라이브 재검증 (2026-07-20, 쿠팡+11번가 1차 수정 배포 후)
- **쿠팡**: 404 PRECONDITION_FAILED **해소**(id 없는 PUT 성공). 그러나 새 사실 2개 — ① 상품수정 PUT resp `{"code":"SUCCESS", message:"필수 구매 옵션이 존재하지 않습니다/유효하지 않은 구매 옵션..."}` (라운드트립 payload의 구매옵션 검증 경고), ② 승인요청 resp `{"code":"ERROR","message":"'임시저장' 상태의 상품만 승인 요청 가능합니다."}` → **이미 승인/판매중 상품 수정엔 `/approvals`가 부적합**. 쿠팡 승인상품 수정 플로우(부분수정 API or 자동 승인대기 전환) 확인 필요. 이미지 실제 반영 여부 미정(승인대기 지연 가능성).
- **11번가**: 에러가드 수정 성공 — 이제 가짜성공 없이 `failed`로 정직하게 잡힘. 그러나 `/rest/prodservices/product/{prdNo}`도 `/productinfo`와 동일하게 **-997 "등록된 API 정보가 존재하지 않습니다"** → 상품조회 API가 해당 openapikey에 미등록/미허용(계정측). 주문·상세수정 API는 정상. **코드밖(11번가 셀러 API 설정) 가능성 높음** — 셀러오피스에서 상품조회 API 활성화 or 올바른 조회 엔드포인트/인증 확인 필요.
- 스토어: 미착수(Naver 이미지업로드 신규). 옥션: Cafe24 ESM 설정(코드밖).
- 다음: 쿠팡 승인상품 수정·승인 플로우 확정(문서/셀러오피스), 11번가 -997 계정측 해소. 확정 후 2차 수정.

### D-092 2차 확정 (2026-07-20, 사용자 제공 문서 기반)
- **쿠팡 근본원인 확정(문서 `쿠팡상품수정(승인필요).pdf`)**: 상품수정(승인필요) = `PUT .../seller-products`(id無), body에 **`requested` 필드**. `false`(기본)=임시저장, `true`=저장+자동승인요청. 우리 코드는 GET한 rawData 그대로 PUT→requested 부재→**임시저장으로 떨어짐**(11583618874 임시저장 재현). 별도 `/approvals`("상품 승인 요청")는 임시저장 전용이라 승인상품 편집엔 부적합.
  - **수정**: `rawData.put("requested", true)` + `/approvals` 호출 제거. 테스트 정정(requested=true 검증, approvals never). `:infrastructure:test` 통과. 라이브 재검증 대기.
  - **잔여 리스크**: PUT resp에 "유효하지 않은 구매 옵션 값/단위(개당 용량/중량/정)" 경고 동반. 문서 Error Spec의 "Invalid Attribute Value(s)" — 라운드트립 attributes가 카테고리 메타와 불일치 가능. requested=true로 승인요청돼도 쿠팡 심사에서 attribute 반려 가능성 → 라이브 확인 필요.
- **11번가 상품조회 문서(`11번가-상품조회.pdf`)**: 제공된 건 **셀러상품조회**(`GET /rest/prodmarketservice/sellerprodcode/{sellerprdcd}`, 판매자상품코드로 조회, **제한 필드만**: prdNo/selStatCd/selPrc 등). 상품수정(전체XML 덮어쓰기)에 필요한 전체 상품 XML은 **신규상품조회/다중상품조회**(미제공). 현재 `/rest/prodservices/productinfo/`·`/product/` 둘 다 -997 → 폐기 추정. **필요**: 신규상품조회 or 다중상품조회 문서(전체 XML 반환 엔드포인트), 또는 우리 DB에서 전체 XML 재구성 방식. 에러가드 수정으로 현재는 정직하게 failed 처리됨.
- 상태: 쿠팡 2차수정 배포·재검증 대기. 11번가 전체조회 엔드포인트 확정 대기. 스토어(Naver 이미지업로드)·옥션(ESM) 미착수.

### D-092 11번가 조회 문서 분석 (2026-07-20)
- **신규상품조회**(`GET /rest/prodmarketservice/prodmarket/{prdNo}`) 응답에 htmlDetail·selPrc 등은 있으나 **prdImage01·brand·prdTypCd·hsCode·ProductCert 등 상품수정 필수 다수 누락** = 편집용 전체 XML 아님. 셀러상품조회도 제한필드. → **어떤 11번가 조회도 상품수정용 전체 XML을 반환하지 않음.** productinfo(-997)는 폐기.
- **결론**: 11번가 대표이미지는 라운드트립(조회→수정) 불가. `buildProductXml`(publish 등록 시 사용, Product 7필드+하드코딩 기본값으로 완전한 등록XML 생성 → 등록 성공 실증)을 **재사용**해 새 hostedImages로 전체 XML 재구성 후 PUT하는 방식이 정답(상품수정=등록과 동일 XML 포맷).
- **필요 변경**: `MarketClient.syncImagesAndHtml`에 Product 전달(현재 marketItemId/rawData/images/html만) 또는 11번가 전용 경로. ProductManageUseCase는 이미 Product 보유. 크로스컷 인터페이스 변경 → 4개 클라이언트+UseCase+테스트. 사용자 확인 후 진행 예정.
- 쿠팡 requested=true 수정 배포완료(9467fd8) — 라이브 재검증 대기(승인대기 전환·구매옵션 경고).

### D-092 11번가 근본 수정 (2026-07-20, 사용자 승인)
- **인터페이스 변경**: `MarketClient.syncImagesAndHtml`에 `Product` 첫 인자 추가(4개 클라이언트+`ProductManageUseCase.republishToMarkets`). ProductManageUseCase는 재게시 직전 새 이미지·상세HTML로 갱신된 product를 전달. Coupang/SmartStore/Cafe24는 인자만 추가·동작 불변.
- **11번가 재작성**: 조회로 전체 편집 XML을 얻을 수 없으므로(신규/셀러상품조회 필드 누락·productinfo -997), 등록 때 쓰는 `buildProductXml(product)`(Product+기본값으로 완전한 상품 XML 생성, publish 등록 성공으로 완전성 실증)를 재사용해 재구성 → `PUT /rest/prodservices/product/{prdNo}`로 대표이미지+상세HTML을 1회에 반영. 기존 GET(productinfo/regex)·별도 상세POST 제거. 실패가드(ERROR/500/Exception/AuthMessage) 유지. 성공=ClientMessage resultCode 200/210.
- 테스트: Elevenst 2개 테스트 재작성(buildProductXml PUT 전문에 새 prdImage01·htmlDetail 포함·에러 throw·AuthMessage throw), core republish 테스트 2개 mock 시그니처 보정(Product any() 추가), Coupang/SmartStore 테스트 null Product 인자.
- 잔여 리스크: buildProductXml이 상품수정 필수필드 일부 누락 가능(dispCtgrNo·인증 등). 단 publish 등록이 같은 XML로 성공하므로 수용 추정 — 라이브 PUT 응답으로 최종 확인(누락 시 그 필드 보강). 상품수정은 전체 덮어쓰기라 라이브 첫 검증 신중.

### D-092 3차 라이브 검증 (2026-07-20, 인터페이스변경+11번가 재작성 배포 후)
- **쿠팡: 대표+서브이미지 반영 성공 ✓**(사용자 육안 확인). 상세설명은 미반영 — 같은 PUT resp `code:SUCCESS`+"필수 구매 옵션" 경고. requested=true로 승인대기 전환됐을 가능성 → 쿠팡 심사 후 상세 반영 대기 가능성(재확인 필요). 이미지 즉시반영 vs 상세 지연 비대칭은 추가 관찰.
- **11번가: 상품수정 PUT 실패** — resp `<resultCode>500</resultCode> 원재료 유형 코드 필수 입력 대상 카테고리입니다`. buildProductXml이 `rmaterialTypCd` 등 상품수정 필수필드 다수 누락(원재료·dispCtgrNo·인증정보 ProductCertGroup/Cert·상품정보제공고시 ProductNotification·selMthdCd·prdTypCd·hsCode·asDetail·rtngExchDetail). 일부(카테고리·인증·고시)는 우리 DB에 없어 기본값 대체 곤란. → 전체 상품수정 재구성은 whack-a-mole + 인증/고시/카테고리 벽 가능성.
- 판단: 11번가 대표이미지 API 수정은 데이터 완전성 한계로 난이도 높음. 상세설명은 기존 전용 API(updateProductDetailCont, resultCode 000 실증)로 확실히 가능 → **상세는 전용 API 복원, 대표이미지는 buildProductXml 필수필드 보강(신규상품조회로 dispCtgrNo 획득 + 기본값) 반복 or 보류** 결정 필요(사용자).

### D-092 11번가 필수필드 기본값 채우기 (2026-07-20, 사용자 결정)
- 사용자 통찰: 11번가가 등록 후 일부 필드를 필수로 승격 → 홈페이지 수정에서도 동일 에러. 기본값으로 채워 성공시키는 방식 채택.
- buildProductXml에 기본값 추가: selMthdCd=01(고정가), prdTypCd=01(일반배송), rmaterialTypCd=05(원산지 상세설명참조), minorSelCnYn=N, suplDtyfrPrdClfCd=01(과세), dlvClf=02(업체배송), dlvCnAreaCd=01(전국), dlvWyCd=01(택배), dlvEtprsCd=00034(CJ대한통운·사용자지정), asDetail=., rtngExchDetail=.
- **한계/권고**: 전체 덮어쓰기라 기본값이 기존 택배사·배송비·AS안내를 덮어쓸 위험. 견고한 해법은 **상품수정 전문과 동일한 전체 필드를 돌려주는 11번가 상품상세조회 API로 round-trip**(이미지만 교체·나머지 원값 보존). 다중/신규/셀러조회는 요약이라 인증/고시/주소코드 부재 → 11번가 고객센터에 전체 편집전문 조회 API 문의 권고. 그때까지 기본값으로 진행하며 다음 에러(고시/인증/주소코드 등) 시 추가 대응.

### D-092 11번가 라운드트립 불가 원인 분석 (2026-07-20)
- 배송비 기본값 후 다음 에러: "출고지 주소를 확인해주세요"(addrSeqOut 필수) — 예측한 주소코드 벽 도달.
- **신규상품조회(전체조회)로 부족한 이유**: 상품수정=등록과 동일 전체전문(수십필드) 요구. 신규상품조회는 요약이라 상품수정 필수필드 중 미제공: prdImage(우리가새로넣음), brand/prdSelQty(우리DB), 판매방식/유형/원재료/부가세/배송비종류/택배사(기본값가능) — **그러나 addrSeqOut/addrSeqIn(출고지·반품지 주소코드=판매자실제값), ProductCertGroup/Cert(인증), ProductNotification(고시), ProductOption(옵션)은 조회 안 되고 기본값도 위험**(실제 배송지·법적고시·옵션 훼손).
- **결론**: 11번가엔 상품수정 전문과 동일한 전체 편집전문을 돌려주는 조회 API가 없음(신규/다중/셀러조회 모두 요약). 라운드트립 근본 불가.
- **가능 경로**: (a) 주소코드는 판매자레벨 1값 → 출고지/반품지 주소조회 API로 획득 or 셀러 제공(벽 아님). (b) 인증/고시는 카테고리 표준 기본값(건강식품)으로 defaultable하나 규정리스크. (c) 옵션상품이면 옵션 소실 → 옵션 보존 불가면 대표이미지 자동수정 포기. (d) 근본해법=등록시 전체전문을 우리DB에 저장→수정시 재사용(기존상품은 데이터 없음). → 11번가 고객센터에 "상품수정용 전체 편집전문 조회 API 유무" 문의 권고.

### D-092 11번가 buying-agent 라운드트립 이식 (2026-07-20, 결정적 해법)
- **형제 프로젝트 buying-agent에 검증된 완성 구현 발견**(사용자 제보). 핵심: 상품수정 전체XML 조회 엔드포인트는 **`GET /rest/prodmarketservice/prodmarket/{prdNo}`(신규상품조회)** — 이게 현재 전체 전문(옵션·카테고리·인증·고시 포함) 반환. 우리가 쓰던 `/rest/prodservices/productinfo/`(-997)는 폐기 경로였음.
- **이식**: sbshop `ElevenstMarketClient.syncImagesAndHtml`을 buildProductXml 재구성 → **GET 라운드트립 + 누락 필수필드 주입**으로 교체. GET 전문에서 htmlDetail·prdImage01~05 정규식 치환 + 주입: dlvEtprsCd=00034(CJ), rmaterialTypCd=03+ProductRmaterial, selMthdCd=01, 원산지(orgnTypCd=02 미국/1405), 배송·반품(dlvCstInstBasiCd=01·dlvCstPayTypCd=03·bndlDlvCnYn=N·rtngd/exchDlvCst=7000·asDetail·rtngExchDetail), **주소코드 addrSeqOut=5(미국 출고지)·addrSeqIn=3(국내 반품지)·outsideYnOut=Y·outsideYnIn=N**(판매자 실계정값). 메타태그(message/validateMsg/nResult) 제거. 성공=resultCode 200/210.
- 옵션·카테고리·인증·고시는 GET 전문에 있는 원값 보존(재구성 방식의 소실 문제 해결). buildProductXml은 publish(등록) 전용으로 잔존.
- 테스트: Elevenst 2개 재작성(GET 라운드트립·주소코드 주입·메타제거·resultCode 성공판정). 잔여: 인터페이스의 Product 인자는 이제 미사용(GET 방식) — 정리 후보.

### D-092 나머지 마켓 buying-agent 대응 (2026-07-20)
- **쿠팡: 완료**(사용자 확인 — 대표이미지+상세설명 성공). requested=true 수정으로 해결.
- **N스토어(스토어): buying-agent 네이버 이미지 업로드 이식**. "올바른 이미지 파일이 아닙니다"(400)=Naver가 외부 R2 URL 거부 → `SmartstoreRestClient.uploadImages`(멀티파트 POST /v1/product-images/upload) 추가, `SmartstoreMarketClient`에 downloadImage/ensureImageExtension/uploadImagesToNaver + customsTaxType=INCLUDED 주입 + detailContent 이중이스케이프 제거. 네이버 업로드 URL로 representativeImage 등록. 실패 시 외부 URL 폴백.
- **옥션(Cafe24): 코드 이슈 아님**. buying-agent Cafe24 syncImagesAndHtml이 sbshop과 완전 동일(base64 detail/list/tiny/small_image POST). Cafe24 API 호출 성공·G마켓 전파 정상이나 옥션 미전파 = **Cafe24→옥션(ESM+) 연동 설정**(코드 밖). 사용자 Cafe24 마켓연동 이미지동기화 설정 확인 필요.
- **G마켓: 정상**(Cafe24 경로).

---

### D-093: 가격 배치가 사용자 마진/할인/최소마진 정책을 무시 — 하드코딩 15/20/5000이 실가격 지배 (P1)

- 심각도: **P1 (기능 불능 — 사용자가 설정한 가격정책이 실제 판매가에 반영되지 않음)**
- 리스크 등급: **중대** (실 판매가·전 상품 마진에 직접 영향, 다마켓, 스케줄러 행위 변경)
- 조사 계기: 2026-07-21 사용자 신고 — 배한순·안희수·이호엽·강정란 주문건 판매가 이상. "배치로 마진율 10%·할인율 15%·최소마진 3500 설정했는데 반영 안 된 듯".
- 위치:
  - `backend/worker/src/main/java/com/sbshop/agent/worker/scheduler/BatchScheduler.java:34-37` — 매일 05:00 KST 정기 배치가 **`new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000")` 하드코딩** 파라미터로 iHerb 전 상품 재가격.
  - `backend/api/src/main/java/com/sbshop/agent/api/controller/BatchController.java:80-82, 152-154` — 수동 배치도 요청 미지정 시 기본값 **15/20/5000**.
  - 마진·할인·최소마진 정책을 **영속 저장하는 테이블/설정이 없음** (grep: config/policy/@Value 읽기 흔적 0). 사용자의 수동 배치 파라미터는 일회성.
- 근본원인: (1) 스케줄러가 정책 파라미터를 하드코딩 → 사용자가 수동 배치로 10/15/3500을 넣어도 다음 정기/공급사 배치(하루 다회)가 15/20/5000으로 **덮어씀**. (2) 크롤 기반이라 매 실행 소싱가·환율 변동으로 가격이 요동. 동일 상품 277(210116IHB040)이 4일간 74000~79400 사이 진동(sb_process_status 이력 확인).
- 증거(계산 대조, 현재 cost_price 기준 15/20/5000 역산 = 현재 sale_price 정확히 일치):
  - 277: cost31522·bq2 → (31522×0.8×2+0)/0.665=75842→올림100→**75,900** = 현재가 75,900 ✓
  - 245: cost28383·bq1 → (28383×0.8+6000)/0.665=43168→**43,200** = 현재가 43,200 ✓
  - 3006: cost6587·bq2 → (6587×0.8×2+6000)/0.665=24870→**24,900** = 현재가 24,900 ✓
  - 사용자 의도(10/15/3500) 계산값: 277=75,000 / 245=42,200 / 3006=24,100 — 실제가와 전부 불일치.
- 진단 결론: 사용자 질문의 **가설(2) "가격 반영 안 됨"이 정답**. 단 일회성 반영 실패가 아니라 **구조적**: 정책 저장소 부재 + 스케줄러 하드코딩이 매일 사용자 값을 덮어쓰는 것. 주문 4건은 전부 이익 상태(손실 없음)이나, 사용자가 의도한 마진 10%(더 경쟁적 저가)가 아니라 15%로 상시 판매 중.
- 상태: **발견** (수정 미착수 — 올바른 목표동작이 사업 결정 사항: 스케줄러가 사용자 정책을 읽도록 정책 저장소 신설 vs 스케줄러 상수 교체 vs 정책 UI. 사용자 방향 확인 필요)
- 이력: 2026-07-21 발견·확정(리더 직접 진단, 운영 DB read-only + 코드 대조)

---

### D-094: MarginCalculator가 마켓별 수수료(sb_fee_policy)를 무시하고 채널수수료 18.5% 전 마켓 고정 (P2)

- 심각도: P2 (오동작 — 마켓별 실수수료와 괴리, 단일가 산정의 경제성 왜곡)
- 리스크 등급: 표준
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/domain/product/service/MarginCalculator.java:10` — `CHANNEL_FEE_RATE = 18.5` 하드코딩, 전 마켓 동일 적용.
- 근본원인: `sb_fee_policy` 테이블에 마켓별 수수료율이 존재(COUPANG 11%·SMART_STORE 8%·ELEVEN_STREET 18%·GMARKET 18%·AUCTION 18%·CAFE24 18%)하나 `MarginCalculator`는 이를 읽지 않고 18.5% 고정. 쿠팡(11%)·스토어(8%)는 실제보다 높은 수수료를 가정해 과대 산정(보수적이나 경쟁력 저하), 정책 테이블은 사실상 사장(死藏).
- 증거: `SELECT * FROM sb_fee_policy` 6행 존재·ACTIVE. grep 결과 MarketFeeService는 별도 존재하나 가격 산정 경로(MarginCalculator)와 미연결.
- 상태: **발견** (D-093과 연계 — 가격정책 재설계 시 함께 처리 권장. 단, 다마켓 단일 sale_price 구조에서는 마켓별 수수료 반영이 설계 변경을 수반)
- 이력: 2026-07-21 발견(리더 진단 중 파생)

---

### D-093 스케줄러 비활성화 + 계산식 실측 검증 (2026-07-21, 사용자 결정)
- **BatchScheduler @Scheduled 비활성화**(`BatchScheduler.java:22`, 주석 처리 + 사유 명시). 사용자가 정기 자동재가격 존재를 몰랐고, 향후 고도화(매출 급감 대응 등) 시 정책 저장소 연결 후 복원키로 함. 가역·경량. :worker:compileJava 통과. **미배포(push 대기)**.
- **계산식 실측 대조(16개 최근 주문, sourcing_amount 보유)**: 현재 DB 판매가 = 15/20/5000 계산값과 **14/14 정확히 일치** → 계산식(MarginCalculator) 자체는 버그 없이 설계대로 정상. 문제는 순수 파라미터.
- **D-095 파생(계산모델 한계)**: 판매가는 cost_price(크롤 스냅샷)×(1-쿠폰가정)으로 산정하나, 실제 iHerb 매입가(sourcing_amount)가 상품별로 ±20% 편차. 특히 231108IHB098(실매입이 가정보다 +10~15% 초과)·220130IHB051(+7%)은 실현마진 4~12%로 붕괴(정두호 4%·장용/윤현주 8%). minMargin은 '가정매입' 기준이라 이 원가 과소평가를 보호하지 못함. 함의: 마진 10%로 낮추면(=판매가 700~1300 하락) 이런 저마진 상품은 손실 위험 증가. 조치 후보: 만성 저마진 상품 cost_price 갱신 or 마켓별/상품별 마진 차등.

### D-093 소규모 10/15/3500 반영 테스트 (2026-07-21, 배포 후 라이브)
- 스케줄러 비활성화 배포 확인(새 빌드 기동 2026-07-21T03:51:11Z). 조사 3개 상품(277·245·3006)에 crawl-and-update(10/15/3500) 트리거(batchId=dd7490d8).
- 결과 전건 SUCCESS·4마켓 반영(성공4/스킵0/실패0): 277 75900→**75000**, 245 43200→**42200**, 3006 24900→**24100**. margin_rate 15→10 저장. 크롤 원가 불변으로 예측치 정확 일치.
- 사용자에게 마켓 리스팅 실반영 확인 요청 중. 전체 IHB 적용은 D-095(저마진 상품 손실위험) 사용자 판단 후 결정.

### D-094 마켓별 가격 분리 구현·라이브 검증 (2026-07-21, 사용자 승인·TDD)
- 사용자 결정: 18.5%=순수 마켓수수료여야 함 + 마켓별 가격 분리(근본). 기준가(sb_product.sale_price)=쿠팡 기준.
- 구현(TDD, 커밋 20ebf9f): (1) MarginCalculator 채널수수료 파라미터 오버로드(하드코딩 18.5는 하위호환 기본값). (2) ProductMarketSyncService.syncPriceStockPerMarket — 마켓 순회 시 MarketFeeService.feeRate로 마켓별 실수수료를 divisor에 넣어 가격 따로 산정·전송(단일가 경로 보존). (3) BatchPriceStockService 크롤 경로가 PricingInputs로 재료 전달 + 기준가=쿠팡. (4) PricingInputs record 신설.
- 회귀: core 전체+api test BUILD SUCCESSFUL, 전 모듈 컴파일 OK. 신규 테스트: MarginCalculator 6-arg, per-market sync, 배치 경로.
- **라이브 검증(배치 95f77d61, 3상품 10/15/3500)**: 마켓별 저장 payload에서 상이 가격 확인 — 277: 스토어(8%)=65400·11번가/Cafe24(18%)=74500·쿠팡(11%,기준가)=67900. 4마켓 반영 성공. 마켓 실수수료에 맞춰 가격이 실제로 갈라짐 확정.
- 잔여: BatchController 수동배치 기본값(15/20/5000)은 유지 — 사용자 수동배치는 10/15/3500 명시 전달. 사용자 마켓 리스팅 최종 확인 대기.

### D-096: 스마트스토어 판매자 즉시할인 일괄 제거 (2026-07-21, 사용자 승인·TDD)
- 배경: D-094로 마켓별 가격이 스토어 저수수료(8%)에 맞게 낮게 산정됨. 그런데 상품마다 판매자 즉시할인이 별도로 걸려 있어 겹치면 이중할인 손해(사용자 신고). 사용자 결정: 일회성 일괄 제거, 범위=전체 스토어 상품.
- 구현(TDD, 커밋 3d0787b): MarketClient.removeSellerImmediateDiscount(default no-op)+Smartstore override(GET→customerBenefit.immediateDiscountPolicy만 제거→PUT, 적립 등 보존). SmartstoreSellerDiscountRemovalService(순회·집계, 소규모동기/전체비동기). 내부 엔드포인트 POST /internal/smartstore/remove-seller-discount(productIds, dryRun). core+infra+api 회귀 통과.
- 라이브 검증(3상품 277·245·3006): dryRun→즉시할인 확인(277=9%·245=12%·3006=12%, 구조 immediateDiscountPolicy.discountMethod.value/unitType). 실제 제거(removed=3)→재조회 dryRun(skipped=3=할인없음)로 제거 확정. "키 제거+PUT"이 네이버에서 실제 할인 제거함을 확인.
- 잔여: 전체 스토어 3183건 비동기 실행(productIds 미지정, dryRun=false) — 사용자 최종 승인 후. 스토어 상품 수=3183(ACTIVE).

### D-096 전체 실행 결과 + rate limit 복원력 개선 (2026-07-21)
- 전체 3183건 비동기 실행 완료: removed=2160, skipped=4(할인없음), **failed=1019(429 TOO_MANY_REQUESTS)**. 실패는 네이버 API 분당 한도 초과 — throttle 200ms에 GET+PUT 2콜 + 동시 주문동기화(SyncWorker)까지 경합.
- **개선(TDD)**: SmartstoreSellerDiscountRemovalService에 항목별 재시도(최대3회, 백오프 2s·4s) + throttle 200→500ms. throttle/backoff는 세터로 튜닝·테스트 가능. 멱등이라 재실행 시 이미 제거된 건은 스킵(GET만).
- 잔여: 실패 1019건 mop-up — 개선 배포 후 전체 재실행(자가치유). 스킵 GET 비용은 있으나 재시도로 429 대부분 흡수.

### D-097: 쿠팡 반품완료 전방 감지 부재 — 배송완료 주문의 반품이 RETURNED+정산0으로 전환되지 않음 (2026-07-21, 사용자 신고 "김대섭 반품건이 배송완료로 표시")
- 심각도: P1 (오동작 — 반품완료 주문이 배송완료로 표시·정산액 유지)
- 리스크 등급: 중대 (마켓 API 계약 신설 — 쿠팡 returnRequests)
- 위치: `CoupangOrderSyncService.postSyncProcess` / `CoupangOrderAdapter`(반품 조회 경로 자체 부재)
- 증상: 배송완료(DELIVERED) 후 고객이 반품하면 쿠팡이 그 주문을 ordersheet API에서 제거(단건조회 400 "취소 또는 반품"). 그러나 앱에는 쿠팡 returnRequests(반품) API 호출 경로가 없어 반품완료를 학습 못 함. detectCancellations는 DELIVERED를 terminal로 보호(D-027)하므로 absence로도 안 잡힘 → 영구 DELIVERED 고착, 정산액도 유지.
- 라이브 근거: 주문 2101402034506(김대섭, order_id 33/li 264) — DB=DELIVERED·정산 63,724. 쿠팡 returnRequests=receiptId 1799887551 RETURN/RETURNS_COMPLETED(고객변심, 완료확정 2026-07-13). 쿠팡 단건 ordersheet=400 "취소 또는 반품".
- D-027과의 구분: D-027은 "이미 RETURNED인 주문의 오취소 방지"(역방향 보호, 수정완료). D-097은 "DELIVERED→RETURNED 전방 전환 경로 신설"(별개).
- 수정 설계(사용자 승인): 쿠팡 returnRequests API(searchType=timeFrame, ≤7일 창 분할)로 receiptStatus=RETURNS_COMPLETED 확증 → 해당 orderId의 lineItem을 RETURNED+settlement 0+verified 전환. absence 추론 아님(오취소 없음), 멱등, 정산동기화(DELIVERED만 처리)가 RETURNED 스킵해 재부풀지 않음.
- 수정(2026-07-21, TDD, 커밋 bfe9d2c): queryReturns(port/client, 7일 창 분할·nextToken·URI.create 콜론 원본전송) + detectReturns(adapter, RETURN·RETURNS_COMPLETED만 → RETURNED+정산0+verified, 멱등) + postSyncProcess 배선. Red: `CoupangDetectReturnsTest` 4건(완료반품 전환 / 미완료 미전환 / DB무 no-op / 멱등). core+infra+api 회귀 전체 통과.
- 검증(2026-07-21, 라이브): 배포(Started 08:39:53Z) 후 쿠팡 동기화 수동 트리거 → 로그 "쿠팡 반품완료 반영: 6건 RETURNED+정산0 전환", COUPANG_SYNC SUCCESS. DB 확인: 쿠팡 RETURNED 6건(김대섭 2101402034506 포함) 전부 settlement 0.00·verified=t. 김대섭 li 264: DELIVERED/63724 → RETURNED/0.00/t. absence 아닌 원본 확증이라 오취소 0.
- 상태: 검증통과 (2026-07-21 라이브 — 배송완료 주문 반품 전방 감지·정산0 자동교정, 자가치유·멱등)

### D-098: 취소·반품 종결 lineItem 정산0 미처리 (전 마켓, 쿠팡 제외) — 정산액 부풀림 (2026-07-22)
- 심각도: P1 (오동작 — 취소/반품 주문의 정산액이 부풀린 채 유지, 손익 왜곡)
- 리스크 등급: 표준 (다마켓·정산 데이터, 회귀 게이트 필수)
- 위치: 각 마켓 postSyncProcess(SmartStore line 204 빈 껍데기·Elevenst·Cafe24), 쿠팡만 detectReturns가 RETURNED 0 처리(D-097).
- 라이브 근거(DB 실측 2026-07-22): CANCELED/RETURNED인데 정산액 유지 3건 — GMARKET 곽금희(4460696482) RETURNED 26,611 / SMART_STORE 정가영(2026061471696071) CANCELED 78,140 / 이명동(2026061486764551) CANCELED 47,590. 전부 settlement_verified=f.
- 상태 감지 자체는 Cafe24(R*→RETURNED 라이브작동)·스토어(취소 매핑작동)에서 이미 됨 → 공백은 "종결됐는데 정산0으로 안 내림"뿐. 마켓 API 무관, DB 파생 가능.
- 수정 설계: ShippingStatus.isRefundTerminal()(취소·반품=true, 교환은 결제유지라 제외) + 마켓무관 TerminalSettlementService.zeroSettlementForRefunded(marketType) — 환불성 종결 lineItem 정산0+verified(멱등). 각 마켓 postSyncProcess에 배선. 쿠팡도 CANCELED 커버 위해 추가.
- 수정(2026-07-22, TDD, 커밋 7cf213e): 위 설계대로 구현. `TerminalSettlementServiceTest` 5건(RETURNED/CANCELED 0처리, EXCHANGED·DELIVERED 불변, 이미0 멱등). 생성자 파라미터 추가로 깨진 기존 테스트 7파일 목 주입 보정. core+infra+api 회귀 전체 통과.
- 검증(2026-07-22, 라이브): 배포(재시작 23:55:02Z) 후 스토어·Cafe24 동기화 트리거 → 로그 "[GMARKET] 1건·[SMART_STORE] 2건 정산0 정규화". DB 확인: 곽금희 RETURNED 26611→0.00/verified, 정가영 CANCELED 78140→0.00, 이명동 CANCELED 47590→0.00. DB 전체 스캔이라 30일 창 밖(곽금희 06-23)도 교정됨.
- 상태: 검증통과 (2026-07-22 라이브 — 전 마켓 취소·반품 정산0 정규화, 멱등·자가치유)

### D-099: 11번가 클레임(취소/반품/교환) 감지 정밀화 — 상세조회 ordPrdStatNm 활용 (2026-07-22)
- 심각도: P2 (오동작 — 반품/교환을 CANCELED로 뭉뚱그림·오취소 가능)
- 리스크 등급: 표준 (라이브 주문 상태, 회귀 게이트 필수)
- 배경: 11번가는 클레임 목록 조회 REST가 없음(라이브 확정 2026-07-22: claimservice/ordservices 7개 후보 전부 -997, 정상 엔드포인트 complete·orderlistalladdr는 200으로 대조검증). 기존 detectCancellations(D-028)는 4개 진행상태 목록에서 사라진 주문을 무조건 CANCELED 처리 → 반품/교환 미구분, 정상 aged-out 주문 오취소 위험.
- 발견: `claimservice/orderlistalladdr` 단건 상세조회 응답에 ordPrdStat(901)·ordPrdStatNm(구매확정 등) 실재. D-031의 "상태 필드 없음" 결론이 틀림 — 파서가 안 읽었을 뿐.
- 수정 설계: ElevenstStatusMapper.mapClaimStatus(ordPrdStatNm 부분일치: 취소→CANCELED·반품→RETURNED·교환→EXCHANGED, 그외 null) + ElevenstOrderAdapter.resolveClaimStatus(단건조회·매핑) + detectClaims(사라진 non-terminal 주문을 상세조회로 실상태 판정, 클레임 아니면 상태 불변=오취소 방지). 반품·취소는 D-098이 정산0 처리.
- 검증 한계: 현 DB에 11번가 클레임 주문 0건 → 라이브 E2E 불가, 단위테스트로 검증. 실 클레임 발생 시 라이브 확인.
- 수정(2026-07-22, TDD, 커밋 5658431): 위 설계대로 구현. `ElevenstClaimStatusMapperTest` 5건 + `ElevenstDetectCancellationsTest`에 D-099 4건(취소/반품/교환 판정·클레임아님 오취소방지) 추가. core+infra+api 회귀 전체 통과.
- 검증(2026-07-22, 스모크): 배포(재시작 00:44:34Z) 후 11번가 동기화 트리거 → ELEVEN_STREET_SYNC SUCCESS. 새 상세조회 경로가 sync 무결. 현 DB 클레임 0건이라 상태변경 로그 없음(정상). 라이브 E2E(실 클레임 RETURNED 전환)는 실 클레임 발생 시.
- 상태: 검증통과(단위+스모크) — 라이브 E2E는 실 11번가 클레임 발생 대기

### D-103: Cafe24 리프레시 토큰 자동 갱신 구조 결함 — 선제 갱신 스케줄러 부재 (2026-07-23)
- 심각도: P1 (오동작 — 오랜 미사용/트래픽 공백 시 리프레시 토큰 만료→재인증 외 복구 불가, Cafe24 연동 전면 중단)
- 리스크 등급: 중대 (스케줄러 활성화 — 사용자 승인 필수, 획득)
- 근본 원인: 토큰 갱신이 주문동기화 API 트래픽의 부산물로만 발생. `Cafe24TokenManager.getValidAccessToken()`(:49-78)은 액세스 토큰(2h) 만료 임박 때만 doRefresh→리프레시 토큰 회전. Cafe24 전용 토큰 스케줄러 부재. Cafe24 리프레시 토큰은 유효 2주·refresh 때마다 회전/연장 → API 호출 공백 ≥2주면 만료. refresh_token_expires_at 추적도 전무.
- 수정 설계(사용자 승인 "선제 스케줄러만", 스키마 무변경): core에 `Cafe24TokenRefreshPort.refreshProactively()` 포트 신설 → `Cafe24TokenManager`가 구현(리프레시 토큰 보유 시 access 유효 여부 무관 강제 refresh·회전·시한연장, advisory lock 하, 실패 삼킴) → worker `MarketTokenScheduler` 매일 03:00(KST) 호출. 과거 startup 강제 refresh는 2 JVM 경쟁으로 폐지됐으나 현 단일 JVM+lock 하 안전.
- 수정(2026-07-23, TDD): `Cafe24TokenManagerTest`에 선제갱신 3종(강제회전·토큰없으면건너뜀·실패삼킴). :infrastructure:test PASS, 전 모듈 compile PASS, `./gradlew test` 전체 PASS.
- 미해결: 이미 만료된 토큰은 코드 복구 불가 → UI 재인증 필요(즉시 조치). refresh_token_expires_at 추적/만료임박경고는 범위 제외(선택 후속).
- 상태: 수정완료(라이브 검증대기) — 재인증+배포 후 익일 03:00 로그 "Cafe24 선제 토큰 갱신 완료" 확인 대기

> 참고(번호): 같은 세션 2026-07-23 프론트 UI 배치(커밋 f53563a)가 커밋 메시지에서 D-099~D-102 라벨을 느슨히 사용했으나 원장 미등재. 원장 정본 기준 D-099는 11번가 클레임(상단)이며, 본 D-103이 원장상 다음 번호다. 프론트 배치 상세는 working_history/20260723_1001_결과서.md 참조.

### D-104: 통합 주문 관리 인라인 편집 성능 — FE 전체 그리드 재렌더 병목 (2026-07-24)
- 심각도: P2 (사용성 — 구매상태 셀렉트/인라인 편집 시 화면 굼뜸)
- 리스크 등급: 표준 (다중 FE 리팩토링·행위 보존, 회귀 게이트 필수. BE 무변경)
- 근본원인: BE 단건 엔드포인트는 PK 단건 조회+저장으로 빠름(Explore 조사). 굼뜸의 주범은 FE — ①`columns` useMemo가 매 렌더 재생성(`handleUpdate` deps가 매 렌더 새 mutation 객체 참조) ②`rowData` state+`useEffect([data])`로 편집마다 500건 flatMap+setState 이중 렌더 ③`patchLineItemInCache`가 전 주문 객체 재생성 ④행 메모이제이션 부재로 셀 1개 편집에 수천 셀 재렌더.
- 수정 설계(frontend/src/pages/OrderGrid.tsx, 행위 보존): (1)`handleUpdate` deps→`*.mutateAsync`(RQ v5 안정참조)로 `columns` 안정화 (2)`rowData` 제거·`processedData` 단일 useMemo로 `data`에서 직접 평탄화 (3)`rowCacheRef`로 변경 안 된 주문의 행 객체 참조 재사용 (4)`patchLineItemInCache` 참조 보존(해당 주문만 새 객체) (5)`OrderTableRow=React.memo`(비교자: row.original·isSelected·isOrderBoundary·colCount) → 편집 시 변경된 주문의 3행만 재렌더.
- 수정(2026-07-24): 위 5개. `tsc -p tsconfig.app.json --noEmit` PASS, `npm run build` PASS. FE 테스트 러너 부재로 자동 단위테스트 대신 빌드+독립 QA 리뷰 게이트. 수정요지 `_workspace/fixes/D-104.md`.
- BE 후속(미적용·P3): shippingMutation 성공 시 500건 무효화 유지(상태전이 정확성), OrderLineItem.order_id 인덱스, purchaseStatus EXISTS 서브쿼리, 구매상태 액션로그 부가 2쿼리 — 측정 후 개선 여지.
- 검증(2026-07-24, qa-verifier): **PASS**. 회귀 게이트 2종 독립 재실행 — `tsc -p tsconfig.app.json --noEmit` EXIT 0(에러 0), `npm run build` EXIT 0(에러 0, 청크경고는 정보성·기존). 행위 동등성 A~H 전항 보존 확인: 행 순서·개수(flatMap↔forEach 동일), isFirstLineItem(`id!==currentOrderId&&order` ↔ `liIndex===0` 다중 lineItem 케이스까지 동등), totalRowCount/lineItemCount/rowSpan 동일, 메모스킵 안전(getRowId 미설정=인덱스 rowId, `key=row.id`로 위치기준 비교→데이터불변 시 stale핸들러 정확·변동 시 original바뀌어 재렌더), 선택토글/전체선택(isSelected 프롭이 memo 무효화) 정상, 셀가시성/frozen/경계선(colCount 프롭化만 차이·값 동일) 보존, 호버(data-order-id DOM위임 불변) 보존, 낙관패치/롤백(patchLineItemInCache 비매칭 조기반환=참조보존·매칭 출력 동일) 정확. 미검증: FE 러너 부재로 런타임 실동작(재렌더 카운트·클릭·refetch후 선택정합)은 코드논증 대체 → 브라우저 수동확인 권함. 판정서: `_workspace/verify/D-104_perf_verdict.md`.
- 상태: 검증통과 — 커밋 게이트 통과 가능. 라이브 성능 체감은 배포 후 사용자 확인 권장.
