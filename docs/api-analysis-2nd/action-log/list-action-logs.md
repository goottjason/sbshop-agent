# GET /action-logs — 사용자 액션 로그 조회

## 1. 개요

| 항목 | 내용 |
|------|------|
| **Method / URL** | `GET /api/v1/action-logs?limit={n}&page={p}` |
| **목적** | 사용자·시스템 액션 로그(ActionLog)를 시간 역순으로 페이지 단위 조회해 평면 JSON 배열로 반환한다. |
| **핵심 상태전이** | 없음(읽기 전용 조회, `@Transactional(readOnly = true)`) |
| **부수효과** | 없음. DB 조회만 수행. |
| **응답** | `200 OK` + `List<ActionLogResponse>` (평면 배열 — 프론트 계약상 Page 엔벌로프 금지) |

**쿼리 파라미터**

| 파라미터 | 타입 | 기본값 | 방어 |
|----------|------|:------:|------|
| `limit` | int | 100 | `<=0 → 100`, 상한 `500`(컨트롤러 L45, 서비스 L60 이중 방어) |
| `page` | int | 0 | `<0 → 0`(컨트롤러 L46, 서비스 L59 이중 방어) |

## 2. 호출 체인

```
ActionLogController.getActionLogs()                    api/.../controller/ActionLogController.java:41-51
  ├─ safeLimit = limit<=0 ? 100 : min(limit,500)       :45   (DEFAULT_LIMIT=100 L26, MAX_LIMIT=500 L27)
  ├─ safePage  = max(page, 0)                           :46
  └─ ActionLogService.recentLogs(safePage, safeLimit)  core/.../actionlog/ActionLogService.java:57-62  @Transactional(readOnly=true)
       ├─ safePage = max(page,0), safeSize = size<=0?100:min(size,500)  :59-60  (재방어)
       └─ ActionLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(...))  core/.../actionlog/repository/ActionLogRepository.java:14
  └─ .map(ActionLogResponse::from).toList()             ActionLogController.java:47-49
       └─ ActionLogResponse.from(ActionLog)             api/.../dto/actionlog/ActionLogResponse.java:17-25
```

**응답 항목 (`ActionLogResponse`, `ActionLogResponse.java:9-15`)**

| 필드 | 타입 | 비고 |
|------|------|------|
| `id` | Long | ActionLog PK |
| `actionType` | String | 액션 유형 코드(예: STOCK_SYNC) |
| `marketType` | String | 마켓 문자열(nullable) |
| `actionStatus` | String | enum `.name()`, null 안전(L22) |
| `message` | String | 최대 1000자(기록 시 truncate) |
| `createdAt` | LocalDateTime | 정렬 키 |

## 3. 유스케이스 다이어그램

```mermaid
flowchart LR
    A([운영자]):::actor

    subgraph SYS[sbshop 시스템]
      UC1(("액션 로그 조회<br/>시간 역순 · 페이지네이션"))
      UC2(("limit/page 방어<br/>상하한 정규화"))
    end

    subgraph DB[PostgreSQL]
      T[("action_log 테이블")]
    end

    A --> UC1
    UC1 -. include .-> UC2
    UC1 -- "findAllByOrderByCreatedAtDesc" --> T

    classDef actor fill:#eef,stroke:#66f;
```

## 4. 시퀀스 다이어그램

```mermaid
sequenceDiagram
    autonumber
    actor U as 운영자
    participant C as ActionLogController
    participant S as ActionLogService
    participant R as ActionLogRepository
    participant DTO as ActionLogResponse
    Note over S: recentLogs 는 @Transactional(readOnly=true)<br/>쓰기 없음 → 롤백 대상 없음

    U->>C: GET /action-logs?limit&page
    C->>C: safeLimit / safePage 정규화 (L45-46)
    C->>S: recentLogs(safePage, safeLimit)
    Note over S: 서비스도 page/size 재방어 (L59-60)
    S->>R: findAllByOrderByCreatedAtDesc(PageRequest)
    R-->>S: List&lt;ActionLog&gt;
    S-->>C: List&lt;ActionLog&gt;
    C->>DTO: from(log) 매핑 (stream)
    DTO-->>C: List&lt;ActionLogResponse&gt;
    C-->>U: 200 OK + 평면 배열
```

## 5. 순서도 (플로우차트)

```mermaid
flowchart TD
    START([GET /action-logs]) --> P1{"limit <= 0?"}
    P1 -- Yes --> L1[safeLimit = 100]
    P1 -- No --> L2["safeLimit = min(limit, 500)"]
    L1 --> P2
    L2 --> P2{"page < 0?"}
    P2 -- Yes --> PG1[safePage = 0]
    P2 -- No --> PG2[safePage = page]
    PG1 --> SVC[recentLogs 재방어 후 조회]
    PG2 --> SVC
    SVC --> Q["findAllByOrderByCreatedAtDesc<br/>PageRequest.of(safePage, safeSize)"]
    Q --> MAP[ActionLogResponse.from 매핑]
    MAP --> OK([200 OK + List]):::ok

    classDef ok fill:#dfd,stroke:#3a3;
```

## 6. 상태 전이표

| 진입 상태 | 허용? | 결과 상태 | 부수효과 | 비고 |
|-----------|:-----:|-----------|----------|------|
| — | — | — | — | **상태 전이 없음(조회)**. 읽기 전용 API로 도메인 상태를 변경하지 않는다. |

## 7. 🔎 발견사항

### MISCA-1 · 🟡 SMELL — `findTop100ByOrderByCreatedAtDesc()` 데드 메서드
- **근거:** `ActionLogRepository.java:11` 에 `findTop100ByOrderByCreatedAtDesc()` 가 선언돼 있으나, 조회 경로(`recentLogs` L61)는 `findAllByOrderByCreatedAtDesc(Pageable)` 만 사용한다. 전체 코드에서 top100 메서드 호출부가 없다.
- **영향:** 동작에는 무해하나 유지보수 시 두 조회 경로가 있는 것처럼 오인될 수 있다.
- **제안:** 미사용 확인 후 제거하거나, 편의 오버로드로 남길 이유가 있으면 주석으로 명시.

### MISCA-2 · 🔵 NOTE — 오프셋 페이지네이션이나 총 개수/다음 페이지 유무 미제공
- **근거:** `ActionLogController.java:41-51` 은 평면 `List` 만 반환(프론트 계약 유지 목적). `recentLogs(page,size)`(`ActionLogService.java:57-62`)는 윈도우만 반환하고 total count·hasNext 를 제공하지 않는다.
- **영향:** 프론트가 페이지 파라미터를 보내더라도 "마지막 페이지 도달" 을 응답만으로 판정할 수 없다(빈 배열/부분 배열로 추정해야 함).
- **제안:** 프론트가 실제 페이지네이션 UI를 도입할 때 total/hasNext 를 헤더나 확장 응답으로 노출 검토. 현재는 의도된 비파괴 설계이므로 NOTE.

### MISCA-3 · 🔵 NOTE — `limit`/`page` 방어가 컨트롤러·서비스에 중복(이중 방어)
- **근거:** 컨트롤러 L45-46 과 서비스 L59-60 이 동일한 상하한 정규화(`<=0→100`, `min(...,500)`, `max(page,0)`)를 각각 수행한다. 주석(L38-39)에 "다중 방어" 로 명시됨.
- **영향:** 상수(100/500)가 두 파일에 흩어져 한쪽만 바뀌면 계약이 어긋날 수 있다.
- **제안:** 의도된 다중 방어이므로 유지하되, 상한/기본값 상수를 단일 출처(예: 도메인 상수)로 공유해 드리프트를 방지.

## 8. 테스트 커버리지 메모

- `ActionLogControllerTest.java` — 평면 배열 반환(프론트 계약), page 전달, 음수 page 방어(→0), limit 상하한 방어 4케이스 검증(`returnsFlatArray`/`passesPageToService`/`clampsNegativePage`/`clampsLimitBounds`).
- `ActionLogServiceTest.java` — record 후 시간 역순 조회, limit 준수, page 오프셋 윈도우, 단일인자=첫 페이지, 잘못된 page/size 방어 5케이스 검증.
- **비어있는 케이스:** ① `findTop100...` 데드 메서드(MISCA-1) 관련 검증 불필요, ② total/hasNext 계약(MISCA-2)은 미도입이라 테스트 없음. 현재 조회 계약은 충실히 커버됨.

---
*생성: 2026-07-17 · 근거: 현재 워킹트리*
