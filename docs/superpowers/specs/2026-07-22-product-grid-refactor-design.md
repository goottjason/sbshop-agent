# 상품 관리 프론트엔드 리팩토링 설계

**작성일:** 2026-07-22
**대상:** `frontend/src/pages/ProductPage.tsx` → 신규 `ProductGrid.tsx`
**목표:** 상품 관리 화면을 통합 주문관리(`OrderGrid.tsx`)와 동일한 UX 패턴(상단 검색 필터 + TanStack 데이터 그리드)으로 리팩토링한다. 프론트를 완성하고, 미구현 백엔드는 프론트 설계에 맞춰 모킹한 뒤 다음 세션에 실제 구현한다.

## 결정 사항 요약

- **프라이머리 컬러:** 포레스트 그린 `#166534` (남색 주문관리와 시각적 구분)
- **그리드 라이브러리:** AG Grid 제거 → TanStack Table v8 + 공통 `components/ui/Table.tsx`
- **필터:** 키워드 · 카테고리 · 마켓 등록상태 · 재고·판매상태 · 소싱처 · 재고유무
- **편집:** 모달 상세편집(내부 필드 전체) + 그리드 인라인(판매가/재고)
- **삭제:** 체크박스 다중 선택 → 일괄 삭제
- **진행:** 4단계, 각 단계 끝 push로 피드백 수령

## A. 아키텍처 & 컴포넌트

주문관리(`OrderGrid.tsx`) 패턴 차용. AG Grid 제거, TanStack Table + 커스텀 `Table` 컴포넌트로 교체.

```
pages/ProductGrid.tsx          ← ProductPage.tsx 대체 (신규 메인)
  ├─ ProductFilterPanel        ← 상단 필터 (OrderFilterPanel 미러링, 그린 테마)
  ├─ ProductToolbar            ← 선택 개수·[선택 삭제]·총 개수·[새로고침]
  ├─ TanStack Table            ← 커스텀 Table 컴포넌트 (components/ui/Table.tsx)
  │   ├─ SelectCheckbox        ← 행/전체 선택 (일괄 삭제용)
  │   ├─ PriceStockInlineCell  ← 판매가·재고 인라인 자동저장 (InlineInput 패턴 재사용)
  │   └─ MarketBadges          ← 마켓 등록 배지 (기존 renderMarketBadges 로직 이식)
  └─ ProductDetailModal        ← 내부 필드 편집 폼 (기존 읽기 모달 → 편집 가능화)
```

**테마 격리:** 전역 `--primary-color`(남색, `index.css :root`)를 변경하지 않는다. 상품 페이지 루트에 래퍼 클래스를 두고 스코프 변수로 격리한다.

```css
.product-theme {
  --product-primary: #166534;
  --product-hover:   #14532d;
  --product-light:   #f0fdf4;
  --product-badge-bg: #dcfce7;
}
```

필터 패널 top-border, 검색 버튼, 활성 배지, 인라인 저장 강조 등이 `--product-*`를 참조. 사이드바·주문화면 남색은 그대로 유지.

**데이터/상태:** React Query `useQuery`(목록) + `useMutation`(인라인·모달 저장·삭제). 주문관리 `optimisticPatch` 방식의 낙관적 업데이트 재사용.

## B. 그리드 컬럼 & 필터

### 컬럼 (평탄 구조: 1상품 = 1행)

| 컬럼 ID | 헤더 | 내용 | 편집 |
|---|---|---|---|
| `select` | ☐ | 체크박스 (전체/행 선택) | — |
| `image` | 이미지 | 대표이미지 썸네일 (44x44) | — |
| `sbCode` | SB코드 | sbCode (고정, 강조) | — |
| `brand` | 브랜드 | brand | — |
| `productInfo` | 상품정보 | 상품명 / 원문명 (2줄), 클릭 → 상세 모달 | — |
| `category` | 카테고리 | category 배지 | — |
| `vendor` | 소싱처 | vendor | — |
| `salePrice` | 판매가 | salePrice | **인라인** |
| `costMargin` | 원가·마진 | costPrice / marginRate (2줄) | — |
| `stock` | 재고 | stock + 재고상태 배지 | **인라인** |
| `markets` | 마켓 | 등록 마켓 배지 (링크) | — |

### 필터 패널 (3행 레이아웃, 그린 top-border)

- **1행:** 통합검색(상품명/SB코드/브랜드) + 카테고리(다중선택)
- **2행:** 마켓 등록상태(다중: 쿠팡/스토어/11번가/G마켓/옥션/Cafe24) + 소싱처(다중)
- **3행:** 재고·판매상태(재고있음/품절) + 재고유무 토글 + [검색] 버튼

## C. 편집 · 삭제 · 모킹 전략

### 인라인 편집 (판매가/재고) — 실제 동작

기존 `PUT /api/v1/products/{id}/price-stock` 엔드포인트가 존재하므로 인라인 저장은 실제 동작한다. blur 시 변경분만 커밋, 상태색(dirty→saving→saved→error) 표시. 응답의 `synced/skipped/failed` 마켓 반영 결과를 토스트로 표면화.

### 상세 모달 편집 (내부 필드 전체) — 모킹

편집 대상 필드: brand, productName, baseName, originalName, category, costPrice, salePrice, marginRate, stock, weight, bundleQuantity, barcode, capacity, measureUnit, vendor, manufacturer, origin, hsCode, sourceUrl, memo, detailHtml + 이미지(기존 업로드/URL/크롤 유지).

저장은 **신규 `PATCH /api/v1/products/{id}` 필요**. 이번 세션은 프론트 모킹 API로 완성(낙관적 로컬 반영 + "백엔드 미구현" 표식). 다음 세션에 백엔드 구현.

### 일괄 삭제

체크박스 선택 → [선택 삭제] → 확인 다이얼로그 → 삭제. 기존 단건 `DELETE /api/v1/products/{id}`가 있으므로 루프 호출로 실제 동작 가능. 성능상 신규 bulk 엔드포인트가 바람직하므로 프론트에서는 `bulkDelete` 인터페이스로 추상화하고, 초기 구현은 단건 루프로 채운 뒤 다음 세션에 bulk 엔드포인트로 교체.

### 모킹 규약

`productApi.ts`에 미구현 엔드포인트(`updateProductFields`, `bulkDelete`, 고급필터 서버검색)를 별도 `productMockApi` 섹션으로 분리. 각 함수에 `// MOCK: 다음 세션 백엔드 구현` 주석 + 콘솔 경고. 실제/모킹 경계를 한 파일에서 스왑 가능하게 유지.

**고급 필터**(카테고리/마켓/소싱처/재고상태): 현 목록 API는 keyword만 지원. 초기엔 로드된 페이지 대상 클라이언트 필터링 + 모킹으로 처리하고, 다음 세션에 서버 쿼리 파라미터로 확장.

## D. 단계적 진행 (중간 push)

각 Stage 종료 시 `git push origin main`으로 피드백 수령. (배포 중 배치가 돌고 있지 않은지 확인 후 push)

1. **Stage 1** — 그린 테마 스캐폴딩 + `ProductFilterPanel` + TanStack 그리드로 AG Grid 교체 (조회 전용, 키워드검색·페이징). → push
2. **Stage 2** — 인라인 편집(판매가/재고, 실제 endpoint) + 낙관적 업데이트. → push
3. **Stage 3** — 상세 모달 편집 폼(모킹 저장) + 체크박스 일괄 삭제. → push
4. **Stage 4** — 고급 필터 연결(클라이언트+모킹) · 마켓 배지 · 빈/로딩 상태 폴리시. → push

## 참조: 현재 백엔드 API (frontend/src/api/productApi.ts, ProductController.java)

| 메서드 | 경로 | 상태 |
|---|---|---|
| GET | `/api/v1/products?page&size&keyword` | 존재 (keyword만) |
| GET | `/api/v1/products/{id}` | 존재 |
| PUT | `/api/v1/products/{id}/price-stock` | 존재 (인라인용) |
| PUT | `/api/v1/products/{id}/images` | 존재 |
| PUT | `/api/v1/products/{id}/images/by-url` | 존재 |
| POST | `/api/v1/products/{id}/images/crawl-and-upload` | 존재 |
| DELETE | `/api/v1/products/{id}` | 존재 (일괄삭제 루프용) |
| PATCH | `/api/v1/products/{id}` | **신규 필요 (모킹)** |
| POST/GET | `/api/v1/products/bulk-delete`, 고급필터 파라미터 | **신규 필요 (모킹)** |

## 비목표 (YAGNI)

- 상품 신규 등록/배치 업데이트 화면(`ProductRegisterPage`, `BatchUpdatePage`)은 이번 리팩토링 범위 밖 — 손대지 않음.
- 상품 그리드의 다중 행 병합(주문관리식 3행 구조)은 불필요 — 평탄 구조 유지.
- 서버사이드 정렬 UI는 이번 범위 밖.
