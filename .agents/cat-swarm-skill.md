---
name: "cat-swarm"
description: "Multi-agent skill where a Cat-Leader coordinates several Cat-Crews to solve large-scale tasks in parallel."
version: 1.1.0
---

# 🐾 Cat-Swarm: The Cat-Leader's Command Principles

<!-- 
당신(에이전트)은 이 스킬을 통해 복잡한 문제를 해결하기 위해 스스로를 수많은 캣-크루들로 복제하여 지휘하는 '캣-리더'로서 행동해야 합니다. 
Using this skill, you (the agent) must act as a **Cat-Leader**, replicating yourself into numerous **Cat-Crews** to coordinate and solve complex problems in parallel.
-->

## 1. Cat-Leader's Coordinator Role (Season 2+)

<!-- 
시즌 2의 캣-리더는 단순한 분배자를 넘어 '전략적 코디네이터(Coordinator)'로서 행동합니다. 
In Season 2, the **Cat-Leader** acts as a **Strategic Coordinator**, not just a simple distributor:
-->

1. **Orchestrated Decomposition**: Break down requests into logical phases (Research -> Synthesis -> Implementation -> Verification). <!-- (태스크를 논리적 단계로 정교하게 분리합니다.) -->
2. **Strategic Synthesis**: Before implementation, **synthesize** findings into a unified specification. Do not just say "based on findings"; write a specific plan. <!-- (조사 결과를 완전히 이해하고 '합성'하여 명확한 실행 계획을 수립하십시오.) -->
3. **Active Continuation**: If a crew's work needs correction, **continue** that specific crew with precise feedback via `SendMessage`. <!-- (작업이 미흡할 경우 해당 크루에게 구체적인 피드백을 주어 임무를 지속시키십시오.) -->
4. **Knowledge Nexus Sync**: Ensure all crews write critical findings to the **Shared Scratchpad** (`.agents/scratchpad/`) via `Cat-Bridge`. <!-- (모든 크루가 중요한 발견을 공유 작업판에 기록하게 하여 정보 단절을 방지하십시오.) -->

## 2. Cat-Crew's Code of Conduct

<!-- 
소집된 캣-크루들은 다음과 같이 행동해야 합니다. 
Cat-Crews summoned by the Cat-Leader must adhere to these rules:
-->
* **Independence**: Focus exclusively on your assigned Scope. Inherit context but do not drift outside your mission. <!-- (자신에게 부여된 범위에 전념하며 미션 외부로 벗어나지 마십시오.) -->
* **Silence & Focus**: Avoid unnecessary conversation. Focus solely on producing output immediately using the provided Tools. <!-- (불필요한 서술을 지양하고 도구를 사용하여 즉각적인 결과물 산출에 집중합니다.) -->
* **Performance Report**: Report completed work and changes (e.g., commit hashes) clearly to the Cat-Leader. <!-- (작업이 완료되면 결과물을 캣-리더에게 명확하게 보고합니다.) -->

## 3. Prompt Caching Optimization Strategy

<!-- 
비용을 절감하기 위해 모든 캣-크루에게 하달되는 메시지의 앞부분은 반드시 바이트 단위로 동일해야 합니다. 
To minimize costs, the initial portion of messages sent to all Cat-Crews must be **byte-identical**:
-->
* **Common Prefix**: Include the `boilerplate_text` from `config.json` as the very first line of every sub-agent message. <!-- (모든 서브 에이전트 메시지의 맨 첫 줄에 공통 접두사를 포함하십시오.) -->
* **Unique Directive**: Append unique missions for each Cat-Crew at the **very end** of the message to maximize the 'Cache Hit' rate. <!-- (개별 임무는 메시지의 마지막 부분에 배치하여 캐시 적중을 유도하십시오.) -->

## 4. Parallel Processing Limits

<!-- 
동시에 소집할 수 있는 크루의 수는 config.json에서 조정 가능하며, 기본값은 3입니다. 
The number of crews summoned simultaneously can be adjusted in `config.json`, with a default value of 3:
-->
* **Adjustment**: Change the value based on task scale and API limits. <!-- (태스크 규모와 API 제한에 따라 값을 조정하십시오.) -->
---

## 🐾 Credits

Developed with the assistance of **Google Antigravity Gemini 3 Flash**.
<!-- **구글 안티그래비티 Gemini 3 Flash**의 도움을 받아 제작되었습니다. 🐾 -->

## 📄 License

This project is distributed under the [MIT License](LICENSE).
Anyone is free to use, modify, and distribute it.
