# API 흐름 분석 문서 (api-analysis)

> **목적** — 백엔드 API를 엔드포인트 단위로 **유스케이스 다이어그램 · 시퀀스 다이어그램 · 순서도(플로우차트)** 로 시각화하고,
> 그 과정에서 드러나는 **놓친 케이스 · 어색한 로직 · 중복 로직**을 근거와 함께 인벤토리로 남긴다.
>
> `docs/api/` 는 **호출 계약(엔드포인트·파라미터·응답) 레퍼런스**이고,
> 이 폴더(`docs/api-analysis/`)는 **내부 동작 흐름과 결함 진단**에 집중한다. 둘은 상호보완 관계다.

## 폴더 구조

```
docs/api-analysis/
├── README.md                       ← (이 파일) 양식 정의 · 색인 · 범례
├── order/
│   ├── line-items-sourcing.md      ← PATCH /line-items/{lineItemId}/sourcing
│   └── line-items-shipping.md      ← PATCH /line-items/{lineItemId}/shipping
├── product/
└── ...                             ← 도메인(컨트롤러) 단위 하위 폴더, API 1개 = 파일 1개
```

## 문서 양식 (각 API 파일의 고정 섹션)

| # | 섹션 | 내용 |
|---|------|------|
| 1 | **개요** | Method·URL·목적·핵심 상태전이 한 줄 요약 |
| 2 | **호출 체인** | Controller → DTO → Command → Service → Domain/외부포트까지 실제 파일·라인 매핑 |
| 3 | **유스케이스 다이어그램** | 행위자(Actor)·시스템·외부 시스템(마켓)과 이 API가 참여하는 유스케이스 (Mermaid) |
| 4 | **시퀀스 다이어그램** | 요청→응답까지 컴포넌트 간 메시지 흐름, 트랜잭션·롤백 경계 표시 (Mermaid) |
| 5 | **순서도(플로우차트)** | 상태 가드·분기·예외 경로를 포함한 처리 흐름 (Mermaid) |
| 6 | **상태 전이표** | 진입 상태별 허용 여부·결과 상태·부수효과(마켓 전송 등) |
| 7 | **🔎 발견사항** | 놓친 케이스·어색/중복 로직. 각 항목은 `[심각도] 제목 / 근거(파일:라인) / 영향 / 제안` |
| 8 | **테스트 커버리지 메모** | 관련 테스트 존재 여부·검증하는 계약·비어있는 케이스 |

## Mermaid 작성 규칙 (Notion 호환)

Notion의 Mermaid 렌더러는 일부 문법에 취약하므로 다음 규칙을 지킨다.

- **줄바꿈은 `<br/>`** 사용 (`\n` 은 리터럴로 표시되어 렌더 실패).
- **`participant X as 라벨` 선언에 `<br/>` 금지** — 부가정보(`@Transactional` 등)는 `Note over` 로 분리.
- **노드 라벨에 괄호 `()` 가 들어가면 따옴표로 감싼다**: `A["applyData(merge)"]`. `[]`·`{}` 안의 괄호는 파싱 충돌을 일으킬 수 있음.
- 화살표(`→`)·슬래시(`/`)·`@` 는 대체로 허용되나, 불안정하면 라벨을 따옴표로 감싼다.
- 사용 다이어그램 타입: `flowchart`(유스케이스·순서도), `sequenceDiagram`(시퀀스). `classDef` 색상 범례는 순서도에 한해 사용.

## 발견사항 심각도 범례

| 태그 | 의미 |
|------|------|
| 🔴 **BUG** | 데이터 정합·기능 오류로 이어지는 결함. 결함 원장(`docs/normalize/defect-ledger.md`) 등재 후보 |
| 🟠 **GAP** | 처리되지 않은 케이스·검증 누락. 오동작 가능성 있으나 조건부 |
| 🟡 **SMELL** | 중복·죽은 코드·어색한 책임 배치. 동작은 하나 유지보수 위험 |
| 🔵 **NOTE** | 의도된 설계일 수 있으나 문서화가 필요한 지점 / 개선 여지 |

> 문서를 작성하며 나온 발견사항은 [`FINDINGS-CHECKLIST.md`](FINDINGS-CHECKLIST.md) 에 **우선순위별 체크리스트**로 집계한다.
> 실제 수정은 `sbshop-normalize` 스킬 소관 — 이 문서는 **진단·기록만** 한다.

## 발견사항 집계

전체 발견사항은 [`FINDINGS-CHECKLIST.md`](FINDINGS-CHECKLIST.md) 에 우선순위별 체크리스트로 집계되어 있다.

## 색인 (57개 API · 완료)

| 도메인 | 컨트롤러 | 문서 수 | 폴더 |
|--------|----------|:------:|------|
| order | OrderController | 11 | [order/](order/) |
| order-sync | OrderSyncController | 8 | [order-sync/](order-sync/) |
| product | ProductController | 9 | [product/](product/) |
| batch | BatchController | 7 | [batch/](batch/) |
| product-sourcing | ProductSourcingController | 3 | [product-sourcing/](product-sourcing/) |
| supplier | SupplierController | 4 | [supplier/](supplier/) |
| market-credential | MarketCredentialController | 3 | [market-credential/](market-credential/) |
| market-registration | MarketRegistrationController | 3 | [market-registration/](market-registration/) |
| cafe24-auth | Cafe24AuthController | 3 | [cafe24-auth/](cafe24-auth/) |
| action-log | ActionLogController | 1 | [action-log/](action-log/) |
| common-code | CommonCodeController | 1 | [common-code/](common-code/) |
| product-sync | ProductSyncController | 1 | [product-sync/](product-sync/) |
| notification | SseNotificationController | 1 | [notification/](notification/) |
| email-fetch | EmailFetchController (worker) | 1 | [email-fetch/](email-fetch/) |
