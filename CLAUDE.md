# sbshop-agent

멀티마켓(쿠팡·스마트스토어·11번가·ESM+·Cafe24) 주문·상품 통합 관리. Java 21 + Spring Boot 3.5 멀티모듈(core/infrastructure/api/worker) + React 19/Vite 프론트엔드 + PostgreSQL/Redis.

## 배포·런타임

- **배포:** `git push origin main` 하면 운영서버 웹훅이 자동으로 pull→build→컨테이너 재생성한다(2026-09-19 복구 — 7/14~9/19 기간은 죽어 있어 수동 배포였다). **직접 SSH해서 `docker compose build`/`up` 하지 말 것** — 자동배포와 경합해 컨테이너명 충돌(`Conflict. The container name ... is already in use`)이 난다. 배포 확인은 SSH 읽기만: `docker ps --filter name=projects-sbshop-api-1`, `docker logs projects-sbshop-api-1 | grep 'Started ApiApplication'`.
- **웹훅:** 서비스 `canagent-webhook.service`(리스너 `/home/ubuntu/webhook/deploy.py`, 포트 9000), 훅 URL `http://168.107.31.154:9000/`, 시크릿은 systemd 유닛 파일의 `Environment=WEBHOOK_SECRET`(저장소에 없음). `X-Hub-Signature-256` 검증 — 서명 없는 요청은 403. main 브랜치 push만, **`.md`·`docs/`만 바뀐 푸시는 배포를 생략**한다(문서 커밋으로는 배포 확인 불가). 배포 확인: `sudo journalctl -u canagent-webhook -n 20`, `tail /home/ubuntu/webhook/webhook-deploy.log`, `tail /home/ubuntu/webhook/deploy-sbshop.log`. 웹훅이 죽었을 때의 수동/긴급 배포는 `./deploy-sbshop.sh`.
- **JVM 토폴로지:** `worker`는 `api` JVM에 라이브러리로 통합됨 — **단일 프로세스(`sbshop-api` 컨테이너 하나, 8080)**. 스케줄러·이메일 수집(EmailFetcherService)·내부 트리거가 모두 api JVM에서 돈다. 이메일 수동 트리거: `docker exec projects-sbshop-api-1 curl -s -X POST localhost:8080/internal/email/fetch`.
- **DB 역할(2026-09-19 재구성):** `goottjason`=superuser(관리·수동 DDL, `docker exec projects-postgres-1 psql -U goottjason -d sbshop`은 로컬 소켓 trust라 비밀번호 불필요), `sbshop`=이 앱 전용 일반 계정, `canagent`=can-agent 전용 일반 계정(예전 superuser 이름이었으나 **이제 일반 계정**이다). 프로젝트 계정은 서로의 DB에 접속할 수 없다. 5432는 `127.0.0.1`에만 바인딩(외부 차단 + 서버 방화벽 이중). 비밀번호는 서버 `.env`(600)에만 있고 compose에 기본값 폴백이 없다. 외부에서 DB를 열려면 SSH 터널(`ssh -L 15432:127.0.0.1:5432 ubuntu@서버`).
- **스키마:** Flyway 제거 — 운영 DB(`docker exec projects-postgres-1 psql -U goottjason -d sbshop`)가 스키마 단일 원본. 엔티티 변경 시 ddl-auto/수동 DDL로 반영.

## 개인정보 (이 저장소는 공개다)

원장·결과서·인계문서·테스트·문서·주석에 **고객 실명·연락처·주소·개인통관고유부호를 쓰지 않는다.** 사례를 인용할 때는 주문 id(`sb_order.id`)나 마스킹(`이○열`)을 쓰고, 테스트 픽스처는 가짜 값(`P000000000001`, `010-0000-0001`, `홍길동`)을 쓴다. 외부 API 응답 예시를 문서에 붙일 때도 값을 치환한다. 운영 DB의 실제 값을 눈으로 확인하는 것은 괜찮지만 저장소에는 옮기지 않는다.

2026-09-19 스캔에서 git 이력에 고객 실명 약 40명·통관번호 3개·전화번호 2개가 남아 있음을 확인했다. 현재 파일의 통관번호·전화번호는 가짜 값으로 치환했고, 실명과 이력은 그대로 두었다(이미 공개됐고 force push 비용이 크다).

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
| 2026-09-19 | 개인정보 규칙 신설 (공개 저장소에 고객 실명·연락처·통관번호 금지) | CLAUDE.md | 스캔에서 실명 ~40명·통관번호 3개·전화 2개 발견 |
| 2026-09-19 | DB 역할 재구성(goottjason=superuser, 프로젝트별 일반 계정)·비밀번호 교체·5432 바인딩 | CLAUDE.md | 공유 Postgres가 인터넷에 노출된 채 공개 기본 비밀번호를 쓰고 있었음 |
| 2026-09-19 | 웹훅 자동배포 복구 사실·운영 정보 반영 (서비스명·훅 URL·시크릿 위치·문서전용 푸시 생략) | CLAUDE.md | 2026-07-14 이후 중단됐던 자동배포를 서명검증 리스너로 복구 |
