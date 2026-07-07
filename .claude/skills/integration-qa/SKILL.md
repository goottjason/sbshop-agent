---
name: integration-qa
description: "sbshop-agent 통합 정합성 검증 방법론. 수정 검증, QA, 회귀 테스트, 경계면 검사, API-프론트 계약 확인, '수정한 거 제대로 되는지 확인해줘' 류 요청 시 반드시 사용. 결함 발굴 전용은 defect-triage 담당."
---

# Integration QA — 통합 정합성 검증

각각 "올바른" 두 컴포넌트가 연결 지점에서 어긋나는 것이 이 코드베이스 런타임 에러의 주원인이다. 존재 확인이 아니라 **경계면 교차 비교**로 검증한다.

## 검증 절차

1. **결함 해소 확인**: 수정의 Red 테스트가 Green인지 직접 실행. `_workspace/fixes/{결함ID}_fix.md`의 주장을 그대로 믿지 않는다.
2. **회귀**: 영향 모듈 전체 테스트 — `cd backend && ./gradlew :모듈:test` (다모듈 수정이면 `./gradlew test`). 프론트 영향 시 `npx tsc --noEmit -p tsconfig.app.json && npm run build` (루트 tsconfig는 references-only라 `-p` 없는 tsc는 헛-그린 — 검사 0건).
3. **경계면 교차 비교**: 아래 체크리스트에서 수정이 걸치는 경계면만 수행.
4. **판정서 작성**: `_workspace/verify/{결함ID}_verdict.md` — 실행 커맨드·결과 원문 요지·미검증 항목 명시.

## 경계면 체크리스트 (양쪽 동시 읽기)

| 경계면 | 생산자 (왼쪽) | 소비자 (오른쪽) | 확인 |
|--------|-------------|---------------|------|
| API ↔ 프론트 | `backend/api/.../dto/` 응답 DTO 필드 | `frontend/src/api/*.ts` 타입·unwrap 로직 | 필드명(카멜/스네이크), 래핑(`PageResponse<T>`의 `content`), null 처리 |
| 스케줄러 ↔ 서비스 | `worker/.../scheduler/*.java` 활성 여부 | core 동기화 서비스 | 비활성 스케줄이 "동작하는 기능"으로 오인되지 않는가 |
| DB 스키마 ↔ 엔티티 | 운영 DB 스키마 (**수동 관리** — 2026-07-07 사용자 결정으로 Flyway 제거, 코드에 스키마 원본 없음) | `core/.../domain/` `@Entity`·`@Embeddable` | 엔티티 변경 시 운영 DB에 대응 DDL을 사용자가 수동 적용해야 함을 판정서에 명시. 드리프트는 코드가 탐지 못 함 |
| 포트 ↔ 어댑터 | core의 port 인터페이스 | infrastructure 구현체 | 구현이 정확히 1벌인가 (중복 구현이 빈 충돌을 일으키지 않는가) |
| 설정 ↔ 빈 | `@Configuration` 클래스 | 주입 지점 (`@Qualifier`, executor 이름) | `@Bean` 누락, 모듈 간 중복 정의, 이름 충돌 |
| 프론트 라우트 ↔ 링크 | `App.tsx` 라우트 정의 | `MainLayout.tsx` nav·페이지 내 이동 | base path `/sbshop-agent` 포함 여부 일관성 |

## 판정 기준

- **PASS**: 결함 해소 + 회귀 전체 통과 + 해당 경계면 불일치 없음.
- **부분**: 실행 검증은 통과했으나 정적 대조에서 이슈 발견, 또는 환경 제약으로 일부 미검증. → 근거와 함께 리더 판단으로.
- **FAIL**: Red 테스트 여전히 실패, 회귀 발생, 또는 경계면 불일치 확인. → tdd-fixer에게 파일:라인 + 기대/실제 동작으로 수정 요청.

판정을 후하게 주지 마라 — 여기서 새는 결함은 커밋 게이트를 그대로 통과한다. 불확실하면 PASS가 아니라 `부분`이다.

## 검증 환경 한계 (침묵 생략 금지)

- H2 기반 테스트는 PostgreSQL JSONB 동작을 보증하지 않는다 — 해당 경로 수정 시 판정서에 "H2 한계로 미검증" 명시.
- 마켓 실 API·운영 DB 접근 검증은 이 하네스 범위 밖 — 필요 시 리더가 사용자에게 수동 확인을 요청하도록 판정서에 기재.
