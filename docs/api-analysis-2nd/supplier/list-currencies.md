# GET /currencies — 통화(환율) 목록 조회

## 1. 개요

이 기능은 "우리가 쓰는 통화(화폐)와 각 통화의 환율 목록을 보여주는" 화면입니다. 통화 코드 순서대로 정렬해서, 각 통화의 코드와 환율만 담아 돌려줍니다. 데이터를 바꾸지 않고 읽기만 하는 조회 기능입니다.

| 항목 | 내용 |
|------|------|
| **Method / URL** | `GET /api/v1/currencies` |
| **목적** | 전체 통화를 `currencyCode`(통화 코드) 오름차순으로 정렬해, 코드와 환율(`exchangeRate`)만 담은 화면용 데이터로 돌려줍니다. |
| **핵심 상태전이** | 없음 (그냥 보기만 하는 조회) |
| **부수효과** | 없음. 클래스 전체가 "읽기 전용"(`@Transactional(readOnly = true)`)으로 묶여 있어 아무것도 바꾸지 않습니다. |
| **응답** | `200 OK` + 통화 목록(`List<CurrencyResponse>`) |

## 2. 호출 체인

아래는 요청이 들어와서 응답이 나가기까지 코드가 거치는 순서입니다.

```
SupplierController.getCurrencies()                         api/.../controller/SupplierController.java:58-61
  └─ SupplierService.getCurrencies()                       core/.../application/supplier/SupplierService.java:50-53   (클래스 @Transactional(readOnly=true) :19)
       └─ CurrencyRepository.findAllByOrderByCurrencyCodeAsc()
                                                            core/.../domain/supplier/repository/CurrencyRepository.java:10
  └─ stream().map(CurrencyResponse::from).toList()          api/.../controller/SupplierController.java:60
       └─ CurrencyResponse.from(Currency)                  api/.../dto/supplier/CurrencyResponse.java:14-16
```

→ 쉽게 말하면: ① 화면(컨트롤러)이 요청을 받아 ② 담당 로직(서비스)에게 넘기고 ③ 서비스가 DB 저장소에 "모든 통화를 코드 순으로 뽑아줘"라고 물어봅니다. ④ 돌아온 통화들을 화면용 형식(응답 DTO)으로 하나씩 바꿔 담아 ⑤ 사용자에게 목록으로 돌려줍니다.

**응답 DTO (`CurrencyResponse`, `CurrencyResponse.java:10-12`)** — 화면으로 내보내는 각 통화의 항목입니다.

| 필드 | 타입 | 출처 | 비고 |
|------|------|------|------|
| `currencyCode` | String | `Currency.getCurrencyCode()` | 통화 코드(기본 키), 3자 |
| `exchangeRate` | BigDecimal | `Currency.getExchangeRate()` | 환율. 반드시 값이 있어야 함(nullable=false) |

> `Currency`(`Currency.java:16-28`)는 "지웠음" 표시 필드나 사용 상태(status)가 아예 없는, 단순 참조용 데이터입니다(통화 코드 자체가 기본 키). 그래서 "지운 것 걸러내기" 같은 필터 없이 전량을 그대로 돌려줍니다.

## 3. 유스케이스 다이어그램

👉 이 그림은 운영자가 이 기능으로 무엇을 하는지(통화 목록 조회 → 코드 정렬 + 화면용 매핑)를 한눈에 보여줍니다.

```mermaid
flowchart LR
    A([운영자]):::actor

    subgraph SYS[sbshop 시스템]
      UC1((통화 목록 조회))
      UC2((currencyCode 정렬))
      UC3((DTO 매핑))
    end

    A --> UC1
    UC1 -. include .-> UC2
    UC1 -. include .-> UC3

    classDef actor fill:#eef,stroke:#66f;
```

## 4. 시퀀스 다이어그램

👉 이 그림은 요청 하나가 들어왔을 때 화면·서비스·저장소·변환기가 시간 순서대로 서로 무엇을 주고받는지를 보여줍니다.

```mermaid
sequenceDiagram
    autonumber
    actor U as 운영자
    participant C as SupplierController
    participant S as SupplierService
    participant R as CurrencyRepository
    participant M as "CurrencyResponse.from"
    Note over S: 클래스 레벨 @Transactional(readOnly=true) — 읽기 전용 경계

    U->>C: GET /api/v1/currencies
    C->>S: getCurrencies()
    S->>R: findAllByOrderByCurrencyCodeAsc()
    R-->>S: List&lt;Currency&gt; (code 오름차순)
    S-->>C: List&lt;Currency&gt;
    loop 각 Currency
        C->>M: from(currency)
        M-->>C: CurrencyResponse
    end
    C-->>U: 200 OK + List&lt;CurrencyResponse&gt;
```

## 5. 순서도 (플로우차트)

👉 이 그림은 이 기능의 처리 흐름(모든 통화를 코드 순으로 뽑고 → 화면용으로 바꾼 뒤 → 목록으로 응답)을 단계별로 보여줍니다.

```mermaid
flowchart TD
    START([GET /currencies]) --> Q[findAllByOrderByCurrencyCodeAsc]
    Q --> MAP[각 Currency → CurrencyResponse.from]
    MAP --> OK([200 OK + List&lt;CurrencyResponse&gt;]):::ok

    classDef ok fill:#dfd,stroke:#3a3;
```

> 예외 경로: 따로 상태를 검사하거나 막는 절차는 없습니다. 조회 도중 DB에서 오류가 나면 공통 오류 처리기(`GlobalExceptionHandler.handleGeneral`, `GlobalExceptionHandler.java:52-63`)가 500(서버 오류)으로 응답합니다.

## 6. 상태 전이표

이 기능은 데이터를 바꾸지 않아서 상태가 변하는 게 없습니다.

| 진입 상태 | 허용? | 결과 상태 | 부수효과 | 비고 |
|-----------|:-----:|-----------|----------|------|
| — | — | — | — | **상태가 바뀌지 않음(보기 전용).** `Currency` 는 사용 상태나 "지웠음" 개념이 없는 참조용 데이터 → 전부 그대로 돌려줌 |

## 7. 🔎 발견사항

발견사항 없음.

> 조회만 하고, 정렬은 저장소에 맡기고, 화면용 변환도 깔끔합니다. 억지로 결함을 만들지 않았습니다. (`Currency` 에는 "지웠음" 개념 자체가 없으니, 목록에 필터가 없는 것은 문제가 아닙니다.)

## 8. 테스트 커버리지 메모

- **서비스 계층:** `SupplierServiceTest.getCurrencies_delegatesToCurrencyCodeOrderedQuery`(`SupplierServiceTest.java:186-196`)가 "코드 순 정렬 쿼리를 제대로 불러 쓰는지(전체 조회 findAll 을 쓰지 않는지)"와 돌려주는 목록을 확인합니다.
- **DTO 계약:** `SupplierResponseContractTest.currencyResponsePreservesContract`(:48-55)와 `currencyResponseDoesNotExposeDomainType`(:59-65)가 "`currencyCode`·`exchangeRate` 라는 JSON 형식이 지켜지는지"와 "내부 도메인 타입이 밖으로 새지 않는지"를 확인합니다.
- **비어있는 케이스:** 웹 화면(컨트롤러) 계층을 직접 두드려보는 테스트는 없습니다(계약·서비스 테스트로 간접적으로만 덮여 있음).

---
*(쉬운 설명판 · 2026-07-17 재작성)*
*생성: 2026-07-17 · 근거: 현재 워킹트리*
