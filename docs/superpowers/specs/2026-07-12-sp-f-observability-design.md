# SP-F 설계 — 관측성 마감 (배치 완료로그 + 진행현황 SSE)

- 작성일: 2026-07-12
- 상태: 설계 승인됨 (구현 계획 대기)
- 서브프로젝트: SP-F (로드맵 6순위·마지막 — 도메인 F ~72%)
- 선행: SP-A·B·C·D·E 완료(main `d8deef3`)
- 관련: [[deployment-two-jvm-topology]] (api·worker 2 JVM)

---

## 1. 문제 정의

배치 완료가 이벤트로 발행되지만 수신되지 않아 유실되고, 진행현황 페이지가 자동 갱신되지 않는다.

- **배치 완료로그 부재**: `BatchCompletedEvent{batchId, success, message}`(`core/.../application/product/event/BatchCompletedEvent.java`)가 `BatchPriceStockService`의 3곳(`:85` crawlAndUpdate, `:135` manualUpdate, `:157` manualUpdateAllFields)에서 `ApplicationEventPublisher.publishEvent`로 발행되나, 이를 받는 `@EventListener`가 **코드베이스에 0개**(확정). → 배치 B1~B4가 `BatchController`(`:51,71,90,109`)에서 STARTED 활동로그만 남기고 SUCCESS/FAILED가 안 남음(fire-and-forget).
- **실패 미반영**: 발행 3곳 모두 `success=true` 하드코딩 → 실패 케이스가 이벤트에 안 담김.
- **actionType 부재**: 이벤트에 어느 배치(B1~B4)인지 구분할 필드 없음 → batchId만으로 `ActionLogConstants.BATCH_*` 역매핑 불가.
- **SSE 주문 국한**: `SseNotificationController`(`:52`)가 `SyncCompletedEvent`(주문 동기화)만 `@EventListener`로 받아 push. `BatchCompletedEvent` 미수신. 프론트는 `OrderGrid.tsx:562`만 `EventSource` 구독(재연결 onerror 선례 `:577`), `ProcessStatusPage`·`Dashboard`는 SSE·폴링 없음 → 배치 완료 자동 미반영(수동 새로고침만).

### 프로세스 경계 확인(해소)
- `BatchController`(api)가 `batchPriceStockService.*`를 호출하고 그 메서드는 `@Async("productBatchExecutor")`(`BatchPriceStockService.java:38,88,138`) — **같은 api JVM 스레드풀**에서 실행. 즉 수동 배치 B1~B4는 api JVM에서 돌고, `BatchCompletedEvent` 발행·SSE emitter가 모두 api JVM에 있어 배선이 유효하다.
- 리스너를 `core`에 두면 api·worker 양 JVM에 인스턴스화 → 활동로그 DB 기록은 트리거 경로(수동 api / 스케줄 worker cron)와 무관하게 남는다.
- worker 스케줄 배치(새벽 cron)는 emitter가 api에만 있어 SSE push는 안 되나, 그 시각 관찰자 없음 → 활동로그로 충분. 수동 배치(관찰 중)는 SSE 정상.

### 재사용 선례
- `ActionLogSyncListener`(주문 동기화 완료 `@EventListener`+`ActionLogService.record` 패턴) — 배치용으로 복사.
- `SseNotificationController`의 `CopyOnWriteArrayList<SseEmitter>` push 패턴 — 오버로드 추가로 확장.
- `ActionLogConstants.BATCH_CRAWL_UPDATE/BATCH_MANUAL_UPDATE/BATCH_MANUAL_UPDATE_ALL/BATCH_BY_SUPPLIER`(B1~B4) 상수 기존 존재. `ActionStatus.{STARTED,SUCCESS,FAILED}`.

---

## 2. 목표 & 성공 기준

- 배치 B1~B4의 완료가 SUCCESS(성공)/FAILED(실패)로 활동로그에 남는다(양 JVM).
- 배치 실패가 조용히 삼켜지지 않고 FAILED로 표면화된다.
- 진행현황 페이지가 수동 배치 완료 시 새로고침 없이 SSE로 자동 갱신된다.

---

## 3. 설계 (4개 축)

### 3.1 BatchCompletedEvent 확장 + 실패 발행
- `BatchCompletedEvent`에 **`String actionType` 필드 추가**(B1~B4 구분 — `ActionLogConstants.BATCH_*` 값). 3개 발행부가 각자 actionType 전달.
- **실패 시 success=false 발행**: 각 배치 메서드(@Async)의 본문을 try/catch로 감싸, 예외 시 `new BatchCompletedEvent(this, batchId, actionType, false, "…실패: " + e.getMessage())` 발행(성공 경로는 success=true 유지). 현재 하드코딩 해소.

### 3.2 배치 완료 활동로그 리스너 (신규, core)
- `ActionLogBatchListener`(@Component, `ActionLogSyncListener`와 같은 패키지) — `@EventListener public void onBatchCompleted(BatchCompletedEvent e)`: `actionLogService.record(e.getActionType(), null, e.isSuccess() ? ActionStatus.SUCCESS : ActionStatus.FAILED, e.getMessage() + " (batchId=" + e.getBatchId() + ")")`. core에 두어 양 JVM에서 DB 기록.

### 3.3 SSE 배치 완료 push (api)
- `SseNotificationController`에 `@EventListener public void onBatchCompleted(BatchCompletedEvent e)` 오버로드 추가 — 이벤트명 `BATCH_COMPLETED`(성공)/`BATCH_FAILED`(실패), data `"{batchId}|{success}"`. 기존 emitter 리스트·전송 헬퍼 재사용.

### 3.4 ProcessStatusPage SSE 구독 (프론트)
- `ProcessStatusPage`가 `new EventSource('/sbshop-agent/api/v1/notifications/subscribe')` 구독(OrderGrid 패턴·onerror 재연결 재사용). `BATCH_COMPLETED`/`BATCH_FAILED` 수신 시 `loadActionLogs()` 재호출(현재 조회 중 batchId와 data의 batchId 일치 시 배치 상태도 재조회). 언마운트 시 `EventSource.close()`.

---

## 4. 에러 처리
- 배치 실패 → success=false 이벤트 → 리스너 FAILED 기록 + SSE BATCH_FAILED push(조용한 실패 제거, SP-A 원칙).
- SSE 재연결: OrderGrid의 `onerror`/`readyState===CLOSED` 처리 재사용.

---

## 5. 테스트 전략 (TDD Red→Green)

1. **리스너**: `ActionLogBatchListener.onBatchCompleted`가 success=true → `record(actionType, null, SUCCESS, …)`, success=false → `record(…, FAILED, …)` 호출(Mockito).
2. **실패 발행**: BatchPriceStockService의 배치 메서드가 내부 예외 시 success=false `BatchCompletedEvent`를 발행(ApplicationEventPublisher mock 캡처, 예외 주입).
3. **SSE push**: `SseNotificationController.onBatchCompleted`가 emitter로 BATCH_COMPLETED/FAILED 전송(기존 SSE 테스트 패턴 있으면 재사용, 없으면 emitter mock/spy).
4. **프론트**: ProcessStatusPage가 EventSource 구독·BATCH 이벤트 시 loadActionLogs 재호출·언마운트 시 close. `tsc -p tsconfig.app.json` 0, `npm run build` 0.

로컬 Docker-off: Mockito/이벤트 단위테스트로 커버, 실 SSE·2 JVM 동작은 라이브.

---

## 6. 범위 밖 / 불확실성
- **Dashboard 자동갱신** — 안 함(주문 카운트만 표시, 배치와 무관).
- 폴링 방식 — SSE 채택으로 미사용.
- worker 스케줄 배치의 SSE push — 구조상 안 됨(emitter api 전용). 활동로그로 충분(범위 밖).
- 라이브 검증: 수동 배치 실행 시 SSE 수신·자동갱신, 실패 배치의 FAILED 기록·BATCH_FAILED push.
- DDL 없음.

---

## 7. 영향 파일 (예상)

| 파일 | 변경 |
|---|---|
| `core/.../application/product/event/BatchCompletedEvent.java` | actionType 필드 추가 |
| `core/.../application/product/BatchPriceStockService.java` | 3개 발행부 actionType 전달 + 실패 시 success=false |
| `core/.../application/actionlog/ActionLogBatchListener.java` (신규) | BatchCompletedEvent → 활동로그 |
| `api/.../controller/SseNotificationController.java` | BatchCompletedEvent SSE push 오버로드 |
| `frontend/src/pages/ProcessStatusPage.tsx` | EventSource 구독·BATCH 이벤트 갱신 |
| 신규 테스트 (core/api/frontend) | 위 4축 |

---

## 8. 검증/배포
- 코드 게이트: `:core:test`, `:api:test`, 프론트 `tsc`/`build`.
- 라이브 확인(배포 후, 사용자 허가): 수동 배치 B1~B4 실행 → 활동로그에 SUCCESS/FAILED 기록, 진행현황 페이지 자동 갱신(새로고침 없이). 실패 유도 시 FAILED·BATCH_FAILED 표면화.
- push/배포는 사용자 확인 후.
