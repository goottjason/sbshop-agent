# bugs-api — api 모듈 리팩토링 중 발견한 버그·백로그

대상: `backend/api/src/{main,test}`. **교리 §5에 따라 어느 것도 수정하지 않았다 — 기록만.** 라인 번호는 리팩토링 **후** 기준.

## TODO 백로그

없음. api 영역 전체에 `TODO`/`FIXME`/`XXX`/`HACK`/`@Deprecated` 마커가 하나도 없었다 (`grep -rn` 확인).

---

## B-API-1 · `Map.of(...)`에 null 메시지가 들어가면 예외 핸들러 자체가 NPE로 터진다 (심각도: 중)

- **위치**: `backend/api/src/main/java/com/sbshop/agent/api/exception/GlobalExceptionHandler.java`
  - `handleNotFound` (:31) — `"message", e.getMessage()`
  - `handleIllegalState` (:39) — `"message", e.getMessage()`
  - `handleIllegalArgument` (:47) — `"message", e.getMessage()`
- **증상**: `Map.of`는 **null 값을 허용하지 않고 NPE를 던진다.** 세 핸들러 모두 `e.getMessage()`를 가공 없이 값으로 넣는데, 메시지 없이 던져진 예외(`new IllegalArgumentException()`, 라이브러리가 메시지 없이 던지는 `IllegalStateException`, 원인만 감싼 래핑 예외 등)에서는 `getMessage()`가 null이다. 그러면 **핸들러 실행 중 NPE가 나면서** 의도한 400/404 대신 컨테이너 기본 에러 응답(500, 프로젝트 JSON 계약과 다른 형태)이 나간다.
- **판단 근거**: 같은 파일의 `handleTypeMismatch`(:22-23)와 `handleGeneral`(:59)은 문자열 연결(`"... " + e.getMessage()`)을 쓰기 때문에 null이 `"null"` 문자열이 되어 안전하다. 즉 **동일 파일 안에서 안전한 패턴과 위험한 패턴이 섞여 있다** — 의도된 차이로 보기 어렵다.
- **영향 범위**: 프로젝트 규약상 컨트롤러 진입부 검증은 전부 `IllegalArgumentException`을 던져 400으로 매핑된다(`ProductControllerInputValidationTest` 참조). 현재 컨트롤러들은 모두 메시지를 붙여 던지므로 실제 노출은 없지만, 메시지 없는 예외가 서비스/도메인 계층에서 올라오는 순간 400이 조용히 500으로 바뀐다.
- **수정 방향(제안, 미적용)**: `Objects.requireNonNullElse(e.getMessage(), "<기본 문구>")` 또는 `HashMap`/`LinkedHashMap` 사용. 회귀 테스트는 `GlobalExceptionHandlerTest`에 "메시지 없는 예외" 케이스 추가.

## B-API-2 · 재고 동기화 트리거가 실패해도 FAILED 활동로그를 남기지 않아 STARTED가 영구히 매달린다 (심각도: 중)

- **위치**: `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductSyncController.java:37-47` (`syncAllProductStock`)
- **증상**: STARTED를 먼저 기록(:37-38)한 뒤 `productSyncService.syncStockForPreparingOrdersAsync()`를 호출하는데, `catch (Exception e)` 블록(:44)이 500 응답만 돌려주고 **FAILED 활동로그를 남기지 않는다.** `@Async` 디스패치 자체가 실패하면 async 본문도 안 돌고 서비스의 완료 기록도 없으므로, 활동로그에는 STARTED만 남고 영원히 완결되지 않는다.
- **판단 근거**: 같은 프로젝트의 `OrderSyncController`는 동일 구조의 5개 트리거 전부에서 `catch` 블록에 FAILED 기록을 넣어 두었다(F-SYNC-3, `OrderSyncControllerActionLogTest`가 이를 단언). `ProductSyncController`만 그 규율이 빠져 있어 **의도적 예외라기보다 누락**으로 보인다.
- **참고**: 403(내부 토큰 불일치) 경로는 STARTED 기록 **전에** 반환하므로 이 문제와 무관하다.

## B-API-3 · `/api/admin/**`가 무인증 공개라 Cafe24 리프레시 토큰을 누구나 덮어쓸 수 있다 (심각도: 중, 보안)

- **위치**: `backend/api/src/main/java/com/sbshop/agent/api/security/SecurityConfig.java:44` (`.requestMatchers("/api/admin/**").permitAll()`)
- **증상**: `Cafe24AuthController`는 `@RequestMapping("/api/admin/sync/cafe24")`이고 그 아래 `POST /issue-token`이 **인가코드를 받아 리프레시 토큰을 발급·저장**한다. 이 경로가 `permitAll`이므로 인증 없이 호출 가능하다. 임의의 code를 던져 저장된 토큰 상태를 교란하거나, `GET /status`로 연동 상태를 무인증 조회할 수 있다.
- **판단 근거**: 바로 위 줄(:42)에서 마켓 크레덴셜은 "시크릿 평문이 노출되므로" 명시적으로 `authenticated()`로 보호하고 있다. 즉 이 파일은 **자격증명 성격의 엔드포인트는 보호한다는 원칙**을 이미 갖고 있는데, 동급의 OAuth 토큰 발급 경로만 그 원칙 밖에 있다. `/internal/**`처럼 별도 가드(`InternalAccessGuard`)가 걸린 것도 아니다.
- **비고**: 사용자 우선순위상 보안 비중요로 분류돼 있으므로 즉시 조치 대상은 아닐 수 있으나, "무인증인 이유"가 코드·주석 어디에도 없어 의도인지 누락인지 판별 불가 — 확인 필요.

## B-API-4 · `toUpperCase()`가 로케일 의존이라 특정 로케일에서 소싱업체 코드 해석이 깨진다 (심각도: 하)

- **위치**: `backend/api/src/main/java/com/sbshop/agent/api/controller/BatchController.java:112` — `VendorType.valueOf(request.supplierCode().toUpperCase())`
- **증상**: 인자 없는 `toUpperCase()`는 기본 로케일을 쓴다. 터키어 로케일(`tr-TR`)에서 `"iherb".toUpperCase()`는 `"IHERB"`가 아니라 `"İHERB"`가 되어 `VendorType.valueOf`가 `IllegalArgumentException`(→400)을 던진다. 컨테이너 로케일 설정이 바뀌면 정상 요청이 거부된다.
- **판단 근거**: 잘못된 코드에 대한 400 자체는 의도된 동작이므로 겉으로는 정상처럼 보이나, **정상 코드가 거부되는** 경로가 열려 있다. `Locale.ROOT` 명시가 정석.

## B-API-5 · 배치 트리거 4종의 응답 키셋이 여전히 비대칭이다 (심각도: 하, 계약 일관성)

- **위치**: `backend/api/src/main/java/com/sbshop/agent/api/controller/BatchController.java`
  - `:67` `/crawl-and-update` → `{batchId, message}`
  - `:82` `/manual-update-price-stock` → `{batchId, message}`
  - `:103` `/manual-update-all` → `{batchId, message}`
  - `:115`, `:130` `/by-supplier` → `{batchId, count, message}`
- **증상**: `/by-supplier`만 `count`를 포함한다. F-BATCH-B2는 **`/by-supplier` 내부의** 0건 케이스와 정상 케이스 키셋을 통일했을 뿐, 4개 트리거 사이의 비대칭은 남아 있다. 클라이언트가 엔드포인트별로 `count` 유무를 알아야 한다.
- **판단 근거**: 행위 변경이라 이번 캠페인 범위 밖. 계약 변경이므로 프론트 소비부 확인이 선행돼야 한다.

---

## 백로그 (구조 — 이번 캠페인에서 손대지 않음)

### BL-API-1 · 계층 위반: `Cafe24AuthController` → `infrastructure` 직접 의존

- **위치**: `backend/api/src/main/java/com/sbshop/agent/api/controller/Cafe24AuthController.java` — `com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager`, `...cafe24.client.Cafe24RestClient` 직접 import.
- **내용**: `api` 모듈의 다른 컨트롤러는 전부 `core`의 서비스/유스케이스를 경유하는데 이 컨트롤러만 `infrastructure`를 직접 뚫는다(Cafe24 OAuth 콜백/관리자 수동 동기화 트리거용). `api/build.gradle`이 `project(':infrastructure')`를 선언하고 있어 컴파일은 되지만, 레이어드 아키텍처상 core 포트를 우회하는 **유일한** 사례다.
- **왜 미조치**: 해소하려면 core에 OAuth 트리거 포트를 신설해야 하는 **구조 변경 + 행위 이동**이라, "구조 변경만·행위 변경 금지" 교리 안에서 안전하게 처리할 수 없다. 리더 지시대로 재배치하지 않고 백로그로만 남긴다.
- **참고**: `api → worker` 의존은 위반이 아니다. CLAUDE.md에 문서화된 현재 배포 토폴로지(worker가 api JVM에 라이브러리로 통합)를 그대로 반영한 정상 상태다.

### BL-API-2 · `api/config/AsyncConfig`는 빈이 없는 껍데기 (정리 후보, D-011)

- **위치**: `backend/api/src/main/java/com/sbshop/agent/api/config/AsyncConfig.java`
- **내용**: `productBatchExecutor` 빈이 core `AsyncConfig`로 이전(D-011)된 뒤 남은 빈 클래스다. `@Configuration("apiAsyncConfig")` + `@EnableAsync`만 갖는다. 원장에 이미 "정리 후보"로 등재돼 있다.
- **왜 미조치**: `@EnableAsync`가 실제로 api 컨텍스트의 비동기 활성화를 담당하는지, 아니면 core 쪽이 이미 켜고 있는지 검증이 필요하다(잘못 지우면 `@Async`가 조용히 동기 실행된다 — `SourcingDiscoveryRunner` salvage 항목 참조). 또 D-009 회귀 테스트(`AsyncConfigBeanNameConflictTest`)가 빈 이름 `apiAsyncConfig`에 묶여 있어 함께 판단해야 한다. **삭제는 행위 변경 위험이 있어 교리 §4의 "고신뢰 데드코드"에 해당하지 않는다.**
- **주의**: 이 클래스를 남겨두는 근거는 원래 클래스 Javadoc에만 있었다 → `salvage-api.md`에 보존했다. 근거를 모르는 사람이 "빈 클래스네" 하고 지우지 않도록.

### BL-API-3 · `requireNonNegative` 헬퍼가 두 컨트롤러에 중복

- **위치**: `OrderController.java:283` (`BigDecimal`), `ProductController.java:291`(`BigDecimal`)·`:297`(`Integer`)
- **내용**: 음수 금액/수량 거부 로직(F-PROD-8/23·F-S4·F-PSRC-11의 `signum() < 0` 패턴)이 같은 이름·같은 동작으로 두 곳에 있다. `ProductSourcingController`의 `costPrice` 음수 검증도 같은 패턴을 인라인으로 반복한다.
- **왜 미조치**: 공통 검증 유틸 추출은 클래스 신설을 동반하는 구조 변경이고, 담당 영역(api) 밖의 공용 위치(core) 선택이 필요해 이번 범위 밖으로 뒀다.

---

## 데드 의심 (미참조 public — 삭제하지 않고 보고만)

전수 조사 결과 **실제 데드 의심 항목은 없다.** `grep -ra`로 `api/src/main`의 모든 public 클래스를 전 코드베이스(backend 전체 + frontend/src)에 대조한 결과 무참조로 나온 것은 아래 3개뿐이고, 전부 교리 §4가 삭제 금지로 지정한 스프링 진입점이다.

| 클래스 | 무참조인 이유 |
|---|---|
| `ApiApplication` | `@SpringBootApplication` 부트 진입점 |
| `SecurityConfig` | `@Configuration` — 컴포넌트 스캔으로만 로드 |
| `CommonCodeController` | `@RestController` — HTTP 라우팅으로만 진입 |

미사용 private 멤버도 정밀 스캔(정규식 + 파일 내 참조 카운트) 결과 0건이다. 초기 스캔에서 `UpdatePurchaseStatusRequest.purchaseStatus`와 `OrderShipRequest.orderIds`가 걸렸으나, 둘 다 Lombok(`@Getter`/`@Data`)이 접근자를 생성하고 Jackson이 역직렬화에 쓰는 필드라 교리 §4의 "Jackson 직렬화 DTO accessor" 예외에 해당한다 — **삭제하지 않았다.**
