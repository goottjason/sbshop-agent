---
name: sbshop-normalize
description: "sbshop-agent 정상화 오케스트레이터. 오류/버그 수정, 기능 정상화, 결함 진단, TDD 수정, 스케줄러 활성화, 중복 코드 통합, 리팩토링, 테스트 추가, 회귀 확인 등 이 프로젝트의 코드 수정·개선 작업 전반에 반드시 사용. 후속 작업 — 다시 실행, 재실행, 이어서, 업데이트, 수정 보완, 특정 결함만 다시, 이전 결과 기반 개선, 원장 갱신 요청에도 반드시 사용. 단순 코드 질문/설명은 직접 응답 가능."
---

# SBShop Normalize — 정상화 오케스트레이터

레거시 병합으로 오류가 누적된 sbshop-agent를 **진단 → TDD 수정 → 검증 → 커밋** 사이클로 정상화한다. 리더(이 스킬을 실행하는 메인 세션)가 팀을 조율하고 판정·커밋 권한을 가진다.

## 실행 모드: 하이브리드

| Phase | 모드 | 이유 |
|-------|------|------|
| 1 진단 | 서브 에이전트 (병렬) | 독립 탐사·기록, 통신 불필요 |
| 2 TDD 수정 | 에이전트 팀 (tdd-fixer + qa-verifier) | 수정↔검증 반려 루프에 직접 통신 필요 |
| 3 게이트·커밋 | 리더 직접 | 판정·커밋은 비위임 |

> 팀 도구(Agent spawn + SendMessage + 공유 작업 목록)가 없는 환경이면 Phase 2를 서브 에이전트 순차(수정 → 검증 → 반려 시 재수정)로 폴백한다. 데이터는 동일하게 원장과 `_workspace/` 파일로 흐르므로 산출물 계약은 변하지 않는다.

## 에이전트 구성

| 팀원 | 정의 | 역할 | 스킬 | 주 출력 |
|------|------|------|------|--------|
| legacy-mapper | `.claude/agents/legacy-mapper.md` | 구조 지도 | — | `docs/normalize/codebase-map.md` |
| defect-scout | `.claude/agents/defect-scout.md` | 결함 발굴·원장 | defect-triage | `docs/normalize/defect-ledger.md` |
| tdd-fixer | `.claude/agents/tdd-fixer.md` | TDD 수정 | tdd-bugfix | 코드 + `_workspace/fixes/` |
| qa-verifier | `.claude/agents/qa-verifier.md` | 독립 검증 | integration-qa | `_workspace/verify/` |

## 워크플로우

### Phase 0: 컨텍스트 확인 (후속 작업 지원)

1. `docs/normalize/defect-ledger.md` 존재 확인:
   - **미존재** → 초기 실행. Phase 1부터.
   - **존재 + 특정 결함/부분 수정 요청** → 부분 재실행. Phase 1 생략, 해당 결함만 Phase 2로.
   - **존재 + "다시 진단" / 새 오류 신고** → 원장은 유지한 채 defect-scout만 재실행해 원장 증분 갱신 후 Phase 2.
2. `_workspace/` 없으면 생성 (`fixes/`, `verify/` 포함). 이전 실행의 미완(`수정완료(검증대기)`·`반려`) 결함이 있으면 그것부터 이어간다.
3. `docs/normalize/working_history/`의 **최신 결과서**가 있으면 `## 다음 단계 참조` 블록을 먼저 읽고 시작한다 (판단 연속성).

### Phase 1: 진단 (서브 에이전트, 병렬)

1. 단일 메시지로 병렬 스폰: legacy-mapper (지도 작성/갱신) + defect-scout (원장 작성/갱신). 둘 다 `run_in_background: true`.
2. 완료 후 원장을 읽고 **이번 사이클의 수정 배치 선정** (아래 배치 규칙). 심각도·리스크 등급이 원장에 비어 있으면 리더가 판정해 기입한다.

**배치 규칙**: 한 사이클에 3~5건. 서로 파일이 겹치지 않는 결함만 병렬 배치(겹치면 순차). 구조 변경(중복 통합)과 행위 변경(버그 수정)은 같은 배치에 섞지 않는다 (Tidy First — 커밋 분리).

### Phase 2: TDD 수정 (에이전트 팀)

1. tdd-fixer, qa-verifier 팀원 스폰. TaskCreate로 결함별 작업 등록 — 수정 작업과 검증 작업을 `depends_on`으로 연결.
2. 루프 (결함별): tdd-fixer 수정 → qa-verifier에게 검증 요청(SendMessage) → PASS면 원장 `검증통과` / FAIL이면 파일:라인 수정 요청 → 재수정.
   - **반려 2회 초과** 시 리더 개입: 결함을 `보류`로 돌리고 배치에서 제외, 사유를 원장에 기록.
   - 팀원이 완료 표시를 못 해 의존 작업이 막히면 SendMessage로 완료 보고를 직접 요구 후 리더가 TaskUpdate.
3. 배치 전체 완료 후 팀원 shutdown.

### Phase 3: 게이트·커밋 (리더 직접, 비위임)

1. **회귀 게이트**: `cd backend && ./gradlew test` 전체 실행 + (프론트 변경 시) `cd frontend && npx tsc --noEmit && npm run build`. 실패 시 커밋 금지, 해당 결함 재수정 또는 배치 제외.
2. **리스크 등급별 게이트** (외부 리뷰어 부재 환경 — 내부 QA가 게이트):

   | 등급 | 조건 | 게이트 |
   |------|------|--------|
   | 경량 | 1파일·가역·오타/설정 | qa-verifier 판정서 PASS |
   | 표준 | 다파일·기능 수정 | 판정서 PASS + 회귀 전체 통과 |
   | 중대 | 스케줄러 활성화·스키마·마켓 API 계약·다도메인 | 판정서 PASS + 회귀 통과 + **사용자 승인 필수** (자율 마커로도 생략 불가) |

3. **승인 관문**: 기본은 사용자 승인 대기. `_workspace/.autonomous` 마커 존재 또는 사용자의 "자율로/승인 생략" 발화 시 경량·표준은 자동 통과 (중대는 항상 승인). push는 자율이어도 항상 대기 — `_workspace/.autonomous-push` 마커 시만 자동.
4. **커밋**: 게이트 통과분만, 결함 단위(또는 같은 배치의 행위 변경 묶음)로 커밋. 메시지에 결함 ID와 구조/행위 구분 명시. 구조 변경과 행위 변경은 별도 커밋.
5. **결과서**: `docs/normalize/working_history/{YYYYMMDD_HHmm}_결과서.md` 작성 — 처리 결함, 판정 요약, 미해결·보류 목록, 핵심 결정과 이유, `## 다음 단계 참조` 블록(다음 배치 권고 포함). 커밋에 포함.

### Phase 4: 반복 판단

원장에 `발견` 상태 결함이 남아 있으면 사용자에게 요약 보고 후 다음 배치 진행 여부를 확인한다 (자율 마커 시 자동으로 다음 배치). 원장이 비면 개선(P3) 단계 제안으로 전환.

## 데이터 흐름

```
legacy-mapper ─→ codebase-map.md ─┐
defect-scout ─→ defect-ledger.md ─┼→ [리더: 배치 선정]
                                   ↓
        tdd-fixer ←SendMessage→ qa-verifier
             │                      │
     _workspace/fixes/      _workspace/verify/
             └──────────┬───────────┘
                        ↓
        [리더: 회귀 게이트 → 승인 → 커밋 → 결과서]
```

- **영속 (`docs/normalize/`, 커밋 대상)**: codebase-map, defect-ledger, working_history 결과서
- **휘발 (`_workspace/`, gitignore)**: fixes/, verify/, 마커. 소실돼도 원장·결과서로 재구성 가능

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| 에이전트 1명 실패 | 1회 재시도 → 재실패 시 해당 결함 `보류` 처리하고 배치 계속, 결과서에 누락 명시 |
| 팀원 무응답 (죽음 의심) | **재스폰 전 반드시 재확인**: 원장·`_workspace/` 파일 mtime과 git status로 작업 흔적 점검 후, 흔적 없어도 5분 이상 간격 두고 2회 확인. 동일 임무 중복 스폰은 동시 편집 충돌을 만든다 (2026-07-07 중복 fixer 사고). 재스폰이 불가피하면 기존 인스턴스에 중단 지시를 먼저 보내고 다른 이름으로 스폰 |
| 빌드 자체 실패 | 모든 배치 중단, 빌드 복구를 P0 단독 배치로 최우선 처리 |
| Red 테스트가 통과 (재현 실패) | 수정 진행 금지, 원장에 기록, defect-scout 재진단 |
| 진단 결과 상충 (지도 vs 원장) | 삭제하지 않고 출처 병기, 리더가 코드 직접 확인으로 판정 |
| 반려 2회 초과 | 결함 `보류` + 에스컬레이션 사유 기록, 사용자 보고 |
| 대규모 실패 (배치 과반) | 비파괴 롤백 (tdd-doctrine 롤백 규율 — checkpoint에서 `git restore`, `reset --hard` 금지) 후 사용자 보고 |

동시 실행 cap: 서브/팀원 동시 3 (최대 5). 초과분은 큐잉.

## 테스트 시나리오

**정상 흐름**: "오류 찾아서 고쳐줘" → Phase 1 병렬 진단 → 원장 신규 결함 N건 → 배치 4건 선정 → Phase 2 수정·검증 (1건 반려 후 재수정 PASS) → Phase 3 회귀 통과 → 승인 → 결함별 커밋 + 결과서 → 잔여 결함 보고.

**에러 흐름**: Phase 2에서 D-7 반려 2회 초과 → `보류` + 배치 제외 → 나머지 3건 게이트 통과·커밋 → 결과서와 사용자 보고에 "D-7 보류: 재현 조건 불명" 명시 → 다음 단계 참조에 defect-scout 재진단 권고.

**부분 재실행**: "D-3만 다시 고쳐줘" → Phase 0에서 원장 확인 → Phase 1 생략 → D-3 단독 배치로 Phase 2~3.

## 참조

- 개발 규칙: `references/dev-rules.md` / TDD 교리: `references/tdd-doctrine.md`
- 외부 리뷰 루프: **미구성** (2026-07-07 점검: codex/agy/gemini 미설치, REVIEWERS: none). 리뷰어 설치 후 `/myharness`로 확장하면 표준·중대 게이트에 외부 리뷰가 추가된다.
