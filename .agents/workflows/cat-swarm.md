---
description: "Cat-Swarm Execution Workflow for multi-agent coordination. (/cat-swarm 멀티 에이전트 협업 실행 워크플로우)"
---

# 🐾 Cat-Swarm: Multi-Agent Command Routine (`/cat-swarm`)

<!-- 
이 워크플로우는 캣-리더가 대규모 태스크를 하부로 쪼개고, 여러 크루를 소집하여 병렬로 해결하는 과정을 정의합니다. 
This workflow defines the process where the Cat-Leader decomposes large tasks into sub-tasks and summons multiple crews to solve them in parallel.
-->

1. **Acknowledge Complexity**: Inform the user that the task is complex and will be handled via Cat-Swarm. <!-- (작업의 복잡성을 인정하고 캣-스웜 체제로 전환함을 사용자에게 알립니다.) -->

2. **Phase 1: Research & Decomposition**: Analyze the request and split it into independent sub-tasks for research crews. <!-- (요청을 분석하고 연구 크루를 위한 독립적인 하부 태스크로 분할합니다.) -->

3. **Phase 2: Strategic Synthesis (Coordinator Mode)**: 
   - Collect reports from research crews. <!-- (연구 크루의 보고서를 수집합니다.) -->
   - **Synthesize** findings into a unified execution plan. <!-- (발견된 내용을 하나의 통합 실행 계획으로 '합성'합니다.) -->
   - Write the plan to `.agents/scratchpad/plan.md` via `Cat-Bridge`. <!-- (합성된 계획을 스크래치패드에 기록합니다.) -->

4. **Phase 3: Orchestrated Implementation**: 
   - Summon implementation crews based on the synthesized plan. <!-- (합성된 계획에 따라 구현 크루를 소집합니다.) -->
   - Ensure crews read `plan.md` from the scratchpad before starting. <!-- (시작 전 크루들이 스크래치패드의 계획서를 읽도록 보장합니다.) -->

5. **Phase 4: Global Verification**: 
   - Monitor progress through the Shared Scratchpad. <!-- (공유 작업판을 통해 진행 상황을 모니터링합니다.) -->
   - Aggregate final reports and verify against the original goal. <!-- (최종 보고서를 집계하고 원래 목표와 대조하여 검증합니다.) -->

6. **Knowledge Preservation**: Update the central `SUMMARY.md` with the final session outcomes. <!-- (최종 세션 결과를 중앙 요약서에 갱신하여 지식을 보존합니다.) -->

---

## 🐾 Credits

Developed with the assistance of **Google Antigravity Gemini 3 Flash**.
**구글 안티그래비티 Gemini 3 Flash**의 도움을 받아 제작되었습니다. 🐾
