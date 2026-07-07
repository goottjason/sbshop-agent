---
name: defect-triage
description: "sbshop-agent 결함 진단·원장 기록 방법론. 버그 찾기, 오류 진단, 결함 인벤토리 작성, 원장(defect-ledger) 갱신, 우선순위 판정, '뭐가 고장났는지 파악해줘' 류 요청 시 반드시 사용. 결함 수정 자체는 tdd-bugfix 스킬 담당."
---

# Defect Triage — 결함 진단·원장 방법론

결함을 **재현 가능한 레코드**로 만들어 원장에 쌓는다. 원장이 정확해야 수정 작업(tdd-bugfix)이 헛돌지 않는다.

## 진단 순서 (비용이 싼 것부터)

1. **컴파일/빌드**: `cd backend && ./gradlew compileJava compileTestJava` → 실패하면 그 자체가 최우선(P0) 결함.
2. **기존 테스트**: `cd backend && ./gradlew test` (모듈별: `:core:test` `:api:test` `:infrastructure:test` `:worker:test`). 프론트: `cd frontend && npm run lint && npx tsc --noEmit -p tsconfig.app.json && npm run build` (`-p` 필수 — 루트 tsconfig는 references-only라 없으면 헛-그린).
3. **정적 대조 (경계면)**: `integration-qa` 스킬의 교차 비교 체크리스트를 진단 모드로 적용 — API 응답 DTO ↔ 프론트 타입, 스케줄러 활성 여부 ↔ 기대 동작, Flyway 스키마 ↔ 엔티티.
4. **알려진 패턴 재확인**: 아래 "이 프로젝트의 알려진 결함 패턴" 목록의 현재 상태 점검.

## 이 프로젝트의 알려진 결함 패턴 (2026-07 초기 탐사 기준)

레거시 병합의 전형적 후유증. 진단 시 이 패턴들의 잔존·재발 여부를 우선 확인하라:

- **비활성 기능**: `backend/worker/.../scheduler/OrderSyncScheduler.java`의 스케줄 6개가 전부 `// TODO: 리팩토링 완료 후 활성화` — 주문 동기화가 실제로 안 돌고 있음.
- **등록 안 되는 빈**: `backend/api/.../config/AsyncConfig.java`의 `productBatchExecutor()`에 `@Bean` 누락. core 모듈 `AsyncConfig`와 중복 정의이기도 함.
- **중복 구현**: `ElevenstRestClient` 2벌(`elevenst/` vs `elevenst/client/`), 이미지 다운로더 3벌, 마켓별 adapter/client 패키지 구조 불일치.
- **스키마 우회로**: `backend/api/src/main/resources/after-migrate.sql` — Flyway 밖에서 스키마를 고치는 idempotent 스크립트. 엔티티와의 정합 확인 필요.
- **미검증 완료 선언**: `task.md`의 체크는 구현 완료 표시일 뿐, 통합 테스트(T9.3/T9.5/T9.7/T9.11)가 없어 동작 보증이 아님.
- **프론트 미사용 함수**: `frontend/src/api/orderApi.ts`의 `purchaseItem`/`shipItem`/`updateTracking` 등 — 호출 누락인지 의도적인지 판별 필요.

## 결함 레코드 스키마 (원장: `docs/normalize/defect-ledger.md`)

```markdown
### D-{번호}: {한 줄 요약}
- 심각도: P0(빌드/데이터 손상) | P1(기능 불능) | P2(오동작) | P3(품질/부채)
- 리스크 등급: 경량 | 표준 | 중대   ← 게이트 강도 결정 (오케스트레이터 판정)
- 위치: {파일:라인}
- 증상: {관찰된 사실}
- 재현: {커맨드 또는 코드 경로 추적. 재현 불가면 "미재현 — {이유}"}
- 원인(추정/확인): {근거 포함. 미확인이면 "미확인"}
- 상태: 발견 | 수정중 | 수정완료(검증대기) | 검증통과 | 반려({사유}) | 보류
- 이력: {날짜 상태변경 한 줄씩 추가}
```

번호는 이어서 증가시키고 재사용하지 않는다. 레코드 삭제 금지 — 오판이었으면 상태를 `보류`로 바꾸고 사유를 남긴다 (원장은 감사 추적이다).

## 우선순위 원칙

P0 > P1 > P2 > P3 순이되, 같은 심각도면 **① 다른 결함 진단을 막는 것 ② 사용자가 지목한 기능 ③ 수정 파급이 작은 것** 순. "주문 동기화 비활성"처럼 조용히 기능이 죽어 있는 P1을 컴파일 경고류 P3보다 항상 앞세운다.
