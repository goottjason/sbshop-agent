---
name: tdd-bugfix
description: "sbshop-agent 결함의 TDD 수정 워크플로우. 버그 수정, 오류 고치기, 결함 해소, 스케줄러 재활성화, 중복 클래스 통합, 리팩토링 요청 시 반드시 사용. 결함 발굴은 defect-triage, 검증은 integration-qa 담당."
---

# TDD Bugfix — 결함 수정 워크플로우

TDD 규율(Red→Green→Refactor, Tidy First)의 일반 원칙은 `.claude/skills/sbshop-normalize/references/tdd-doctrine.md`를 따른다. 이 스킬은 그 규율을 **이 프로젝트에 적용하는 방법**이다.

## 수정 사이클

1. **원장 확인**: `docs/normalize/defect-ledger.md`에서 할당 결함의 재현 방법·리스크 등급을 읽는다. 상태를 `수정중`으로 갱신.
2. **Red — 재현 테스트 작성**:
   - 결함이 속한 모듈의 `src/test/java/...` 아래에 동작 설명형 이름으로 작성 (예: `shouldRegisterProductBatchExecutorBean`).
   - 실행해 **실패를 눈으로 확인**한다. 통과하면 재현 방법이 틀린 것 — 수정하지 말고 원장에 기록 후 보고.
3. **Green — 최소 수정**: 테스트를 통과시키는 최소 변경만. 인접 코드 개선 유혹 금지.
4. **Refactor**: 통과 상태에서만, 한 번에 하나씩, 매 단계 후 테스트 재실행.
5. **모듈 테스트 전체 실행**: `cd backend && ./gradlew :모듈:test`. 경계면 수정이면 반대편(프론트 `npx tsc --noEmit && npm run build`)도.
6. **산출물 기록**: `_workspace/fixes/{결함ID}_fix.md` + 원장 상태 `수정완료(검증대기)`.

## 결함 유형별 성공 기준

테스트로 재현이 안 되는 유형은 검증 커맨드를 성공 기준으로 삼는다:

| 유형 | 성공 기준 |
|------|----------|
| 로직 버그 | 재현 단위 테스트 Red→Green |
| 빈 등록/설정 오류 | `@SpringBootTest` 컨텍스트 로드 테스트 또는 `ApplicationContext.getBean()` 검증 테스트 |
| 중복 클래스 통합 (구조 변경) | 전후 테스트 전체 통과로 동작 불변 증명. 통합 전 양쪽 구현 차이를 원장에 기록했는지 확인 |
| 스케줄러 재활성화 | 스케줄 메서드가 호출하는 서비스의 단위/통합 테스트 Green 확보 **후** 활성화. 활성화 자체는 중대 등급 — 오케스트레이터 승인 필요 |
| 스키마/마이그레이션 | H2 대신 실제 PostgreSQL 계열 검증 필요 시 testcontainers 도입은 리더 승인 후 (신규 의존성) |
| 프론트 결함 | 테스트 러너 부재 — `npx tsc --noEmit -p tsconfig.app.json` + `npm run build` + 수동 검증 절차 명시. 루트 tsconfig는 references-only(`files: []`)라 `-p` 없는 tsc는 아무것도 검사 안 하는 헛-그린이다. vitest 도입은 개선 후보로 원장에 기록 |

## 이 프로젝트의 함정

- **H2 ≠ PostgreSQL**: 테스트는 H2로 돌지만 운영은 PostgreSQL + JSONB(`after-migrate.sql`). JSONB·PL/pgSQL 의존 로직은 H2 통과가 동작 보증이 아님을 판정서에 남겨라.
- **task.md 체크를 믿지 마라**: "완료" 표시는 테스트 근거가 없다. 결함 수정 중 관련 기능을 건드리면 그 기능의 테스트부터 확보하라.
- **마켓 API 클라이언트는 mock으로**: Coupang/SmartStore/11st/ESM+/Cafe24 실 API 호출 테스트 금지 (자격증명·rate limit·부작용). MockWebServer 또는 인터페이스 mock 사용.
- **`.env`·자격증명 파일 접근 금지**: 테스트에 실 자격증명을 넣지 않는다.

## 금지 사항

- 커밋·브랜치 생성·`git reset` (오케스트레이터 전용 — tdd-doctrine 롤백 규율 참조)
- 할당 외 결함의 수정 (발견 시 원장에 후보 기록만)
- 실패 테스트의 삭제·`@Disabled` 처리로 Green 만들기
