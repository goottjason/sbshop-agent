# 리팩토링 교리 (2026-08-21 전면 구조 정리 캠페인)

**대원칙: 구조 변경만. 행위 변경 절대 금지 (Tidy First).** 버그를 발견해도 고치지 말고 리포트에 기록만 한다. 모든 변경 후 해당 모듈이 컴파일되어야 한다.

## 1. 주석 정리 (완전 제거)
- 모든 주석 제거: Javadoc, 블록 주석, 라인 주석, JSX 주석. (추후 새 규칙으로 재작성 예정)
- 예외: 어노테이션(@SuppressWarnings 등)은 주석이 아님 — 유지.
- 주석처리된 코드 블록 = 데드코드 → 삭제.
- TODO/FIXME 내용은 삭제 전 `_workspace/refactor/bugs-<scope>.md`의 "TODO 백로그" 섹션에 파일:라인과 함께 옮겨 적는다.
- 사라지면 위험한 "왜" 주석(마켓 API 함정, 멱등성 조건, 순서 제약 등 코드로 표현 불가한 제약)은 `_workspace/refactor/salvage-<scope>.md`에 파일:메서드와 함께 보존 기록 후 제거.

## 2. FQN 배제
- 본문·시그니처의 FQN(예: `com.sbshop.agent.core.domain.Order o = ...`, `java.time.LocalDate.now()`)을 import + Simple Name으로 전환.
- 동일 Simple Name 2개를 한 파일에서 쓰는 충돌 시: 더 많이 쓰는 쪽을 import, 나머지만 FQN 유지.
- 와일드카드 import 금지. 미사용 import 제거.

## 3. 메서드 정렬 (스텝다운 규칙)
클래스 내부 순서: static 상수 → 필드 → 생성자/정적 팩토리 → public API → package-private/protected → private 헬퍼 → equals/hashCode/toString.
- public API 내부 순서: 도메인 흐름 순(생성→조회→수정→삭제, 또는 처리 파이프라인 순). 호출자가 피호출자보다 위(스텝다운).
- private 헬퍼는 첫 호출 지점 그룹 근처에 호출 순서대로.
- 컨트롤러: 리소스 경로·CRUD 순으로 엔드포인트 정렬.
- 테스트 클래스: 대상 클래스의 메서드 순서를 따라 정렬.

## 4. 데드코드 삭제 (고신뢰만)
- 전역 인벤토리(`_workspace/refactor/survey-*.md`)에 등재된 고신뢰 항목만 삭제.
- private 미사용 멤버: 삭제.
- 미참조 public: 스프링 진입점(@Component류·@Scheduled·@EventListener·핸들러)·JPA 엔티티·Jackson 직렬화 DTO accessor·리플렉션 의심은 삭제 금지 → '데드 의심'으로 리포트만.
- 생성 코드(Q*.java, build/ 산하)는 건드리지 않는다.

## 5. 발견 버그 백로그
`_workspace/refactor/bugs-<scope>.md`에 기록: 위치(파일:라인), 증상, 판단 근거, 심각도 추정. 절대 직접 수정하지 않는다. defect-ledger.md는 직접 편집 금지(리더가 병합).

## 6. 완료 보고
작업 후 스스로 검증: 백엔드는 `cd backend && ./gradlew :<module>:compileJava :<module>:compileTestJava`, 프론트는 `cd frontend && npx tsc -p tsconfig.app.json --noEmit`. 최종 보고에 처리 파일 수·삭제 데드코드 목록·버그 리포트 유무를 요약한다.
