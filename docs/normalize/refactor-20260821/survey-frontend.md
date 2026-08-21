# 프론트엔드 서베이 (Phase 1 조사 결과)

**대상:** `/Users/jasonair/Projects/sbshop-agent/frontend/src` — ts/tsx 39파일, 7,472 LOC
**조사일:** 2026-08-21 · **코드 변경 없음(조사 전용)**
**경로 표기:** 이 문서의 상대경로는 모두 `frontend/src/` 기준. 절대경로 접두사 = `/Users/jasonair/Projects/sbshop-agent/frontend/src/`

---

## 0. 게이트 현황 (Phase 2 착수 전 기준선)

| 게이트 | 명령 | 결과 |
|---|---|---|
| 타입체크 | `cd frontend && npx tsc -p tsconfig.app.json --noEmit` | **PASS (exit 0, 출력 없음)** |
| 린트 | `cd frontend && npx eslint src --quiet` | **FAIL — error 9건** (unused 계열 0건, 전부 react-hooks/react-refresh) |

### 미사용 변수 관련 컴파일러 옵션 — **이미 켜져 있음**
`tsconfig.app.json`에 `noUnusedLocals: true`, `noUnusedParameters: true`가 설정돼 있고 tsc가 통과한다.
→ **파일 내부(로컬 변수·미사용 파라미터·미사용 import) 데드코드는 이미 0건이다.** Phase 2에서 그 층위를 훑을 필요 없음.
남은 데드코드는 tsc가 볼 수 없는 층위, 즉 **모듈 경계를 넘는 미참조 export / 미참조 파일 / 미사용 npm 의존성 / 미참조 CSS 규칙**뿐이다. 아래 §1이 그 전수 조사 결과다.

> ⚠️ Phase 2 주의: `noUnusedLocals`가 켜져 있으므로, 주석 제거 중 "주석 안에서만 쓰이던" 상수·import를 남기면 **즉시 컴파일 에러**가 난다. 반대로 이건 안전망이기도 하다 — 주석 정리 후 tsc를 돌리면 고아 심볼이 자동 검출된다.

### eslint error 9건 (전부 행위 관련 — 교리 §5에 따라 수정 금지, 백로그행)
| 파일:라인 | 규칙 | 내용 |
|---|---|---|
| `pages/Settings.tsx:83` | react-hooks/set-state-in-effect | effect 본문에서 `setFormData` 동기 호출 |
| `pages/sourcing/DiscoveryPage.tsx:103` | react-hooks/set-state-in-effect | effect 본문에서 `loadCandidates()` |
| `pages/sourcing/DraftReviewPage.tsx:87` | react-hooks/set-state-in-effect | effect 본문에서 `load()` |
| `pages/dashboard/PeriodControl.tsx:6` | react-refresh/only-export-components | 컴포넌트 파일이 `computeRange` 함수도 export |
| `pages/product/ProductGrid.tsx:30` | react-refresh/only-export-components | 컴포넌트 파일이 `applyClientFilters` 함수도 export |

(나머지 4건은 위 3개 set-state-in-effect 항목의 중복 출력 라인)

**react-refresh 2건은 §1-B 처리로 자동 해소된다** — `applyClientFilters`는 export를 떼면 되고(외부 미사용), `computeRange`는 외부에서 쓰므로 `pages/dashboard/period.ts`로 분리 이동하면 된다. 이건 순수 구조 변경이라 교리 위반이 아니다.

---

## 1. 데드코드 인벤토리

### 1-A. 고신뢰 삭제 — 선언부 외 참조 0건 (즉시 삭제 가능)

전부 `grep -rn "\b<이름>\b" --include="*.ts" --include="*.tsx"` 으로 개별 재검증했고, 히트가 **선언 라인 하나뿐**임을 확인했다.

| # | 파일 | 심볼 | 종류 | 비고 |
|---|---|---|---|---|
| D1 | `api/supplierApi.ts` **(파일 전체)** | `supplierApi` | 객체 + 4메서드 | **파일 자체가 어디서도 import되지 않음.** 파일 통째 삭제. 메서드: `getSuppliers`/`createSupplier`/`getCurrencies`/`createCurrency` |
| D2 | `api/marketApi.ts:23` | `fetchCredential` | export const | 목록용 `fetchCredentials`(복수형)만 쓰임 |
| D3 | `api/orderApi.ts:172` | `fetchOrderCount` | export const | |
| D4 | `api/orderApi.ts:270` | `confirmOrder` | export const | 배치판 `confirmOrdersBatch`만 쓰임 |
| D5 | `api/productApi.ts:104` | `PriceStockSyncResult` | export interface | |
| D6 | `utils/datetime.ts:17` | `formatKstDateTime` | export function | 같은 파일 `formatKst`만 쓰임 |
| D7 | `pages/product/productMockApi.ts:7-14` | `updateProductFields` | export function | **모킹 잔재이자 데드코드.** 유일한 히트 3건은 전부 자기 자신(선언 1 + `console.warn` 문자열 2). 호출부 없음 — §2-M1 참조 |

**D8. 미사용 npm 의존성 — `ag-grid-react`, `ag-grid-community`**
- `AgGridReact` 컴포넌트 import: **0건**. 남은 흔적은 CSS import 2줄뿐:
  - `App.tsx:11` `import 'ag-grid-community/styles/ag-grid.css';`
  - `App.tsx:12` `import 'ag-grid-community/styles/ag-theme-quartz.css';`
- src 전체에 `ag-` 접두 className 사용 0건 → 위 CSS는 아무 것도 스타일링하지 않는다.
- TanStack Table 이전 후 남은 잔재로 판단. `App.tsx:11-12` 삭제 + `package.json`에서 두 패키지 제거.
- **판정:** 고신뢰. 다만 package.json 변경은 번들·lockfile에 영향이 있으니 별도 커밋으로 분리 권장.

**D9. 미참조 CSS 파일 — `src/App.css` (182줄, 파일 전체)**
- `App.css`를 import하는 곳이 **0건**(`main.tsx`는 `index.css`만 import, `index.html`도 참조 없음).
- 내용이 Vite 스캐폴드 잔재(`.counter`, `.hero` 등). 파일 통째 삭제.

**D10. 미참조 정적 asset — `src/assets/` 3개 파일**
- `hero.png`, `react.svg`, `vite.svg` — ts/tsx/css 어디서도 참조 0건. `App.css`의 `.hero`(자체가 데드)와만 이름이 겹칠 뿐 실제 참조 아님.
- `src/assets/` 디렉터리 통째 삭제 가능. (`frontend/public/favicon.svg`, `icons.svg`는 별개이며 살아있음 — 건드리지 말 것)

**D11. 미참조 CSS 규칙 — `src/index.css`의 `.ag-theme-alpine` 블록 (235행~243행)**
- index.css 전체 셀렉터를 tsx 사용량과 대조한 결과, 프로젝트 자체 클래스 중 사용량 0인 것은 이 하나뿐이다.
- 게다가 실제 import된 테마는 **quartz**인데 이 override는 **alpine**을 겨냥 — 처음부터 적용된 적 없음. D8과 함께 삭제.
- (`.Toastify__*` 규칙들도 tsx 사용량 0으로 나오지만 **react-toastify 라이브러리가 런타임에 붙이는 클래스**다. 삭제 금지.)

### 1-B. `export` 한정자만 제거 (심볼은 살아있음, 모듈 경계만 축소)

선언 파일 **안에서는 쓰이지만** 외부 참조가 0인 export들이다. 심볼 자체는 살아있으니 **삭제가 아니라 `export` 키워드만 떼는 것**이 정확한 조치다. TS는 비-export 타입을 export 함수 시그니처에 쓰는 것을 허용하므로 컴파일 안전하다(`noEmit` 모드라 declaration emit 이슈 없음).

| 파일 | 심볼 | 우선순위 |
|---|---|---|
| `pages/product/ProductGrid.tsx:30` | `applyClientFilters` | **높음 — eslint react-refresh error 해소** |
| `pages/OrderGrid.tsx:21` | `StockCellInfo` (interface) | 중 |
| `pages/OrderGrid.tsx:26` | `stockCellInfo` | 중 |
| `pages/OrderGrid.tsx:83` | `shortAccountLabel` | 중 |
| `api/axios.ts:6` | `ADMIN_AUTH_KEY` | 낮음 |
| `api/marketApi.ts:37` | `Cafe24Status` | 낮음 |
| `api/orderApi.ts:107` | `OrderLineItemDetailDto` | 낮음 |
| `api/orderApi.ts:254` | `BulkResult` | 낮음 |
| `api/orderApi.ts:261` | `BulkShipResult` | 낮음 |
| `api/orderApi.ts:285` | `SyncStatus` | 낮음 |
| `api/productApi.ts:80` | `MarketOutcome` | 낮음 |
| `api/productApi.ts:84` | `MarketFailure` | 낮음 |
| `api/productApi.ts:88` | `ImageProcessFailure` | 낮음 |
| `api/productApi.ts:112` | `MarketPlusHandoff` | 낮음 |
| `api/sourcingApi.ts:28` | `MarketPublishResponse` | 낮음 |
| `api/sourcingApi.ts:37` | `MarketPublishPriceParams` | 낮음 |
| `api/sourcingDiscoveryApi.ts:4` | `CustomsVerdict` | 낮음 |
| `api/sourcingDiscoveryApi.ts:156` | `MarketDraftPatch` | 낮음 |
| `pages/dashboard/dashboardApi.ts:11` | `BreakdownItem` | 낮음 |
| `pages/product/productGridShared.tsx:58` | `NO_LINK_MARKET_KEYS` | 낮음 |
| `pages/product/productGridShared.tsx:69` | `BadgeVisual` | 낮음 |

> **판단:** '낮음' 항목들은 API DTO 타입이라 향후 소비자가 생길 여지가 있고, 떼도 얻는 게 거의 없다. **비용 대비 효용이 낮으니 Phase 2에서는 '높음'·'중' 4건만 처리하고 나머지는 손대지 않기를 권한다.** 특히 `api/*.ts`의 DTO는 백엔드 계약을 표현하는 공개 표면으로 읽는 편이 자연스럽다.

### 1-C. 데드 의심 — 삭제 보류, 백엔드와 교차 확인 필요

**API 클라이언트 메서드 중 호출부 0건**인 것들이다. 프론트에서만 보면 데드지만, **삭제하면 해당 백엔드 엔드포인트가 UI에서 영영 도달 불가**가 된다. 백엔드 서베이(`survey-backend.md`)와 대조 후 리더가 판정할 사안이다.

| 파일:라인 | 메서드 | 대응 백엔드 엔드포인트 |
|---|---|---|
| `api/batchApi.ts:10` | `batchApi.manualUpdate` | `POST /api/v1/products/batch/manual-update-price-stock` |
| `api/productApi.ts:144` | `productApi.crawlSourceImages` | `GET /api/v1/products/{id}/images/crawl` |
| `api/productApi.ts:150` | `productApi.getMarketRegistrations` | `GET /api/v1/products/{id}/markets` |
| `api/productApi.ts:153` | `productApi.getLocalMarketData` | `GET /api/v1/products/{id}/markets/{marketType}/local` |
| `api/productApi.ts:156` | `productApi.syncMarketLive` | `POST /api/v1/products/{id}/markets/{marketType}/sync` |
| `api/sourcingDiscoveryApi.ts:190` | `sourcingDiscoveryApi.candidate` | `GET /api/v1/sourcing/candidates/{id}` |
| `api/sourcingDiscoveryApi.ts:199` | `sourcingDiscoveryApi.drafts` | `GET /api/v1/sourcing/drafts` (목록) |

- `productApi` 4건은 상품 상세의 마켓 연동 UI가 `MarketBadgeCell` 방식으로 재작성되면서 남은 이전 세대 API로 보인다.
- `sourcingDiscoveryApi.drafts`(목록)는 **초안 목록 화면이 아예 없다**는 뜻이다 — 현재 초안 진입 경로는 발굴 직후 첫 건으로 바로 navigate(`DiscoveryPage.tsx:164`)하는 것뿐. 기능 공백일 가능성이 있어 §5에 버그 후보로 옮겨 적었다.

### 1-D. 주석처리된 코드 블록

**0건.** `^\s*//` 뒤에 코드 문법(`const`/`function`/`if`/`return`/`<Tag` 등)이 오는 라인을 전수 스캔했고, 히트한 13건은 전부 **문장이 코드 식별자로 시작하는 산문 주석**이었다(예: `// MarketBadgeCell.tsx에 두면...`, `// status: 'SYNCED' 등록 완료...`, `// window.open은 사용자 제스처...`). 진짜 주석처리 데드코드는 없다.

---

## 2. 모킹/스텁 잔재

`productMockApi.ts` 하나에 모여 있고, 메모리에 기록된 "모킹 3종"의 현재 상태는 **1종은 이미 실물화, 1종은 반쯤 실물, 1종은 여전히 모킹**이다.

### M1. `updateProductFields` — **모킹이자 데드코드. 삭제 대상.**
- 위치: `pages/product/productMockApi.ts:1-14` (파일 상단 절반)
- 관련 주석: `api/productApi.ts:53` `// 상세 모달 편집 대상 필드(평탄화). 백엔드 PATCH 미구현 → productMockApi로 모킹(다음 세션 구현).`
- **주석이 현실과 어긋나 있다.** 실제 저장 경로는 이미 실물 API로 넘어갔다:
  `pages/product/ProductDetailModal.tsx:191` → `await productApi.updateProduct(productId, { ...rest, name: productName });`
- `updateProductFields` 호출부는 **0건**. 즉 모킹은 남아있지만 아무도 안 쓴다.
- **조치:** 함수 삭제 + `productApi.ts:53`의 낡은 주석 삭제(교리 §1대로 주석은 어차피 전면 제거). 백로그 기록 불필요 — 이미 해소된 항목이다.

### M2. `bulkDeleteProducts` — **이름만 모킹, 동작은 실물. 유지.**
- 위치: `pages/product/productMockApi.ts:16-29`
- 호출부: `pages/product/ProductGrid.tsx:16` (import), 일괄삭제 핸들러
- 실제로는 `productApi.deleteProduct(id)`를 **순차 반복 호출하는 진짜 구현**이다. 모킹이 아니다.
- 남은 백로그: `POST /api/v1/products/bulk-delete` 단일 엔드포인트로 교체(인터페이스 유지). N회 왕복 → 1회.
- **조치:** 코드 유지. 단 **파일명 `productMockApi.ts`가 거짓말을 하고 있다** — M1을 삭제하면 이 파일에는 실물 구현만 남는다. `pages/product/productBulkApi.ts` 등으로 rename 권장(순수 구조 변경).

### M3. 상품 목록 서버 필터/카테고리 미구현 — **여전히 모킹 상태. 유지, 백로그 기록.**
- **증상:** 서버는 `keyword`만 필터링하고, 나머지 고급필터·페이지네이션·카테고리 옵션을 **전부 클라이언트가 500건 한도 안에서** 처리한다.
- 위치:
  - `pages/product/ProductGrid.tsx:67` — `productApi.fetchProducts(0, 500, keyword || undefined)` — **500건 하드 상한**
  - `pages/product/ProductGrid.tsx:29-48` — `applyClientFilters` (카테고리/마켓/소싱처/재고상태 클라이언트 필터). 29행 주석이 직접 명시: `// 로드된 500건에 고급필터를 적용(서버는 keyword만)`
  - `pages/product/ProductGrid.tsx:73-79` — 클라이언트 페이지네이션(`pageCount`/`safePage`/`pageRows`)
  - `pages/product/ProductGrid.tsx:81-84` — `categoryOptions`를 **로드된 500건에서 역산**해 생성 → `ProductFilterPanel.tsx:18`로 전달
  - `api/productApi.ts:21` — `// 상품 카테고리(ProductCategory enum). 목록 API가 아직 미포함 → 값 없으면 '-'. 다음 세션 백엔드 확장.`
- **백로그 항목:** (1) 목록 API에 `category` 필드 포함, (2) 카테고리/마켓/소싱처/재고상태 서버 필터 파라미터, (3) 서버 페이지네이션 복원, (4) 카테고리 옵션 전용 엔드포인트.
- **조치:** Phase 2에서 **코드 손대지 말 것**(행위 변경). 위 4개를 `bugs-frontend.md` TODO 백로그에 이관. 단 29행·21행 주석은 교리 §1에 따라 제거되므로 **제거 전 반드시 백로그로 옮겨 적어야 한다** — 이 주석들이 사라지면 500건 상한이라는 중요한 제약이 코드에서 완전히 보이지 않게 된다.

> **500건 상한은 잠재 버그다.** 상품이 500개를 넘는 순간 필터·카운트·페이지네이션이 조용히 부정확해진다(에러 없이 일부만 표시). §5에 기록.

---

## 3. 아키텍처 비일관성

각 항목에 **[기계적]**(Phase 2에서 바로 처리, 순수 구조 변경) / **[백로그]**(행위·설계 변경 수반, 손대지 말 것) 판정을 붙였다.

### 3-1. 파일 배치 규칙 혼재

**현행 구조**
```
src/api/            actionLog, axios, batch, market, order, product, sourcing, sourcingDiscovery, supplier
src/components/ui/  Table.tsx           ← ui 폴더에 단 1개
src/layouts/        MainLayout.tsx
src/pages/          Dashboard, OrderGrid, Settings, BatchUpdatePage, ProcessStatusPage, ProductRegisterPage
src/pages/dashboard/  AttentionPanel, BreakdownPanels, KpiCards, PeriodControl, TrendChart, dashboardApi.ts, drilldown.ts
src/pages/product/    MarketBadgeCell, ProductDetailModal, ProductFilterPanel, ProductGrid, productGridShared.tsx, productMockApi.ts
src/pages/sourcing/   DiscoveryPage, DraftReviewPage, ScoreBreakdownPanel, SourcingSettingsPage
src/utils/          datetime, orderExcelExport, phone
```

| 문제 | 근거 | 판정 |
|---|---|---|
| **API 클라이언트 위치가 2군데** | 8개는 `src/api/`인데 `pages/dashboard/dashboardApi.ts`만 페이지 옆에 colocate. 이 파일도 `apiClient`는 `../../api/axios`에서 당겨온다(`dashboardApi.ts:1`) | **[기계적]** — `src/api/dashboardApi.ts`로 이동 + import 경로 수정. 참조처 4개(`Dashboard.tsx`, `KpiCards`, `TrendChart`, `BreakdownPanels`, `AttentionPanel`) |
| **훅 디렉터리 부재** | `src/hooks/` 없음. 커스텀 훅 자체가 0개 — 모든 상태 로직이 컴포넌트 본문에 인라인 | **[백로그]** — 훅 추출은 구조 변경을 넘어 렌더 타이밍에 영향 가능. 캠페인 범위 밖 |
| **`components/ui/`에 파일 1개** | `Table.tsx`만 존재. 공유 UI 프리미티브 층이 사실상 미형성 | **[백로그]** — 새 컴포넌트를 만드는 일이라 Tidy First 위반 |
| **`.tsx` 확장자인데 JSX 없는 파일** | `pages/product/productGridShared.tsx` — 상수·타입·순수함수만 있음(JSX 0). `.ts`여야 함 | **[기계적]** — `productGridShared.ts`로 rename. import는 확장자 없이 쓰므로 참조처 수정 불필요 |
| **`pages/` 평면 vs 하위폴더 혼재** | `dashboard/`·`product/`·`sourcing/`은 폴더로 묶였는데 `OrderGrid`(2065줄)·`Settings`·`BatchUpdatePage`·`ProcessStatusPage`·`ProductRegisterPage`는 `pages/` 직속 | **[백로그 — 단, `pages/order/` 분리만 별도 검토]** — 아래 3-6 참조 |

### 3-2. 스타일 접근 혼재 — **4가지 방식이 동시에 쓰인다**

Tailwind는 **없다**(의존성·설정 모두 부재). 확인된 4방식:

| 방식 | 규모 | 위치 |
|---|---|---|
| ① 인라인 `style={{}}` | **476회** — 압도적 주류 | 전 파일. 상위: `OrderGrid.tsx` **144회**, `Settings.tsx` 52, `ProductGrid.tsx` 45, `ProductDetailModal.tsx` 30 |
| ② 전역 CSS 클래스 (`index.css` 279줄) | className 63회 | `.card`, `.card-title`, `.btn-primary`, `.input-field`, `.topbar*` 등. 상위: `Settings.tsx` 20회, `MainLayout.tsx` 11회, dashboard 하위 14회 |
| ③ JSX 안 `<style>{\`...\`}</style>` 태그 (컴포넌트 로컬 CSS) | 4곳 | `ProductGrid.tsx:193`, `OrderGrid.tsx:1938`, `ProductFilterPanel.tsx:42`, `ProductDetailModal.tsx:296` |
| ④ 공유 스타일 객체 | 1개 | `productGridShared.tsx:16` `inputStyle: CSSProperties` |

- 대시보드 하위 5파일만 ②를 일관되게 쓰고, 나머지는 대부분 ①. `Settings.tsx`는 ①②를 한 요소에서 섞는다(`Settings.tsx:129` `<div className="card" style={{...}}>`).
- **판정: [백로그]** — 인라인 476회를 CSS로 옮기는 건 시각적 회귀 위험이 크고 검증 수단(스냅샷 테스트)이 없다. 교리 §범위 밖.
- **다만 [기계적] 부분 1건:** ③의 `<style>` 태그 4개는 **전역 스코프에 CSS를 주입**한다(scoped 아님). `ProductGrid.tsx:193`과 `ProductFilterPanel.tsx:42`가 각각 `.pf-search`·`.pd-inp` 계열을 정의하는데, 셀렉터 충돌 여부만 확인해 두면 좋다. 지금은 접두사(`pf-`/`pd-`)로 갈라져 있어 실제 충돌은 없음 — **현상 유지 권고**.

### 3-3. API 호출 패턴 혼재 — **3가지 데이터 페칭 방식**

HTTP 층은 **일관적이다**: `fetch()` 직접 호출 0건, `axios` 직접 import는 `api/axios.ts:1` 한 곳뿐, 나머지 전부 `apiClient` 경유. **이 층은 손댈 것 없음.**

문제는 그 위의 **상태 관리 층**이다:

| 패턴 | 파일 |
|---|---|
| **A. TanStack Query** (`useQuery`) | `pages/Dashboard.tsx`, `pages/Settings.tsx`, `pages/OrderGrid.tsx`, `pages/product/ProductGrid.tsx`, `pages/dashboard/BreakdownPanels.tsx` |
| **B. `useEffect` + `useState` 수동 페칭** | `pages/ProcessStatusPage.tsx`, `pages/BatchUpdatePage.tsx`, `pages/product/ProductDetailModal.tsx`, `pages/sourcing/DraftReviewPage.tsx`, `pages/sourcing/SourcingSettingsPage.tsx`, `pages/sourcing/DiscoveryPage.tsx` |
| **C. 원시 `EventSource` (SSE)** | `pages/OrderGrid.tsx:1126`, `pages/ProcessStatusPage.tsx:122`, `pages/BatchUpdatePage.tsx:103` |

- **sourcing 모듈 4파일은 전부 B** — 이 모듈만 통째로 react-query를 안 쓴다. 앞의 eslint `set-state-in-effect` error 3건도 정확히 B 패턴에서 나온다.
- **C의 SSE 구독/재연결 로직이 3곳에 각각 복붙**돼 있다(`new EventSource(...)` → `readyState === EventSource.CLOSED` 체크 → 재연결). 훅으로 뽑을 후보지만 재연결 타이밍이 행위다.
- **판정: [백로그 전부]** — B→A 전환은 캐싱·리페치·로딩 상태 semantics를 바꾸므로 명백한 행위 변경. SSE 훅 추출도 마찬가지.

### 3-4. 알림(toast) 라이브러리 이중화

| 라이브러리 | 사용 파일 |
|---|---|
| `antd`의 `message` | `BatchUpdatePage`, `ProcessStatusPage`, `ProductRegisterPage`, `ProductDetailModal`, `SourcingSettingsPage`, `DiscoveryPage`, `DraftReviewPage`, `ScoreBreakdownPanel`, `MarketBadgeCell` |
| `react-toastify`의 `toast` | `ProductGrid`, `OrderGrid`, `MarketBadgeCell`, (`App.tsx`에 `<ToastContainer>`) |

- **`MarketBadgeCell.tsx`는 한 파일에서 둘 다 쓴다.**
- `index.css:243-279`에 `.Toastify__*` 커스터마이징이 36줄 있어 react-toastify 쪽이 "정식" 스타일 기준으로 보인다.
- **판정: [백로그]** — 통일은 사용자가 보는 알림 위치·모양·지속시간을 바꾸는 행위 변경.

### 3-5. 타입 정의 중복

| 중복 | 위치 | 판정 |
|---|---|---|
| **상품 타입 3종 병존** | `api/orderApi.ts:52` `ProductDto`(중첩 구조: `productSpec`/`logisticsInfo`/`sourcingInfo`, 전 필드 optional) vs `api/productApi.ts:3` `ProductList`(평탄, 대부분 required) vs `api/productApi.ts:34` `ProductDetail`(중첩) vs `api/productApi.ts:54` `ProductEditFields`(평탄) — **같은 도메인 엔티티의 4가지 표현** | **[백로그]** — 실제로 백엔드가 엔드포인트마다 다른 모양을 내려주므로 프론트 단독으로는 통합 불가. 백엔드 서베이와 교차 확인 필요 |
| **`VENDOR_OPTIONS` 4중 정의** | `pages/product/productGridShared.tsx:32` (정본, `['IHB','AMZ','FTN','COK','OCD','TES','VTB']`) · `pages/OrderGrid.tsx:74` (빈 문자열 `''` 추가판) · `pages/ProductRegisterPage.tsx:8` (정본과 동일) · `pages/product/ProductDetailModal.tsx:29` (`{value,label}` 객체 변환판) | **[기계적 — 부분]** ✔ `ProductRegisterPage.tsx:8`은 정본과 **완전 동일** → import로 교체(무위험). ✔ `ProductDetailModal.tsx:29`는 정본을 `.map()`한 것과 동일 → 정본 import 후 map. ⚠️ `OrderGrid.tsx:74`는 선행 `''`(=선택 해제)가 있어 **의미가 다름** → `['', ...VENDOR_OPTIONS]`로 파생시키면 동등. `OrderGrid.tsx:648`이 이미 `.filter(v => v !== '')`로 되돌리고 있어 파생 관계가 명확함 |
| **마켓 라벨 맵 3중 정의 (내용 불일치)** | `pages/OrderGrid.tsx:970` `marketLabels`(6키, **CAFE24·AUCTION 누락**) · `pages/ProcessStatusPage.tsx:16` `marketTypeLabels`(8키, 주석이 "OrderGrid 선례 이식"이라 명시) · `utils/orderExcelExport.ts:39` `MARKET_LABELS`(6키, GMARKET을 `'G마켓'`으로 — 앞 둘은 `'G마켓/옥션'`) | **[백로그 — 기계적 통합 금지]** ⚠️ **세 맵의 키 집합과 값이 서로 다르다.** 통합하면 화면 표시 문자열이 바뀐다 = 행위 변경. §5에 불일치를 버그로 기록 |
| **마켓 필터 옵션 2중 정의** | `pages/product/productGridShared.tsx:22` `MARKET_FILTER_OPTIONS` vs `pages/OrderGrid.tsx:797-802` 인라인 배열 | **[백로그]** — 두 목록의 마켓 구성이 화면별로 의도적으로 다를 수 있음. 확인 전 통합 금지 |
| **마켓 배지 색상 2중 정의** | `pages/product/productGridShared.tsx:42` `MARKET_BADGES` vs `pages/OrderGrid.tsx:1470-1474` 인라인 색상 맵 | **[기계적 — 값 대조 후]** 확인된 겹침(COUPANG `#fce4ec/#c2185b`, GMARKET `#c8e6c9/#1b5e20`)은 **값이 동일**. CAFE24만 다름(`#fffde7/#fbc02d` vs `#ede7f6/#5e35b1`) → **색이 다르므로 통합 시 행위 변경.** 백로그행으로 강등 |
| **`CARRIER_LABELS` 2중 정의** | `pages/OrderGrid.tsx:49` vs `utils/orderExcelExport.ts:30` — **6키 전부 값까지 완전 동일**. `orderExcelExport.ts:29` 주석이 스스로 인정: `/** ... 그리드의 CARRIER_LABELS와 같은 표를 쓴다. */` | **[기계적] ✔ 안전한 통합 1순위** — `utils/`의 공용 모듈로 뽑고 양쪽에서 import. 값이 100% 같아 행위 변경 없음 |
| **날짜 포맷 헬퍼 산재** | 정본은 `utils/datetime.ts`인데 별도 구현이 6곳: `utils/orderExcelExport.ts:65,118`(`pad` 2회 중복 — **같은 파일 안에서도 2번 정의**) · `pages/dashboard/TrendChart.tsx:7`(`pad`) · `pages/dashboard/PeriodControl.tsx:16`(`pad`) · `pages/OrderGrid.tsx:1465`(인라인 `padStart` 체인) · `pages/dashboard/AttentionPanel.tsx:6` · `pages/OrderGrid.tsx:662,991-992` | **[기계적 — 부분]** ✔ `orderExcelExport.ts` 내부의 `pad` 중복 2개 → 파일 상단 1개로 병합(동일 구현, 무위험). ⚠️ 나머지는 **타임존 취급이 서로 다르다** — `datetime.ts`는 `Asia/Seoul` 명시 변환인데 `OrderGrid.tsx:662`·`991`은 `toISOString()`(=UTC) 사용. 통합하면 날짜가 밀린다. §5에 버그로 기록 |
| **금액 포맷 헬퍼 산재** | `pages/dashboard/KpiCards.tsx:8-9` (`won`/`num`) · `pages/sourcing/DiscoveryPage.tsx:31` (`₩` 접두) · `pages/sourcing/DraftReviewPage.tsx:35` (DiscoveryPage와 **완전 동일 구현**) · `ProductGrid.tsx:134` (`원` 접미) | **[기계적 — 1건만]** ✔ `DiscoveryPage.tsx:31`과 `DraftReviewPage.tsx:35`는 문자 단위로 동일(`v == null ? '-' : \`₩${Math.round(v).toLocaleString()}\``) → 공용 추출. 나머지는 접두/접미 표기가 달라 통합 시 표시 변경 |

### 3-6. 단일 파일 비대화 — `pages/OrderGrid.tsx` **2,065줄**

- 프로젝트 전체 7,472줄 중 **27.6%가 이 한 파일**이다. 2위(`DraftReviewPage.tsx` 470줄)의 4.4배.
- 한 파일 안에 정의된 것: 상수 9개(`CARRIER_LABELS`, `CARRIER_OPTIONS`, `ACCOUNT_OPTIONS`(23개 이메일 하드코딩), `VENDOR_OPTIONS`, `NO_SEND_STATUSES`, `SYNC_BADGE`, `SOURCE_ICON`, 컬럼 그룹 4종, `TERMINAL_STATUSES`, `ALL_STATUSES`) + 컴포넌트 7개(`InlineInput:129`, `FinancialEditCell:190`, `SourceIcon:357`, `ShippingEditCell:369`, `SourcingEditCell:449`, `OrderTableRow:571`, `OrderFilterPanel:643`) + 메인 `OrderGrid:907` + 순수함수 2개.
- **판정: [백로그 — 단 분해 계획은 세워둘 가치 있음]**
  파일 분할 자체는 이론상 순수 구조 변경이지만, 2,065줄 이동은 **리뷰 불가능한 diff**를 만들고 `OrderTableRow`의 `React.memo` 경계·클로저 캡처가 얽혀 있어 실수 시 렌더 회귀가 조용히 발생한다. 다른 모든 항목을 끝낸 뒤 **독립 사이클**로 다루기를 권한다.
  - 선행 저위험 조각 (원한다면 이것만 먼저): `ACCOUNT_OPTIONS`(63행, 이메일 23개) → `pages/order/accounts.ts`, `CARRIER_LABELS`(49행) → §3-5의 공용 추출과 동시 처리.
  - `ACCOUNT_OPTIONS`에 **개인 이메일 23개가 소스에 하드코딩**돼 있다는 점도 별도로 §5에 기록.

---

## 4. 주석 규모

전체 `.ts`/`.tsx` 주석 라인 **약 660줄 / 7,472줄 ≈ 8.8%**.

### 4-1. 밀도 상위 (교리 §1 전면 제거 대상 — 우선순위 순)

| 밀도 | 주석/전체 | 파일 |
|---|---|---|
| **61%** | 8/13 | `utils/phone.ts` ← 13줄짜리 파일의 절반 이상이 주석 |
| **30%** | 28/93 | `pages/product/productGridShared.tsx` |
| **27%** | 8/29 | `utils/datetime.ts` |
| 16% | 29/175 | `utils/orderExcelExport.ts` |
| 13% | 4/30 | `pages/product/productMockApi.ts` |
| 11% | 6/53 | `api/sourcingApi.ts` |
| 11% | 3/27 | `api/axios.ts` |
| 10% | 17/161 | `api/productApi.ts` |
| 10% | 9/87 | `pages/sourcing/ScoreBreakdownPanel.tsx` |
| 9% | **205/2065** | `pages/OrderGrid.tsx` ← **절대량 1위. 전체 주석의 31%가 이 파일** |

### 4-2. 절대량 상위 (실제 작업량 기준)

`OrderGrid.tsx` **205줄** ≫ `ProductDetailModal.tsx` 33 > `productGridShared.tsx` 28 > `orderApi.ts` 25 > `orderExcelExport.ts` 29 > `MarketBadgeCell.tsx` 18 > `productApi.ts` 17 > `BatchUpdatePage.tsx` 16.

**주석 0줄 파일 (작업 불필요):** `layouts/MainLayout.tsx`, `pages/Dashboard.tsx`, `pages/dashboard/KpiCards.tsx`, `pages/dashboard/AttentionPanel.tsx`, `pages/dashboard/dashboardApi.ts`, `main.tsx`, `api/supplierApi.ts`(어차피 삭제)

### 4-3. 주석 형태별 분포
- 라인 주석 `//` — 압도적 다수
- JSDoc `/** */` — **43개** (`orderExcelExport.ts` 6, `sourcingDiscoveryApi.ts` 8, `datetime.ts` 3, `OrderGrid.tsx` 5, `DraftReviewPage.tsx` 3 등)
- JSX 주석 `{/* */}` — **32개** (`OrderGrid.tsx` 10, `ProductDetailModal.tsx` 8, `ProductFilterPanel.tsx` 3, `ProductGrid.tsx` 2)
- CSS 블록 내 `/* */` — `<style>` 태그 안에 3개 (`OrderGrid.tsx:1939`, `ProductDetailModal.tsx:302,309`)

### 4-4. **salvage 필수 주석** — 제거 전 `salvage-frontend.md`로 반드시 이관

코드로 표현 불가능한 제약을 담고 있어, 사라지면 다음 사람이 같은 함정에 빠진다. 교리 §1 마지막 항목 대상이다.

| 파일:라인 | 보존해야 할 "왜" |
|---|---|
| `pages/product/MarketBadgeCell.tsx:75` | **`window.open`은 사용자 제스처 콜스택 안에서 동기 실행돼야 팝업 차단을 피한다** — 비동기로 옮기면 조용히 깨짐 |
| `pages/product/MarketBadgeCell.tsx:77` | 프로미스 완료 후 `.then()/.catch()`로 알림 — 실패를 삼키지 않기 위한 의도적 순서 |
| `pages/product/MarketBadgeCell.tsx:203` | 쿠폰율·최소마진 미반영으로 등록가가 목표가보다 높아지는 **기존 결함 B** 언급 |
| `utils/datetime.ts:1-7` | **백엔드 `LocalDateTime`은 존 없는 UTC 벽시계값** → 프론트가 UTC로 간주해 KST 변환해야 함. 이 프로젝트 전체 날짜 처리의 근본 전제 |
| `api/marketApi.ts:9` | **시크릿을 빈 값으로 저장하면 서버가 기존 값을 유지한다 (F-CRED-8)** |
| `api/axios.ts:3-5` | 인증은 `/api/v1/market-credentials/**`만 요구, 나머지는 헤더 무시 |
| `pages/Settings.tsx:81-82` | 인증된 관리자에겐 서버가 시크릿 평문을 내려줌 / 비운 채 저장 시 기존값 유지 (F-CRED-8) |
| `pages/product/productGridShared.tsx:71` | **상수를 여기 둔 이유 = `MarketBadgeCell.tsx`에 두면 `react-refresh/only-export-components`에 걸림.** 되돌리면 린트 깨짐 |
| `pages/product/productGridShared.tsx:89` | 등록가와 배치 재산정가의 기본값을 일치시켜야 하는 이유 |
| `pages/sourcing/SourcingSettingsPage.tsx:18` | **가중치 키는 서버 `CandidateScoringService`와 1:1로 맞춰야 함** |
| `pages/sourcing/ScoreBreakdownPanel.tsx:6` | **서브스코어 키는 서버 키와 1:1** |
| `pages/sourcing/DraftReviewPage.tsx:26` | **마켓별 상품명 최대 길이는 서버 `MarketProductRules`와 같은 값이어야 함** |
| `api/productApi.ts:27-28` | `marketRegistrations` semantics — `SYNCED`/`PENDING` 의미, `url: null`은 링크 식별자 미확보 |
| `api/orderApi.ts:94,96` | 마켓 송장이 우리 송장과 다르면 마켓 미반영 / **마켓이 영구 거부해 사람이 판매자센터에서 직접 고쳐야 하는 상태** |
| `utils/orderExcelExport.ts:10-11` | **exceljs 번들이 커서 모듈 자체를 호출 시점 dynamic import** — 정적 import로 바꾸면 초기 번들 급증 |
| `pages/OrderGrid.tsx:161` | `select()`만으론 부족 — **마우스 클릭이 focus 뒤에 커서를 놓아 선택을 푼다** |
| `pages/OrderGrid.tsx:1939` | **TD에 `!important`로 적용해야 frozen 셀의 인라인 배경까지 리렌더 없이 덮인다** |
| `pages/OrderGrid.tsx:414,426,435` | 배지·전송버튼 한 줄 배치는 셀 높이 억제 목적 (2026-08-07 사용자 판정) / 출처 아이콘 위치 근거 |
| `pages/OrderGrid.tsx:683` | 필터 대상 필드가 모두 nullable이라 필터링에서 빠질 수 있음 |
| `pages/BatchUpdatePage.tsx:112` | **SSE payload 포맷 `"batchId\|success"`** — 파싱 계약 |
| `pages/dashboard/TrendChart.tsx:17` | `MONTH`: 다음 달 0일 = 이번 달 말일 (Date 트릭) |
| `pages/ProcessStatusPage.tsx:28-29,35-36` | `actionType`은 **enum이 아닌 자유문자열**, 관례상 `{MARKET}_SYNC` 패턴 / 명시 라벨이 패턴보다 우선 |
| `api/productApi.ts:21` · `ProductGrid.tsx:29` | **§2-M3의 500건 상한·서버 필터 미구현** — TODO 백로그로 이관 |
| `pages/product/productMockApi.ts:5,16-17` | 모킹 상태 및 bulk-delete 교체 계획 — TODO 백로그로 이관 |

---

## 5. 발견 사항 — `bugs-frontend.md` 이관 대상 (수정 금지)

교리 §5에 따라 기록만 한다. Phase 2 리팩토링 에이전트는 **이 항목들을 건드리지 말 것.**

| # | 위치 | 증상 | 근거 | 심각도 |
|---|---|---|---|---|
| B1 | `pages/product/ProductGrid.tsx:67` | **상품 500건 초과 시 목록·필터·카운트가 조용히 부정확해진다.** 서버에서 500건만 받아 클라이언트 필터/페이지네이션을 돌리므로 501번째부터 존재하지 않는 것처럼 동작. 에러도 경고도 없음 | `fetchProducts(0, 500, ...)` 하드코딩 + `applyClientFilters`(29-48) + 클라이언트 페이지네이션(73-79) | **높음** |
| B2 | `pages/OrderGrid.tsx:662, 991-992` | **날짜 필터 기본값이 타임존만큼 밀릴 수 있다.** `toISOString().split('T')[0]`은 UTC 기준 날짜 — KST 자정~오전 9시 사이엔 전날이 나온다. 같은 프로젝트의 `utils/datetime.ts`는 `Asia/Seoul`을 명시 변환하는데 이 경로만 규칙이 다름 | `datetime.ts:1-7`이 "백엔드는 naive UTC" 전제를 명시한 것과 불일치 | **중** |
| B3 | `pages/OrderGrid.tsx:970` | **마켓 라벨 맵에 `CAFE24`·`AUCTION` 키가 없다.** 해당 마켓 주문의 동기화 상태 표시에서 한글 라벨 대신 원문 코드가 노출될 수 있음. `ProcessStatusPage.tsx:16`의 같은 성격 맵은 8키를 모두 갖고 있음 | 세 맵(`OrderGrid:970` 6키 / `ProcessStatusPage:16` 8키 / `orderExcelExport:39` 6키) 키 집합 불일치 | **중** |
| B4 | `utils/orderExcelExport.ts:44` vs `pages/OrderGrid.tsx:974` | **같은 마켓이 화면과 엑셀에서 다르게 표기된다.** 화면 `GMARKET: 'G마켓/옥션'`, 엑셀 `GMARKET: 'G마켓'` + 엑셀만 `AUCTION: '옥션'` 별도 보유 | 위 표 대조 | **낮음** |
| B5 | `api/sourcingDiscoveryApi.ts:199` | **초안(draft) 목록 화면이 없다.** 목록 API 클라이언트 `drafts()`가 정의만 되고 호출부 0건. 현재 초안 진입 경로는 발굴 직후 첫 건으로 바로 이동하는 것뿐(`DiscoveryPage.tsx:164`)이라, 이전 세션의 미처리 초안에 도달할 방법이 UI에 없음 | §1-C 참조 | **중 (기능 공백)** |
| B6 | `pages/OrderGrid.tsx:63-73` | **개인 이메일 계정 23개가 소스에 하드코딩**돼 있다(`ACCOUNT_OPTIONS`). 계정 변경 시 코드 수정·재배포 필요 | 해당 라인 | **낮음 (운영 부채)** |
| B7 | `api/productApi.ts:53` 주석 | **주석이 현실과 어긋난다.** "백엔드 PATCH 미구현 → productMockApi로 모킹"이라 쓰여 있으나 실제 저장은 `ProductDetailModal.tsx:191`에서 실물 `productApi.updateProduct`를 호출한다. 모킹 함수는 호출부 0건 | §2-M1 | **낮음 (문서 정합)** |
| B8 | `pages/Settings.tsx:83`, `pages/sourcing/DiscoveryPage.tsx:103`, `pages/sourcing/DraftReviewPage.tsx:87` | eslint `react-hooks/set-state-in-effect` — effect 본문 동기 setState로 연쇄 렌더 유발 | eslint 출력 | **낮음 (성능)** |

---

## 6. Phase 2 실행 체크리스트

**권장 순서.** 각 단계 후 `cd frontend && npx tsc -p tsconfig.app.json --noEmit` 통과 확인. `noUnusedLocals`가 켜져 있어 고아 심볼을 즉시 잡아준다.

### 단계 1 — 데드코드 삭제 (§1-A, 고신뢰)
- [ ] `api/supplierApi.ts` **파일 삭제**
- [ ] `src/App.css` **파일 삭제** (182줄, 미참조)
- [ ] `src/assets/` **디렉터리 삭제** (`hero.png`, `react.svg`, `vite.svg` 전부 미참조)
- [ ] `api/marketApi.ts:23` `fetchCredential` 삭제
- [ ] `api/orderApi.ts:172` `fetchOrderCount` 삭제
- [ ] `api/orderApi.ts:270` `confirmOrder` 삭제
- [ ] `api/productApi.ts:104` `PriceStockSyncResult` 삭제
- [ ] `utils/datetime.ts:17` `formatKstDateTime` 삭제
- [ ] `pages/product/productMockApi.ts:1-14` `updateProductFields` 삭제 (모킹 잔재, 호출부 0)
- [ ] `src/index.css` `.ag-theme-alpine` 블록(235행~) 삭제 — **`.Toastify__*`는 유지**
- [ ] `App.tsx:11-12` ag-grid CSS import 2줄 삭제
- [ ] `package.json`에서 `ag-grid-community`·`ag-grid-react` 제거 **(별도 커밋 — lockfile 영향)**

### 단계 2 — 안전한 중복 통합 (§3-5 중 [기계적]만)
- [ ] `CARRIER_LABELS` 공용 추출 — `OrderGrid.tsx:49` ≡ `orderExcelExport.ts:30` **값 100% 동일**, 무위험
- [ ] `orderExcelExport.ts` 내부 `pad` 중복 제거 — 65행·118행에 동일 구현 2개 → 상단 1개로
- [ ] `VENDOR_OPTIONS` — `ProductRegisterPage.tsx:8`(동일) 및 `ProductDetailModal.tsx:29`(map 변환)를 `productGridShared`에서 import로 교체. `OrderGrid.tsx:74`는 `['', ...VENDOR_OPTIONS]`로 파생
- [ ] `₩` 금액 포맷 — `DiscoveryPage.tsx:31` ≡ `DraftReviewPage.tsx:35` **문자 단위 동일** → 공용 추출
- [ ] ⛔ **마켓 라벨 맵 3종·마켓 배지 색상은 통합 금지** — 값이 서로 달라 행위 변경(B3/B4로 백로그행)
- [ ] ⛔ **날짜 헬퍼 나머지는 통합 금지** — 타임존 취급이 달라 날짜가 밀림(B2)

### 단계 3 — 배치·명명 정리 (§3-1)
- [ ] `pages/dashboard/dashboardApi.ts` → `api/dashboardApi.ts` 이동, import 5곳 수정
- [ ] `pages/product/productGridShared.tsx` → `.ts` rename (JSX 0건). import 수정 불필요
- [ ] `pages/product/productMockApi.ts` → `productBulkApi.ts` rename (단계 1 후 실물 구현만 남음)

### 단계 4 — export 표면 축소 + eslint 해소 (§1-B)
- [ ] `ProductGrid.tsx:30` `applyClientFilters` — `export` 제거 → **react-refresh error 1건 해소**
- [ ] `PeriodControl.tsx:6` `computeRange`(+ `PeriodValue`) — `pages/dashboard/period.ts`로 분리 이동 → **react-refresh error 1건 해소**. 참조처 `Dashboard.tsx:3`
- [ ] `OrderGrid.tsx:21,26,83` `StockCellInfo`/`stockCellInfo`/`shortAccountLabel` — `export` 제거
- [ ] ⚠️ §1-B '낮음' 17건(api DTO 타입)은 **건드리지 않기를 권장** — 효용 없음

### 단계 5 — 주석 전면 제거 (§4, 교리 §1)
- [ ] **먼저** §4-4의 salvage 24항목을 `_workspace/refactor/salvage-frontend.md`로 이관
- [ ] **먼저** §2-M3, B7의 TODO 내용을 `_workspace/refactor/bugs-frontend.md` "TODO 백로그" 섹션으로 이관
- [ ] 그 다음 제거 진행. 절대량 순: `OrderGrid.tsx`(205) → `ProductDetailModal.tsx`(33) → `orderExcelExport.ts`(29) → `productGridShared.tsx`(28) → `orderApi.ts`(25) → 나머지
- [ ] JSX 주석 32개, JSDoc 43개, `<style>` 내부 CSS 주석 3개 포함
- [ ] 각 파일 후 tsc 확인 — 주석에서만 참조되던 심볼이 있으면 `noUnusedLocals`가 잡아준다

### 손대지 말 것 (백로그행 — `bugs-frontend.md` 기록만)
- 스타일 4방식 혼재 (인라인 476회) — 시각 회귀 검증 수단 없음
- 데이터 페칭 3패턴 혼재 (react-query / useEffect / EventSource) — 캐싱·리페치 semantics 변경
- toast 라이브러리 이중화 (antd message ↔ react-toastify)
- 상품 타입 4중 표현 — 백엔드 응답 형태에 종속, 백엔드 서베이와 교차 확인 필요
- `pages/OrderGrid.tsx` 2,065줄 분해 — **독립 사이클로 별도 진행 권장**
- §1-C 데드 의심 API 메서드 7개 — 백엔드 엔드포인트의 유일한 클라이언트라 삭제 시 UI 도달 불가
- §5 버그 B1~B8 전부
