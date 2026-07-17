# GET /market-credentials/{marketType} — 특정 마켓 한 곳의 로그인 정보 보기

> 쉽게 말하면: "쿠팡"처럼 마켓 하나를 콕 집어서 그 마켓의 로그인 정보(아이디·열쇠 채움 여부)를 한 건만 꺼내 보는 API입니다. 설정 화면을 열 때 그 마켓 칸에 기존 값을 미리 채워 넣는 용도로 씁니다.

## 1. 개요

| 항목 | 내용 |
|------|------|
| **Method / URL** | `GET /api/v1/market-credentials/{marketType}` — 주소 끝의 `{marketType}` 자리에 마켓 이름(정해진 마켓 목록 중 하나)을 넣어 "이 마켓 정보 주세요"라고 요청 |
| **목적** | 지정한 마켓 한 곳의 로그인 정보 1건을 돌려준다. 설정 폼에 기존 값을 미리 채우기 위해 사용. |
| **핵심 상태전이** | 없음. 그냥 읽어서 보여주기만 함. |
| **부수효과** | 없음. 읽기 전용(`@Transactional(readOnly = true)`). |
| **응답** | 있으면 `200 OK` + 그 마켓 정보(비밀 열쇠는 값 대신 채움 여부만), 없으면 `404 Not Found`(그런 정보 없음), 마켓 이름을 잘못 적으면 `400`(잘못된 요청 — 공통 오류 처리기가 응답) |

## 2. 호출 체인

> 아래는 요청이 응답으로 바뀌기까지 코드가 거쳐 가는 순서입니다. "→ 쉽게 말하면"이 각 단계의 실제 의미입니다.

```
MarketCredentialController.getCredential(MarketType marketType)   api/.../controller/MarketCredentialController.java:36-41
  └─ MarketCredentialService.getCredential(marketType)            core/.../application/market/MarketCredentialService.java:27-32  @Transactional(readOnly=true)
       ├─ MarketCredentialRepository.findByMarketType(marketType) core/.../domain/market/repository/MarketCredentialRepository.java:11
       ├─ .map(MarketCredentialDto::fromEntity)                   core/.../application/market/dto/MarketCredentialDto.java:20-31 (시크릿 마스킹)
       └─ .orElse(null)                                           MarketCredentialService.java:31
  └─ dto != null ? ok(dto) : notFound()                           MarketCredentialController.java:40
  (marketType enum 바인딩 실패 시) MethodArgumentTypeMismatchException
       └─ GlobalExceptionHandler.handleTypeMismatch → 400          api/.../exception/GlobalExceptionHandler.java:19-25
```

- `getCredential(MarketType marketType)` (입구 코드) → 쉽게 말하면: 주소에 적힌 마켓 이름을 받아 요청을 처음 받는 창구입니다. 마켓 이름이 정해진 목록에 없는 엉뚱한 값이면 여기 도달하기 전에 걸러집니다.
- `MarketCredentialService.getCredential(marketType)` → 쉽게 말하면: 실제로 그 마켓 정보를 찾아오는 담당자. 읽기 전용이라 아무것도 바꾸지 않습니다.
- `findByMarketType(marketType)` → 쉽게 말하면: 데이터베이스 서랍에서 "이 마켓" 정보를 찾아봅니다. 있을 수도, 없을 수도 있습니다.
- `.map(...fromEntity)` → 쉽게 말하면: 찾았으면 화면에 내보내기 안전한 형태로 바꾸며 비밀 열쇠를 예/아니오로 감춥니다(마스킹).
- `.orElse(null)` → 쉽게 말하면: 못 찾았으면 "없음"을 뜻하는 빈 값으로 처리합니다.
- `dto != null ? ok(dto) : notFound()` → 쉽게 말하면: 찾았으면 성공(200)으로 돌려주고, 없으면 "그런 정보 없음(404)"으로 답합니다.
- `MethodArgumentTypeMismatchException → 400` → 쉽게 말하면: 마켓 이름을 아예 잘못 적었으면(예: 존재하지 않는 `FOO`) 공통 오류 처리기가 "잘못된 요청(400)"으로 깔끔하게 응답합니다. 서버 폭발(500)이 아니라는 뜻입니다.

**주소에 넣는 값**

| 이름 | 타입 | 쉬운 설명 |
|------|------|------|
| `marketType` | MarketType(정해진 마켓 목록) | 정해진 마켓 이름 중 하나여야 함. 목록에 없는 값(예: `FOO`)을 넣으면 공통 오류 처리기가 400(잘못된 요청)으로 응답 |

## 3. 유스케이스 다이어그램

👉 이 그림은 운영자가 "마켓 한 곳의 로그인 정보 조회"를 하면 비밀 열쇠 가리기(마스킹)가 함께 딸려 오고, 정보가 없으면 404·마켓 이름을 잘못 적으면 400으로 갈라진다는 것을 보여줍니다.

```mermaid
flowchart LR
    A([운영자]):::actor

    subgraph SYS[sbshop 시스템]
      UC1((단건 마켓<br/>자격증명 조회))
      UC2((시크릿 마스킹))
      UC3((미존재 → 404<br/>잘못된 enum → 400))
    end

    A --> UC1
    UC1 -. include .-> UC2
    UC1 -. extend .-> UC3

    classDef actor fill:#eef,stroke:#66f;
```

## 4. 시퀀스 다이어그램

👉 이 그림은 두 갈래 흐름을 보여줍니다. 마켓 이름을 잘못 적으면 곧바로 400으로 끝나고, 제대로 적었으면 담당자가 서랍을 뒤져 있으면 200(마스킹된 정보), 없으면 404로 돌려주는 대화 순서입니다.

```mermaid
sequenceDiagram
    autonumber
    actor U as 운영자
    participant C as MarketCredentialController
    participant S as MarketCredentialService
    participant R as MarketCredentialRepository
    participant D as MarketCredentialDto
    participant G as GlobalExceptionHandler
    Note over S: getCredential 는 @Transactional(readOnly=true) — 쓰기·롤백 경계 없음

    U->>C: GET /market-credentials/{marketType}
    alt marketType enum 바인딩 실패
        C-->>G: MethodArgumentTypeMismatchException
        G-->>U: 400 (success:false)
    else 바인딩 성공
        C->>S: getCredential(marketType)
        S->>R: findByMarketType(marketType)
        R-->>S: Optional&lt;MarketCredential&gt;
        alt 존재
            S->>D: fromEntity(credential)
            D-->>S: MarketCredentialDto (마스킹)
            S-->>C: dto
            C-->>U: 200 OK + MarketCredentialDto
        else 없음
            S-->>C: null
            C-->>U: 404 Not Found
        end
    end
```

## 5. 순서도 (플로우차트)

👉 이 그림은 갈림길 두 개를 보여줍니다. 먼저 마켓 이름이 올바른지 판단(틀리면 400), 다음으로 그 마켓 정보가 서랍에 있는지 판단(없으면 404, 있으면 마스킹해서 200)합니다.

```mermaid
flowchart TD
    START([GET /market-credentials/marketType]) --> BIND{enum 바인딩 성공?}
    BIND -- No --> B400["400 (전역 핸들러)"]:::warn
    BIND -- Yes --> FIND[findByMarketType]
    FIND --> EX{존재?}
    EX -- No --> NF([404 Not Found]):::warn
    EX -- Yes --> MAP[fromEntity<br/>시크릿 마스킹]
    MAP --> OK([200 OK + DTO]):::ok

    classDef ok fill:#dfd,stroke:#3a3;
    classDef warn fill:#fe8,stroke:#e90;
```

## 6. 상태 전이표

> 이 API는 보여주기만 하므로 바뀌는 상태가 없습니다.

| 진입 상태 | 허용? | 결과 상태 | 마켓 전송 | 비고 |
|-----------|:-----:|-----------|-----------|------|
| — | — | — | — | 상태 전이 없음(그냥 조회). 읽기 전용이라 아무것도 바뀌거나 마켓으로 나가지 않음. |

## 7. 🔎 발견사항

### CRED-2 · 🔵 NOTE — 마켓 이름을 잘못 적으면 서버 폭발(500)이 아니라 깔끔한 400으로 처리됨(SP-7/F-CRED-4 계약 유지 확인)
- **무엇이 문제인가(사실 확인):** 이건 문제가 아니라 "잘 되고 있다"는 확인 노트입니다. 주소에 마켓 이름을 넣는데, 정해진 목록에 없는 엉뚱한 값을 넣으면 시스템이 이를 알아채 "잘못된 요청(400)"으로 응답합니다. 서버가 예상 못 한 오류(500)로 터지지 않습니다.
- **근거:** `MarketCredentialController.java:37-38`이 `@PathVariable MarketType marketType`을 받고, 미매칭 값은 Spring MVC가 `MethodArgumentTypeMismatchException`을 던진다. `GlobalExceptionHandler.java:19-25`가 이를 `400 + {success:false, message}`로 변환한다. `EnumPathVariableMismatchTest`(`api/.../exception/EnumPathVariableMismatchTest.java:46-53`)가 이 계약을 고정.
- **왜 문제인가(영향):** 잘못된 마켓명 요청이 500이 아니라 400으로 나오니 정상적인 동작입니다. 발견(고칠 것)이 아니라 계약이 잘 지켜지는지 확인한 메모입니다.
- **어떻게 고치면 되나(제안):** 없음(지금 그대로 유지). 새 마켓을 목록에 추가할 때 이 회귀 테스트가 계속 지켜 줍니다.

### CRED-3 · 🔵 NOTE — 로그인 없이 아무나 이 단건 조회를 부를 수 있음(`@CrossOrigin(origins = "*")`)
- **무엇이 문제인가:** 이 단건 조회 창구에도 "누가 요청하는지" 확인하는 문지기(인증·인가)가 없습니다. `@CrossOrigin(origins = "*")` 때문에 어떤 웹 페이지에서든 이 주소로 요청할 수 있습니다.
- **근거:** `MarketCredentialController.java:24` CORS 전체 허용, 인증/인가 애너테이션 부재. 시크릿은 `MarketCredentialDto`(`MarketCredentialDto.java:16-18`)에서 마스킹.
- **왜 문제인가(영향):** 진짜 비밀 열쇠 값은 가려져 나가지 않으므로 유출 위험은 낮습니다. 다만 `clientId`·`redirectUri`와 "연동 여부"는 로그인 없이도 조회됩니다(목록 조회 CRED-1과 같은 성격의 이야기입니다).
- **어떻게 고치면 되나(제안):** 운영 환경에서 접근 허용 범위(CORS)와 인증 정책을 정하고 문서로 남기길 권합니다(list-credentials의 CRED-1과 동일).

## 8. 테스트 커버리지 메모

> 이 기능이 자동 검사(테스트)로 얼마나 지켜지는지 정리한 메모입니다.

- **직접 대상 테스트:** `EnumPathVariableMismatchTest`(`api/.../exception/EnumPathVariableMismatchTest.java`)가 "마켓 이름을 잘못 적으면 400"이 되는지를 실제 요청을 흉내 내(MockMvc) 검증합니다(공통 오류 처리기까지 적용).
- **간접 커버:** `MarketCredentialDtoMaskingTest`가 단건 응답을 조립하는 `fromEntity`에서 비밀 열쇠가 가려지는지를 지켜 줍니다.
- **비어있는(아직 안 만든) 케이스:** ① 실제로 존재하는 마켓을 조회했을 때 200과 함께 필드가 정확히 채워지는지, ② 없는 마켓을 조회했을 때 404가 나오는지(현재 404 경로 `MarketCredentialController.java:40`를 겨냥한 명시 테스트 없음).

---
*(쉬운 설명판 · 2026-07-17 재작성)*
*생성: 2026-07-17 · 근거: 현재 워킹트리*
