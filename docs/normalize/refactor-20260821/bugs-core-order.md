# bugs-core-order.md — 리팩토링 중 발견한 버그·백로그 (기록만, 수정 안 함)

2026-08-21 구조 리팩토링 캠페인. 범위: `backend/core/src/{main,test}/java/com/sbshop/agent/core/**/order/**`.
교리 §5에 따라 **직접 수정하지 않았다.** `docs/normalize/defect-ledger.md`는 리더가 병합한다.

## TODO 백로그

`TODO` · `FIXME` · `XXX` · `HACK` 마커: **0건.** 주석처리된 코드 블록: **0건**(`Order.java:77`이 패턴에 걸렸으나 `// @JsonIgnore: ...`로 시작하는 서술문이라 코드가 아니다).
아래 B-3은 마커 대신 산문으로 "백로그 등재"라 적혀 있던 항목이라 여기로 옮긴다.

---

## B-1. `markSentIfSucceeded`의 계약 주석이 사실과 다르고, 실패 로그가 일어나지 않는 롤백을 안내한다

- **위치**: `core/src/main/java/com/sbshop/agent/core/application/order/service/OrderService.java` — 리팩토링 전 기준 L605-618(`markSentIfSucceeded`), 호출부 L362-363 · L507 · L541
- **심각도**: 중 (운영 혼선 · 오진단 유발. 데이터 손상은 없음)

**증상**
`markSentIfSucceeded`의 Javadoc이 *"실패(isFailed)는 호출부에서 예외를 던져 `@Transactional` 롤백으로 처리하므로 이 지점에 도달하지 않는다"* 라고 적고 있고, 내부 `else if (result.isFailed())` 분기에도 `// 도달 불가(호출부가 isFailed에서 이미 throw)` 주석이 붙어 있다. **둘 다 거짓이다.**

**판단 근거**
1. `OrderService`의 세 호출부(L362-363 `updateShipping`, L507 `dispatchLineItem`, L541 `updateTracking`) 어디에서도 `result.isFailed()`에 대해 예외를 던지지 않는다. `grep -n "isFailed()" OrderService.java` 결과 실패 판정은 `logIfNotSent`(L593)와 `markSentIfSucceeded`(L615) 안에만 존재한다.
2. 같은 파일 `logIfNotSent`의 Javadoc이 **D-125에서 계약이 반전됐음**을 명시한다 — *"마켓 전송 실패를 기록해도 로컬 저장은 롤백하지 않는다… 송장은 마켓 API 호출의 성공 여부와 무관하게 실재하는 사실이므로 로컬 기록을 보존한다."* 즉 `markSentIfSucceeded`의 주석은 **D-125 이전(D-069 후속) 계약의 잔재**이고, 반전 시 함께 갱신되지 않았다.
3. 따라서 그 분기는 **실제로 도달하며**, 도달하면 `"라인아이템 {} 마켓 송장 전송 실패 — 롤백 예정: {}"` 이라는 로그가 남는다. **롤백은 예정돼 있지 않다.** 운영자가 이 로그를 보면 저장이 되돌아간 것으로 오해한다.

**부수 문제 (같은 뿌리)**
호출부 3곳 중 **L362-363만** `logIfNotSent`를 함께 부른다. L507(`dispatchLineItem`)과 L541(`updateTracking`)은 `markSentIfSucceeded`만 부르므로, 그 두 경로의 전송 실패는
- 영구 거부(terminal)와 일시 실패의 **구분 없이** 기록되고,
- `logIfNotSent`가 제공하는 정확한 문구("로컬 송장은 보존, 마켓 반영 불가" / "다음 재시도 대상") 대신 위의 거짓 "롤백 예정" 문구로만 남는다.

**수정 방향 제안 (미적용)**
`markSentIfSucceeded`에서 실패 분기를 걷어내고 세 호출부 모두 `logIfNotSent` → `markSentIfSucceeded` 순으로 부르게 통일하면, 실패 로깅이 한 곳(`logIfNotSent`)으로 모이고 terminal/재시도 구분도 세 경로에서 같아진다. **행위 변경이므로 이번 캠페인 범위 밖.**

---

## B-2. `ElevenstOrderSyncService`에 다른 메서드의 Javadoc 2개가 고아로 쌓여 있었다

- **위치**: `core/src/main/java/com/sbshop/agent/core/application/order/service/ElevenstOrderSyncService.java` — 리팩토링 전 기준 L426-443
- **심각도**: 하 (문서 결함. 이번 캠페인의 주석 전량 삭제로 자연 해소됨 — 재발 방지 목적의 기록)

**증상**
`private void applyMarketTrackingFromMissingOrder(...)`(L444) 바로 위에 Javadoc **3개가 연달아** 붙어 있었다.

| 블록 | 실제로 설명하는 메서드 | 그 메서드의 위치 |
|---|---|---|
| 1번째 — "terminal(종결) 상태가 아닌지 판정한다 … (D-028)" | `isNonTerminal` | L473 |
| 2번째 — "이 라인아이템에 적용할 클레임 상태를 고른다" | `resolveClaimFor` | L461 |
| 3번째 — "D-158: 사라진 주문의 … 마켓 보유 송장을 배송 계층에 기록한다" | `applyMarketTrackingFromMissingOrder` | L444 (정상) |

즉 **메서드는 순서가 바뀌었는데 Javadoc은 따라가지 않아** 두 개가 남의 자리에 얹혀 있었다. 컴파일·동작에는 영향이 없지만, IDE의 hover 문서와 생성 Javadoc이 `applyMarketTrackingFromMissingOrder`에 대해 **엉뚱한 설명 3개를 보여주고** `isNonTerminal`·`resolveClaimFor`는 문서가 없는 것으로 나온다.

**시사점**: 메서드를 옮길 때 Javadoc이 딸려가지 않은 이력이 실재한다. 새 주석 규칙을 세울 때 **주석이 코드 위치에 물리적으로 매여 있다는 전제**를 재검토할 근거로 남긴다. (내용 자체는 `salvage-core-order.md` §2.6·§3.7에 보존했다.)

---

## B-3. `Order.marketSpecificData`의 자체 구현 유사 JSON 파서가 값을 조용히 훼손한다

- **위치**: `core/src/main/java/com/sbshop/agent/core/domain/order/Order.java` — `getMarketSpecificDataMap()`(리팩토링 전 L278-302) · `setMarketSpecificDataFromMap()`(L313-329)
- **심각도**: 중 (현재는 회피 규약으로 봉합돼 있으나, 규약을 모르는 다음 사람이 밟는다)
- **코드에 이미 "백로그 등재"라고 적혀 있던 항목** — `ElevenstOrderAdapter.toNestedDto`의 `ordPrdSeqs` 주석.

**증상**
`marketSpecificData`는 Jackson이 아니라 손으로 만든 파서/직렬화기를 쓴다.
- 읽기: `json.split(",")` → `pair.split(":", 2)` → 양쪽에서 `"`를 **전부 제거**
- 쓰기: `"key":"value"`를 그대로 이어붙이고 **이스케이프를 전혀 하지 않는다**

따라서 값에 다음 문자가 들어가면 데이터가 조용히 망가진다.

| 값에 포함된 문자 | 결과 |
|---|---|
| `,` (콤마) | **그 지점에서 값이 잘리고** 뒤쪽이 별개의 잘못된 key:value로 해석되거나 버려진다 |
| `"` (따옴표) | `replace("\"", "")`로 무조건 제거돼 원값이 손실된다. 쓰기 시엔 JSON 구조 자체가 깨진다 |
| `{` `}` | 첫/끝 문자면 substring에 먹힌다 |

`catch (Exception e) { return Map.of(); }`가 모든 실패를 삼키므로 **손상이 예외로 드러나지도 않는다** — 맵이 통째로 비어 발주확인·취소·발송처리가 식별자를 못 찾는 형태로만 나타난다.

**현재의 회피 규약 (D-135)**
11번가 `ordPrdSeqs`와 N스토어 `productOrderIds`는 **콤마 대신 `|`를 정본 구분자로** 쓴다. 읽기는 `[|,]` 둘 다 받아 준다. 상품주문 순번·상품주문번호가 숫자라 오인 여지가 없어 성립하는 규약이다.
**즉 버그를 고친 것이 아니라 특정 두 필드만 피해 간 것이다.** 콤마를 포함할 수 있는 값(예: 주소·상품명·오류 메시지)을 이 맵에 넣는 순간 같은 형태로 터진다.

**수정 방향 제안 (미적용)**
`marketSpecificData`를 Jackson `ObjectMapper`(또는 PostgreSQL `jsonb`) 기반으로 교체하고, 교체 시 기존 행의 값이 새 파서로도 읽히는지 마이그레이션 검증이 필요하다. `catch`가 실패를 삼키는 것도 함께 손봐야 한다 — 지금은 파싱 실패와 "데이터 없음"이 구분되지 않는다. **행위 변경 + 스키마 영향이라 이번 캠페인 범위 밖.**

---

## B-4. 삭제한 `BusinessDayCalculator`의 공휴일 테이블은 2026년까지만 유효했다

- **위치**: `core/src/main/java/com/sbshop/agent/core/domain/order/util/BusinessDayCalculator.java` (**이번 캠페인에서 삭제**)
- **심각도**: 정보 (현재 참조 0건이라 실피해 없음)

전 코드베이스 참조 0건으로 확인돼 교리 §4에 따라 삭제했다. 다만 삭제 전 내용에 남길 만한 사실이 있다:
- `FIXED_HOLIDAYS`가 **2024·2025·2026 3개 연도만 하드코딩**돼 있었다. 2027년 이후 날짜를 넣으면 공휴일이 전부 평일로 계산된다.
- 음력 공휴일(설날·추석)은 코드 주석에 **"대략적 계산"**이라 명시돼 있었다 — 대체공휴일 지정이 바뀌면 어긋난다.

**시사점**: 영업일 계산이 다시 필요해지면 이 클래스를 되살리지 말 것. **되살리는 순간 조용히 틀린 답을 내기 시작한다.** 공휴일 소스를 외부화(공공데이터 특일 정보 API 등)하는 것이 전제다. (`salvage-core-order.md` §15에도 같은 취지로 남겼다.)

---

## 데드 의심 (삭제하지 않음 — 리포트만)

교리 §4에 따라 미참조 public 중 스프링 진입점·JPA 엔티티·Jackson DTO accessor·리플렉션 의심은 삭제하지 않았다.
**이 영역(`core/**/order/**`)에서 새로 발견된 데드 의심 항목은 없다.** `survey-backend.md` §①이 등재한 `SourcingAgentFactory`+`SourcingAgent` 쌍은 `sourcing` 패키지 소관이라 이 담당 범위 밖이다.
