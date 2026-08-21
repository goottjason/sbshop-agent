# 프론트엔드 "왜" 주석 보존 기록 (Phase 2)

**작성일:** 2026-08-21 · **작성 주체:** refactor-frontend
**목적:** 교리 §1에 따라 프론트 전 소스의 주석을 제거하기 전에, **코드로 표현할 수 없는 제약**을 담고 있어
사라지면 다음 사람이 같은 함정에 빠지는 내용을 원문에 가깝게 옮겨 적는다.

**경로 표기:** 모두 `frontend/src/` 기준. 절대경로 접두사 = `/Users/jasonair/Projects/sbshop-agent/frontend/src/`
**라인 번호:** 제거 **이전** 기준(현재 파일과는 어긋난다). 위치는 파일:심볼로 찾아라.

---

## 1. 날짜·타임존 — 이 프로젝트 전체의 근본 전제

### `utils/datetime.ts` (파일 헤더)
> 백엔드는 모든 시간 필드를 zone 정보 없는 `LocalDateTime`(UTC 벽시계값)으로 직렬화한다
> (컨테이너 TZ=UTC, Jackson time-zone은 LocalDateTime 직렬화에 영향 없음).
> 따라서 프론트에서 naive 문자열을 UTC로 간주해 KST(Asia/Seoul)로 변환·표시한다.
> 이 방식은 기존 데이터·신규 데이터를 모두 일관되게 교정하며, 브라우저 로컬 존과 무관하다.

- `HAS_ZONE` 정규식이 존 정보 유무를 판정하고, 없으면 `Z`를 붙여 UTC로 파싱한다 (`toKstDate`).
- **이 전제를 모르고 `new Date(naiveString)`을 쓰면 브라우저 로컬(KST)로 오해석해 9시간 어긋난다.**

### `utils/orderExcelExport.ts` · `formatDateTime`
> 백엔드 LocalDateTime(zone 없는 UTC 벽시계값)을 KST 표시 문자열로. 그리드와 같은 규칙.

### `pages/OrderGrid.tsx` · 상대시각 계산부 (구 957행)
> 백엔드 시각은 zone 없는 UTC 벽시계값(`LocalDateTime.now()`)이므로 `toKstDate`로 UTC로 파싱해야
> 경과시간이 맞다. raw `new Date(naive)`는 브라우저 로컬(KST)로 오해석해 9시간 어긋난다
> (동기화 바·재고 반영시각 공용).

### `pages/dashboard/TrendChart.tsx` · `bucketEndDate`
> `MONTH`: 다음 달 0일 = 이번 달 말일 (`new Date(y, m, 0)` Date 트릭)

### `pages/dashboard/period.ts`(구 `PeriodControl.tsx`) · `computeRange`
> 선택된 (연,월,단위) → 조회 구간. 일/주는 그 달, 월은 최근 12개월.
> `new Date(v.year, v.month, 0)` = 그 달 말일 · `new Date(v.year, v.month - 1 - 11, 1)` = 12개월 전 1일
> `PeriodValue.month`는 **1-12**(JS Date의 0-11이 아님).

---

## 2. 마켓 API 함정 · 계약

### `api/marketApi.ts` · `MarketCredential`
> 시크릿 평문(`accessKey`·`secretKey`·`refreshToken`)은 관리자 인증(HTTP Basic) 하의
> `/api/v1/market-credentials/**` 응답에만 담겨 내려온다.
> **빈 값으로 저장하면 서버가 기존 값을 유지한다 (F-CRED-8).**

### `api/marketApi.ts` · `issueCafe24Token`
> `code` 또는 `code=...`를 포함한 **리다이렉트 전체 URL을 그대로 보내도 서버가 code만 추출**한다.

### `api/axios.ts` · `ADMIN_AUTH_KEY`
> 관리자 HTTP Basic 자격증명 저장 키(sessionStorage). 로그인 시 base64("id:pw")를 저장하고,
> 요청 인터셉터가 있으면 Authorization 헤더로 실어보낸다.
> **시크릿 보호 엔드포인트(`/api/v1/market-credentials/**`)만 인증을 요구하며, 나머지 요청은 헤더가 있어도 무시된다.**

### `pages/Settings.tsx` · 자격증명 로드 effect
> 인증된 관리자에게는 서버가 시크릿 평문을 내려주므로 저장값을 그대로 표시한다.
> 비운 채 저장하면 서버가 기존 값을 유지한다(F-CRED-8).
> `enabled: authed` — 인증 전에는 조회하지 않는다(401 방지).
> Cafe24 상태는 토큰의 '존재'가 아니라 **실제 API 호출 성공 여부**로 판정한다.
> 재인증 카드는 상태 점검이 상품·주문 권한을 **모두** 검사하므로 주문 권한만 없어도 뜬다.

### `api/orderApi.ts` · `ShipmentDto` (D-148)
> **송장의 진실은 발송처(iHerb 발송메일)이고, `marketTrackingNo`는 "마켓이 알고 있는 값"일 뿐이다.
> 두 값의 불일치가 곧 "마켓 미반영"이며, 화면은 이 비교로 배지를 판정한다.**
> - `marketTrackingNo`: 마켓이 알고 있는 송장. 우리 송장과 다르면 마켓 미반영이다.
> - `manualFixRequired`: **마켓이 영구 거부해 사람이 판매자센터에서 직접 고쳐야 하는 상태.**
> - `trackingSource`: `'EMAIL'`은 iHerb 메일이 확인한 진짜 송장, `'MANUAL'`·`'MARKET'`은 사람·마켓이
>   넣은 진위 불명 값. `null`은 이 기능 이전의 과거 데이터.

### `api/orderApi.ts` · `OrderLineItemDto.shippingData.trackingSentToMarket` (D-129)
> 마켓이 이 송장을 갖고 있는가. 우리가 전송해 성공했거나, 마켓이 실송장을 알려준 경우 true.
> 송장은 있는데 이 값이 true가 아니면 "저장됨 · 마켓 미반영" 상태다.

### `pages/OrderGrid.tsx` · `marketSyncState` — **캠페인 최중요 salvage**
마켓 반영 상태는 **세 가지**다(D-148). D-127(11번가 송장 전송이 항상 실패)이 오래 눈에 띄지 않았던
이유가 이 구분이 화면에 없었기 때문이다. 2026-08-07에는 한 걸음 더 나아갔다: "기다리면 되는 건"과
"사람이 마켓에 가서 직접 고쳐야 하는 건"이 같은 배지로 보여, 영원히 반영되지 않는 주문이 조용히 쌓였다.

| 상태 | 뜻 |
|---|---|
| `synced` | 마켓이 아는 송장 = 실제 송장. 할 일 없음 |
| `waiting` | 아직 못 보냈고 다음 사이클에 자동 재시도. 기다리면 됨 |
| `manual` | 마켓이 영구 거부(배송중 송장 수정 등). **사람이 판매자센터에서 직접 고쳐야 함** |
| `unknown` | 마켓 값을 확인하지 못했다 |

> **판정 근거는 `trackingSentToMarket` 플래그가 아니라 두 송장 값의 비교다.**
> 그 플래그는 전송이 실패해도 참으로 남을 수 있어 미반영을 가린다(D-147).

**D-156:** 마켓 송장을 모르면 **모른다고 말한다**. 종전에는 `trackingSentToMarket`으로 폴백해
`synced`(반영됨)라고 단정했는데, **영구 거부 종결 처리가 바로 그 플래그를 true로 세운다**
(D-146/D-154의 `markTrackingAsSent`). 그래서 **사람이 고쳐야 할 건일수록 "반영됨"으로 보였다** —
신고 2026-08-08: 우리는 CJ, 마켓은 우체국인데 화면은 `✓ 마켓 반영됨`.
`waiting`으로 뭉뚱그리지도 않는다. 그건 "기다리면 자동으로 된다"는 또 다른 거짓이다
(이미 전송을 끝냈거나 종결된 건은 재시도하지 않는다). 모르는 것은 `unknown`으로 드러낸다.

또한 (D-156에서 제거) **배송상태로 "마켓이 송장을 갖고 있다"고 추정하던 폴백은 근거가 아니었다** —
상태가 SHIPPED라는 건 우리가 그렇게 기록했다는 뜻일 뿐, 마켓이 어떤 송장을 갖고 있는지와 무관하다.

`waiting`은 "기다리면 자동으로 반영된다"는 약속이다. 그 약속이 참인 경우에만 쓴다 —
재시도 큐는 **종결 전 주문만** 담고(D-144), 배송완료 주문은 마켓도 송장 수정을 거부한다.
그래서 배송완료인데 미전송인 건(2026-08-08 라이브: 쿠팡 81건, 6~7월 주문)은 대기가 아니라 미확인이다.

`NO_SEND_STATUSES = ['CANCELED','RETURNED','EXCHANGED']` — **종결 상태는 마켓 전송 자체가 불가하므로
미반영 경고 대상이 아니다.**

### `pages/product/MarketBadgeCell.tsx` · `handoff` — 팝업 차단
> **`window.open`은 사용자 제스처 콜스택 안에서 동기 실행돼야 팝업이 차단되지 않는다.**
> `writeText`를 await하면 그 콜스택이 끊기므로, 순서는 그대로 두고 성공/실패 토스트만
> 프로미스가 끝난 뒤 `.then()/.catch()`로 나중에 띄운다 — 실패를 조용히 삼키지 않는다.
> G마켓·옥션은 자동 등록이 불가능하므로 사람을 마켓플러스로 데려간다. 조회를 먼저 끝내고
> 다이얼로그 확인(사용자 제스처) 안에서 새 탭을 연다 — **조회 후에 열면 팝업이 차단된다.**

### `pages/product/MarketBadgeCell.tsx` · 등록가 파라미터 (결함 B)
> 등록가가 쿠폰율·최소마진 미반영으로 목표가보다 크게 높게 올라간다(정기 재가격 배치는 **D-093으로 비활성**).
> `AntModal.confirm`의 문구만으로는 값을 받을 수 없어 제어형 Modal로 바꾸고,
> `ProductGrid.tsx` 일괄 업데이트 모달과 같은 기본값(15/20/5000)으로 입력받는다.

### `api/sourcingApi.ts` · `MarketPublishPriceParams` (결함 B)
> 등록가 산정(마진율·쿠폰율·최소마진) — 정기 재가격 배치가 비활성(D-093)이라 등록 시점에
> 다이얼로그가 직접 받아 반영한다. **값을 안 보내면 백엔드는 종전 동작(오버라이드 없음)과 같다.**

### `api/productApi.ts` · `MarketPlusHandoff`
> G마켓·옥션은 상품등록 API가 없어 사람이 마켓플러스에서 전송한다.
> 서버는 "어느 상품코드로 찾으면 되는지"까지만 알려준다.

### `api/productApi.ts` · `MarketBadgeState` semantics
> 마켓 배지 1칸의 서버 상태. **키가 없으면 그 마켓은 미등록**(클릭하면 등록).
> `status`: `'SYNCED'` 등록 완료 · `'PENDING'` 등록행은 있으나 동기화 미완료.
> `url`: 마켓 상품페이지. **링크 식별자 미확보면 null.**
> `ProductList.marketRegistrations` 키는 백엔드 `MarketType.name()`
> (COUPANG / SMART_STORE / ELEVEN_STREET / GMARKET / AUCTION / CAFE24) — D-047.

### `api/productApi.ts` · `updatePriceStock`
> **`soldOut=null`이면 재고상태는 변경하지 않고 가격만 반영한다(백엔드 F-PROD-7).**

### `api/orderApi.ts` · `updateOrderLineItem`
> 백엔드 `OrderLineItemUpdateRequest`는 **`isUnipassDone`만 받는다.** 소싱금액/물류비는
> `updateSourcingInfo`로 보낸다.

### `pages/BatchUpdatePage.tsx` · SSE 파싱 계약
> `BATCH_COMPLETED`/`BATCH_FAILED` payload 포맷은 **`"batchId|success"`** — `split('|')[0]`이 batchId다.
> D-089: SSE 구독으로 다른 클라이언트(동업자)가 시작한 배치도 진행바에 공유한다.
> `batchIdRef`는 SSE 콜백이 최신 batchId를 참조하게 해 **자기 배치 중복 `startTracking`(요약 리셋)을 막는다.**
> D-038: 대상 상품이 없으면 백엔드는 `{message}`만 반환(batchId·count 없음) → undefined 방지 분기 필요.

### `pages/ProcessStatusPage.tsx` · `actionType` 라벨링 (D-050/D-076)
> **`actionType`은 enum이 아니라 자유문자열**이며 관례상 `{MARKET}_SYNC` 패턴이다.
> **명시 라벨이 `_SYNC` 접미 패턴보다 우선한다** (CUSTOMS_SYNC/STOCK_SYNC 등은 마켓 라벨 조합이
> 아니라 고정 라벨). 그 외는 원문 폴백(미매칭 시 깨지지 않게).
> `actionTypeLabels`는 백엔드 `ActionLogConstants`와 값이 매칭된다.

---

## 3. 렌더 성능 · React 규칙 (되돌리면 조용히 깨지는 것들)

### `pages/product/productGridShared.ts` · 파일이 존재하는 이유
> **상수·헬퍼를 `MarketBadgeCell.tsx`에 두면 `react-refresh/only-export-components`에 걸린다**
> (컴포넌트 파일은 컴포넌트만 export해야 HMR이 안전). 그래서 상수/헬퍼 전용인 이 파일로 옮겼다.
> **되돌리면 린트가 깨진다.**

### `pages/dashboard/BreakdownPanels.tsx` · 훅 규칙
> **고정 4회 명시 호출 (조건/반복 금지).** `useBreakdownQuery`를 `.map()`으로 도는 순간
> 훅 호출 순서가 데이터에 종속돼 훅 규칙을 위반한다.

### `pages/OrderGrid.tsx` · `OrderTableRow` 메모 경계
> 셀 1개를 편집하면 낙관적 캐시 패치로 **"그 주문의 행 객체"만 새 참조**가 되고(`patch*`가 나머지
> 주문의 참조를 보존), 나머지 행은 `original` 참조가 동일하므로 이 memo가 재렌더를 건너뛴다.
> 결과적으로 편집 시 변경된 주문의 3행만 재렌더 → 수백~수천 셀 전체 재렌더로 인한 굼뜸이 사라진다.
> **`row` 인스턴스는 매 렌더 새로 생성되므로 비교에서 의도적으로 무시**하고, 안정적인
> `original`/`isSelected`/`isOrderBoundary`/`colCount`만 비교한다
> (스킵 시 이전 렌더 출력은 동일 데이터라 안전).

### `pages/OrderGrid.tsx` · 낙관적 캐시 패치 (`patchOrder`/`patchLineItem`)
> 해당 lineItem을 가진 주문만 새 객체로 교체하고 **나머지 주문은 참조를 그대로 유지한다.
> 이 참조 안정성이 행 메모이제이션의 전제**가 된다. 저장 즉시 화면 반영(왕복 대기·깜빡임 제거),
> 실패 시 스냅샷으로 롤백.

### `pages/OrderGrid.tsx` · `handleUpdate` 안정 참조
> react-query v5의 `mutateAsync`는 렌더 간 안정 참조라 `handleUpdate`가 안정된다
> → **`columns` useMemo가 매 렌더 재생성되지 않아 전체 그리드 재렌더가 사라진다.**

### `pages/OrderGrid.tsx` · `processedData` 참조 재사용
> `data`가 바뀔 때만 재계산하고, **변경되지 않은 주문의 행 객체는 이전 참조를 재사용한다**
> → 낙관적 캐시 패치로 한 주문만 바뀌면 그 주문의 행만 새 참조가 되어, 메모된 행이 변경된
> 주문의 3행만 재렌더한다(셀 1개 편집에 전체 그리드가 재렌더되던 문제 제거).

### `pages/OrderGrid.tsx` · 행 호버 처리
> 행 호버(같은 주문 그룹 전체 음영)는 **React state 대신 DOM 클래스 토글**로 처리한다.
> 이전엔 `hoveredOrderId` state가 매 호버마다 전체 그리드(수백~수천 행)를 리렌더해 음영 지연이 발생했다.

### `pages/OrderGrid.tsx` · `<style>` 안 호버 규칙
> 같은 주문 그룹 전체 호버 음영 — **TD에 `!important`로 적용해야** frozen 셀의 인라인 배경까지
> 리렌더 없이 즉시 덮인다.

### `pages/OrderGrid.tsx` · `InlineInput`의 `selectAllOnFocus`
> **`select()`만으로는 부족하다 — 마우스 클릭은 focus 뒤에 커서를 놓아 선택을 풀어버린다.**
> 다음 틱으로 미뤄(`setTimeout(..., 0)`) 클릭 처리가 끝난 뒤 선택한다.
> 클릭·포커스 즉시 전체 선택은 통째로 복사하거나 덮어쓰는 필드(배송메시지)용이다.

### `pages/OrderGrid.tsx` · `blurLeftToPage`
blur가 "페이지 내 다른 영역으로 이탈"인지 판정 → **이때만 저장한다.**
- 컨테이너 내부 이동(select↔input) → false (계속 편집 중)
- **창/탭 전환(Alt-Tab 등, `document.hasFocus()`=false) → false (복붙 위해 잠깐 나간 것, 저장 안 함)**
- 그 외(페이지 내 다른 셀/빈 영역 클릭 or Tab) → true (저장)

인라인 자동저장 입력의 키 계약: Enter=커밋(=blur 유발, 필수 아님) · Escape=원복 후 이탈 ·
저장 성공=초록 플래시, 실패=빨강+원복 · **외부 값(동기화·낙관적 패치)이 바뀌면 편집 중이 아닐 때만 draft에 반영.**

### `pages/OrderGrid.tsx` · 인라인 저장상태 언어(전 셀 공통)
> `dirty`(앰버)=변경됨·미저장 · `saving`(파랑)=전송중 · `saved`(초록)=저장완료 플래시 ·
> `error`(빨강)=실패·원복

### `pages/OrderGrid.tsx` · 배송정보 셀이 자동저장이 아닌 이유
> 다른 자동저장 셀과 달리 **마켓 API 실호출(실패 시 백엔드 롤백)**이므로 blur 자동저장이 아니라
> 사용자가 명시적으로 [전송]한다. 택배사·송장이 둘 다 있고 저장값과 다를 때만 버튼 활성.

### `pages/OrderGrid.tsx` · 다중필드 셀의 1회 저장 배칭
> 실구매가+물류비 → sourcing 1회 · 구매계정+공급처+구매주문번호+할인코드 → sourcing 1회 ·
> 택배사+송장 → `updateShippingInfo` 1회 → **마켓 API 1회.**
> 택배사와 송장을 따로 저장하면 **"새 택배사 + 옛 송장"이 마켓에 전송되는 불일치**가 생긴다.

### `pages/OrderGrid.tsx` · 저장 성공/실패 판정
> **200 응답 = 마켓 전파가 실제로 성공한 경우만**(실패는 백엔드가 롤백 후 500).
> 마켓 반영 실패 시 백엔드가 `@Transactional` 롤백 후 500을 반환한다. 사유를 토스트로 표면화하고,
> 그리드 셀이 롤백된 원본 값으로 되돌아오도록 orders 쿼리를 무효화(refetch)한다.

### `pages/product/ProductDetailModal.tsx` · `EditRow`가 모듈 최상위에 있는 이유
> **모듈 최상위에 두어 매 입력 리렌더 시 리마운트(포커스 이탈)를 방지한다.**
> 컴포넌트 본문 안에서 정의하면 매 렌더마다 새 컴포넌트 타입이 되어 입력 도중 포커스가 날아간다.

### `pages/product/ProductDetailModal.tsx` · dirty 판정
> 로드 시 `baseline`과 현재 폼을 비교. **둘 다 `toFields` 산출이라 키 순서가 동일 → JSON 비교가 안전하다.**

---

## 4. 보안 · 데이터 무결성

### `pages/product/ProductDetailModal.tsx` · `safeHttpUrl`
> 편집 가능한 URL만 새 탭으로 연다. **사용자가 직접 고치는 필드라 `javascript:` 같은 스킴이 들어올 수
> 있고, 그걸 그대로 href에 넣으면 클릭 한 번에 스크립트가 실행된다. http/https만 통과시킨다.**
> 인풋 자체를 링크로 만들면 편집이 불가능해지므로, 여는 동작은 옆 버튼이 맡는다.
> 값이 없거나 http(s)가 아니면 **버튼 자리를 유지하되 비활성** — 레이아웃이 흔들리지 않게.

### `pages/product/ProductDetailModal.tsx` · enum 옵션 하드코딩 이유
> enum 필드 옵션(백엔드 enum명 = value, 한글 라벨 = label). **잘못된 값 저장(400)을 원천 차단.**
> 현재 값이 옵션에 없으면(레거시 값) **그 값도 옵션에 보존해 표시** — 사용자가 건드리지 않으면 원값이 유지되도록.
> 빈 선택은 `undefined`로 저장(미변경 = 백엔드에서 스킵).

### `pages/product/ProductDetailModal.tsx` · 저장 경로 2단계 (D-060/F-PROD-7, D-106)
> 판매가 변경 시 **먼저** 마켓 가격 동기화 경로(`updatePriceStock`, 재고상태 미변경 → `soldOut=null`)로
> 반영하고, 그 다음 전체 편집 필드를 `PUT /api/v1/products/{id}`로 저장한다.
> 평탄 폼 → `ProductUpdateRequest` 매핑의 **유일한 차이는 `productName` → `name`**.

### `utils/orderExcelExport.ts` · 엑셀 서식 강제
> **주문번호(17자리)·송장(12~13자리)·우편번호는 엑셀이 숫자로 읽으면 지수표기(2.02607E+16)로
> 뭉개지거나 앞자리 0이 사라진다. 텍스트 서식(`numFmt='@'`)을 강제해 원본을 보존한다.**
> 상품URL도 text 서식 고정 — **URL을 엑셀이 하이퍼링크로 자동 변환하며 값을 건드리는 것을 막는다.**

### `utils/orderExcelExport.ts` · dynamic import (번들 크기)
> **exceljs는 번들이 크므로 이 모듈 자체를 호출 시점에 dynamic import 하도록 설계했다**
> (호출부에서 `await import('../utils/orderExcelExport')`). 다운로드를 누르지 않는 사용자는
> 이 코드를 내려받지 않는다. **정적 import로 바꾸면 초기 번들이 급증한다.**

### `utils/orderExcelExport.ts` · 3행 → 1행 평탄화
> 그리드는 한 칸에 여러 값을 겹쳐 보여주고 한 주문상품을 3행(order/product/fulfillment)에 나눠
> 그리므로 화면과 1:1로 옮길 수 없다. **엑셀에서는 주문상품 1건 = 1행으로 되돌리고 모든 값을
> 개별 컬럼으로 평탄화한다.** `processedData` 순서(=화면 정렬)를 유지해야 엑셀과 화면을 대조할 수 있다.

### `pages/OrderGrid.tsx` · 필터 "전체 선택"을 빈 배열로 보내는 이유 — **되돌리면 데이터 유실**
> `stockStatuses`/`vendors`는 백엔드에서 correlated EXISTS 서브쿼리(`Product.stockStatus`,
> `OrderLineItem.productId`, `sourcingData.sourcingVendor` — **모두 nullable/미설정 가능**)로 필터링된다.
> **SQL `IN(...)`은 NULL을 매치하지 않으므로, "전체 선택" 상태에서 명시적 전체 목록을 보내면
> 상품/재고/소싱 메타데이터가 없는 라인아이템의 주문이 검색 결과에서 조용히 누락된다.**
> 전체 선택(기본값)은 **빈 배열(=백엔드 no-op, 무필터)**로 보내고, 실제 부분선택일 때만 목록을 보낸다.

### `pages/OrderGrid.tsx` · 대시보드 드릴다운의 기간 처리
> 대시보드 드릴다운은 **기간 무관 조회(전체 기간)를 의도하고 `startDate`/`endDate`를 의도적으로 생략**할 수 있다.
> 이 경우 그리드 기본값(1개월 전)으로 클램프하면 오래된 주문(예: 40일 지연 NEW)이 숨겨지므로
> `undefined`(무제한)로 둔다.

### `pages/OrderGrid.tsx` · 미매핑 상품 표시
> **미매핑은 '-'로 감추지 않는다** — 그 라인아이템은 재고·정산·소싱에서 통째로 빠지므로 사람이
> 손을 대야 한다(2026-08-12 G마켓 유령 리스팅 주문 4478251768).

### `pages/OrderGrid.tsx` · D-023 SSE 안전장치
> SSE 연결이 영구 실패(CLOSED)하면 `SYNC_COMPLETED`/`SYNC_FAILED`가 도달하지 않아
> **로딩 오버레이가 무한 고착된다.** 동기화 중이었다면 로딩을 해제하고 사용자에게 알린다.
> 각 동기화 핸들러의 타임아웃도 같은 목적의 안전장치다.
> `isSyncingRef`는 SSE `onerror` 콜백(이펙트 클로저)에서 `isSyncing`의 최신값을 stale 없이 읽기 위한 ref다.

### `pages/OrderGrid.tsx` · 재고 동기화의 다단계 refetch (D-057)
> 재고 동기화는 마켓 동기화와 달리 **완료 이벤트(SSE)가 없는 백그라운드 크롤(수 초~수 분)**이라,
> 고정 3초 refetch만으로는 화면 변화가 없어 "반응 없음"으로 체감됐다.
> ① 시작을 즉시 토스트로 명확히 알리고 ② 오버레이는 짧게 풀되 ③ **지연 refetch를 다단계로 걸어**
> 크롤이 끝나는 대로 갱신이 반영되게 한다.

### `pages/sourcing/DraftReviewPage.tsx` · 등록 직전 강제 저장
> **저장하지 않은 편집이 등록에 반영되지 않는 사고를 막기 위해 등록 직전에 항상 저장한다.**

---

## 5. 서버와 1:1로 맞춰야 하는 상수 (어긋나면 조용히 오작동)

| 위치 | 제약 |
|---|---|
| `pages/sourcing/SourcingSettingsPage.tsx` · `WEIGHT_FIELDS` | **가중치 키는 서버 `CandidateScoringService`의 키와 1:1로 맞춰야 한다** |
| `pages/sourcing/ScoreBreakdownPanel.tsx` · `LABELS` | **서브스코어 키는 서버 키와 1:1** |
| `pages/sourcing/DraftReviewPage.tsx` · `NAME_LIMITS` | **마켓별 상품명 최대 길이는 서버 `MarketProductRules`와 같은 값이어야 한다** |
| `pages/ProcessStatusPage.tsx` · `actionTypeLabels` | 백엔드 `ActionLogConstants`와 값이 매칭된다 |
| `pages/product/productGridShared.ts` · `MARKET_BADGES` 키 | 백엔드 `MarketType.name()` |
| `pages/dashboard/drilldown.ts` | **드릴다운 URL 빌더는 OrderGrid의 URL 파라미터 파싱과 계약이 일치해야 한다** |

---

## 6. 마켓 배지 상태 기계 (`productGridShared.ts` · `badgeVisual`)

배지 1칸이 가질 수 있는 화면 상태와 **각 상태를 그렇게 다루는 이유**:

| 상태 | 의미 · 이유 |
|---|---|
| `registered` | 등록 완료 + 상품페이지 링크 확보 → 채색 배지, 클릭 시 새 탭 |
| `registeredNoLink` | 등록 완료 + **애초에 링크를 만들 수 없는 마켓(카페24)** → 채색 배지, 클릭 없음(`<span>`) |
| `linkless` | 등록됐으나 링크 식별자 미확보(**비정상**) → 채색 테두리 반투명, 클릭 없음 |
| `pending` | 등록행은 커밋됐으나(고아 방지) 마켓 동기화 미완료. **마켓이 등록을 거절하면 영구히 이 상태로 남는다.** 성공/실패 불명이라 **재시도 버튼을 두면 마켓에 중복 리스팅이 생길 수 있어 클릭 불가** → 주의색(호박색) 테두리 |
| `missing` | 미등록 → 점선 배지, 클릭 시 등록 |
| `blocked` | 미등록 + **선행조건 미충족**(카페24 미등록 상태의 G마켓·옥션) → 흐린 점선, 클릭 불가 |

- **`PENDING`은 url 유무와 무관하게 최우선으로 판정한다** — 동기화 미완료 상태를 "링크만 없는 정상 등록"으로
  오인시키면 안 된다.
- **`NO_LINK_MARKET_KEYS = ['CAFE24']`**: 카페24는 상품페이지 URL을 만들 방법이 없다 — 백엔드가 항상
  `MarketBadgeState.of(synced, null)`로 내려준다(`ProductController.buildMarketMap`). 그래서 이 목록에 있는
  마켓은 url이 없어도 "등록됐지만 링크 미확보"(비정상)가 아니라 "정상 등록, 원래 링크가 없음"으로 판정한다.
  **다른 마켓(쿠팡·N스토어·11번가)은 링크를 만들 수 있으므로 없으면 진짜 비정상이다 — 여기 넣지 말 것.**
- **`ESM_MARKET_KEYS = ['GMARKET','AUCTION']`**: ESM 계열은 Cafe24 등록행을 경유해야 전송할 수 있다.
  카페24 등록행이 없으면 마켓플러스로 보낼 수 없다.
- `MARKET_BADGES` 순서는 화면 표시 순서 그대로다. **카페24가 G마켓·옥션의 선행조건이라 그 앞에 둔다.**
- 통합 주문 관리 배지와 동일한 파스텔 팔레트(연배경 + 채도 낮춘 글자색)를 채용한다.

### `productGridShared.ts` · 마켓 등록 가격 기본값
> `DEFAULT_MARKET_MARGIN_RATE=15` / `COUPON_RATE=20` / `MIN_MARGIN_PRICE=5000`
> **`ProductGrid.tsx`의 일괄 가격/재고 업데이트 모달과 같은 기본값을 쓴다 — 등록가와 배치 재산정가가
> 기본적으로 어긋나지 않도록 사용자가 화면마다 다시 맞출 필요가 없게 한다.**

---

## 7. UI 레이아웃 판정 (사용자가 내린 결정)

| 위치 | 판정 |
|---|---|
| `pages/OrderGrid.tsx` 송장 셀 | **배지와 전송 버튼은 한 줄에 둔다 — 셀 높이를 늘리지 않기 위해서다 (2026-08-07 사용자 판정)** |
| `pages/OrderGrid.tsx` 송장 셀 | 출처 아이콘은 배지와 전송 버튼 **사이**에 둔다 — 마켓 반영 여부와 다른 축이므로 배지에 섞지 않는다 |
| `pages/OrderGrid.tsx` 송장 수정 힌트 | 입력칸이 "바꿀 값", 아래 줄이 "**지금 마켓에 잘못 들어가 있는 값**" |
| `pages/OrderGrid.tsx` 툴바 | 제목·동기화 상태·액션을 한 줄에 병합(이전 2줄 82px → 34px) |
| `pages/OrderGrid.tsx` 필터 패널 | 기본 접힘(화면당 주문 건수 확보), **사용자의 선택은 브라우저(localStorage)에 기억** |
| `pages/OrderGrid.tsx` 요약칩 | 접힌 상태에선 검색어·검색 버튼까지 요약 바에 노출해 펼칠 필요를 없앤다. 비기본값은 강조색 |
| `pages/OrderGrid.tsx` 미반영 필터칩 | D-148: "마켓 수동수정 필요"는 이 화면에서 **유일하게 사람의 행동을 요구하는 신호**다. 190건 사이에 섞인 5건은 배지만으로는 놓치므로 칩으로 걸러 볼 수 있게 한다 |
| `pages/OrderGrid.tsx` 행 필터링 단위 | **행 3개(주문/상품/발송)가 한 라인아이템을 이루므로 라인아이템 단위로 걸러야 표가 깨지지 않는다** |
| `pages/OrderGrid.tsx` 컬럼 폭 | 폭은 `table-layout:fixed`가 헤더 기준으로 비율 확장 → 셀에는 최소폭만 두고 maxWidth 캡은 제거 |
| `pages/OrderGrid.tsx` 반응형 확장 | 뷰 폭이 총 컬럼폭보다 넓으면 그 비율(scale)만큼 모든 컬럼이 늘어난다. **frozen 컬럼의 고정 left 오프셋은 이 scale로 함께 보정해야** 정렬이 어긋나지 않는다 |
| `pages/OrderGrid.tsx` 주문 구분선 | 다음 행이 다른 주문(또는 마지막 행)이면 이 행이 주문의 마지막 행. 그 위치에 전체 폭 회색 스페이서 행을 삽입해 주문 단위를 구분 |
| `pages/OrderGrid.tsx` 인라인 편집 스타일 | 크기는 `index.css`의 밀도 토큰(`--field-pad`/`--field-fs`)을 따른다. **이 값은 행 높이(`--row-h`)와 연동된다** — 구매정보 셀이 컨트롤 4개를 rowSpan=3 안에 쌓기 때문 |
| `pages/product/MarketBadgeCell.tsx` 배지 줄 | `nowrap`: 6개 마켓이 한 줄에 모두 보이도록(줄바꿈 방지). 컬럼 폭은 ProductGrid에서 확보 |
| `components/ui/Table.tsx` `fluid` | `fluid=true`면 부모 폭 100%(전폭 데이터테이블용). 기본값(false)은 `inline-block`으로 콘텐츠 폭에 맞춘다 — **OrderGrid 등 가로스크롤·frozen 컬럼 화면 하위호환.** `minTableWidth`는 좁은 화면에서 컬럼이 찌그러지지 않게 바깥 overflow 컨테이너에 가로 스크롤을 만든다 |
| `pages/BatchUpdatePage.tsx` 테마 | antd 전역 primary가 검정이라 **이 페이지만 `ConfigProvider`로 그린 적용**(상품/주문 페이지와 통일) |
| `pages/sourcing/DiscoveryPage.tsx` REVIEW 노출 | **통관 REVIEW 후보를 목록에서 빼지 않고 경고 배지로 노출하는 게 핵심** — 판정이 애매한 상품을 조용히 감추면 사용자가 기회를 잃고, 왜 사라졌는지도 모른다. 대신 초안 생성 후 검수 화면에서 명시적 승인을 요구한다 |
| `pages/sourcing/DraftReviewPage.tsx` 배치 | 사용자가 실제로 손대는 건 대부분 **상품명과 묶음수량**이라 공통 영역 맨 위에 둔다. 마켓별 탭에는 **미충족 필수필드**를 보여준다 — **등록 버튼을 막는 이유가 화면에 있어야 한다** |
| `pages/sourcing/ScoreBreakdownPanel.tsx` | 기여도(contribution)는 이미 "가용 가중치 대비"로 정규화된 값이라 그대로 더하면 총점이 된다. **결측 항목을 함께 보여주는 게 중요하다 — 점수가 낮은 이유가 "상품이 별로"인지 "신호를 못 얻었다"인지 구분되지 않으면 사용자가 잘못된 판단을 한다** |
| `pages/sourcing/DiscoveryPage.tsx` 폴링 | 발굴이 도는 동안만 폴링(10초). 크롤이 수 분 걸려 짧게 볼 이유가 없다 |
| `api/sourcingDiscoveryApi.ts` `domesticMedianPrice` | **국내 시세 중앙값이 가격 경쟁력 판정 기준 — 최저가는 소용량·샘플에 걸려 비교 불가** |
| `api/sourcingDiscoveryApi.ts` `parseJsonField` | JSON 문자열 컬럼을 안전하게 파싱. **서버가 `"[]"`·null·깨진 값을 줄 수 있다** |
| `api/sourcingDiscoveryApi.ts` `CustomsVerdict` | **REVIEW는 사용자가 성분을 확인하고 승인해야 등록할 수 있다** |
| `api/batchApi.ts` `manualUpdate` (F-BATCH-M1) | **`productId`·`price`·`stock`을 쌍(items)으로 전송해 값이 엉뚱한 상품에 적용되는 오염을 차단한다** |
| `pages/ProductRegisterPage.tsx` (F-PSRC-2/6) | 크롤·저장 실패 항목을 **조용히 누락하지 않고 사용자에게 표면화한다** |
| `pages/product/MarketBadgeCell.tsx` `extractError` | axios 오류에서 사용자에게 보여줄 사유를 뽑는다. 백엔드는 `{ message }` 또는 `{ error }`로 내려준다. 409는 "카페24 등록이 먼저 필요합니다" |
| `pages/product/ProductDetailModal.tsx` `extractErrorMessage` | **백엔드는 실패 시 `{ message }`를 준다(`GlobalExceptionHandler`) — 사유를 그대로 표면화** |
| `pages/OrderGrid.tsx` 통관 색상 | VALID일 때만 `verifiedPerson`에 따라 파란색, `INVALID_*` 상태일 때 주황색 |
| `pages/OrderGrid.tsx` 배송정보 셀 | 우편번호는 **마켓이 주는 값이라 읽기 전용**. 배송메시지는 주소와 같은 인라인 편집 필드 |
| `api/orderApi.ts` `ProductDto.updatedAt` | 상품 마지막 갱신시각(재고 크롤 save 시 갱신). **"재고 반영 시각" 프록시로 사용** |
| `pages/OrderGrid.tsx` `stockCellInfo` | IN_STOCK → 구입가능 뱃지만(재입고일 행 없음) · OUT_OF_STOCK + restockDate → 품절 뱃지 + 입고일 행 · OUT_OF_STOCK + 무재입고일 → 품절 뱃지만 · 그 외(null/undefined) → `'-'`. `updatedAt`은 존재할 때만 상대시각으로 표시 |
| `utils/phone.ts` `formatPhone` | 하이픈을 붙이지 않는 이유: 이 값은 읽는 것보다 **택배·마켓 시스템에 그대로 옮겨 붙이는** 용도가 많다. 하이픈이 있으면 붙여넣은 뒤 매번 지워야 한다. 마켓마다 하이픈 유무가 제각각이라 표시 시점에 숫자만 남겨 통일한다. **숫자가 하나도 없으면 원본을 그대로 둔다**(빈 값·이상 데이터를 조용히 삼키지 않기 위해) |
| `pages/dashboard/dashboardApi.ts` `iso` | 항등 함수인 이유 = 인자가 `'YYYY-MM-DDTHH:mm:ss'` 형태로 **이미 조립된 상태**로 들어오기 때문 |
| `api/orderApi.ts` `ProductDto.productName`/`originalName` | Product 엔티티의 **flat 필드(둘 다 String)**. 이전엔 중첩 객체로 잘못 정의돼 상품명이 표시되지 않았다 |
| `api/productApi.ts` `ImageUploadResult` (D-049, F-PROD-12/16) | 자사 저장 성공 여부(`storageUpdated`)와 마켓별 재게시 결과(성공/스킵/실패)를 분리해 담는다. **부분 실패를 사용자에게 표면화하기 위함.** `imagesFailed`는 개별 이미지 처리(리사이즈/다운로드) 실패가 조용히 드롭되지 않게 한다 |

---

## 8. 삭제된 데드코드가 갖고 있던 정보

| 삭제 대상 | 주석 내용 (참고용 보존) |
|---|---|
| `api/orderApi.ts` `fetchOrderCount` | 대시보드 현황 집계용 경량 카운트. `size=1`로 조회해 `totalElements`만 사용한다 |
| `api/productApi.ts` `PriceStockSyncResult` (D-060) | 가격/재고 저장 시 마켓 동기화 결과(`MarketRepublishResult` 레코드). `synced`/`skipped` = `MarketType.name()` 배열, `failed` = `{MARKET: 오류메시지}` |
| `utils/datetime.ts` `formatKstDateTime` | KST `"YYYY-MM-DD HH:mm"`(분 단위). **`sv-SE` 로케일이 `"YYYY-MM-DD HH:mm:ss"` 형태를 반환하므로 앞 16자만 잘라 쓰는 트릭** — 다시 필요해지면 이 방식이 가장 짧다 |
| `pages/product/productMockApi.ts` `updateProductFields` | 모킹 잔재. 실제 저장은 이미 `productApi.updateProduct`로 넘어갔다 — [[bugs-frontend.md]] B7 참조 |
