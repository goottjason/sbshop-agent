# sbshop-agent

멀티마켓(쿠팡·스마트스토어·11번가·ESM+·Cafe24) 주문·상품 통합 관리. Java 21 + Spring Boot 3.5 멀티모듈(core/infrastructure/api/worker) + React 19/Vite 프론트엔드 + PostgreSQL/Redis.

## 배포·런타임

- **배포:** `git push origin main` 하면 GitHub Actions(`.github/workflows/deploy.yml`)가 ① 입력 검증 → ② 테스트(`tests.yml`: 백엔드 gradle·프런트 tsc/vitest)와 arm64 이미지 빌드(`images.yml` → `ghcr.io/goottjason/sbshop-agent-{api,frontend,scraper}:<커밋 SHA 12자>`)를 병렬로 → ③ 둘 다 통과하면 서버에서 `ops/deploy.sh`가 **이미지를 pull만** 하고(서버는 빌드하지 않는다) 바뀐 서비스만 교체 → nginx reload → `/internal/health` 확인. 전체 약 8분(테스트가 가장 길다). `.md`·`docs/`만 바뀐 푸시는 배포하지 않는다. 도는 소싱처 배치가 있으면 끝날 때까지(최대 30분) 기다리며, 급하면 Actions 수동 실행의 `force`. **직접 SSH해서 `docker compose build`/`up` 하지 말 것** — 배포자와 경합해 컨테이너명 충돌이 난다. 배포 확인은 Actions 결과 + SSH 읽기: `docker ps --filter name=projects-sbshop-api-1`, 실행 중 이미지의 커밋: `docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' projects-sbshop-api-1`.
- **롤백·검증(Actions 수동 실행 입력):** `rollback=sbshop-api` 는 직전 `prev-*` 로컬 태그로, `rollback=sbshop-api rollback_tag=<커밋 SHA 12자>` 는 레지스트리에서 그 커밋의 이미지를 받아 되돌린다(재빌드 없음, 과거 임의 커밋 가능). `image_tag=<SHA 12자>` 는 이미 빌드된 태그를 테스트·빌드 없이 배포, `skip_tests=true` 는 긴급용, `recreate=<서비스>` 는 이미지가 같아도 다시 만든다.
- **자동 롤백(3단계):** 교체 뒤 api 헬스(DB 포함)·nginx 컨테이너 안 사용자 경로 라우팅(api·frontend)·scraper 헬스를 점검하고, 기동 실패·이미지 불일치·점검 실패면 **이번 실행에서 교체한 서비스 전부**를 역순으로 직전 이미지로 되돌려 재점검한다(종료코드 7=롤백 성공·서비스 정상, 8=롤백도 실패→수동 `rollback`; Actions 실행은 실패로 표시된다). 무중단(블루/그린)은 하지 않는다 — api 는 스케줄러 26개·이메일 수집이 도는 단일 JVM 이라 두 인스턴스를 동시에 띄우면 중복 실행되며 스케줄러 리더 선출이 선행돼야 한다. 교체 시 api 약 20초 끊김은 유지.
- **JVM 토폴로지:** `worker`는 `api` JVM에 라이브러리로 통합됨 — **단일 프로세스(`sbshop-api` 컨테이너 하나, 8080)**. 스케줄러·이메일 수집(EmailFetcherService)·내부 트리거가 모두 api JVM에서 돈다. 이메일 수동 트리거: `docker exec projects-sbshop-api-1 curl -s -X POST localhost:8080/internal/email/fetch`.
- **DB 역할(2026-09-19 재구성):** `goottjason`=superuser(관리·수동 DDL, `docker exec projects-postgres-1 psql -U goottjason -d sbshop`은 로컬 소켓 trust라 비밀번호 불필요), `sbshop`=이 앱 전용 일반 계정. 5432는 `127.0.0.1`에만 바인딩(외부 차단 + 서버 방화벽 이중). 비밀번호는 서버 `.env`(600)에만 있고 compose에 기본값 폴백이 없다. 외부에서 DB를 열려면 SSH 터널(`ssh -L 15432:127.0.0.1:5432 ubuntu@서버`).
- **스키마:** Flyway 제거 — 운영 DB(`docker exec projects-postgres-1 psql -U goottjason -d sbshop`)가 스키마 단일 원본. 엔티티 변경 시 ddl-auto/수동 DDL로 반영.

## 개인정보 (이 저장소는 공개다)

원장·결과서·인계문서·테스트·문서·주석에 **고객 실명·연락처·주소·개인통관고유부호를 쓰지 않는다.** 사례를 인용할 때는 주문 id(`sb_order.id`)나 마스킹(`이○열`)을 쓰고, 테스트 픽스처는 가짜 값(`P000000000001`, `010-0000-0001`, `홍길동`)을 쓴다. 외부 API 응답 예시를 문서에 붙일 때도 값을 치환한다. 운영 DB의 실제 값을 눈으로 확인하는 것은 괜찮지만 저장소에는 옮기지 않는다.

2026-09-19 스캔에서 git 이력에 고객 실명 약 40명·통관번호 3개·전화번호 2개가 남아 있음을 확인했다. 현재 파일의 통관번호·전화번호는 가짜 값으로 치환했고, 실명과 이력은 그대로 두었다(이미 공개됐고 force push 비용이 크다).

## 하네스: sbshop 정상화

**목표:** 레거시 병합으로 누적된 오류를 TDD로 수정하고 모든 기능을 정상 동작 상태로 되돌린 뒤 개선한다.

**트리거:** 이 프로젝트의 오류 수정·기능 정상화·결함 진단·리팩토링·테스트 추가 등 코드 수정 작업 요청 시 `sbshop-normalize` 스킬을 사용하라. 단순 질문·코드 설명은 직접 응답 가능.

**핵심 원장:** `docs/normalize/defect-ledger.md` (결함 원장) · `docs/normalize/codebase-map.md` (구조 지도) · `docs/normalize/working_history/` (사이클별 결과서 — 최신 결과서의 `## 다음 단계 참조`부터 읽기)

## 상신 형식

- **작업 완료 상신·정식 보고는 첫머리를 답변 시각 `[YYYY-MM-DD HH:MM]`으로 시작한다.** 짧은 문답·중간 경과 보고에는 붙이지 않는다. 이유: 전역 Stop 훅(`~/.claude/hooks/notify_on_report.py`)이 마지막 답변이 이 형식으로 시작할 때만 사용자에게 디스코드 알림을 보낸다. 시각은 UserPromptSubmit 훅이 주입하는 `[Now: …]`를 쓰고, 주입이 없는 턴은 셸로 조회한다.
