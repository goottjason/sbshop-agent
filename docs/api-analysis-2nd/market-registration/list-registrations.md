# GET /markets — 상품의 마켓 등록현황 목록

## 1. 개요

이 기능은 "이 상품을 어느 마켓들에 올려놨는지" 그 목록을 한 번에 보여줍니다. 우리 DB에 저장된 정보만 읽어 오며, 외부 마켓에 전화 걸듯 물어보지는 않습니다(순수 조회).

| 항목 | 내용 |
|------|------|
| **Method / URL** | `GET /api/v1/products/{productId}/markets` |
| **목적** | 특정 상품(`productId`)이 마켓별로 어떻게 등록돼 있는지(`MarketRegistration`) 그 목록을 우리 DB에서 찾아 화면용 형태(응답 DTO)로 돌려줍니다. |
| **핵심 상태전이** | 없음 — 그냥 조회만 하고 아무 것도 바꾸지 않습니다. |
| **부수효과** | 없음. "읽기 전용" 표시(`@Transactional(readOnly = true)`)가 붙은 단순 조회입니다. |
| **응답** | 정상이면 `200 OK` + 등록 목록(`List<MarketRegistrationResponse>`). 상품 자체가 없으면 `404`. |

## 2. 호출 체인

아래는 요청이 들어온 뒤 어떤 코드들이 차례로 불려 일이 처리되는지의 흐름입니다. 각 줄 옆에 어떤 코드 파일의 몇 번째 줄인지도 함께 적었습니다.

```
MarketRegistrationController.getMarketRegistrations()   api/.../controller/MarketRegistrationController.java:26-34
  └─ MarketRegistrationService.getRegistrations(productId)  core/.../application/market/MarketRegistrationService.java:27-31  @Transactional(readOnly=true)
       ├─ ProductReader.findById(productId)                 core/.../product/component/ProductReader.java:11
       │     └─ orElseThrow → ResourceNotFoundException     MarketRegistrationService.java:28-29  (→ 404)
       └─ MarketRegistrationRepository.findByProductId()    core/.../market/repository/MarketRegistrationRepository.java:15
  └─ .stream().map(MarketRegistrationResponse::from)        api/.../controller/MarketRegistrationController.java:30-32
       └─ MarketRegistrationResponse.from(MarketRegistration)  api/.../dto/market/MarketRegistrationResponse.java:30-44
  └─ ResponseEntity.ok(registrations)                       MarketRegistrationController.java:33
```

→ 쉽게 말하면: ① 요청을 받는 입구(컨트롤러)가 서비스에 "이 상품의 마켓 등록 목록 줘"라고 부탁합니다. ② 서비스는 먼저 "그 상품이 실제로 있는지"부터 확인하고(없으면 404로 끝냄), ③ 있으면 DB에서 등록 목록을 꺼내옵니다. ④ 꺼낸 목록을 화면용 형태로 하나씩 갈아 끼운 뒤 ⑤ `200 OK`와 함께 돌려줍니다.

**경로 변수** (URL 주소에 끼워 넣는 값)

| 변수 | 타입 | 필수 | 비고 |
|------|------|:----:|------|
| `productId` | Long | ✅ | 그런 상품이 없으면 `ResourceNotFoundException`이 나면서 404가 됩니다 (`GlobalExceptionHandler.java:28-34`) |

**응답 형태(`MarketRegistrationResponse`, `MarketRegistrationResponse.java:16-28`)** — 우리가 DB에 저장해 둔 등록 정보를 거의 그대로 옮겨 담은 모양입니다. 이 중 `marketIdentifiers`·`marketDetailedInfo` 두 칸은 JSON 덩어리를 손대지 않고 그대로 내보내는데(`@JsonRawValue`), 값이 비었거나 이상하면 안전하게 빈 값(`"{}"`)으로 대체합니다(`MarketRegistration.java:69-77`).

## 3. 유스케이스 다이어그램

👉 이 그림은 "운영자가 이 기능을 쓸 때, 시스템이 속으로 어떤 일들을 함께 처리하는지"를 보여줍니다. 목록 조회 하나를 하면 그 안에 "상품이 있는지 확인"과 "화면용 형태로 바꾸기"가 딸려 붙습니다.

```mermaid
flowchart LR
    A([운영자/프론트]):::actor

    subgraph SYS[sbshop 시스템]
      UC1((상품 마켓 등록현황<br/>목록 조회))
      UC2((상품 존재 검증<br/>404 가드))
      UC3((응답 DTO 변환<br/>raw JSON 방출))
    end

    A --> UC1
    UC1 -. include .-> UC2
    UC1 -. include .-> UC3

    classDef actor fill:#eef,stroke:#66f;
```

## 4. 시퀀스 다이어그램

👉 이 그림은 "요청 하나가 들어오면 각 담당자(컨트롤러·서비스·조회기·DB 등)가 시간 순서대로 주고받는 대화"를 보여줍니다. 상품이 없으면 곧바로 404로 끝나고, 있으면 목록을 꺼내 화면용으로 바꿔 돌려줍니다.

```mermaid
sequenceDiagram
    autonumber
    actor U as 운영자/프론트
    participant C as MarketRegistrationController
    participant S as MarketRegistrationService
    participant PR as ProductReader
    participant R as MarketRegistrationRepository
    participant D as MarketRegistrationResponse
    Note over S: getRegistrations 는 @Transactional(readOnly=true)

    U->>C: GET /products/{id}/markets
    C->>S: getRegistrations(productId)
    S->>PR: findById(productId)
    alt 상품 없음
        S-->>C: throw ResourceNotFoundException
        C-->>U: 404 Not Found
    else 상품 있음
        S->>R: findByProductId(productId)
        R-->>S: List&lt;MarketRegistration&gt;
        S-->>C: List&lt;MarketRegistration&gt;
        C->>D: from(each) 매핑
        C-->>U: 200 OK + List&lt;MarketRegistrationResponse&gt;
    end
```

## 5. 순서도 (플로우차트)

👉 이 그림은 "조건에 따라 어느 길로 가는지"를 갈림길 형태로 보여줍니다. 먼저 상품이 있는지 묻고, 없으면 404, 있으면 목록을 꺼내(0건이면 빈 목록) 화면용으로 바꿔 200으로 응답합니다.

```mermaid
flowchart TD
    START([GET /products/{id}/markets]) --> PF{상품 존재?}
    PF -- No --> NF["throw ResourceNotFoundException<br/>→ 404"]:::warn
    PF -- Yes --> FETCH[findByProductId 조회]
    FETCH --> MAP["MarketRegistrationResponse.from 매핑<br/>(0건이면 빈 리스트)"]
    MAP --> OK([200 OK + List]):::ok

    classDef ok fill:#dfd,stroke:#3a3;
    classDef warn fill:#fe8,stroke:#e90;
```

## 6. 상태 전이표

이 기능은 아무 상태도 바꾸지 않는 순수 조회입니다. 지금 저장돼 있는 등록 목록을 그대로(스냅샷처럼) 보여줄 뿐입니다.

| 진입 조건 | 결과 |
|-----------|------|
| 상품이 아예 없음 | 404 (그런 상품 없음) |
| 상품은 있는데 마켓 등록이 하나도 없음 | 200 + 빈 목록 |
| 상품 있고 등록이 N건 있음 | 200 + N건 목록 |

## 7. 🔎 발견사항

### MREG-1 · 🔵 NOTE — 목록 조회는 상품 존재를 404로 가드하나 로컬 조회(`getLocalData`)는 미가드로 비대칭
- **무엇이 문제인가:** 이 목록 조회 기능은 "그런 상품이 없으면 404(없는 리소스)"라고 확실히 알려 줍니다. 그런데 같은 서비스에 있는 "로컬 단건 조회"(`getLocalData`)는 상품이 실제로 있는지 전혀 확인하지 않습니다. 둘 다 `/products/{productId}/markets/...` 라는 같은 주소 계열 밑에 있는데도 "상품이 없을 때"를 서로 다르게 다룹니다.
- **근거:** `MarketRegistrationService.java:28-29` 는 `productReader.findById` 로 상품 존재를 명시적으로 확인하고 없으면 `ResourceNotFoundException`(404)을 냅니다. 반면 같은 서비스의 `getLocalData`(:33-38)는 상품 존재를 전혀 확인하지 않습니다.
- **왜 문제인가:** 같은 상황(상품이 없음)인데 목록은 404, 로컬 조회는 (등록이 없으면) 400을 돌려줍니다. 그러면 프론트 화면 입장에서 "상품 자체가 없는 건지" 아니면 "상품은 있는데 그 마켓엔 아직 안 올린 건지"를 응답 코드만 봐서는 구분하기 어렵습니다.
- **어떻게 고치면 되나:** 같은 주소 계열 밑의 조회들이 "상품 없음"을 똑같은 방식으로(둘 다 404로, 또는 문서로 약속한 규칙대로) 처리하도록 통일하는 것을 검토합니다.

## 8. 테스트 커버리지 메모

이 기능이 약속대로 동작하는지 확인하는 자동 테스트가 어디까지 있는지를 정리한 것입니다.

- `MarketRegistrationServiceTest`(`core/src/test/.../MarketRegistrationServiceTest.java`)가 서비스의 약속을 다음처럼 확인합니다:
  - `getRegistrations_returnsRepositoryResult`(:41-49) — 상품이 있을 때 DB에서 꺼낸 결과를 그대로 돌려주는지.
  - `getRegistrations_productExistsButNoRegistrations_returnsEmpty`(:51-58) — 등록이 0건이면 빈 목록을 주는지.
  - `getRegistrations_productNotFound_throws`(:60-68) — 상품이 없으면 `ResourceNotFoundException`을 내는지.
- **아직 테스트가 없는 부분:** 입구(컨트롤러) 단계까지 묶어 확인하는 통합 테스트(화면용 형태로 잘 바뀌는지, 404가 실제 HTTP 상태로 잘 나가는지, `@JsonRawValue`로 JSON을 그대로 내보내는 약속이 지켜지는지)는 찾지 못했습니다.

---
*(쉬운 설명판 · 2026-07-17 재작성)*
*생성: 2026-07-17 · 근거: 현재 워킹트리*
