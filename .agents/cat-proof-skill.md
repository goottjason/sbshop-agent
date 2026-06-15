---
name: "cat-proof"
description: "Active code integrity guardian that monitors, verifies, and heals modified code in real-time."
version: 1.0.0
---

# 🛡️ Cat-Proof: Active Verification Protocol & Guidelines

<!-- 
이 스킬은 에이전트가 코드를 수정할 때 발생할 수 있는 실수나 기술적 오류를 즉각적으로 걸러내어 최상의 품질을 보장하도록 합니다. 
This skill ensures that any technical mistakes when you modify code are filtered out immediately, providing perfect quality.
-->

## 1. The Verification Protocol (🛡️ Guarding Steps)

<!-- 
코드 수정 도구 사용 후, 반드시 내부적으로 다음 검증 단계를 수행해야 합니다. 
After using any code modification tools, you must internally execute the following verification steps:
-->
1.  **Syntax Verification**: Perform linting or a dry-run check. <!-- (구문 검증: 린트 체크 또는 테스트 실행을 수행합니다.) -->
2.  **Structural Integrity**: Ensure markers like braces and imports are intact. <!-- (구조적 무결성: 괄호와 임포트 등이 온전히 유지되는지 확인합니다.) -->
3.  **Style Guide Adherence**: Follow project-specific formatting rules. <!-- (스타일 가이드 준수: 프로젝트의 포맷팅 규칙을 따르는지 확인합니다.) -->
4.  **Reporting Result**: Log status or attempt **Self-healing** if errors are found. <!-- (결과 보고: 상태를 기록하거나 오류 발견 시 자가 수정을 시도합니다.) -->

## 2. Self-healing Mechanism (🛡️ Proactive Repair)

<!-- 
사소한 오류가 발견된 경우, 사용자 보고 전 스스로 수정을 시도할 기회를 가집니다. 
If minor errors are discovered, you have the opportunity to fix them yourself:
-->
*   **Trivial Fixes**: Automatically correct minor syntax and formatting issues. <!-- (사소한 구문 및 포맷팅 문제는 자동으로 수정합니다.) -->
*   **Fix Reporting**: Inform the user: "Code integrity check detected [error]. Repaired." <!-- (사용자에게 "무결성 검사에서 [오류]를 발견하여 수정했습니다."라고 보고합니다.) -->
*   **Threshold**: If the error is structural, **do not** attempt an automatic fix. <!-- (구조적 오류인 경우 자동 수정을 시도하지 말고 사용자에게 보고하십시오.) -->

## 3. Synthetic Output Guard (Season 2+)

<!-- 
정형 데이터 수호는 에이전트 간 통신 시 JSON 스키마를 강제하여 무결성을 확보하는 기술입니다. 
Synthetic Output Guard ensures integrity by enforcing JSON schemas during inter-agent communication:
-->
*   **JSON-Only Mode**: Force outputs into strictly parseable JSON format. <!-- (출력을 엄격하게 파싱 가능한 JSON 형식으로 강제합니다.) -->
*   **Schema Validation**: Verify generated JSON matches required structure. <!-- (생성된 JSON이 요구되는 구조와 일치하는지 검증합니다.) -->
*   **Zero Hallucination**: Reject any conversational filler in "Guard Mode". <!-- (가드 모드에서는 불필요한 대화형 군더더기를 배제합니다.) -->

## 4. Reporting Specification

<!-- 
검증 보고서는 사용자에게 불필요한 노이즈를 주지 않도록 간결하게 작성되어야 하며, 다음 레이블을 사용합니다. 
-->
Verification reports must be concise to avoid unnecessary noise for the user, using the following labels:

```markdown
🛡️ [CAT_PROOF_START: filename]
[Scan: PASS/FAIL]
[Action: Verified/Healed/Manual_Check_Required/JSON_Enforced]
🛡️ [CAT_PROOF_END: filename]
```
