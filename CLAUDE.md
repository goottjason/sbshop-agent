# sbshop-agent

멀티마켓(쿠팡·스마트스토어·11번가·ESM+·Cafe24) 주문·상품 통합 관리. Java 21 + Spring Boot 3.5 멀티모듈(core/infrastructure/api/worker) + React 19/Vite 프론트엔드 + PostgreSQL/Redis.

## 배포·런타임

- **배포:** `git push origin main` 하면 운영서버 웹훅이 자동으로 pull→build→컨테이너 재생성한다. **직접 SSH해서 `docker compose build`/`up` 하지 말 것** — 자동배포와 경합해 컨테이너명 충돌(`Conflict. The container name ... is already in use`)이 난다. 배포 확인은 SSH 읽기만: `docker ps --filter name=projects-sbshop-api-1`, `docker logs projects-sbshop-api-1 | grep 'Started ApiApplication'`.
- **JVM 토폴로지:** `worker`는 `api` JVM에 라이브러리로 통합됨 — **단일 프로세스(`sbshop-api` 컨테이너 하나, 8080)**. 스케줄러·이메일 수집(EmailFetcherService)·내부 트리거가 모두 api JVM에서 돈다. 이메일 수동 트리거: `docker exec projects-sbshop-api-1 curl -s -X POST localhost:8080/internal/email/fetch`.
- **스키마:** Flyway 제거 — 운영 DB(`docker exec projects-postgres-1 psql -U canagent -d sbshop`)가 스키마 단일 원본. 엔티티 변경 시 ddl-auto/수동 DDL로 반영.

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
| 2026-07-07 | 프론트 타입 게이트 교정 (tsc -p tsconfig.app.json) | skills 3개 | 루트 tsconfig references-only → -p 없는 tsc는 헛-그린 |
| 2026-07-07 | 스키마 수동 관리 체제 반영 (Flyway 제거) | skills 3개 | 사용자 결정 — 운영 DB가 스키마 단일 원본, 엔티티 변경 시 수동 DDL |
| 2026-07-18 | 배포·런타임 섹션 신설 (push 자동배포·단일 JVM 명문화) | CLAUDE.md | worker+api 단일 JVM 통합 + git push 웹훅 자동배포 확정 |
