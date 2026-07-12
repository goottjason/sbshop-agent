# SP-F: 관측성 마감 (배치 완료로그 + 진행현황 SSE) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 발행되지만 수신되지 않는 BatchCompletedEvent를 배선해 배치 B1~B4의 SUCCESS/FAILED가 활동로그에 남고 진행현황이 SSE로 자동 갱신되게 한다.

**Architecture:** `BatchCompletedEvent`에 actionType를 추가하고 배치 성공/실패를 항목 실패 카운트로 판정한다. `ActionLogBatchListener`(core, 양 JVM)가 이벤트를 활동로그로 기록하고, `SseNotificationController`(api)가 SSE로 push하며, `ProcessStatusPage`가 EventSource로 구독해 자동 갱신한다. 기존 `ActionLogSyncListener`·SSE emitter 패턴을 재사용한다.

**Tech Stack:** Java 21, Spring Boot 3.5 (core/api), React 19/Vite/TS, JUnit 5 + Mockito + AssertJ.

## Global Constraints

- 배치 성공/실패 판정: 각 @Async 배치 메서드의 per-item catch(이미 `markFailed` 호출)에서 `failCount++`, 종료 시 `success = (failCount == 0)`로 이벤트 발행. 현재 `success=true` 하드코딩 해소(실패 표면화, SP-A 원칙).
- `BatchCompletedEvent`에 `String actionType` 필드 추가 — B1~B4 구분(`ActionLogConstants.BATCH_*`). B1 crawlAndUpdatePriceStock=`BATCH_CRAWL_UPDATE`, B2 manualUpdatePriceStock=`BATCH_MANUAL_UPDATE`, B3 manualUpdateAllFields=`BATCH_MANUAL_UPDATE_ALL`. (B4 by-supplier는 crawlAndUpdatePriceStock 재사용 → BATCH_CRAWL_UPDATE.)
- 리스너는 `core.application.actionlog`(ActionLogSyncListener와 동일 패키지) — 양 JVM 인스턴스화로 활동로그 DB 기록.
- SSE: 이벤트명 `BATCH_COMPLETED`(성공)/`BATCH_FAILED`(실패), data `"{batchId}|{success}"`. 기존 `CopyOnWriteArrayList<SseEmitter>` emitter 재사용.
- ProcessStatusPage: `EventSource('/sbshop-agent/api/v1/notifications/subscribe')`, BATCH 이벤트 시 `loadActionLogs()` 재호출, 언마운트 시 close. OrderGrid onerror/CLOSED 패턴 재사용.
- Dashboard 자동갱신·폴링은 범위 밖. DDL 없음. 신규 의존성 없음.
- 커밋 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: BatchCompletedEvent actionType 추가 + 실패 카운트 발행

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/event/BatchCompletedEvent.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/product/BatchPriceStockService.java` (3개 발행부 `:85, :135, :157` + 각 per-item catch)
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/product/BatchCompletedEventPublishTest.java`

**Interfaces:**
- Produces: `BatchCompletedEvent(Object source, String batchId, String actionType, boolean success, String message)` — `getActionType():String` 추가. 배치 메서드가 항목 실패 시 success=false, actionType 포함해 발행.

- [ ] **Step 1: 이벤트에 actionType 추가**

`BatchCompletedEvent.java` — 필드·생성자·게터 추가:
```java
public class BatchCompletedEvent extends ApplicationEvent {
	private final String batchId;
	private final String actionType;
	private final boolean success;
	private final String message;

	public BatchCompletedEvent(Object source, String batchId, String actionType, boolean success, String message) {
		super(source);
		this.batchId = batchId;
		this.actionType = actionType;
		this.success = success;
		this.message = message;
	}

	public String getBatchId() { return batchId; }
	public String getActionType() { return actionType; }
	public boolean isSuccess() { return success; }
	public String getMessage() { return message; }
}
```

- [ ] **Step 2: 실패 재현 테스트 작성**

`BatchCompletedEventPublishTest.java`: `ApplicationEventPublisher`를 Mockito mock으로 주입한 `BatchPriceStockService`를 구성(다른 의존성도 mock). `manualUpdateAllFields`(구조가 명확 — per-item try에서 `productReader.findById` 사용)로 검증:
```java
// 준비: productIds=[1L, 2L]. productReader.findById(1L)=상품, findById(2L)=Optional.empty() (→ IllegalArgumentException → per-item catch → markFailed + failCount++)
// 실행: service.manualUpdateAllFields("B-1", List.of(1L,2L), List.of(cmd1, cmd2));
// 검증: publishEvent로 발행된 BatchCompletedEvent 캡처(ArgumentCaptor) →
//   actionType == "BATCH_MANUAL_UPDATE_ALL", isSuccess() == false (2L 실패)
// 그리고 전량 성공 케이스: 둘 다 정상 → isSuccess() == true
```
(기존 BatchPriceStockService 테스트(SP-B에서 만든 것 있으면)의 mock 준비 재사용. commands·ProductUpdateCommand 생성은 기존 테스트 패턴 재사용.)

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*BatchCompletedEventPublishTest*'`
Expected: FAIL(컴파일) — 생성자 5-arg 미존재 / 현재 success=true 하드코딩.

- [ ] **Step 4: 3개 발행부 수정 (actionType + failCount)**

`BatchPriceStockService.java`의 3개 @Async 메서드 각각:
- 루프 진입 전 `int failCount = 0;` 선언.
- 기존 per-item `catch (Exception e) { log.error(...); processStatusService.markFailed(...); }` 블록에 `failCount++;` 추가(markFailed 옆).
- 메서드 끝 `eventPublisher.publishEvent(...)` 라인을 actionType·success 반영으로 교체:
  - crawlAndUpdatePriceStock(`:85`):
    ```java
    eventPublisher.publishEvent(new BatchCompletedEvent(this, batchId,
        com.sbshop.agent.core.domain.actionlog.ActionLogConstants.BATCH_CRAWL_UPDATE,
        failCount == 0, failCount == 0 ? "배치 완료" : "배치 완료(실패 " + failCount + "건)"));
    ```
  - manualUpdatePriceStock(`:135`): 동일 형태, actionType `BATCH_MANUAL_UPDATE`, 메시지 "수동 배치 완료…".
  - manualUpdateAllFields(`:157`): 동일 형태, actionType `BATCH_MANUAL_UPDATE_ALL`, 메시지 "전체 필드 배치 완료…".
(import은 FQCN 사용하거나 `ActionLogConstants` import 추가.)

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*BatchCompletedEventPublishTest*'`
Expected: PASS

- [ ] **Step 6: core 회귀 확인**

Run: `cd backend && ./gradlew :core:test`
Expected: 신규 PASS. 기존 BatchCompletedEvent 4-arg 생성자를 쓰던 코드/테스트가 있으면 5-arg로 갱신. pre-existing `SmartStoreOrderFetchFailureTest` 무관.

- [ ] **Step 7: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/product/event/BatchCompletedEvent.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/product/BatchPriceStockService.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/product/BatchCompletedEventPublishTest.java
git commit -m "feat(SP-F): BatchCompletedEvent actionType + 항목 실패 카운트로 success 판정

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 배치 완료 활동로그 리스너 (신규)

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/actionlog/ActionLogBatchListener.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/actionlog/ActionLogBatchListenerTest.java`

**Interfaces:**
- Consumes: `BatchCompletedEvent`(Task 1: getActionType/isSuccess/getMessage/getBatchId), `ActionLogService.record(String actionType, String marketType, ActionStatus, String message)`, `ActionStatus.{SUCCESS,FAILED}`.
- Produces: `ActionLogBatchListener` (@Component) — `@EventListener onBatchCompleted(BatchCompletedEvent)`가 활동로그 기록.

- [ ] **Step 1: 실패 테스트 작성**

`ActionLogBatchListenerTest.java` (ActionLogSyncListener 테스트 있으면 패턴 재사용):
```java
package com.sbshop.agent.core.application.actionlog;

import static org.mockito.Mockito.verify;

import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionLogBatchListenerTest {

	@Mock ActionLogService actionLogService;

	@Test
	@DisplayName("배치 성공 이벤트를 SUCCESS 활동로그로 기록한다")
	void recordsSuccess() {
		var listener = new ActionLogBatchListener(actionLogService);
		listener.onBatchCompleted(new BatchCompletedEvent(this, "B-1", "BATCH_CRAWL_UPDATE", true, "배치 완료"));
		verify(actionLogService).record(
			ArgumentMatchers.eq("BATCH_CRAWL_UPDATE"), ArgumentMatchers.isNull(),
			ArgumentMatchers.eq(ActionStatus.SUCCESS), ArgumentMatchers.contains("B-1"));
	}

	@Test
	@DisplayName("배치 실패 이벤트를 FAILED 활동로그로 기록한다")
	void recordsFailed() {
		var listener = new ActionLogBatchListener(actionLogService);
		listener.onBatchCompleted(new BatchCompletedEvent(this, "B-2", "BATCH_MANUAL_UPDATE", false, "배치 완료(실패 1건)"));
		verify(actionLogService).record(
			ArgumentMatchers.eq("BATCH_MANUAL_UPDATE"), ArgumentMatchers.isNull(),
			ArgumentMatchers.eq(ActionStatus.FAILED), ArgumentMatchers.contains("B-2"));
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ActionLogBatchListenerTest*'`
Expected: FAIL(컴파일) — `ActionLogBatchListener` 미존재.

- [ ] **Step 3: 리스너 구현**

`ActionLogBatchListener.java`:
```java
package com.sbshop.agent.core.application.actionlog;

import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 배치 완료 이벤트를 활동 로그로 자동 기록한다 (SP-F).
 * BatchCompletedEvent는 발행되나 수신처가 없어 배치가 영구 STARTED로만 남던 문제를 해소.
 * core에 두어 api(수동 배치)·worker(스케줄 배치) 양 JVM에서 DB 기록된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionLogBatchListener {

	private final ActionLogService actionLogService;

	@EventListener
	public void onBatchCompleted(BatchCompletedEvent event) {
		ActionStatus status = event.isSuccess() ? ActionStatus.SUCCESS : ActionStatus.FAILED;
		actionLogService.record(event.getActionType(), null, status,
			event.getMessage() + " (batchId=" + event.getBatchId() + ")");
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*ActionLogBatchListenerTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/actionlog/ActionLogBatchListener.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/actionlog/ActionLogBatchListenerTest.java
git commit -m "feat(SP-F): 배치 완료 활동로그 리스너 — BatchCompletedEvent → SUCCESS/FAILED 기록

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: SSE 배치 완료 push

**Files:**
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/controller/SseNotificationController.java`
- Test: `backend/api/src/test/java/com/sbshop/agent/api/controller/SseNotificationBatchTest.java`

**Interfaces:**
- Consumes: `BatchCompletedEvent`(Task 1).
- Produces: `SseNotificationController.onBatchCompleted(BatchCompletedEvent)` — emitter로 `BATCH_COMPLETED`/`BATCH_FAILED` 전송.

> 참고: emitter 리스트는 private. 테스트는 `subscribe()`로 emitter를 등록한 뒤 `onBatchCompleted`를 호출하고, SseEmitter를 spy/mock으로 넣기 어려우므로 — subscribe가 반환한 실제 SseEmitter에 완료 콜백을 걸거나, 리스너가 IOException 없이 예외 없이 수행되는지(전송 시도) 수준으로 검증. 가장 단순: `subscribe()` 후 `onBatchCompleted` 호출이 예외를 던지지 않고, emitter가 제거되지 않는지 확인. 정밀 검증이 필요하면 emitters에 Mockito mock SseEmitter를 리플렉션/패키지-프라이빗 접근으로 주입.

- [ ] **Step 1: 실패 테스트 작성**

`SseNotificationBatchTest.java`: Mockito `SseEmitter` mock을 컨트롤러의 emitters 리스트에 넣고(패키지가 같지 않으면 subscribe()로 실제 emitter 등록 후 spy), `onBatchCompleted(성공 이벤트)` 호출 시 `emitter.send(...)`가 `BATCH_COMPLETED` 이름으로 호출되는지 검증. 실패 이벤트는 `BATCH_FAILED`.
```java
// 간단 버전(리플렉션 없이): SseEmitter mock을 만들고, 컨트롤러의 subscribe()가 아닌
// onBatchCompleted에 직접 emitter를 노출하려면 emitters가 필요.
// 대안: onBatchCompleted가 event 데이터를 올바른 문자열로 만드는 헬퍼를 분리해 순수 함수로 검증.
// 권장: onBatchCompleted 내부의 payload 조립을 private static 헬퍼(batchPayload(event))로 분리하고
//   그 헬퍼를 테스트("B-1|true", "B-2|false")하고, send 호출은 통합 스모크로.
```
구현 시 payload 헬퍼를 분리해 순수 검증:
```java
// SseNotificationController에 추가할 헬퍼(package-private static):
static String batchEventName(boolean success) { return success ? "BATCH_COMPLETED" : "BATCH_FAILED"; }
static String batchPayload(String batchId, boolean success) { return batchId + "|" + success; }
```
테스트:
```java
assertThat(SseNotificationController.batchEventName(true)).isEqualTo("BATCH_COMPLETED");
assertThat(SseNotificationController.batchEventName(false)).isEqualTo("BATCH_FAILED");
assertThat(SseNotificationController.batchPayload("B-1", true)).isEqualTo("B-1|true");
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests '*SseNotificationBatchTest*'`
Expected: FAIL(컴파일) — 헬퍼 미존재.

- [ ] **Step 3: 컨트롤러에 리스너 + 헬퍼 추가**

`SseNotificationController.java`에 추가(기존 onSyncCompleted 아래):
```java
	static String batchEventName(boolean success) {
		return success ? "BATCH_COMPLETED" : "BATCH_FAILED";
	}

	static String batchPayload(String batchId, boolean success) {
		return batchId + "|" + success;
	}

	@EventListener
	public void onBatchCompleted(com.sbshop.agent.core.application.product.event.BatchCompletedEvent event) {
		String name = batchEventName(event.isSuccess());
		String data = batchPayload(event.getBatchId(), event.isSuccess());
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().name(name).data(data));
			} catch (java.io.IOException e) {
				emitters.remove(emitter);
			}
		}
	}
```

- [ ] **Step 4: 테스트 통과 + api 컴파일**

Run: `cd backend && ./gradlew :api:test --tests '*SseNotificationBatchTest*' :api:compileJava`
Expected: PASS / 컴파일 성공.

- [ ] **Step 5: 커밋**

```bash
git add backend/api/src/main/java/com/sbshop/agent/api/controller/SseNotificationController.java \
        backend/api/src/test/java/com/sbshop/agent/api/controller/SseNotificationBatchTest.java
git commit -m "feat(SP-F): SSE로 배치 완료 push (BATCH_COMPLETED/BATCH_FAILED)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: ProcessStatusPage SSE 구독

**Files:**
- Modify: `frontend/src/pages/ProcessStatusPage.tsx`

**Interfaces:**
- Consumes: `POST` 아님 — SSE `GET /sbshop-agent/api/v1/notifications/subscribe`, 이벤트 `BATCH_COMPLETED`/`BATCH_FAILED`(Task 3). 기존 `loadActionLogs`(useCallback).

- [ ] **Step 1: EventSource 구독 useEffect 추가**

`ProcessStatusPage.tsx` — 기존 마운트 useEffect(`loadActionLogs()`, ~라인 116-118) 아래에 SSE 구독 useEffect 추가(OrderGrid 패턴):
```tsx
  useEffect(() => {
    const eventSource = new EventSource('/sbshop-agent/api/v1/notifications/subscribe');
    const onBatch = () => { loadActionLogs(); };
    eventSource.addEventListener('BATCH_COMPLETED', onBatch);
    eventSource.addEventListener('BATCH_FAILED', onBatch);
    // OrderGrid 선례: 연결이 영구 종료(CLOSED)돼도 페이지가 깨지지 않도록 조용히 정리.
    eventSource.onerror = () => {
      if (eventSource.readyState === EventSource.CLOSED) {
        eventSource.close();
      }
    };
    return () => eventSource.close();
  }, [loadActionLogs]);
```
(`loadActionLogs`가 useCallback으로 안정적이면 deps 안전. 아니면 useCallback으로 감싸져 있는지 확인 — 이미 useCallback.)

- [ ] **Step 2: 타입체크 + 빌드**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: tsc 0, build 성공.

- [ ] **Step 3: 수동 스모크(선택)**

`npm run dev` → 진행현황 페이지 열어둔 채 배치 실행 → 활동로그가 새로고침 없이 갱신되는지(SSE 수신) 확인.

- [ ] **Step 4: 커밋**

```bash
git add frontend/src/pages/ProcessStatusPage.tsx
git commit -m "feat(SP-F): 진행현황 페이지 SSE 구독 — 배치 완료 시 활동로그 자동 갱신

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 통합 게이트 + 라이브 확인 준비

**Files:** 없음(검증 전용).

- [ ] **Step 1: 백엔드 전체 게이트**

Run: `cd backend && ./gradlew :core:test --tests '*BatchCompletedEventPublish*' --tests '*ActionLogBatchListener*' :api:test --tests '*SseNotificationBatch*' :api:compileJava`
Expected: SP-F 신규 테스트 PASS, api 컴파일. pre-existing 무관 실패(core `SmartStoreOrderFetchFailureTest`, infra `ImageDownloadServiceCharacterizationTest` SIGABRT)는 `git diff --name-only <base>..HEAD`로 diff 밖임을 확인해 기록.

- [ ] **Step 2: 프론트 게이트**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: 0 / 성공.

- [ ] **Step 3: 라이브 확인 체크리스트 문서화**

배포 후(사용자 허가): 진행현황 페이지 열어둔 채 수동 배치 B1~B4 실행 → 활동로그에 SUCCESS(전량 성공)/FAILED(일부 실패) 기록, 페이지 자동 갱신(새로고침 없이). 일부러 실패 유도(잘못된 productId 등) 시 FAILED·BATCH_FAILED 표면화. worker 스케줄 배치(새벽)는 활동로그만(SSE 없음, 정상).

---

## Self-Review 체크

- **Spec 커버리지:** actionType+실패 발행(Task 1)·활동로그 리스너(Task 2)·SSE push(Task 3)·ProcessStatusPage 구독(Task 4)·게이트(Task 5). Dashboard/폴링 범위 밖. DDL 없음. ✅
- **Placeholder:** 코드/명령/기대출력 구체화. 배치 성공 판정을 항목 실패 카운트로 확정(spec의 "try/catch 감싸" 대비 개선 — per-item catch가 이미 있어 카운트가 정확·테스트가능). SSE 테스트는 payload 순수 헬퍼로 검증(emitter private 회피). ✅
- **타입 일관성:** `BatchCompletedEvent(source, batchId, actionType, success, message)` — Task 1 정의, Task 2 리스너·Task 3 SSE 사용 일치. `getActionType()` 일관. `record(actionType, null, status, msg)` — ActionLogSyncListener와 동일 시그니처. SSE 이벤트명 `BATCH_COMPLETED`/`BATCH_FAILED`·data `"{batchId}|{success}"` — Task 3 정의, Task 4 프론트 addEventListener 일치. ✅
- **미검증 라이브 주의:** 실 SSE 수신·2 JVM 이벤트 경계(수동배치 api JVM에서 SSE 유효, worker 스케줄은 활동로그만)는 Task 5 라이브 확인에 명시.
