# API 흐름 분석 문서 — 2차 (api-analysis-2nd)

> **목적** — **현재 코드베이스**(2026-07-17 기준, main@8dfa405 이후)를 엔드포인트 단위로
> **유스케이스 다이어그램 · 시퀀스 다이어그램 · 순서도(플로우차트)** 로 재시각화하고,
> 그 과정에서 드러나는 **놓친 케이스 · 어색한 로직 · 중복 로직**을 근거와 함께 새로 인벤토리로 남긴다.
>
> **1차(`docs/api-analysis/`)와의 관계** — 1차는 2026-07-14 시점의 스냅샷이며, 이후 R1~R6·재무버그·완전삭제 등
> 대규모 수정이 배포되었다. 이 2차 문서는 **1차 발견사항을 계승하지 않는다.** 오직 현재 코드를 다시 읽어
> 지금 이 순간의 흐름과 결함을 처음부터 재진단한다 (1차에서 해결된 항목은 자연히 재발견되지 않는다).

## 폴더 구조

```
docs/api-analysis-2nd/
├── README.md                       ← (이 파일) 양식 정의 · 색인 · 범례
├── FINDINGS-CHECKLIST.md           ← 전체 발견사항 우선순위 집계
├── order/                          ← 도메인(컨트롤러) 단위 하위 폴더, API 1개 = 파일 1개
└── ...
```

## 문서 양식 (각 API 파일의 고정 섹션)

| # | 섹션 | 내용 |
|---|------|------|
| 1 | **개요** | Method·URL·목적·핵심 상태전이 한 줄 요약 (표 형식) |
| 2 | **호출 체인** | Controller → DTO → Command → Service → Domain/외부포트까지 실제 파일·라인 매핑 (현재 코드 기준) |
| 3 | **유스케이스 다이어그램** | 행위자·시스템·외부 시스템(마켓)과 이 API가 참여하는 유스케이스 (Mermaid) |
| 4 | **시퀀스 다이어그램** | 요청→응답까지 컴포넌트 간 메시지 흐름, 트랜잭션·롤백 경계 표시 (Mermaid) |
| 5 | **순서도(플로우차트)** | 상태 가드·분기·예외 경로를 포함한 처리 흐름 (Mermaid) |
| 6 | **상태 전이표** | 진입 상태별 허용 여부·결과 상태·부수효과(마켓 전송 등) |
| 7 | **🔎 발견사항** | 놓친 케이스·어색/중복 로직. 각 항목 `[심각도] 제목 / 근거(파일:라인) / 영향 / 제안` |
| 8 | **테스트 커버리지 메모** | 관련 테스트 존재 여부·검증하는 계약·비어있는 케이스 |

## Mermaid 작성 규칙 (Notion 호환)

- **줄바꿈은 `<br/>`** 사용 (`\n` 은 리터럴로 표시되어 렌더 실패).
- **`participant X as 라벨` 선언에 `<br/>` 금지** — 부가정보(`@Transactional` 등)는 `Note over` 로 분리.
- **노드 라벨에 괄호 `()` 가 들어가면 따옴표로 감싼다**: `A["applyData(merge)"]`.
- 화살표(`→`)·슬래시(`/`)·`@` 는 대체로 허용되나, 불안정하면 라벨을 따옴표로 감싼다. `<`,`>`는 `&lt;`,`&gt;`.
- 사용 다이어그램: `flowchart`(유스케이스·순서도), `sequenceDiagram`(시퀀스). `classDef` 색상 범례는 순서도에 한해 사용.

## 발견사항 심각도 범례

| 태그 | 의미 |
|------|------|
| 🔴 **BUG** | 데이터 정합·기능 오류로 이어지는 결함. 결함 원장 등재 후보 |
| 🟠 **GAP** | 처리되지 않은 케이스·검증 누락. 오동작 가능성 있으나 조건부 |
| 🟡 **SMELL** | 중복·죽은 코드·어색한 책임 배치. 동작은 하나 유지보수 위험 |
| 🔵 **NOTE** | 의도된 설계일 수 있으나 문서화가 필요한 지점 / 개선 여지 |

> 발견사항 ID 체계 — 2차는 유닛 프리픽스 기반(예: `ORDA-1`, `PRODB-3`, `SYNCB-2`)으로 1차 `F-*` 및 원장 ID와 충돌하지 않게 부여한다.
> 실제 수정은 `sbshop-normalize` 스킬 소관 — 이 문서는 **진단·기록만** 한다.

## 색인 (56개 API · 현재 코드베이스)

| 도메인 | 컨트롤러 | 문서 수 | 폴더 |
|--------|----------|:------:|------|
| order | OrderController | 10 | [order/](order/) |
| order-sync | OrderSyncController | 9 | [order-sync/](order-sync/) |
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

> 1차 대비 변화: `order/delete-order` 엔드포인트 제거(현재 DELETE /orders/{id} 매핑 부재), `product/delete-product` 는 완전삭제(마켓 API 연동)로 재구현됨.

*생성: 2026-07-17 · 근거: main@8dfa405 이후 현재 워킹트리*
