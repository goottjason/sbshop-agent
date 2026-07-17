# GET /api/v1/products/{id} — 상품 상세 조회

## 1. 개요

| 항목 | 내용 |
|------|------|
| **Method / URL** | `GET /api/v1/products/{id}` |
| **목적** | 단일 상품을 조회해 도메인 VO를 API 소유 중첩 DTO(`ProductDetailResponse`)로 래핑해 반환한다. |
| **핵심 상태전이** | 없음(조회 전용) |
| **부수효과** | 없음(읽기만). 활동로그·마켓호출·트랜잭션 경계 없음. 미존재 시 404. |
| **응답** | `200 OK` + `ProductDetailResponse` / 미존재 `404` |

## 2. 호출 체인

```
ProductController.getProduct(id)                       api/.../controller/ProductController.java:99-104
  └─ ProductSearchUseCase.getProductDetail(id)         core/.../product/ProductSearchUseCase.java:32-35
       └─ ProductReader.findById(id)                   core/.../product/component/ProductReader.java:11
       │    └─ ProductReaderImpl.findById → ProductRepository.findById  infra/.../repository/product/ProductReaderImpl.java:20-23
       └─ .orElseThrow(ResourceNotFoundException)      ProductSearchUseCase.java:33-34  (→ 404, GlobalExceptionHandler:28-34)
  └─ ProductDetailResponse.from(product)               api/.../dto/product/ProductDetailResponse.java:112-133
       └─ PriceInfoDto/LogisticsInfoDto/ProductSpecDto/SourcingInfoDto.from(vo)  (VO null → DTO null)  :50-109
```

**경로 변수**

| 변수 | 타입 | 필수 | 비고 |
|------|------|------|------|
| `id` | Long | 필수 | 비-숫자 → `MethodArgumentTypeMismatchException` → 400(`GlobalExceptionHandler:19-25`) |

## 3. 유스케이스 다이어그램

```mermaid
flowchart LR
    A([운영자]):::actor

    subgraph SYS[sbshop 시스템]
      UC1((상품 상세 조회))
      UC2((도메인 VO → 응답 DTO 매핑<br/>계약 고정))
    end

    A --> UC1
    UC1 -. include .-> UC2

    classDef actor fill:#eef,stroke:#66f;
```

## 4. 시퀀스 다이어그램

```mermaid
sequenceDiagram
    autonumber
    actor U as 운영자
    participant C as ProductController
    participant S as ProductSearchUseCase
    participant R as ProductReader
    participant D as ProductDetailResponse
    Note over C: 조회 전용 — @Transactional 없음, 롤백 경계 없음

    U->>C: GET /products/{id}
    C->>S: getProductDetail(id)
    S->>R: findById(id)
    R-->>S: Optional&lt;Product&gt;
    alt 미존재
        S-->>C: throw ResourceNotFoundException
        C-->>U: 404
    else 존재
        S-->>C: Product
        C->>D: from(product) (VO→중첩 DTO)
        C-->>U: 200 OK + ProductDetailResponse
    end
```

## 5. 순서도 (플로우차트)

```mermaid
flowchart TD
    START([GET /products/id]) --> PV{id 숫자 파싱?}
    PV -- No --> E400[TypeMismatch → 400]:::warn
    PV -- Yes --> FIND[findById]
    FIND --> EX{존재?}
    EX -- No --> E404["ResourceNotFoundException<br/>→ 404"]:::warn
    EX -- Yes --> MAP["ProductDetailResponse.from<br/>VO null → DTO null"]
    MAP --> OK([200 OK + 상세]):::ok

    classDef ok fill:#dfd,stroke:#3a3;
    classDef warn fill:#fe8,stroke:#e90;
```

## 6. 상태 전이표

상태 전이 없음(조회). 상품을 읽어 반환만 하며 도메인 상태를 변경하지 않는다. 유일한 분기는 존재여부(200 vs 404)이며 이는 상태 전이가 아닌 리소스 유무이다.

## 7. 🔎 발견사항

발견사항 없음. 단순 단건 조회로 404(미존재)·400(비숫자 id) 처리가 명확하고, VO→DTO 매핑이 null-안전(`from`이 VO null 시 DTO null 반환)하다.

## 8. 테스트 커버리지 메모

- `ProductNotFoundExceptionTest.java:63-64` — `getProductDetail: 미존재 id → ResourceNotFoundException(404)` 검증.
- **비어있는 케이스:** ① `ProductDetailResponse.from` VO별 null 매핑(priceInfo/logisticsInfo 등 null인 상품)의 직렬화 형태 검증, ② 비-숫자 `{id}` → 400(TypeMismatch) 통합검증. 조회 특성상 리스크 낮음.

---
*생성: 2026-07-17 · 근거: 현재 워킹트리*
