# 스마트스토어 정기 상태 확인 — Q23·Q24, 2026-09-06

**로컬 구현·검증 완료, 운영 미배포.** 사용자가 Q23 추천안(전체 상품 하루 1회 + 선택 즉시 확인)과 Q24(기존 스마트스토어 상품은 현재 연동된 한 계정에 속함)를 확정했다. [선택 상품 조회 작업](2026-09-06-products-inspection-jobs.md)에 정기 접수와 계정 확인 저장을 연결했다. 이번 작업에서 운영 서버·DB·외부 마켓은 호출하지 않았다.

## 실행 방식

- 정기 시작 시각은 한국시간 매일 오전 3시로 설정했다. 주기는 사용자 확정이며, 시각은 운영 기본값이다. 시작 시각부터 처리하므로 3시에 전체 확인이 끝난다는 뜻은 아니다.
- 기본 60초마다 접수를 확인하고 한 번에 최대 500개씩 기존 조회 큐에 넣는다. 실제 외부 조회는 기존 단일 임대·최소 2초 간격·429 공유 대기·제한된 재시도를 따른다.
- 상품을 선택하면 다음 정기 시각을 기다리지 않고 바로 접수한다. 실제 조회는 큐와 호출 제한을 따르며 대기량에 따라 늦어질 수 있다. 이미 대기·실행 중인 상품은 중복 호출하지 않고 사유와 함께 제외한다.
- 대상은 폐기되지 않은 시스템상품의 **연결 유지 중인 스마트스토어 등록**이다. 이미 확정 해제한 상품은 제외한다. 상품번호가 없으면 제외 사유를 기록한다. 과거 삭제 상품의 재등록은 별도 후속 기능이다.
- 시작할 때 마지막 등록 ID를 고정하고 ID 순으로 접수 위치를 저장한다. 시작 후 추가된 등록은 다음 회차 대상이다. 접수 시점의 활성 연결을 사용하므로 등록 수정·해제와 동시에 실행되는 경우는 기존 작업의 연결 버전 검사로 보호한다.
- 회차 생성, 작업 묶음 생성, 상품별 작업 저장, 접수 위치 이동은 같은 트랜잭션이다. 저장 실패는 접수 위치도 되돌리며 다음 실행에서 재시도한다. 여러 서버가 함께 실행해도 공유 잠금과 날짜 유일 제약으로 같은 날 회차를 중복 생성하지 않는다.
- 전체 대기·실행 항목이 20,000개에 도달하면 접수를 멈추고 여유가 생긴 뒤 저장한 위치에서 계속한다. 전날 정기 작업이 남으면 이를 끝낸 뒤 당일 회차를 시작한다. 장기간 중단 시 과거 누락 날짜를 모두 재생하지 않고 오늘 회차를 만든다. 대량·429 상황에서 하루 안에 전부 완료됨을 보장하지 않는다.

## 계정 확인 근거

`sb_market_inspection_gate`에 확인한 앱 참조, 확인 시각, `USER_Q24_2026-09-06` 근거를 저장한다. `MARKET_INSPECTION_SINGLE_ACCOUNT_CONFIRMED=true`인 첫 작업자 초기화에서 현재 연동된 SELF 앱 참조를 한 번 고정한다. API 자격 정보·토큰을 이 테이블에 저장하지 않는다. Q24 답변은 현재 연동에 대한 확인이며 이후 계정 변경에 대한 포괄 승인이 아니다.

고정한 참조는 자동으로 덮어쓰지 않는다. 이후 앱 참조가 바뀌면 정기 접수를 보류하고 화면에 표시한다. 대기 중인 이전 계정 작업은 기존 계정 일치 검사로 외부 조회/해제를 차단한다. 새 계정으로 수동 조회해도 고정한 계정과 일치하지 않는 404는 `ACCOUNT_REVIEW_REQUIRED`로 남기며 연결을 유지한다. 기존 수동 설정 `MARKET_INSPECTION_VERIFIED_ACCOUNT_REFERENCE`는 고정값이 없는 경우에만 쓰며, 저장한 확인값을 우회하지 않는다.

현재 계정으로 정확한 원상품 번호를 조회한 **HTTP 404 + `NOT_FOUND`**만 계정 확인 근거와 함께 삭제로 판정한다. 범용 404, 인증/권한 오류, 429, 잘못된 응답은 삭제 근거가 아니다. 명시적 `DELETE`·`PROHIBITION` 응답과 판매자 직접 확인은 기존의 별도 근거를 따른다. 일시 품절은 연결을 유지한다.

## 화면과 API

상품 관리의 `마켓 상태 확인·작업`에 스마트스토어 정기 확인 영역을 추가했다. 일정, 계정 보류, 최신 회차의 접수·대기·확인·해제·확인 필요·제외 건수를 표시한다. `처리 종료`는 모든 항목의 시도가 끝났다는 뜻이며, 미확인 결과가 있으면 별도로 강조한다. 필드 동기화 완료를 뜻하지 않는다.

최신 정기 회차의 세부 작업은 최근 20개 작업과 별도로 모두 열 수 있다. 선택한 작업의 SB코드별 결과·실패 사유·재시도는 기존 화면을 사용한다. 정기 현황 또는 세부 목록 조회 실패 시 해당 이전 데이터를 숨기고 오류를 표시한다. 결과 요약은 작업별 반복 DB 조회 대신 한 번의 그룹 집계로 가져온다.

| 읽기 전용 API | 용도 |
| --- | --- |
| `GET /api/v1/products/connection-inspections/daily` | 설정·계정 확인·다음 예정·최신 회차 집계 |
| `GET /api/v1/products/connection-inspections/daily/{id}/batches` | 해당 회차의 모든 세부 작업 요약 |

브라우저에서 읽기 API를 호출한다고 회차를 시작하거나 계정 확인을 승인하지 않는다. 계정 보류 또는 진행 중 회차가 있으면 다음 시작 시각을 확정 표시하지 않는다. 지난 예정 시각이 표시되면 작업자 접수 대기 상태이며 실행 완료 표시가 아니다. 이전 회차의 데이터는 보존하지만 회차 날짜별 탐색·서버 페이지·정리 정책은 후속 범위다.

## 운영 반영 조건과 남은 범위

[편집 DDL](../../backend/docs/ddl/2026-09-06-product-edit-review.sql) → [연결 DDL](../../backend/docs/ddl/2026-09-06-market-connection-lifecycle.sql) → [조회 작업 DDL](../../backend/docs/ddl/2026-09-06-market-inspection-jobs.sql) → [정기 확인 DDL](../../backend/docs/ddl/2026-09-06-market-inspection-daily.sql) 순으로 적용한다. 마지막 DDL은 계정 확인 컬럼·회차 테이블·작업 참조·조회 인덱스를 추가한다. PostgreSQL에서 DDL 실행·실데이터 부하 검증은 아직 하지 않았다.

앱 설정은 `MARKET_INSPECTION_DAILY_ENABLED=true`, `MARKET_INSPECTION_SINGLE_ACCOUNT_CONFIRMED=true`가 기본이다. 정기 접수만 끄면 이미 접수된 작업은 계속 처리한다. 운영 반영에는 [연결 단계의 전환·롤백 조건](2026-09-06-products-connection-lifecycle.md)을 함께 적용해야 한다. 확인 근거·작업·연결 해제 이력은 롤백 때 삭제하지 않는다.

다른 마켓의 자동 판정, 모든 API 호출의 제한 공유, 외부 쓰기 동기화·결과 검증·선택 재등록은 이번 범위에 포함되지 않는다. 기존 외부 쓰기의 진행 중 요청까지 조회 임대로 보호하는 것은 아니다.

## 검증

- 백엔드 173건 통과: Core 40, API 12, Infrastructure 120, Worker 1. 실패·오류·건너뜀 0. 이전 164건 범위에 정기 접수 H2 통합 7건과 API 2건을 추가했다.
- 날짜 경계·늦은 시작·두 페이지 이어받기·시작 후 추가 등록·전날 미완료 우선·같은 날 재실행 방지·동시 접수·저장 실패 원자성·큐 포화 후 재개·제외 대상·미확인 집계·계정 고정/변경을 확인했다. 운영 프로세스 강제 종료 실험 대신 DB에 저장한 위치에서 다음 호출로 복구하는 조건을 검사했다.
- API는 `/daily`가 일반 작업 ID 라우트와 구분되는지, 조회만 수행하는지, 세부 목록과 404를 확인했다.
- TypeScript, 변경 파일 ESLint, Vite 빌드, 변경 Java 96개 한정 Spotless 검사 통과. 기존 큰 번들 경고는 남아 있다.
- Chrome 실제 React + 로컬 가상 API: 일정·1,002건 집계·미확인 강조, 정기 현황 조회 실패 시 이전 표시 제거, 계정 변경 보류, 세부 목록 실패/회복, 최근 20개 밖의 정기 세부 작업 열기와 읽기 전용 동작을 검사했다. 이전 선택 접수/실패 재접수의 응답 유실·동일 키·429 표시 검증도 함께 통과했다. `data-browser-checks="passed"`.
- 브라우저 자료: `/private/tmp/sbshop-connection-browser/source-harness.tsx`, `result.html`, `preview.png`. 이전 선택 단계 자료는 `selected-`, 연결 단계는 `lifecycle-` 접두사로 보존했다.

검증 명령(`backend`):

```sh
./gradlew :core:test --tests '*MarketInspectionServiceIntegrationTest' --tests '*ProductEditServiceIntegrationTest' --tests '*MarketConnectionWriteGuardTest' :api:test --tests '*MarketInspectionControllerTest' --tests '*DailyMarketInspectionControllerTest' --tests '*MarketConnectionControllerTest' :infrastructure:test --tests '*Smartstore*Test' --tests '*InspectionRetryAfterTest' :worker:test --tests '*MarketInspectionSchedulingConfigTest' --offline
```
