# GET /status — 전체 배치 번호 목록 조회

## 1. 개요

이 API는 지금까지 실행된 모든 작업 묶음(배치)의 번호를 최신 순서로 쭉 뽑아 줍니다. "어떤 배치들이 있었는지" 목록을 화면에 보여주고, 그중 하나를 골라 상세를 들여다볼 수 있게 하는 첫 단계 화면에 쓰입니다.

| 항목 | 내용(쉬운 설명) |
|------|------|
| **Method / URL** | `GET /api/v1/products/batch/status` (파라미터 없음) — 아무 값도 안 넣고 그냥 부릅니다. |
| **목적** | 존재하는 모든 배치의 번호(batchId)를, 최신 것부터(각 배치의 가장 마지막 시작시각 기준 내림차순) 돌려줍니다. |
| **핵심 상태전이** | 없음(그냥 읽어오기만 함). 데이터를 바꾸지 않는 읽기 전용 조회입니다. 서비스 메서드에 트랜잭션 표시는 없습니다. |
| **부수효과** | 없음. DB에서 중복 없이(distinct) 뽑는 쿼리 1번만 돌립니다. |
| **응답** | `200 OK` + 배치 번호 목록(`List<String>`). 배치가 하나도 없으면 빈 목록. |

## 2. 호출 체인

아래는 이 요청이 코드 안에서 어떤 순서로 처리되는지를 보여줍니다. 각 줄 오른쪽은 실제 코드 위치입니다.

```
BatchController.getAllBatchIds()                             api/.../controller/BatchController.java:180-183
  └─ processStatusService.getAllBatchIds()                  core/.../process/ProcessStatusService.java:156-160  @Transactional(readOnly=true)
       └─ repository.findDistinctBatchIds()                 :159
            └─ ProcessStatusRepository.findDistinctBatchIds()  ProcessStatusRepository.java:16-17
                 └─ @Query "select p.batchId from ProcessStatus p group by p.batchId order by max(p.startedAt) desc"
  └─ ResponseEntity.ok(List<String>)                        BatchController.java:182
```

→ 쉽게 말하면: 입구(BatchController)가 서비스에 "모든 배치 번호 줘"라고 요청하고, 서비스는 저장소에 넘깁니다. 저장소는 DB에서 배치 번호를 중복 없이 뽑되, 각 배치의 가장 마지막 시작시각을 기준으로 최신 것이 위로 오게 정렬해서 돌려줍니다. 그 목록을 그대로 200으로 응답합니다.

**파라미터:** 없음.

**응답:** `List<String>` — 배치 번호 문자열 목록입니다. 배치가 하나도 없으면 빈 목록 `[]`(200)이 나갑니다.

## 3. 유스케이스 다이어그램

👉 이 그림은 운영자(또는 화면)가 이 기능으로 전체 배치 번호 목록을 최신순으로 받아오는 관계를 보여줍니다.

```mermaid
flowchart LR
    A([운영자/프론트]):::actor

    subgraph SYS[sbshop 시스템]
      UC1((전체 배치 ID 목록 조회<br/>최신순))
    end

    subgraph DB[PostgreSQL]
      T[(sb_process_status<br/>DB distinct)]
    end

    A --> UC1
    UC1 -- "group by batchId" --> T

    classDef actor fill:#eef,stroke:#66f;
```

## 4. 시퀀스 다이어그램

👉 이 그림은 요청 하나가 입구→서비스→저장소를 거쳐 배치 번호 목록을 받아 돌려주는 과정을 시간 순서로 보여줍니다.

```mermaid
sequenceDiagram
    autonumber
    actor U as 운영자/프론트
    participant C as BatchController
    participant S as ProcessStatusService
    participant R as ProcessStatusRepository
    Note over S: getAllBatchIds 는 @Transactional(readOnly=true)

    U->>C: GET /status
    C->>S: getAllBatchIds()
    S->>R: findDistinctBatchIds()
    Note over R: group by batchId<br/>order by max(startedAt) desc
    R-->>S: List&lt;String&gt; (없으면 빈 목록)
    S-->>C: List&lt;String&gt;
    C-->>U: 200 OK + List&lt;String&gt;
```

## 5. 순서도 (플로우차트)

👉 이 그림은 "배치가 있든 없든 항상 200을 돌려주되, 있으면 최신순 목록·없으면 빈 목록"이 나가는 흐름을 보여줍니다. 여기서는 404를 내지 않습니다.

```mermaid
flowchart TD
    START([GET /status]) --> Q["findDistinctBatchIds<br/>group by batchId, order by max(startedAt) desc"]
    Q --> EMPTY{행 있음?}
    EMPTY -- No --> OKE([200 OK + 빈 배열]):::ok
    EMPTY -- Yes --> OK([200 OK + batchId 목록<br/>최신순]):::ok

    classDef ok fill:#dfd,stroke:#3a3;
```

## 6. 상태 전이표

> **상태 전이 없음(조회 전용).** 데이터를 바꾸지 않고, 별도 상태 검사도 없습니다.

| 진입 조건(쉬운 설명) | 응답 | 비고 |
|-----------|------|------|
| 배치가 하나라도 있을 때 | `200` + 배치 번호 목록(최신순) | 가장 마지막 시작시각 기준 내림차순 정렬 |
| 배치가 하나도 없을 때 | `200` + `[]` | 목록 조회라서 404를 내지 않고 빈 목록으로 응답 |

## 7. 🔎 발견사항

### BATB-5 · 🔵 NOTE — 개수 제한·페이지 나눔 없이 전체 배치 번호를 다 돌려줌(이력이 쌓이면 목록이 끝없이 커짐)
- **무엇이 문제인가:** 이 조회는 조건이나 개수 제한(LIMIT) 없이, DB에 있는 모든 배치 번호를 중복만 없앤 채 전부 돌려줍니다. 예전에는 전체 데이터를 다 읽어와 앱 메모리에서 중복을 없애다가 메모리 부족(OOM)이 났었는데, 지금은 DB에서 직접 중복을 없애는 방식으로 그 문제는 해결했습니다. 다만 "돌려주는 목록 자체의 개수 상한"은 여전히 없습니다.
- **근거:** `ProcessStatusService.java:159` → `findDistinctBatchIds()`(`ProcessStatusRepository.java:16-17`)는 조건·LIMIT 없이 존재하는 모든 distinct batchId 를 반환한다. 주석(:158)은 과거 "전 행 findAll 후 메모리 distinct"의 OOM 을 DB distinct 로 해소했다고 명시하나, 반환 목록 자체의 상한은 없다.
- **왜 문제인가:** 배치 번호는 배치를 돌릴 때마다 새로 생겨(`ProcessStatusService.java:52`) 계속 쌓입니다. 배치 이력이 수만 건까지 쌓이면 응답 목록이 아주 커지고, 화면이 이 목록 전체를 받아 그리는 경우 전송·렌더링 비용이 커집니다. 게다가 오래된 처리상태 줄을 정리하는 규칙(보존정책)이 없으면 sb_process_status 테이블 자체도 끝없이 커집니다.
- **어떻게 고치면 되나:** 최근 N개만 돌려주는 제한(LIMIT)이나 페이지 나눔 파라미터를 추가하고, 오래된 처리상태 줄을 정리하는 보존정책(일정 기간 지나면 삭제/보관)을 도입할지 검토합니다.

## 8. 테스트 커버리지 메모

- 서비스 단위 테스트 존재: `core/.../process/ProcessStatusServiceTest.java` — 이 기능이 제대로 도는지 확인하는 자동 테스트가 이미 있습니다.
  - `getAllBatchIds_usesDistinctQuery_notFindAll`(:141-150) — 전체를 다 읽는 방식이 아니라 DB에서 중복 없이 뽑는 방식을 쓰는지 확인 (F-BATCH-ST1, 메모리 부족 방지)
  - `getAllBatchIds_preservesLatestFirstOrder`(:154-163) — 저장소가 돌려준 최신순 정렬이 그대로 유지되는지 확인 (F-BATCH-ST2)
- **아직 테스트가 없는 부분:** ① 배치가 하나도 없을 때 빈 목록으로 200이 나가는지 확인하는 테스트가 안 보입니다. ② 쿼리의 `group by ... order by max(startedAt) desc` 정렬이 실제 DB에서 제대로 동작하는지 확인하는 저장소 통합 테스트(@DataJpaTest)가 안 보입니다(서비스 테스트는 저장소를 가짜로 대체하므로 쿼리 자체의 정확성은 확인되지 않음).

---
*(쉬운 설명판 · 2026-07-17 재작성)*
*생성: 2026-07-17 · 근거: 현재 워킹트리*
