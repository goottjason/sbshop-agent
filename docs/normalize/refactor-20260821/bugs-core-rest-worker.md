# 발견 항목 — core(order/product/sourcing 제외) + worker

리팩토링 중 발견했으나 **수정하지 않은** 것들. 교리 §5에 따라 기록만 한다.
defect-ledger.md 반영은 리더가 판단한다.

## TODO 백로그

없음. 담당 범위 107개 파일에 `TODO`/`FIXME`/`XXX`/`HACK` 주석이 하나도 없었다.

---

## 버그 후보

### B-1. 수동 이메일 트리거가 진행 중인 동기화를 "완료"로 덮어쓴다
- **위치**: `backend/worker/src/main/java/com/sbshop/agent/worker/controller/EmailFetchController.java:41-46`
- **증상**: 스케줄러(cron 0/30)가 `fetchAndProcessEmails()`를 실행 중일 때 `/internal/email/fetch`를 부르면, 재진입 가드가 본처리를 스킵해 `executed=false`가 반환되는데도 컨트롤러는 그대로 `syncStatusService.markCompleted(EMAIL)`을 호출한다.
- **결과**: 아직 돌고 있는 실행에 대해 `/orders/sync/status`가 `COMPLETED` + `lastSyncedAt=지금`을 표시한다. 화면상 "완료"인데 실제로는 진행 중이다. 진짜 실행이 끝나면 다시 completed로 찍히므로 최종 상태는 맞지만, 그 사이 구간의 표시가 거짓이다.
- **판단 근거**: `markRunning` → `fetchAndProcessEmails()` → `markCompleted`가 반환값 `executed`와 무관하게 무조건 실행된다. `executed==false`면 이번 호출은 아무 일도 하지 않았으므로 상태를 건드리지 않는 것이 맞다.
- **심각도 추정**: 낮음~중간 (표시 오류, 데이터 훼손 없음)
- **참고**: `EmailFetchControllerSyncStatusTest`는 `executed=true` 경로와 예외 경로만 검증하고 스킵 경로의 상태 기록은 검증하지 않는다 — 수정 시 Red 테스트를 여기에 추가할 수 있다.

---

## 데드 의심 (교리 §4 — 미참조 public이므로 삭제하지 않고 리포트만)

### D-1. `EmailAccount` enum 전체가 미참조
- **위치**: `backend/worker/src/main/java/com/sbshop/agent/worker/config/EmailAccount.java` (전체 46줄)
- **근거**: `backend/**` 전체에서 이 타입을 참조하는 코드가 자기 자신 외에 **0건**이다 (`fromEmail`, `getAllEmails`, `getEmail`, `getImapEmail`, `getDisplayName` 모두 외부 호출자 없음). 계정 목록은 `EmailAccountProperties`(env `EMAIL_ACCOUNTS` + `sbshop.email.accounts`)로 완전히 대체됐다.
- **부수 문제**: 하드코딩된 7개 계정이 현행 운영 계정 구성(Gmail 중앙전달 + 비-Gmail 직접 IMAP)과 맞지 않아, 살아 있다고 착각하고 참고하면 틀린 정보다. 실계정 주소가 소스에 박혀 있기도 하다.
- **제안**: 파일 삭제. Spring 진입점·JPA 엔티티·Jackson DTO 어디에도 해당하지 않는 순수 enum이라 리플렉션 경로도 없다.

### D-2. `BatchScheduler.scheduleDailyIherbPriceUpdate()` — 호출자 없음
- **위치**: `backend/worker/src/main/java/com/sbshop/agent/worker/scheduler/BatchScheduler.java:22`
- **근거**: D-093(2026-07-21 사용자 결정)으로 `@Scheduled`가 제거되어 스케줄러도 다른 코드도 이 메서드를 부르지 않는다. **삭제하지 않았다** — 재활성화 대기 상태의 의도적 보존이기 때문이다(근거는 `salvage-core-rest-worker.md` §1에 보존).
- **주의**: 컴파일러가 지켜주지 못하는 코드라 조용히 썩는다. 특히 하드코딩 파라미터 `margin 15 / coupon 20 / minMargin 5000`은 D-093이 문제 삼은 바로 그 값들이므로, 재활성화 시 정책 저장소 참조로 바꾸기 전에는 복원하면 안 된다.

### D-3. `MarketRegistrationDefaults.unconfigured()` — 호출자 없음
- **위치**: `backend/core/src/main/java/com/sbshop/agent/core/config/MarketRegistrationDefaults.java:67`
- **근거**: "판매자가 직접 확인해야 하는 미설정 항목(운영 점검용)"을 반환하는 API인데 백엔드·프론트 어디에서도 호출하지 않는다. 즉 **운영 점검 기능이 화면/엔드포인트에 연결되지 않은 채로 남아 있다.**
- **제안**: 점검 화면에 연결하거나(설정 진단 API), 계획이 없다면 삭제. 지금 상태는 "있는 줄 알았는데 아무 데도 안 뜨는" 기능이다.

### D-4. 기타 미참조 public 멤버 (도메인 API, 저위험)
| 위치 | 멤버 | 비고 |
|---|---|---|
| `core/.../domain/market/MarketRegistration.java:249` | `assignSbProductId(Long)` | 호출자 0 |
| `core/.../domain/process/ProcessStatus.java:80` | `mergeChannelResult(String)` | 호출자 0 |
| `core/.../domain/common/BaseEntity.java:41` | `archive()` | 호출자 0 (`delete()`는 사용 중) |

셋 다 JPA 엔티티의 상태 변경 메서드라 리플렉션·프록시 경로 가능성이 있어 삭제하지 않았다. 실제로 쓰이지 않는다면 정리 대상.

---

## 관찰 (버그 아님, 참고용)

- **`InternalAccessGuard.isAllowed`가 `String.equals`로 토큰을 비교**한다(`core/.../config/InternalAccessGuard.java`). 타이밍 공격 관점에서는 `MessageDigest.isEqual`이 정석이지만, 이 프로젝트의 위협 모델(내부 트리거, nginx 미노출)에서는 과잉 대응으로 보인다.
- **`EmailFetchController`의 옛 Javadoc이 "내부(컨테이너 로컬, 8081) 전용"이라고 적고 있었다.** 2026-07-17 worker+api 단일 JVM 병합 이후 실제 포트는 8080이므로 이미 틀린 서술이었다 — 주석 제거로 자연히 해소됐고, 정확한 운영 호출법은 `salvage-core-rest-worker.md` §7에 옮겨 적었다.
- **import 그룹 순서가 파일마다 다르다.** 대부분은 `com` → `java` → `lombok` → `org` 알파벳 순인데 `EmailFetchController`·`EmailAccount`·`OrderSyncScheduler` 등 일부는 그룹을 나눠 다른 순서로 쓴다. 리더의 최종 `spotless` 실행에서 정규화될 것으로 보고 손대지 않았다.
