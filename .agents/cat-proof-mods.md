# 🛡️ Cat-Proof: Structured Code Modifications Guide

<!-- 
이 문서는 에이전트가 고정밀 코드 수정 작업을 수행할 때 따라야 할 정형화된 절차를 정의합니다. 
This document defines structured procedures for high-precision code modifications.
-->

## 1. JSON Enforcement Protocol

<!-- 
중요한 코드 수정 시 기계 판독 가능한 JSON 구조를 사용하여 변경 사항을 제안하고 검증합니다. 
During critical code edits, use the following JSON structure to propose and verify changes:
-->

```json
{
  "file": "path/to/file",
  "reason": "Detailed explanation of the change",
  "changes": [
    {
      "type": "modify|add|delete",
      "startLine": 10,
      "endLine": 15,
      "content": "New content here"
    }
  ],
  "impact_analysis": "How this affects other modules",
  "verification_plan": "Specific steps to verify this change"
}
```

## 2. Validation Criteria

<!-- 
Cat-Proof는 위 JSON 데이터를 바탕으로 다음 사항을 검증합니다. 
Cat-Proof validates the following:
-->

1.  **Parseability**: Verify if the JSON is valid and parseable. (JSON 데이터가 유효한 형식인지 확인합니다.)
2.  **Context Match**: Ensure line ranges match current file content. (라인 범위가 실제 파일 내용과 일치하는지 확인합니다.)
3.  **Safety**: Validate impact analysis for unintended side effects. (잠재적 부작용에 대한 영향 분석이 타당한지 확인합니다.)

## 3. Shared Accountability

<!-- 
모든 변경 사항은 구조화된 로그로 남으며, 시스템 무결성을 유지하는 감사 추적(Audit Trail) 역할을 합니다. 
All changes are recorded as structured logs, acting as an audit trail to maintain system integrity.
-->

---

## 🐾 Credits

Developed with the assistance of **Google Antigravity Gemini 3 Flash**.
**구글 안티그래비티 Gemini 3 Flash**의 도움을 받아 제작되었습니다. 🐾

## 📄 License

This project is distributed under the [MIT License](LICENSE).
