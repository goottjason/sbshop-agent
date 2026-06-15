---
description: "Cat-Memory-Recovery Smart Snip Workflow."
---

# 🧠 Cat-Memory-Recovery: Smart Snip Workflow (`/cat-snip`)

<!-- 
이 워크플로우는 대화 이력이 길어질 때 핵심 맥락은 유지하면서 불필요한 데이터를 압축하여 토큰 소모를 최적화하는 과정을 정의합니다. 
This workflow defines the process of optimizing token consumption by compressing unnecessary data while maintaining core context.
-->

1. **Monitor Token Usage**: Check if context usage exceeds 50% or response speed is slowing down. <!-- (컨텍스트 사용량이 50%를 넘거나 응답 속도가 느려졌는지 확인합니다.) -->

2. **Identify Snip Targets**: Look for older, completed task blocks or long technical outputs <!-- (e.g., directory listings). (완료된 이전 작업 블록이나 긴 기술적 출력물을 타겟으로 지정합니다.) -->

3. **Perform Semantic Summary**: Briefly summarize the identified block's purpose and its final conclusion. <!-- (타겟 블록의 목적과 최종 결론을 짧게 요약합니다. 예: "12개 파일의 인증 로직 분석 완료.") -->

4. **Apply [CAT_SNIP] Tags**: Replace the original block with the summarized text using standardized tags. <!-- (`SKILL.md`에 정의된 표준 스닙 태그를 사용하여 원본 블록을 요약문으로 대체합니다.) -->

5. **Verify Continuity**: Ensure that the most recent task and critical design decisions are NOT snipped. <!-- (현재 진행 중인 작업과 핵심 설계 결정 사항이 유실되지 않았는지 확인합니다.) -->

6. **Preservation**: Ensure important information is mirrored in the Shared Scratchpad via `Cat-Bridge`. <!-- (중요한 정보는 공유 작업판에 기록되어 지식이 보존되는지 확인합니다.) -->

---

## 🐾 Credits

Developed with the assistance of **Google Antigravity Gemini 3 Flash**.
<!-- **구글 안티그래비티 Gemini 3 Flash**의 도움을 받아 제작되었습니다. 🐾 -->
