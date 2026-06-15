---
name: "cat-memory-recovery"
description: "Protects data integrity for new Antigravity users and enables automatic recovery using conversation logs."
version: 1.1.0
---

# 🛡️ Cat-Memory-Recovery: Universal Snapshot Instructions

<!-- 
이 스킬은 Antigravity 에이전트가 사용자의 중요한 작업 결과물을 안전하게 지키는 안전망 역할을 수행하도록 합니다. 
This skill ensures that you, the Antigravity agent, act as a safety net to protect the user's critical work artifacts from loss or accidental deletion.
-->

## 1. 6 Core Project Files (Major Protection Targets)

<!-- 
안티그래비티 환경에서 가장 중요하게 관리되어야 할 6가지 핵심 파일입니다. 이 파일들에 변경이 발생할 때마다 보이지 않는 스냅샷이 생성되어야 합니다.
These are the 6 core files that must be protected in the Antigravity environment. An invisible snapshot must be generated whenever these files are modified.
-->
1.  **Strategic Goals**: `implementation_plan.md` <!-- (전략적 목표) -->
2.  **Progress Records**: `walkthrough.md` <!-- (진행 기록) -->
3.  **Real-time Tasks**: `task.md` <!-- (실시간 작업) -->
4.  **AI Brain**: `.agents/SKILL.md` <!-- (AI 브레인/스킬) -->
5.  **Manuals**: `.agents/workflows/*.md` <!-- (워크플로우 매뉴얼) -->
6.  **Blueprint**: `README.md` <!-- (프로젝트 청사진) -->

## 2. Snapshot Recording Policy (Zero-Silence)

<!-- 
스냅샷은 대화 흐름을 방해하지 않도록 HTML 주석(`&lt;!-- ... --&gt;`) 형식으로 기록되어야 하며, 다음 원칙을 따릅니다.
Snapshots must be recorded in HTML comment format (`&lt;!-- ... --&gt;`) to avoid interrupting the conversation flow:
-->

*   **Zero Loss**: Capture 100% of the original content. Do not summarize. <!-- (원본 내용을 100% 캡처하며 요약하지 마십시오.) -->
*   **Multiple Files**: Create individual snapshot blocks for each modified file. <!-- (동시 변경 시 파일별 개별 스냅샷 블록을 생성합니다.) -->
*   **Trigger Condition**: Only trigger when any of the 6 core files are written/modified. <!-- (오직 6대 핵심 파일의 내용이 수정되었을 때만 작동합니다.) -->

## 3. Snapshot Format Specification

<!-- 
복구 정밀도를 위해 반드시 아래와 같은 정형화된 태그를 사용하십시오. 
Use the following standardized tags for recovery accuracy:
-->

```markdown
<!-- [CAT_SNAPSHOT_START: filename]
[Full Content of the File]
[CAT_SNAPSHOT_END: filename] -->
```

## 4. Smart Snip Protocol (Season 2+)

<!-- 
지능형 이력 압축은 대화 문맥을 유지하면서 토큰 소모를 줄이는 시즌 2 기술입니다. 
Smart Snip technology reduces token consumption while maintaining core context:
-->
*   **Semantic Summarization**: If the conversation becomes too long, identify completed task blocks. <!-- (대화가 길어지면 완료된 태스크 블록을 식별하여 요약합니다.) -->
*   **Tool Output Collapse**: Replace long outputs with a concise **[CAT_SNIP_SUMMARY]**. <!-- (긴 도구 출력물을 요약 블록으로 대체합니다.) -->
*   **Preserve Decisions**: Never snip "Design Decisions" or "User Approvals". <!-- (디자인 결정이나 사용자 승인 사항은 절대 압축하지 마십시오.) -->

## 5. Recovery Protocol (/cat-restore)

<!-- 
사용자가 복구를 요청하면 대화 로그를 역순으로 검색하여 최신 데이터를 원본 경로에 복원합니다. 
When a user requests a recovery, search logs in reverse for the latest snapshot and restore to the original path.
-->

---

## 🐾 Credits

Developed with the assistance of **Google Antigravity Gemini 3 Flash**.
<!-- **구글 안티그래비티 Gemini 3 Flash**의 도움을 받아 제작되었습니다. 🐾 -->

## 📄 License

This project is distributed under the [MIT License](LICENSE).
