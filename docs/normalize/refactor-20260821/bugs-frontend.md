# 프론트엔드 버그·TODO 백로그 (Phase 2 리팩토링 중 확인)

**작성일:** 2026-08-21 · **작성 주체:** refactor-frontend
**상태:** **전부 미수정.** 교리 §5에 따라 기록만 한다. `defect-ledger.md` 편집은 리더가 병합한다.

**경로 표기:** 모두 `frontend/src/` 기준. 절대경로 접두사 = `/Users/jasonair/Projects/sbshop-agent/frontend/src/`
**라인 번호:** 리팩토링 **이전**(서베이 시점) 기준. 주석 제거·데드코드 삭제로 현재 파일은 줄이 당겨졌다 —
위치는 **파일:심볼**로 찾아라.

---

## A. 버그 (서베이 §5에서 이관 + 리팩토링 중 재확인)

### B1. 상품 500건 초과 시 목록·필터·카운트가 조용히 부정확해진다 — **심각도 높음**

- **위치:** `pages/product/ProductGrid.tsx` · `ProductGrid` 컴포넌트의 `useQuery`
- **증상:** 서버에서 500건만 받아 클라이언트에서 필터·페이지네이션을 돌리므로, 상품이 500개를 넘는
  순간 501번째부터는 **존재하지 않는 것처럼 동작한다.** 에러도 경고도 없다.
- **근거 (리팩토링 중 코드로 재확인):**
  - `productApi.fetchProducts(0, 500, keyword || undefined)` — **500건 하드코딩 상한**
  - `applyClientFilters(allRows, filters)` — 카테고리/마켓/소싱처/재고상태를 로드된 500건 안에서만 적용
  - `pageCount`/`safePage`/`pageRows` — 클라이언트 페이지네이션
  - `categoryOptions` — 로드된 500건에서 카테고리를 **역산**해 생성
- **제거된 주석이 명시하던 내용(중요 — 이제 코드에서 안 보인다):**
  > `// 로드된 500건에 고급필터를 적용(서버는 keyword만). 카테고리/마켓/소싱처/재고상태·재고유무.`
  > `// 필터링된 결과에 클라이언트 페이지네이션 적용. 현재 페이지가 범위를 벗어나면 마지막 페이지로 보정.`
- **화면에는 안내가 남아 있다:** ProductGrid 상단 안내 문구
  "고급필터는 로드된 500건 대상 클라이언트 필터입니다. 카테고리는 목록 API 확장 후 활성화됩니다."
  → **이 문구가 유일하게 남은 제약 표시다. 서버 필터를 구현하기 전까지 지우지 말 것.**

### B2. 날짜 필터 기본값이 타임존만큼 밀릴 수 있다 — **심각도 중**

- **위치:** `pages/OrderGrid.tsx` (구 662행, 991-992행) · 기본 날짜 범위 계산부
- **증상:** `toISOString().split('T')[0]`은 **UTC 기준 날짜**다. KST 자정~오전 9시 사이에는 전날이 나온다.
- **근거:** 같은 프로젝트의 `utils/datetime.ts`는 `Asia/Seoul`을 명시 변환하며
  "백엔드는 naive UTC" 전제를 세워 두었는데(→ [[salvage-frontend]] §1) **이 경로만 규칙이 다르다.**
- **주의:** `pages/dashboard/AttentionPanel.tsx`의 `todayMinus`도 같은 `toISOString().slice(0,10)` 패턴이다.
- **수정 금지 이유:** 타임존 취급이 서로 다른 헬퍼를 통합하면 표시 날짜가 밀린다. 행위 변경.

### B3. 마켓 라벨 맵에 `CAFE24`·`AUCTION` 키가 없다 — **심각도 중**

- **위치:** `pages/OrderGrid.tsx` · `marketLabels` (구 970행)
- **증상:** 해당 마켓 주문의 동기화 상태 표시에서 한글 라벨 대신 원문 코드(`CAFE24`)가 노출될 수 있다.
- **근거:** 같은 성격의 맵 3개가 **키 집합이 서로 다르다.**
  | 맵 | 키 수 | 비고 |
  |---|---|---|
  | `pages/OrderGrid.tsx` `marketLabels` | 6 | **CAFE24·AUCTION 누락** |
  | `pages/ProcessStatusPage.tsx` `marketTypeLabels` | 8 | EMAIL·COUPANG_SETTLEMENT 포함 |
  | `utils/orderExcelExport.ts` `MARKET_LABELS` | 6 | AUCTION 있음, EMAIL 없음 |
- **수정 금지 이유:** 통합하면 화면 표시 문자열이 바뀐다(행위 변경). B4와 함께 한 번에 정리해야 한다.

### B4. 같은 마켓이 화면과 엑셀에서 다르게 표기된다 — **심각도 낮음**

- **위치:** `utils/orderExcelExport.ts` `MARKET_LABELS.GMARKET` vs `pages/OrderGrid.tsx` `marketLabels.GMARKET`
- **증상:** 화면은 `GMARKET: 'G마켓/옥션'`, 엑셀은 `GMARKET: 'G마켓'` + 엑셀만 `AUCTION: '옥션'`을 별도 보유.
  같은 주문이 화면과 다운로드 파일에서 다른 이름으로 보인다.
- **판단 필요:** G마켓과 옥션을 한 라벨로 묶는 게 맞는지(ESM+ 계정이 하나라 실무상 묶어 보던 흔적)
  아니면 분리가 맞는지 — **사용자 판정이 필요한 사안이다.**

### B5. 초안(draft) 목록 화면이 없다 — **심각도 중 (기능 공백)**

- **위치:** `api/sourcingDiscoveryApi.ts` · `sourcingDiscoveryApi.drafts`
- **증상:** 목록 API 클라이언트 `drafts(status?)`가 정의만 되고 **호출부가 0건**이다.
  현재 초안 진입 경로는 발굴 직후 첫 건으로 바로 navigate하는 것뿐
  (`pages/sourcing/DiscoveryPage.tsx` · `handleCreateDrafts`의 `navigate('/sourcing/drafts/' + drafts[0].id)`).
  → **이전 세션에서 만들어 두고 처리하지 않은 초안에 도달할 방법이 UI에 없다.**
  2건 이상을 한 번에 초안 생성하면 첫 건 외에는 사실상 접근 불가.
- **연관:** 이 메서드는 데드코드처럼 보이지만 **삭제하면 안 된다** — 아래 §C 참조.

### B6. 개인 이메일 계정 23개가 소스에 하드코딩돼 있다 — **심각도 낮음 (운영 부채)**

- **위치:** `pages/OrderGrid.tsx` · `ACCOUNT_OPTIONS`
- **증상:** 구매계정을 추가·변경·삭제하려면 **코드 수정 + 재배포**가 필요하다.
- **부수 효과:** 개인 이메일 주소가 저장소에 평문으로 남는다.
- **제안:** 서버 설정(공통코드) 또는 환경변수로 이관.

### B7. 주석이 현실과 어긋나 있었다 (모킹 잔재) — **해소됨, 기록만**

- **위치:** `api/productApi.ts` · `ProductEditFields` 위 주석 (구 53행)
- **내용:** "백엔드 PATCH 미구현 → productMockApi로 모킹(다음 세션 구현)"이라 쓰여 있었으나,
  실제 저장 경로는 이미 실물 API로 넘어가 있었다
  (`pages/product/ProductDetailModal.tsx` · `handleSave` → `productApi.updateProduct`).
- **조치 (이번 사이클에서 완료):** 데드 모킹 함수 `updateProductFields`와 그 낡은 주석을 **삭제했다.**
  더 이상 추적할 항목이 아니다.

### B8. eslint `react-hooks/set-state-in-effect` **5건** — **심각도 낮음 (성능)**

effect 본문에서 동기 `setState`를 호출해 연쇄 렌더를 유발한다.
[React 공식 문서](https://react.dev/learn/you-might-not-need-an-effect) 기준 권장되지 않는 패턴이다.

| 파일 | 위치(심볼) | 내용 |
|---|---|---|
| `pages/Settings.tsx` | 자격증명 로드 `useEffect` | effect 본문에서 `setFormData` 동기 호출 |
| `pages/sourcing/DiscoveryPage.tsx` | 초기 로드 `useEffect` | effect 본문에서 `void loadCandidates()` |
| `pages/sourcing/DraftReviewPage.tsx` | 초기 로드 `useEffect` | effect 본문에서 `void load()` |
| `pages/BatchUpdatePage.tsx` | 새로고침 복원 `useEffect` | effect 본문에서 `startTracking(saved)` |
| `pages/ProcessStatusPage.tsx` | 활동 로그 초기 로드 `useEffect` | effect 본문에서 `loadActionLogs()` |

> **⚠️ 서베이 정정.** `survey-frontend.md` §0은 이 규칙을 **3건**으로, react-refresh를 **2건**으로 적었으나,
> Phase 2 착수 시 `git checkout HEAD -- src` 상태에서 직접 측정한 결과는
> **set-state-in-effect 5건 + react-refresh 4건 = 9건**이었다.
> 서베이가 놓친 react-refresh 2건은 `pages/OrderGrid.tsx`가 `stockCellInfo`(구 26행)와
> `shortAccountLabel`(구 83행)을 export하던 것으로, §1-B의 export 한정자 제거로 **함께 해소됐다.**
> (서베이는 이 둘을 '중' 우선순위의 단순 표면 축소로만 분류했는데, 실제로는 lint error 해소 항목이었다.)

- **공통 원인:** 이 5파일은 모두 **TanStack Query를 쓰지 않고 `useEffect` + `useState` 수동 페칭**을 한다.
  `useQuery`로 전환하면 5건이 함께 사라지지만, 캐싱·리페치·로딩 semantics가 바뀌므로 **행위 변경**이다.
- **참고:** `pages/product/ProductDetailModal.tsx`는 같은 패턴을 쓰되
  `// eslint-disable-next-line react-hooks/set-state-in-effect`로 억제해 두었다
  (이번 사이클에서 **유지**했다 — 억제 지시자는 주석이 아니라 어노테이션으로 취급, 교리 §1 예외).

---

## B. TODO 백로그 (제거된 주석에서 이관 — 코드에 더 이상 남아 있지 않음)

### T1. 상품 목록 API 서버 필터/카테고리 미구현 (§2-M3)

**출처 주석 (삭제됨):**
- `api/productApi.ts` `ProductList.category`:
  > 상품 카테고리(ProductCategory enum). **목록 API가 아직 미포함 → 값 없으면 `'-'`.** 다음 세션 백엔드 확장.
- `pages/product/ProductGrid.tsx` `applyClientFilters`:
  > 로드된 500건에 고급필터를 적용(**서버는 keyword만**).

**해야 할 일 4가지:**
1. 목록 API 응답에 `category` 필드 포함
2. 카테고리/마켓/소싱처/재고상태의 **서버 필터 파라미터** 추가
3. **서버 페이지네이션 복원** (현재는 500건 로드 후 클라이언트 분할)
4. **카테고리 옵션 전용 엔드포인트** (현재는 로드된 500건에서 역산)

→ 1~4가 끝나야 **B1이 근본적으로 해소된다.**

### T2. `bulkDeleteProducts`를 단일 엔드포인트로 교체

**출처 주석 (삭제됨):** `pages/product/productMockApi.ts`
> 일괄 삭제: 신규 bulk 엔드포인트 대신 기존 단건 DELETE를 순차 호출(**실동작**).
> 다음 세션에 `POST /api/v1/products/bulk-delete`로 교체(인터페이스 유지).

- **현재 상태:** 이름과 달리 **모킹이 아니라 진짜 구현**이다. `productApi.deleteProduct(id)`를 for 루프로
  순차 호출하고 실패 id를 모아 반환한다. 동작에는 문제가 없다.
- **부채:** 선택 N건 삭제 시 **HTTP 왕복 N회**. 100건 선택 삭제가 100회 요청이 된다.
- **교체 시 주의:** 호출부(`pages/product/ProductGrid.tsx` · `handleBulkDelete`)는
  `{ deleted, failed }` 형태를 기대한다 — **인터페이스를 유지**하면 호출부 수정이 필요 없다.

### T3. `pages/product/productMockApi.ts` 파일명이 사실과 다르다

- 이번 사이클에서 데드 모킹(`updateProductFields`)을 삭제하고 나니 이 파일에는 **실물 구현
  `bulkDeleteProducts` 하나만 남았다.** 파일명 `productMockApi.ts`가 거짓말을 하고 있다.
- **제안:** `productBulkApi.ts`로 rename (순수 구조 변경, 참조처 1곳: `ProductGrid.tsx`).
- **이번 사이클에서 하지 않은 이유:** 리더가 지시한 작업 범위(§1-A 데드코드 · §1-B export 축소 ·
  `applyClientFilters`/`computeRange` 2건)에 **파일 rename이 포함되지 않았다.** 임의 확대하지 않았다.

---

## C. 삭제하면 안 되는 "데드처럼 보이는" 코드 (서베이 §1-C — 이번 사이클에서 손대지 않음)

프론트에서만 보면 호출부 0건이지만, **삭제하면 해당 백엔드 엔드포인트가 UI에서 영영 도달 불가**가 된다.
백엔드 서베이와 대조 후 리더가 판정할 사안이라 **그대로 두었다.**

| 파일 | 메서드 | 대응 백엔드 엔드포인트 |
|---|---|---|
| `api/batchApi.ts` | `batchApi.manualUpdate` | `POST /api/v1/products/batch/manual-update-price-stock` |
| `api/productApi.ts` | `productApi.crawlSourceImages` | `GET /api/v1/products/{id}/images/crawl` |
| `api/productApi.ts` | `productApi.getMarketRegistrations` | `GET /api/v1/products/{id}/markets` |
| `api/productApi.ts` | `productApi.getLocalMarketData` | `GET /api/v1/products/{id}/markets/{marketType}/local` |
| `api/productApi.ts` | `productApi.syncMarketLive` | `POST /api/v1/products/{id}/markets/{marketType}/sync` |
| `api/sourcingDiscoveryApi.ts` | `sourcingDiscoveryApi.candidate` | `GET /api/v1/sourcing/candidates/{id}` |
| `api/sourcingDiscoveryApi.ts` | `sourcingDiscoveryApi.drafts` | `GET /api/v1/sourcing/drafts` (목록) → **B5** |

- `productApi` 4건은 상품 상세의 마켓 연동 UI가 `MarketBadgeCell` 방식으로 재작성되면서 남은
  **이전 세대 API**로 보인다. 백엔드에서도 미사용이면 양쪽을 함께 지우는 게 맞다.

---

## D. 손대지 않은 구조 부채 (서베이 §3 — [백로그] 판정 그대로)

교리 범위 밖이거나 행위 변경을 수반해 **이번 사이클에서 의도적으로 제외**했다.

| 항목 | 규모 | 제외 사유 |
|---|---|---|
| 스타일 4방식 혼재 | 인라인 `style={{}}` **476회** + 전역 CSS 클래스 63회 + JSX `<style>` 4곳 + 공유 스타일 객체 1개 | 시각 회귀 검증 수단(스냅샷 테스트)이 없다 |
| 데이터 페칭 3패턴 혼재 | TanStack Query 5파일 / `useEffect`+`useState` 6파일 / 원시 `EventSource` 3곳 | 캐싱·리페치·로딩 semantics 변경 = 행위 변경. **B8의 근본 원인이기도 하다** |
| SSE 구독·재연결 로직 복붙 | `OrderGrid` · `ProcessStatusPage` · `BatchUpdatePage` 3곳 | 훅으로 추출 가능하나 **재연결 타이밍이 행위**다 |
| toast 라이브러리 이중화 | antd `message` 9파일 / `react-toastify` 3파일 (`MarketBadgeCell`은 **둘 다** 사용) | 알림 위치·모양·지속시간이 바뀐다 |
| 상품 타입 4중 표현 | `orderApi.ProductDto`(중첩) / `productApi.ProductList`(평탄) / `ProductDetail`(중첩) / `ProductEditFields`(평탄) | **백엔드가 엔드포인트마다 다른 모양을 내려주므로 프론트 단독 통합 불가** |
| `VENDOR_OPTIONS` 4중 정의 | `productGridShared`(정본) · `OrderGrid`(`''` 선행) · `ProductRegisterPage`(동일) · `ProductDetailModal`(`{value,label}` 변환) | 통합 가능하나 **이번 지시 범위 밖**. `OrderGrid` 판은 선행 `''`(선택 해제)로 의미가 다르다 |
| 마켓 배지 색상 2중 정의 | `productGridShared.MARKET_BADGES` vs `OrderGrid` 인라인 색상 맵 | 쿠팡·G마켓은 값이 같지만 **CAFE24가 다르다**(`#fffde7/#fbc02d` vs `#ede7f6/#5e35b1`) → 통합 시 색이 바뀐다 |
| `CARRIER_LABELS` 2중 정의 | `OrderGrid` ≡ `orderExcelExport` (**6키 전부 값까지 동일**) | 통합해도 무위험이나 **이번 지시 범위 밖**. 다음 사이클 1순위 |
| `orderExcelExport.ts` 내부 `pad` 중복 | 같은 파일 안에 동일 구현 2개(`formatDateTime`·`timestamp` 각각의 `p`) | 무위험 통합이나 범위 밖 |
| `pages/OrderGrid.tsx` 2,065줄 | 프로젝트 전체의 **27.6%** | **독립 사이클 권장.** `OrderTableRow`의 `React.memo` 경계·클로저 캡처가 얽혀 있어 실수 시 렌더 회귀가 조용히 발생한다 |
| `pages/dashboard/dashboardApi.ts` 위치 | API 클라이언트 9개 중 유일하게 페이지 옆에 colocate | 이동 자체는 기계적이나 **이번 지시 범위 밖** |
| `pages/product/productGridShared.tsx` 확장자 | JSX 0건인데 `.tsx` | rename 가능하나 **이번 지시 범위 밖** |
| `src/hooks/` 부재 | 커스텀 훅 0개, 모든 상태 로직이 컴포넌트 본문에 인라인 | 훅 추출은 렌더 타이밍에 영향 가능 |
| `src/components/ui/`에 파일 1개 | `Table.tsx`만 존재 | 새 컴포넌트를 만드는 일이라 Tidy First 위반 |
