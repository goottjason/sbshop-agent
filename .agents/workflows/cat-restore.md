---
description: "Snapshot Recovery Workflow (/cat-restore)"
---

# 🐾 Cat-Memory-Recovery: Snapshot Recovery Workflow (`/cat-restore`)

<!-- 
이 워크플로우는 보호 대상 파일이 삭제되거나 손상되었을 때, 대화 로그(`overview.txt`) 내의 투명 스냅샷 데이터를 탐색하여 완벽하게 복원하는 과정을 안내합니다.
-->
This workflow guides the restoration of protected files if they are deleted or corrupted, by searching for transparent snapshot data within the conversation logs (`overview.txt`).

1. **Request Filename**: Ask the user which specific file needs recovery (e.g., `implementation_plan.md`).
    <!-- 
    복구가 필요한 파일명을 사용자에게 물어보고 확인합니다. 
    -->

2. **Scan Conversation Logs**: Search for the latest valid `[CAT_SNAPSHOT_START: filename]` block in the system logs.
    <!-- 
    시스템 로그(overview.txt)에서 최신 유효 스냅샷 블록을 탐색합니다. 
    -->

3. **Validate Content**: Verify the snapshot's integrity and ensure no data was truncated.
    <!-- 
    스냅샷의 무결성을 검증하고 데이터 유실이 없는지 확인합니다. 
    -->

4. **Restore File**: Write the retrieved content back to the target file path.
    <!-- 
    대상 파일 경로에 복원된 내용을 다시 기록합니다. 
    -->

5. **Completion Report**: Display a message indicating the file was successfully restored to its previous state.
    <!-- 
    파일이 성공적으로 복구되었음을 사용자에게 보고합니다. 
    -->

6. **Verify Results**: Ensure the restored file is correctly tracked by the monitoring system.
    <!-- 
    복구된 파일이 다시 제대로 보호(감시)되고 있는지 확인합니다. 
    -->
