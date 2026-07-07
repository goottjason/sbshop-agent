# sbshop-agent

멀티마켓(쿠팡·스마트스토어·11번가·ESM+·Cafe24) 주문·상품 통합 관리. Java 21 + Spring Boot 3.5 멀티모듈(core/infrastructure/api/worker) + React 19/Vite 프론트엔드 + PostgreSQL/Flyway/Redis.

## 하네스: sbshop 정상화

**목표:** 레거시 병합으로 누적된 오류를 TDD로 수정하고 모든 기능을 정상 동작 상태로 되돌린 뒤 개선한다.

**트리거:** 이 프로젝트의 오류 수정·기능 정상화·결함 진단·리팩토링·테스트 추가 등 코드 수정 작업 요청 시 `sbshop-normalize` 스킬을 사용하라. 단순 질문·코드 설명은 직접 응답 가능.

**핵심 원장:** `docs/normalize/defect-ledger.md` (결함 원장) · `docs/normalize/codebase-map.md` (구조 지도) · `docs/normalize/working_history/` (사이클별 결과서 — 최신 결과서의 `## 다음 단계 참조`부터 읽기)

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-07-07 | 초기 구성 (에이전트 4 + 스킬 4, 하이브리드 모드) | 전체 | - |
| 2026-07-07 | 외부 리뷰 루프 미구성 | - | codex/agy/gemini 미설치 (REVIEWERS: none) — 내부 QA 게이트로 대체 |
| 2026-07-07 | 검증자 트리 변경 명령 금지 명문화 | agents/qa-verifier.md | 사이클 2 spotlessApply 오염 사고 |
| 2026-07-07 | 무응답 팀원 재스폰 가드 추가 | skills/sbshop-normalize | 사이클 2 중복 fixer 스폰 사고 |
| 2026-07-07 | 검증 요청 후 트리 동결 규율 추가 | agents/tdd-fixer.md | 사이클 4 mid-edit 검증 오염 사건 |
