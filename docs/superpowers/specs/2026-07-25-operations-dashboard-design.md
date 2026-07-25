# 운영 대시보드 설계 (Operations Dashboard)

- 작성일: 2026-07-25
- 상태: 설계 승인 (구현 계획 작성 전)
- 대상: `frontend/src/pages/Dashboard.tsx` 전면 개편 + 백엔드 집계 API 신설 + 주문검색 필터 확장

## 1. 목적

현재 대시보드는 상태별 카운트 카드 7개뿐이다. 운영에 도움이 되도록 **주문 정보를 일/주/월별로 추이·분포로 확인**하고, **그래프로 보고**, **클릭하면 해당 주문 목록(통합 주문 관리)으로 이동**하는 종합 대시보드로 개편한다.

핵심 목적 4가지(사용자 확정, 모두 포함):
1. 매출/수익 추이
2. 주문량 추이
3. 운영 병목/처리현황
4. 문제/이상 감지

## 2. 화면 구성 (IA)

상단 공통 **기간 컨트롤**, 아래 목적별 4개 존.

```
[기간 컨트롤]  ‹ 2026년 7월 ›   [일별|주별|월별]   (직접 지정)
ZONE 1 · KPI 카드    [주문 수][정산금액][순수익][미발주][배송중][통관오류]  → 클릭 시 주문목록
ZONE 2 · 추이 그래프  콤보차트: 막대=주문수 / 선1=정산금액 / 선2=순수익      → 요소 클릭 시 주문목록
ZONE 3 · 분포        마켓별(도넛) · 주문상태 퍼널(막대) · 상품 Top N(가로막대) · 소싱처별(도넛/막대)  → 항목 클릭
ZONE 4 · 문제/이상   통관 오류/대기 · 재고부족(품절) · 배송/처리 지연 · 반품/취소  → 각 줄 클릭 시 주문목록
```

- ZONE 3/4는 2열, 좁은 화면에서 세로 스택.
- 기간 컨트롤은 **전역**: 바꾸면 ZONE 1·2·3 갱신. ZONE 4(이상)는 기간과 **무관**한 현재 상태 실시간 카운트.
- 모든 숫자·차트 요소는 클릭 시 통합 주문 관리로 이동(해당 필터 자동적용).

## 3. 시간 모델 (사용자 확정)

- **일별 기본**: 진입 시 이번 달(달력) 일별 표시. `‹ ›` 화살표로 이전/다음 달 이동. (+ 직접 기간 지정 가능)
- **주별**: 월요일 시작(ISO 주). 그 달의 주들.
- **월별**: 달력 월(롤링 30일 아님), 최근 12개월.
- 모든 버킷 **캘린더 정렬**, **KST** 기준. 빈 구간은 0으로 채워 x축 연속.

## 4. 백엔드 집계 API

신규 `DashboardController` (`/api/v1/dashboard/*`), 서비스에서 QueryDSL `GROUP BY`. KST 변환 후 버킷팅(기존 그리드 `toKstDate` 규칙과 동일). 취소/반품은 정산 0 정규화(D-098)로 매출에 0 기여.

집계 단위: **금액(정산·순수익)은 lineItem 합산**, **주문수는 distinct order**.

```
GET /dashboard/summary?start=&end=
→ { period:  { orderCount, settlementSum, profitSum },
    current: { newCount, shippingCount, customsIssueCount } }

GET /dashboard/timeseries?start=&end=&unit=DAY|WEEK|MONTH
→ [ { bucketStart:"2026-07-01", orderCount, settlementSum, profitSum }, ... ]   // 빈 구간 0채움

GET /dashboard/breakdown?start=&end=&dimension=MARKET|STATUS|PRODUCT|VENDOR&limit=10
→ [ { key, label, orderCount, settlementSum, profitSum }, ... ]                  // PRODUCT는 orderCount Top N

GET /dashboard/attention
→ { customsIssue, outOfStock, delayed, returnCancel }                            // 현재 미해결(기간 무관)
```

- `attention.delayed` = (NEW & 주문일 ≤ 오늘-1일) + (PREPARING & 주문일 ≤ 오늘-3일). (사용자 확정 기준)
- `attention.customsIssue` = PENDING·INVALID_PCCC·INVALID_PHONE·INVALID_ZIPCODE (기존 대시보드 정의 재사용).
- `attention.outOfStock` = 주문의 상품이 OUT_OF_STOCK 상태인 미종결 주문.
- `attention.returnCancel` = 최근 CANCELED·RETURNED (범위는 현재 상태 기준).
- 인증: `/api/v1/**` permitAll 유지(집계는 시크릿 아님). market-credentials만 인증 보호(기존).

## 5. 프론트엔드 구성

recharts 추가. `Dashboard.tsx`를 컴포넌트로 분리.

```
pages/dashboard/
  Dashboard.tsx          // 레이아웃 + 기간 상태 소유
  dashboardApi.ts        // 4개 fetcher + 타입
  PeriodControl.tsx      // ‹ 월 › + [일|주|월] 토글 (+ 직접 지정)
  KpiCards.tsx           // ZONE 1
  TrendChart.tsx         // ZONE 2 — recharts ComposedChart(막대=주문수 좌축, 선=정산·순수익 우축)
  BreakdownPanels.tsx    // ZONE 3 — PieChart(도넛)·가로 BarChart(Top N)·퍼널
  AttentionPanel.tsx     // ZONE 4 — 현재 상태, refetchInterval 주기 갱신
  drilldown.ts           // 필터 → /orders URL 빌더 (순수함수, 단일 출처)
```

- 데이터 로딩: react-query. summary/timeseries/breakdown은 (start,end,unit) queryKey 의존, attention은 독립+주기 갱신.
- 차트 색: 통합 주문 관리 파스텔 팔레트 재사용(마켓 색 일치).
- **드릴다운**: `drilldown.ts`가 필터 객체 → `/orders?markets=&statuses=&customsStatuses=&stockStatus=&vendor=&startDate=&endDate=&keyword=` 생성 → `useNavigate` 이동.

## 6. 주문검색 필터 확장 (드릴다운 완성용)

대시보드 드릴다운을 완전히 지원하려면 주문검색에 2개 필터 추가(사용자 확정):
- **재고상태(stockStatus)**: 주문 상품의 재고상태 필터(품절 주문 드릴다운용).
- **소싱처(sourcingVendor)**: lineItem 소싱처 필터(소싱처별 드릴다운용).

반영 지점: `OrderSearchCondition` + QueryDSL predicate + 통합 주문 관리 필터 패널 UI + URL 쿼리파라미터 파싱.

드릴다운 매핑:
| 대시보드 요소 | 주문검색 필터 |
|---|---|
| 마켓 도넛 항목 | markets=<마켓> + 기간 |
| 주문상태 퍼널 | statuses=<상태> + 기간 |
| 상품 Top N 항목 | keyword=<SB코드> + 기간 |
| 소싱처 항목 | vendor=<소싱처> + 기간 |
| 이상: 통관오류 | customsStatuses=PENDING,INVALID_* |
| 이상: 재고부족 | stockStatus=OUT_OF_STOCK |
| 이상: 배송지연(NEW) | statuses=NEW & endDate=오늘-1 |
| 이상: 배송지연(PREPARING) | statuses=PREPARING & endDate=오늘-3 |
| 이상: 반품/취소 | statuses=CANCELED,RETURNED |

## 7. OrderGrid URL 파라미터 수용

통합 주문 관리(OrderGrid)가 진입 시 URL 쿼리파라미터를 읽어 초기 필터로 적용하도록 수정(현재는 내부 state만). 파라미터 있으면 그 값, 없으면 기존 기본값(종결상태 제외 등 D 기존 규칙 유지). `useSearchParams` 사용.

## 8. 엣지 케이스 / 데이터 처리

- **주문수 = distinct order**, 금액 = lineItem 합산.
- **타임존**: 백엔드 orderDate는 zone 없는 UTC 벽시계값 → KST 변환 후 버킷팅.
- **취소/반품**: 정산 0(D-098) → 매출 0 기여, 주문수엔 포함(정상).
- **순수익 데이터 품질**: 실구매가·물류비 미입력 건은 순수익 과대. 카드/차트에 주석("순수익은 실구매가 입력 기준").
- **빈 구간**: 0채움으로 x축 연속.
- **이상 패널**: 기간 무관 현재 상태 카운트.

## 9. 테스트 전략

- **백엔드(TDD)**: 시계열 버킷팅(월요일 시작·달력 월·KST·빈 구간·distinct 주문수)은 가능한 순수함수로 뽑아 단위테스트. 집계 서비스는 목 리포지토리 표본으로 summary/breakdown/Top N 검증. 신규 필터(재고상태·소싱처) predicate 테스트. `./gradlew test` 전체 회귀 + 배포 후 기동 확인.
- **프론트(러너 없음)**: `tsc` + `npm run build` + 브라우저 수동. `drilldown.ts`·버킷 라벨 포맷은 순수함수로 작성(향후 러너 도입 대비).
- **QA**: integration-qa로 대시보드 집계 ↔ 주문검색 필터 경계면(같은 조건 = 같은 건수) 교차 검증.

## 10. 범위 밖 (YAGNI / 후속)

- 사전집계 테이블·스케줄러(접근안 C) — 현재 규모엔 과함. 느려지면 그때.
- 대시보드 자체 인증/권한 — 집계는 시크릿 아님, 기존 정책 유지.
- 커스텀 알림/이메일 리포트, 목표 대비(target) 지표 — 후속.
