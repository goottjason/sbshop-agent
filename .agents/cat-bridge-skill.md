---
name: "cat-bridge"
description: "Knowledge Nexus skill for cross-agent synchronization via Shared Scratchpad."
version: 1.0.0
---

# 📝 Cat-Bridge: The Knowledge Nexus

<!-- 
당신(에이전트)은 이 스킬을 통해 여러 에이전트가 소환되고 해산되는 과정에서도 '공통의 지식'이 유실되지 않도록 관리하는 '지식 창구' 역할을 수행합니다. 
Using this skill, you (the agent) act as a **Knowledge Nexus**, ensuring that 'Common Knowledge' is not lost even as multiple agents are summoned and dismissed.
-->

## 0. Deployment & Initialization

<!-- 
이 스킬은 사용자의 워크스페이스에 자동으로 지식 기지를 구축하도록 설계되었습니다. 
This skill is designed to automatically establish a knowledge base in the user's workspace:
-->
* **Location**: The scratchpad MUST be located at `.agents/scratchpad/` within the active workspace root. <!-- (스크래치패드는 반드시 활성 워크스페이스 루트의 `.agents/scratchpad/`에 위치해야 합니다.) -->
* **Auto-Creation**: If the directory does not exist, the agent must proactively create it during the first `Cat-Bridge` operation. <!-- (디렉토리가 존재하지 않는 경우, 첫 번째 `Cat-Bridge` 작업 중에 에이전트가 사전에 생성해야 합니다.) -->
* **Distribution**: When distributing this toolkit, only the package should be provided. The scratchpad folder is environment-specific and will be generated dynamically on-site. <!-- (이 툴킷을 배포할 때는 패키지만 제공되어야 합니다. 스크래치패드 폴더는 환경에 따라 다르며 현장에서 동적으로 생성됩니다.) -->

## 1. Shared Scratchpad Protocol

<!-- 
모든 캣-툴킷 에이전트는 작업 중 생성된 중요한 발견이나 중간 데이터, 공통 규칙을 다음 경로에 저장하여 공유해야 합니다. 
All Cat-Toolkit agents must save and share important findings, intermediate data, and common rules in the following path:
-->
* **Path**: `.agents/scratchpad/` (Hidden directory)

1. **Read on Arrival**: Absorb context from existing files in `.agents/scratchpad/` immediately upon arrival. <!-- 조사(Research) - 에이전트 소환 직후, 스크래치패드 내의 파일들을 조사하여 이전 작업자의 맥락을 흡수하십시오. -->
2. **Write on Progress**: Log critical findings (e.g., code structure, logic analysis) to the scratchpad immediately. <!-- 기록(Record) - 새로운 중요한 정보(예: 발견된 코드 구조, 복잡한 로직 분석 결과 등)를 얻으면 즉시 파일로 기록하십시오. -->
3. **Sync on Dismissal**: Update `SUMMARY.md` with a final session summary before dismissal for the next agent. <!-- 동기화(Sync) - 임무 종료 전, 현재까지의 요약본을 `SUMMARY.md` 파일로 갱신하여 다음 에이전트에게 전달하십시오. -->

## 2. Scratchpad Usage Guidelines

<!-- 
공유 작업판을 효율적으로 사용하기 위한 지침입니다. 
Guidelines for efficient use of the Shared Scratchpad:
-->
* **Context Isolation**: Separate analysis from implementation; record "findings" in the scratchpad instead of direct source modification. <!-- 분리(Isolation) - 소스 코드를 직접 수정하는 대신, 코드에 대한 '분석 결과'를 스크래치패드에 적으십시오. -->
* **Atomic Updates**: Use clear, atomic filenames like `research_auth.md` or `logic_flow.md`. <!-- 원자성(Atomicity) - 파일명은 `research_auth.md`와 같이 명확하게 지정하십시오. -->
* **No Redundancy**: Update existing files instead of creating redundant information. <!-- 중복 배제(No Redundancy) - 이미 공유된 정보는 다시 쓰지 말고, 기존 파일을 업데이트하십시오. -->

## 3. Benefits of Independent Bridge

<!-- 
이 스킬은 독립 패키지로 구성되어 있어 어떤 툴킷과도 호환됩니다. 
This skill is an independent package, ensuring compatibility with all Cat-Toolkit modules:
-->
* **Swarm Integration**: Used when tasks divided by `Cat-Leader` are shared by `Cat-Crew`. <!-- `Cat-Leader`가 분할한 업무를 `Cat-Crew`들이 공유할 때 사용. -->
* **Reasoning Sync**: Used when delivering conclusions reached through deep reasoning to the implementation agent. <!-- 깊은 사고 끝에 얻은 결론을 구현 에이전트에게 전달할 때 사용. -->
* **Proof Validation**: Used when centrally managing verification results and repair recommendations. <!-- 검증 결과와 수정 권고안을 중앙에서 관리할 때 사용. -->

---

## 🐾 Credits

Developed with the assistance of **Google Antigravity Gemini 3 Flash**.
<!-- **구글 안티그래비티 Gemini 3 Flash**의 도움을 받아 제작되었습니다. 🐾 -->

## 📄 License

This project is distributed under the [MIT License](LICENSE).
