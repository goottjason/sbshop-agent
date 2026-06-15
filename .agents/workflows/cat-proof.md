---
description: "Code Integrity Verification Workflow. (/cat-proof 실시간 코드 검증 워크플로우)"
---

# 🐾 Cat-Proof: Active Verification Workflow (`/cat-proof`)

<!-- 
이 워크플로우는 코드 수정 직후 자동 린트와 구문 검증을 수행하여 무결성을 확보하는 과정을 안내합니다. 
This workflow guides the automatic linting and syntax verification immediately after code modification.
-->

1. **Target Identification**: Identify modified files requiring audit. <!-- (감사가 필요한 수정된 파일들을 식별합니다.) -->

2. **Silent Background Scan**: Run language-specific linting tools or dry-run tests. <!-- (언어별 린트 도구 또는 테스트 실행을 수행합니다.) -->

3. **The Proof Cycle**: <!-- (검증 사이클 수행) -->
   - If pass: Continue silently.
   - If fail: Initiate **Self-healing**.

4. **Self-healing Protocol**: Attempt automatic correction of minor errors. <!-- (사소한 오류를 자동으로 수정합니다.) -->

5. **Final Integrity Report**: Report results using the standardized 🛡️ labels. <!-- (표준화된 🛡️ 레이블을 사용하여 검증 결과를 사용자에게 보고합니다.) -->

---

## 🐾 Credits

Developed with the assistance of **Google Antigravity Gemini 3 Flash**.
**구글 안티그래비티 Gemini 3 Flash**의 도움을 받아 제작되었습니다. 🐾
