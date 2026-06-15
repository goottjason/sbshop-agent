---
name: "cat-reasoning"
description: "Structured thinking skill that analyze requirements, alternatives, and risks before taking action."
version: 1.0.0
---

# ⏳ Cat-Reasoning: Thinking Protocol & Guidelines

<!-- 
이 스킬은 에이전트가 복잡한 요청에 대해 성급하게 반응하는 대신, 논리적 추론 과정을 거쳐 최적의 결과를 도출하도록 하는 '지능적 안전장치'입니다. 
This skill serves as an "intelligent safeguard" to ensure that you derive optimal results through a structured logical reasoning process.
-->

## 1. The Thinking Protocol (⏳ Deliberation Steps)

<!-- 
모든 복잡하거나 고위험군 작업을 수행하기 전, 반드시 내부적으로 다음 4단계 사고 과정을 거쳐야 합니다. 
Before performing any high-risk task, you must internally undergo the following 4-step thinking process:
-->
1.  **Contextual Analysis**: Identify the ultimate goal and constraints. <!-- (맥락 분석: 궁극적 목표와 제약 조건을 파악합니다.) -->
2.  **Multilateral Evaluation**: Compare implementation strategies and trade-offs. <!-- (다각도 평가: 여러 구현 전략과 트레이드오프를 비교합니다.) -->
3.  **Predictive Risk Assessment**: Identify potential side effects and failures. <!-- (예측적 위험 평가: 잠재적 부작용과 실패 가능성을 식별합니다.) -->
4.  **Strategic Selection**: Choose the stable and maintainable path. <!-- (전략적 최종 결정: 가장 안정적이고 유지보수가 용이한 경로를 선택합니다.) -->

## 2. Reasoning Visibility (Transparency)

<!-- 
사고 과정은 반드시 사용자에게 투명하게 공개되어야 하며 아래 형식을 준수하십시오. 
The thinking process must be transparently shared with the user:
-->

```markdown
⏳ [CAT_THINKING_START: task_name]
[Analysis -> Alternatives -> Risks -> Decision]
⏳ [CAT_THINKING_END: task_name]
```

## 3. Trigger Conditions (Proactivity)

<!-- 
다음과 같은 상황에서는 능동적으로 이 스킬을 발동하십시오. 
Proactively activate this skill in the following scenarios:
-->
*   **Architectural Changes**: Modifying core structures or design systems. <!-- (핵심 구조 또는 디자인 시스템 수정 시) -->
*   **Massive Deletions**: Deleting or replacing large portions of code. <!-- (대규모 코드 삭제 또는 교체 시) -->
*   **Unclear Directives**: When a prompt is ambiguous. <!-- (지시가 모호하여 여러 해석이 가능할 때) -->
*   **Security-Critical Operations**: Handling sensitive data or API keys. <!-- (보안이 중요한 작업 수행 시) -->

## 4. Implicit Memory Mechanics (Season 2+)

<!-- 
자가 인식 메모리는 사고 전후로 전역 상태를 동기화하여 지능의 연속성을 확보하는 기술입니다. 
Implicit Memory ensures continuity by synchronizing the "Project State" before and after thinking:
-->
*   **Pre-Reasoning Sync**: Read `.agents/state.md` to understand current goals. <!-- (사고 전 `state.md`를 읽어 현재 목표를 파악합니다.) -->
*   **Post-Reasoning Update**: Record new "State Changes" in `.agents/state.md`. <!-- (결정 후 새로운 '상태 변화'를 `state.md`에 기록합니다.) -->
*   **Shared Knowledge**: Use `Cat-Bridge` to sync this state to the scratchpad. <!-- (브리지를 사용하여 이 상태를 스크래치패드에 공유합니다.) -->
---

## 🐾 Credits

Developed with the assistance of **Google Antigravity Gemini 3 Flash**.
<!-- **구글 안티그래비티 Gemini 3 Flash**의 도움을 받아 제작되었습니다. 🐾 -->

## 📄 License

This project is distributed under the [MIT License](LICENSE).
