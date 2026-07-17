# GET /suppliers — 공급사 목록 조회

## 1. 개요

이 기능은 "우리와 거래하는 공급사(물건을 대주는 업체) 목록을 보여주는" 화면입니다. 단, 이미 지운(보관/삭제 처리한) 공급사는 빼고, 지금 살아있는(사용 중인) 공급사만 코드 순서대로 정렬해서 돌려줍니다. 데이터를 바꾸지 않고 읽기만 하는 조회 기능입니다.

| 항목 | 내용 |
|------|------|
| **Method / URL** | `GET /api/v1/suppliers` |
| **목적** | 지워진(ARCHIVED/DELETED) 공급사는 빼고, 사용 중(`ACTIVE`)인 공급사만 `supplierCode`(공급사 코드) 오름차순으로 골라 화면용 데이터로 돌려줍니다. |
| **핵심 상태전이** | 없음 (그냥 보기만 하는 조회) |
| **부수효과** | 없음. 클래스 전체가 "읽기 전용"(`@Transactional(readOnly = true)`)으로 묶여 있어 아무것도 바꾸지 않습니다. |
| **응답** | `200 OK` + 공급사 목록(`List<SupplierResponse>`). 기본 정보만 담고, 통화 정보는 일부러 빼서 내보냅니다(LAZY currency 미노출). |

## 2. 호출 체인

아래는 요청이 들어와서 응답이 나가기까지 코드가 거치는 순서입니다.

```
SupplierController.getSuppliers()                          api/.../controller/SupplierController.java:36-39
  └─ SupplierService.getSuppliers()                        core/.../application/supplier/SupplierService.java:25-29   (클래스 @Transactional(readOnly=true) :19)
       └─ SupplierRepository.findByStatusOrderBySupplierCodeAsc(ACTIVE)
                                                            core/.../domain/supplier/repository/SupplierRepository.java:17
  └─ stream().map(SupplierResponse::from).toList()         api/.../controller/SupplierController.java:38
       └─ SupplierResponse.from(Supplier)                  api/.../dto/supplier/SupplierResponse.java:19-27
```

→ 쉽게 말하면: ① 화면(컨트롤러)이 요청을 받아 ② 담당 로직(서비스)에게 넘기고 ③ 서비스가 DB 저장소에 "사용 중인 공급사만 코드 순으로 뽑아줘"라고 물어봅니다. ④ 돌아온 공급사들을 화면용 형식(응답 DTO)으로 하나씩 바꿔 담아 ⑤ 사용자에게 목록으로 돌려줍니다.

**응답 DTO (`SupplierResponse`, `SupplierResponse.java:11-17`)** — 화면으로 내보내는 각 공급사의 항목입니다.

| 필드 | 타입 | 출처 | 비고 |
|------|------|------|------|
| `id` | Long | `Supplier.getId()` | 공급사 고유 번호(기본 키) |
| `supplierCode` | String | `Supplier.getSupplierCode()` | 공급사 코드. 중복 불가, 최대 10자 |
| `supplierName` | String | `Supplier.getSupplierName()` | 공급사 이름, 최대 100자 |
| `status` | RecordStatus | `Supplier.getStatus()` | 사용 상태. 조회 조건상 항상 ACTIVE(사용 중)만 나옵니다 |
| `createdAt` | LocalDateTime | BaseEntity | 만든 시각 |
| `updatedAt` | LocalDateTime | BaseEntity | 마지막 수정 시각 |

> 통화 정보(`@ManyToOne currency`, `Supplier.java:26-28`)는 일부러 화면 데이터에서 뺐습니다. 이유는, 실제로 통화 값이 필요한 순간에야 DB에서 꺼내오는 "지연 로딩" 방식인데, 이걸 무심코 내보내면 조회 도중 엉뚱한 시점에 DB를 다시 두드리는 문제가 생길 수 있어서입니다(`SupplierResponse.java:8-9`).

## 3. 유스케이스 다이어그램

👉 이 그림은 운영자가 이 기능으로 무엇을 할 수 있는지(공급사 목록 조회 → 코드 정렬 + 통화 정보 안 새게 매핑)를 한눈에 보여줍니다.

```mermaid
flowchart LR
    A([운영자]):::actor

    subgraph SYS[sbshop 시스템]
      UC1((공급사 목록 조회<br/>ACTIVE만))
      UC2((supplierCode 정렬))
      UC3((DTO 매핑<br/>currency 유출 차단))
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
    participant R as SupplierRepository
    participant M as "SupplierResponse.from"
    Note over S: 클래스 레벨 @Transactional(readOnly=true) — 읽기 전용 경계

    U->>C: GET /api/v1/suppliers
    C->>S: getSuppliers()
    S->>R: findByStatusOrderBySupplierCodeAsc(ACTIVE)
    R-->>S: List&lt;Supplier&gt; (ACTIVE, code 오름차순)
    S-->>C: List&lt;Supplier&gt;
    loop 각 Supplier
        C->>M: from(supplier) (currency 제외)
        M-->>C: SupplierResponse
    end
    C-->>U: 200 OK + List&lt;SupplierResponse&gt;
```

## 5. 순서도 (플로우차트)

👉 이 그림은 이 기능의 처리 흐름(사용 중인 공급사만 코드 순으로 뽑고 → 화면용으로 바꾼 뒤 → 목록으로 응답)을 단계별로 보여줍니다.

```mermaid
flowchart TD
    START([GET /suppliers]) --> Q[findByStatusOrderBySupplierCodeAsc<br/>status=ACTIVE]
    Q --> MAP[각 Supplier → SupplierResponse.from<br/>LAZY currency 제외]
    MAP --> OK([200 OK + List&lt;SupplierResponse&gt;]):::ok

    classDef ok fill:#dfd,stroke:#3a3;
```

> 예외 경로: 따로 상태를 검사하거나 막는 절차는 없습니다. 조회 도중 DB에서 오류가 나면 공통 오류 처리기(`GlobalExceptionHandler.handleGeneral`, `GlobalExceptionHandler.java:52-63`)가 500(서버 오류)으로 응답합니다.

## 6. 상태 전이표

이 기능은 데이터를 바꾸지 않아서 상태가 변하는 게 없습니다.

| 진입 상태 | 허용? | 결과 상태 | 부수효과 | 비고 |
|-----------|:-----:|-----------|----------|------|
| — | — | — | — | **상태가 바뀌지 않음(보기 전용).** 지워진(ARCHIVED/DELETED) 공급사는 조회에서 아예 빠져 화면에 안 나옵니다 |

## 7. 🔎 발견사항

### SUP-1 · 🔵 NOTE — 페이지 나눔·검색 없이 사용 중인 공급사를 전부 한 번에 돌려줌
- **근거:** `SupplierService.java:28` 의 `findByStatusOrderBySupplierCodeAsc(ACTIVE)` 는 사용 중인 공급사를 개수 제한 없이 전부 가져옵니다. "몇 개씩 나눠 보기"(limit/offset)나 검색어 같은 옵션이 없습니다.
- **영향:** 지금은 공급사 수가 적어서 문제없습니다. 다만 다른 목록 화면(예: 활동로그)은 페이지 나눔을 갖추고 있는데 여기만 없어 방식이 다릅니다. 공급사가 크게 늘어나면 응답이 무거워질 수 있습니다.
- **제안:** 지금대로 두어도 무방합니다. 규모가 커질 때를 대비해 "나중에 페이지 나눔을 넣을 수 있다"는 정도만 문서로 남겨두면 됩니다.

## 8. 테스트 커버리지 메모

- **서비스 계층:** `SupplierServiceTest.getSuppliers_returnsOnlyActive`(`SupplierServiceTest.java:157-168`)와 `getSuppliers_delegatesToSupplierCodeOrderedQuery`(:172-182) 가 "사용 중인 공급사만 나오는지"와 "코드 순 정렬 쿼리를 제대로 불러 쓰는지(전체 조회 findAll을 쓰지 않는지)"를 확인합니다.
- **DTO 계약:** `SupplierResponseContractTest.supplierResponseDoesNotExposeDomainTypes`(:25-32)와 `supplierResponseDoesNotLeakLazyCurrency`(:36-44) 가 "내부 도메인 타입이 밖으로 새지 않는지"와 "통화 정보가 실수로 노출되지 않는지"를 확인합니다.
- **비어있는 케이스:** 웹 화면(컨트롤러) 계층을 직접 두드려보는 테스트는 없습니다. 즉 실제 JSON 응답의 필드 순서·정렬을 처음부터 끝까지 확인하는 테스트는 없고, 계약 테스트로 간접적으로만 덮여 있습니다.

---
*(쉬운 설명판 · 2026-07-17 재작성)*
*생성: 2026-07-17 · 근거: 현재 워킹트리*
