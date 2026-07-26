# 신규 상품 등록 자동화 — 기획·설계

> 작성 2026-07-26 · 대체 대상: `ProductRegisterPage`(URL 수동입력 4단계) → 후보 자동발굴 6단계 파이프라인

---

## 1. 문제 정의

### 현행 흐름의 한계

```
[1 크롤링]        [2 보정·가격]      [3 마켓 등록]    [4 완료]
URL 수동 붙여넣기 → 표에서 6필드 편집 → 마켓 다중선택 → 결과
```

| # | 현행 문제 | 근거 |
|---|---|---|
| P1 | **어떤 상품을 팔지 사람이 정해야 함** — 자동화의 시작점이 없음 | `ProductRegisterPage.tsx:151` TextArea |
| P2 | **iHerb catalog API가 Cloudflare에 막힘** | `catalog.app.iherb.com/product/38903` → **403** (실측 2026-07-26) |
| P3 | **상품명이 기계 조립** — `"{brand} {baseName}, {cap}{unit}, {qty}개"`, iHerb 영문명 그대로 | `Product.assembleMarketName()` |
| P4 | **키워드가 무의미** — `brand,baseName,originalName` 나열 + 매직키워드 5개 고정 | `Product.generateSearchKeywords()`, `CoupangSearchTagGenerator` |
| P5 | **마켓 publish가 필수필드 미달** — 스마트스토어는 `originProduct` 6필드뿐(leafCategoryId·detailAttribute·deliveryInfo 전무) | `SmartstoreMarketClient.publish()` |
| P6 | **중복 등록 방지 장치 없음** | — |
| P7 | **통관 가능 여부를 사람이 판단** — 반입차단 성분 상품을 올리면 주문 후 전량 폐기/반송 | — |
| P8 | 마켓 등록이 프론트에서 `productId × market` 이중 for-loop 직렬 호출 | `ProductRegisterPage.tsx:95-108` |

### 목표

> iHerb 베스트셀러에서 **팔릴 만하고, 통관되고, 아직 안 판** 상품을 매일 자동으로 추려
> 마켓별 등록 데이터를 전부 채워두고, 사용자는 **상품명·묶음수량만 최종 검수**한 뒤 4개 마켓에 일괄 등록한다.

---

## 2. 실측 근거 (2026-07-26)

설계를 가르는 사실은 전부 실행해서 확인했다.

| 대상 | 방법 | 결과 |
|---|---|---|
| iHerb 베스트셀러 목록 | Scrapling `DynamicFetcher` | **✅ 200** — `/c/supplements?sort=10`에서 카드 48개 |
| iHerb 상품 상세 | Scrapling `DynamicFetcher` | **✅ 200** — 한글 성분표·UPC·중량 확보 |
| iHerb catalog API | 직접 HTTP | **❌ 403 Cloudflare** — 현행 경로 폐기 필요 |
| 쿠팡 검색 | `DynamicFetcher` / `StealthyFetcher` | **❌ 403** (스텔스도 실패) |
| 네이버쇼핑 검색 | `DynamicFetcher` / `StealthyFetcher` | **❌ 418** (스텔스도 실패) |
| OpenCode Zen 무료모델 | `POST /zen/v1/chat/completions` | **✅** — 아래 표 참조 |

### 2.1 iHerb 목록 페이지에서 뽑히는 필드

`div.product-cell` 1장에서 전부 나온다 — **상세 페이지를 안 열어도 스코어링이 가능**하다.

| 필드 | 출처 |
|---|---|
| 상품ID `124745` | `a[data-product-id]` |
| 파트넘버 `CGN-02333` | `data-part-number` |
| 브랜드 `California Gold Nutrition` / `CGN` | `data-ga-brand-name` / `data-ga-brand-id` |
| **한글 상품명** | `div.product-title > bdi` — `"California Gold Nutrition (캘리포니아골드뉴트리션), 비타민D3 + K2(MK-7), 베지 캡슐 180정"` |
| **원화 가격 ₩24,351** | `data-ga-discount-price` |
| 정가/할인율 | `data-cart-info` JSON의 `listPrice`/`discountPercentage` |
| 품절·단종 | `data-ga-is-out-of-stock` / `data-ga-is-discontinued` |
| **평점 4.8 / 리뷰 34,554** | `a.rating-count[title="4.8/5 - 34,554 구매후기"]` |
| **30일 판매량** | `.recent-activity-message-wrapper` — `"30일 동안 50,000+개 판매"` |
| 랭킹 순위 | `data-ga-product-position` |
| 대표 이미지 | `span.product-image > img@src` |

> 가격이 **원화**로 나온다. 현행 `IherbScraperClient`는 catalog API의 **USD**를 `costPrice`에 넣는데,
> `BatchPriceStockService`/`MarginCalculator`는 `buyPrice`를 **원화**로 다룬다. 새 파이프라인은
> kr.iherb.com 원화를 그대로 써서 이 불일치를 없앤다.

### 2.2 iHerb 상세 페이지에서 뽑히는 필드

| 필드 | 출처 |
|---|---|
| **주요 성분 + 기타 성분 (한글)** | `.prodOverviewIngred` — `"마이크로크리스탈린셀룰로오스, 변성셀룰로오스(하이프로멜로스베지캡슐), …"` |
| 사용법 / 주의사항 | `.prodOverviewDetail` |
| **UPC(바코드)** `898220023332` | `#product-specs-list` |
| **배송 무게** `0.06 kg` | `.product-shipping-weight-label` |
| 수량 `180 개` | `.package-quantity` |
| 부피 `9.7 x 5.2 x 5.2 cm` | `#dimensions` |
| 알레르기 유발물질 문구 | `.prodOverviewIngred` 말미 |

**한글 성분표가 그대로 나온다** — 식약처 반입차단 성분 목록(한글)과 직접 대조할 수 있다.

### 2.3 OpenCode Zen 무료모델 실측

`~/.local/share/opencode/auth.json`의 `opencode-go` 키로 `https://opencode.ai/zen/v1/chat/completions` 호출.
동일 프롬프트(iHerb 영문명 → 한글 상품명 + 쿠팡 키워드 10개, JSON 강제):

| 모델 | 결과 | completion 토큰 | 판정 |
|---|---|---|---|
| **`nemotron-3-ultra-free`** | 완전한 JSON, 자연스러운 한글 | **185** (reasoning 60) | **★ 주력** |
| `ling-3.0-flash-free` | 완전한 JSON, 품질 양호 | 1,108 (reasoning 747) | 1차 폴백 |
| `deepseek-v4-flash-free` | 빈 content | — | 미사용 |
| `big-pickle` | 2,000토큰 초과, 미완성 | 2,000+ | 미사용 |

`nemotron-3-ultra-free` 출력 예:
```json
{"name":"캘리포니아골드뉴트리션 락토비프 프로바이오틱스 300억 60캡슐",
 "keywords":["프로바이오틱스","유산균","락토비프","300억유산균","캘리포니아골드뉴트리션",
             "장건강","유산균추천","가성비유산균","캡슐유산균","직구유산균"]}
```

**결정:** 주력 `nemotron-3-ultra-free` → 폴백 `ling-3.0-flash-free` → 최종 폴백 **규칙 기반**(현행 로직).
LLM은 어디까지나 **품질 향상 레이어**이고, 전부 죽어도 파이프라인은 돌아간다.

### 2.4 국내 수요 신호 — 스크래핑 불가, 공식 API만

쿠팡·네이버쇼핑 검색 페이지는 `StealthyFetcher`로도 뚫리지 않는다(403/418).
사용자가 보유를 확인한 **무료 공식 API** 2종으로 간다.

| API | 엔드포인트 | 얻는 것 | 한도 |
|---|---|---|---|
| 네이버 검색API(쇼핑) | `openapi.naver.com/v1/search/shop.json` | `total`(경쟁상품수), `lprice`(최저가), `category1~4`, `brand` | 25,000/일 |
| 네이버 검색광고API | `api.searchad.naver.com/keywordstool` | `monthlyPcQcCnt`/`monthlyMobileQcCnt`(월간 검색량), `compIdx`(경쟁정도) | 무료 |

둘 다 **선택적 의존**으로 설계한다. 자격증명이 없으면 해당 서브스코어를 0 가중으로 빼고
나머지 신호로 정규화해 점수를 낸다 (`DemandSignalPort` 미구현 시 graceful degradation).

---

## 3. 새 파이프라인

```
┌─ 자동 (스케줄러 매일 03:00 + 수동 트리거) ────────────────────────────┐
│                                                                        │
│  S0 발굴        iHerb 4개 카테고리 베스트셀러 크롤 → 후보 풀 적재       │
│      ↓                                                                 │
│  S1 중복제외    이미 등록된 상품 / 과거 거절 후보 제거                  │
│      ↓                                                                 │
│  S2 통관게이트  상세 성분 크롤 → 식약처 반입차단 원료성분 대조          │
│      ↓          BLOCKED 제외 · REVIEW 경고 · PASS 통과                 │
│  S3 스코어링    iHerb신호 + 국내수요 + 자사이력 + 수익성가드 → 0~100점  │
│      ↓                                                                 │
└──────┼─────────────────────────────────────────────────────────────────┘
       │
       ▼  사용자가 추천 목록에서 N개 선택
┌─ 반자동 ───────────────────────────────────────────────────────────────┐
│  S4 인리치먼트  마켓별 상품명·키워드·카테고리·묶음·가격·고시정보 자동생성 │
│      ↓                                                                 │
│  S5 검수 UI     상품명/묶음수량 중심 최종 검수 · 필수필드 충족 검사      │
│      ↓                                                                 │
│  S6 등록        4개 마켓 병렬 publish (필수필드 완전 충족 payload)       │
└────────────────────────────────────────────────────────────────────────┘
```

### S0 — 후보 발굴 (Discovery)

- 대상: `/c/supplements`, `/c/grocery`, `/c/sports-nutrition`, `/c/herbs-homeopathy`
- URL: `https://kr.iherb.com/c/{slug}?sort=10&p={n}` (sort=10 = 베스트셀러)
- 카테고리당 기본 3페이지(≈144개) → 4카테고리 ≈ 576 후보/회. 설정에서 조정.
- `sb_sourcing_candidate`에 **upsert** (`vendor` + `external_id` 유니크). 재수집 시 가격·랭킹·리뷰수 갱신, 이력은 `discovered_at`/`last_seen_at`으로 추적.

### S1 — 중복 제외 (Dedup)

| 제외 사유 | 판정 |
|---|---|
| 이미 등록됨 | `sb_product.source_url`에서 iHerb ID 추출 → 후보 `external_id`와 일치 |
| 사용자가 거절함 | `sb_sourcing_candidate.status = REJECTED` (쿨다운 90일) |
| 품절·단종 | `is_out_of_stock` / `is_discontinued` |
| 이미 초안 진행중 | `status IN (DRAFTED, PUBLISHING)` |

### S2 — 통관 적격성 게이트 (Customs Eligibility) ★신규

구매대행 불가 성분 상품을 **등록 전에** 걸러낸다. 주문 후 통관 폐기/반송은 손실이 크다.

**데이터 원본:** [식품의약품안전처_해외직구식품 국내 반입차단 대상 원료성분 서비스](https://www.data.go.kr/data/15132686/openapi.do)
— 원료·성분명(한글/영문), **기타명칭(별칭)**, 지정/해제 일자, 사유 제공. 무료.

**판정 절차:**

1. 상세 페이지 `.prodOverviewIngred`에서 **주요 성분 + 기타 성분** 원문 추출
2. 정규화: 소문자화, 공백·하이픈·괄호 제거, 한글 자모 보존
3. `sb_banned_ingredient`의 한글명·영문명·별칭 전체와 부분일치 매칭
4. 추가 룰(하드코딩 보완):
   - 축산물 가공품(육포·젤라틴 소/양 유래) → `REVIEW`
   - 상품명·카테고리에 의약품성 표현(처방·mg 고함량) → `REVIEW`
   - 성분표 추출 실패 → `REVIEW` (조용히 PASS 금지)
5. LLM 보조: 성분 원문을 개별 성분 배열로 정규화(오탈자·이명 흡수). 실패 시 정규식 분해로 폴백.

**판정 결과:**

| 판정 | 처리 |
|---|---|
| `BLOCKED` | 후보 목록에서 제외. 사유(검출 성분 + 식약처 지정사유) 기록, 별도 탭에서 열람 가능 |
| `REVIEW` | 목록에 **노랑 경고 배지**로 노출. 검수 화면에서 성분 원문 + 의심 사유 표시, 사용자가 승인해야 등록 가능 |
| `PASS` | 정상 통과 |

> 면책: 이 게이트는 **1차 스크리닝**이다. 최종 통관 책임은 판매자에게 있고, `REVIEW`는 반드시
> 사람이 본다. 식약처 목록은 매일 새벽 동기화하고, 동기화 실패 시 마지막 성공본을 쓰되 UI에
> "성분 DB 기준일" 을 표시한다.

### S3 — 스코어링

```
총점 = Σ (서브스코어 × 가중치)     ← 가중치는 설정에서 조정 가능
```

| 그룹 | 서브스코어 | 산식 | 기본 가중치 |
|---|---|---|---|
| **iHerb 신호** | 판매량 | `log10(30일 판매량)` 정규화 | 20 |
| | 리뷰수 | `log10(리뷰수)` 정규화 | 10 |
| | 평점 | `(평점-3.5)/1.5` 클램프 | 8 |
| | 랭킹 | `1 - position/총개수` | 7 |
| | 할인율 | 할인 클수록 가점 (원가 우위) | 5 |
| **국내 수요** | 검색량 | `log10(월간 검색량)` 정규화 | 20 |
| | 경쟁강도 | `1 - log10(네이버 total)` 정규화 (**역방향**) | 10 |
| | 가격 경쟁력 | `(국내최저가 - 내 예상판매가) / 국내최저가` | 10 |
| **자사 이력** | 브랜드 실적 | 해당 브랜드 최근 90일 주문건수 정규화 | 5 |
| | 카테고리 실적 | 해당 카테고리 최근 90일 매출 정규화 | 5 |

**수익성 하드 가드** (점수와 별개로 즉시 탈락):
- `MarginCalculator`로 마켓별 예상 판매가 산정 → **최소마진 미달 시 탈락**
- 예상 판매가 > 국내 최저가 × 1.3 → 탈락 (가격 경쟁 불가)
- 개인통관 자가사용 기준 초과 우려(총액 > $150) → `REVIEW`

각 서브스코어와 원본값을 `score_breakdown` JSON에 저장해 **UI에서 "왜 추천됐는지" 를 보여준다.**

> 사용자가 선택한 신호 3종(iHerb·국내수요·자사이력)에 **수익성 가드**를 추가했다.
> 선택 항목은 아니었으나, 마진 미달 상품을 추천하면 추천 자체가 무의미해지므로
> 점수 신호가 아닌 **탈락 조건**으로만 넣었다. 불필요하면 설정에서 끌 수 있다.

### S4 — 자동 인리치먼트

사용자가 후보를 고르는 순간, 4개 마켓 초안을 전부 채운다.

| 항목 | 생성 방법 |
|---|---|
| **마켓별 상품명** | LLM(마켓별 글자수·금지어 규칙 주입) → 규칙기반 폴백. 쿠팡/스토어/11번가 100자, Cafe24 250자 |
| **검색 키워드 20개** | LLM 생성 + 네이버 검색광고 연관키워드 병합 → 중복제거 → 검색량순 정렬 |
| **카테고리** | 쿠팡 `categorization/predict`(기존) · 스토어 카테고리 검색 API · 11번가 카테고리 코드 · Cafe24 분류 |
| **묶음수량** | 배대지 배송비 최적화 — 개당 배송비가 최소가 되는 수량 추천 (`scraper/pricing.py` 로직) |
| **마켓별 판매가** | `MarketFeeService` 실수수료 + `MarginCalculator` (기존 D-094 경로 재사용) |
| **원산지·HS코드** | 카테고리 기반 (`SUPPLEMENT` → `2106.90.9099`) + 상세페이지 원산지 |
| **상품정보제공고시** | 카테고리별 고시유형 매핑 + 성분/용량/섭취방법을 상세 크롤 결과로 자동 채움 |
| **상세 HTML** | 기존 템플릿 + 한글 성분표/사용법/주의사항 섹션 추가 |
| **이미지** | 대표 + 상세 이미지 R2 호스팅 (기존 `ImageStorageClient`) |
| **바코드** | 상세페이지 UPC |
| **중량** | 상세페이지 배송 무게 |

### S5 — 검수 UI

```
┌────────────────────────────────────────────────────────────────┐
│ 추천 상품 (23건)              [설정] [지금 재수집] [기준일 07-26] │
├────────────────────────────────────────────────────────────────┤
│ ☑ 92점 ● 통관OK   락토비프 프로바이오틱스 300억 60캡슐          │
│        ₩24,351 · ★4.8(34,554) · 30일 50,000+판매                │
│        검색량 18,200/월 · 경쟁 1,203건 · 최저가 ₩31,000         │
│        ▸ 점수근거                                               │
│ ☑ 87점 ▲ 통관주의  ○○○ 멜라토닌 함유 의심                     │
│ ☐ 81점 ● 통관OK   ...                                          │
├────────────────────────────────────────────────────────────────┤
│                        [선택 2건 초안 생성 →]                    │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│ 검수 · 락토비프 프로바이오틱스        [쿠팡][스토어][11번가][Cafe24] │
├────────────────────────────────────────────────────────────────┤
│ 공통  상품명(기본) [락토비프 프로바이오틱스 300억 60캡슐    ]     │
│       묶음수량 [2 ▾]  → 원가 ₩48,702 · 배송비 ₩3,000            │
│       마진율 [20 %]                                             │
│ ─────────────────────────────────────────────────────────────  │
│ 쿠팡  상품명 [캘리포니아골드뉴트리션 락토비프 …  ] 43/100자      │
│       카테고리 [73199 건강기능식품>유산균] 판매가 ₩64,900        │
│       키워드 [프로바이오틱스][유산균][락토비프] +17              │
│       ✓ 필수필드 12/12 충족                                     │
│ 스토어 … ⚠ 필수필드 미충족: A/S 안내, 반품배송비                 │
├────────────────────────────────────────────────────────────────┤
│                     [4개 마켓 등록] (스토어 미충족 → 비활성)     │
└────────────────────────────────────────────────────────────────┘
```

- **필수필드 충족 검사**를 마켓별로 프론트/백엔드 양쪽에서 수행. 미충족 마켓은 등록 버튼 비활성 + 빠진 필드 명시.
- `REVIEW` 통관 판정은 성분 원문과 함께 **명시적 승인 체크박스**를 요구.

### S6 — 마켓 등록

- 백엔드 단일 엔드포인트로 `초안 → 상품 생성 → N개 마켓 병렬 publish`
- 진행률은 기존 `sb_process_status` + SSE(`SseNotificationController`) 재사용
- 프론트 이중 for-loop 제거 (P8)

---

## 4. 마켓별 필수필드 (S6 재작성 범위)

현행 payload와 공식 스펙의 차이. 실제 구현 시 각 마켓 문서로 최종 검증한다.

### 쿠팡 — 보완 수준
현행 `CoupangProductPayload`가 카테고리·고시정보·검색태그·이미지까지 갖춤. 보완 대상:
- `searchTags` 품질 (P4 — 매직키워드 5개 고정 → LLM 생성)
- `outboundShippingPlaceCode` / `returnCenterCode` 실제 값 검증
- 구매대행 상품 필수: `originCountryCode`, `importer`, `maximumBuyForPerson`

### 스마트스토어 — **전면 재작성**
현행은 `productName`/`salePrice`/`stockQuantity`/`productCode`/`detailContent`/`images` 6필드뿐.
커머스API `originProduct`에 최소 다음이 더 필요하다:
- `statusType`, `saleType`, **`leafCategoryId`**
- `deliveryInfo` — `deliveryType`, `deliveryAttributeType`, `deliveryFee`, `claimDeliveryInfo`(반품/교환 배송비, 출고지·반품지 주소ID)
- `detailAttribute.naverShoppingSearchInfo` — `modelId`/`manufacturerName`/`brandName`
- `detailAttribute.afterServiceInfo` — A/S 전화번호·안내
- `detailAttribute.originAreaInfo` — 원산지 코드
- `detailAttribute.productInfoProvidedNotice` — **건강기능식품 고시 항목 전체**
- `detailAttribute.sellerCodeInfo`, `minorPurchasable`, `customerBenefit`
- 해외직구: `productInfoProvidedNotice` 구매대행 표기

### 11번가 — 재작성
XML 전문에 카테고리(`dispCtgrNo`), 배송정보, A/S, 원산지, 상품고시정보, 해외구매대행 플래그 추가.
참고 스펙: `docs/external-api/elevenst/신규상품조회.pdf`, `11번가-상품수정.pdf`

### Cafe24 — 보완
`category`(진열 분류), `origin_place`, `product_material`, `payment_info`, `shipping_fee` 추가.
참고: `docs/external-api/카페24.pdf`

---

## 5. 데이터 모델

Flyway 제거 체제이므로 **엔티티 + 수동 DDL**로 반영한다 (운영 DB가 단일 원본).

```sql
-- 후보 풀
CREATE TABLE sb_sourcing_candidate (
  id                BIGSERIAL PRIMARY KEY,
  vendor            VARCHAR(10)  NOT NULL,      -- IHB
  external_id       VARCHAR(50)  NOT NULL,      -- iHerb product id
  source_url        TEXT         NOT NULL,
  part_number       VARCHAR(50),
  brand             VARCHAR(100),
  brand_code        VARCHAR(20),
  name_ko           VARCHAR(500),
  category_slug     VARCHAR(50),                -- supplements / grocery / ...
  list_price        NUMERIC(15,2),              -- 원화
  discount_price    NUMERIC(15,2),              -- 원화
  discount_pct      INT,
  rating            NUMERIC(3,2),
  review_count      INT,
  sales_30d         INT,                        -- "30일 동안 50,000+개 판매" 파싱값
  rank_position     INT,
  is_out_of_stock   BOOLEAN DEFAULT FALSE,
  is_discontinued   BOOLEAN DEFAULT FALSE,
  image_url         TEXT,
  -- 통관 게이트
  customs_verdict   VARCHAR(20),                -- PASS / REVIEW / BLOCKED
  customs_reason    TEXT,
  ingredients_raw   TEXT,
  -- 스코어링
  total_score       NUMERIC(6,2),
  score_breakdown   JSONB,
  -- 상태
  status            VARCHAR(20) NOT NULL,       -- NEW/SCORED/REJECTED/DRAFTED/PUBLISHED/EXCLUDED
  exclude_reason    VARCHAR(200),
  discovered_at     TIMESTAMP,
  last_seen_at      TIMESTAMP,
  created_at        TIMESTAMP, updated_at TIMESTAMP,
  UNIQUE (vendor, external_id)
);

-- 식약처 반입차단 원료성분
CREATE TABLE sb_banned_ingredient (
  id            BIGSERIAL PRIMARY KEY,
  name_ko       VARCHAR(300),
  name_en       VARCHAR(300),
  aliases       TEXT,                 -- 기타명칭 (쉼표 구분)
  norm_keys     TEXT,                 -- 정규화 매칭키 (파이프 구분)
  designated_on DATE,
  released_on   DATE,                 -- 해제일 (NULL이면 현재 차단중)
  reason        TEXT,
  source        VARCHAR(30),          -- MFDS_OPENAPI / MANUAL
  created_at    TIMESTAMP, updated_at TIMESTAMP
);
CREATE INDEX idx_banned_active ON sb_banned_ingredient (released_on) WHERE released_on IS NULL;

-- 등록 초안 (검수 대상)
CREATE TABLE sb_product_draft (
  id             BIGSERIAL PRIMARY KEY,
  candidate_id   BIGINT REFERENCES sb_sourcing_candidate(id),
  base_name_ko   VARCHAR(255),
  brand          VARCHAR(100),
  bundle_qty     INT DEFAULT 1,
  margin_rate    NUMERIC(5,2),
  cost_price     NUMERIC(15,2),
  origin         VARCHAR(100),
  hs_code        VARCHAR(30),
  barcode        VARCHAR(50),
  weight_g       NUMERIC(10,2),
  capacity       NUMERIC(10,2),
  measure_unit   VARCHAR(20),
  detail_html    TEXT,
  source_images  JSONB,
  hosted_images  JSONB,
  ingredients_ko TEXT,
  usage_ko       TEXT,
  caution_ko     TEXT,
  customs_ack    BOOLEAN DEFAULT FALSE,   -- REVIEW 판정 사용자 승인
  status         VARCHAR(20),             -- ENRICHING/READY/PUBLISHING/PUBLISHED/FAILED
  product_id     BIGINT,                  -- publish 성공 후 sb_product FK
  created_at     TIMESTAMP, updated_at TIMESTAMP
);

-- 마켓별 초안
CREATE TABLE sb_market_draft (
  id              BIGSERIAL PRIMARY KEY,
  draft_id        BIGINT NOT NULL REFERENCES sb_product_draft(id) ON DELETE CASCADE,
  market_type     VARCHAR(30) NOT NULL,
  product_name    VARCHAR(500),
  category_id     VARCHAR(50),
  category_path   VARCHAR(300),
  sale_price      NUMERIC(15,0),
  keywords        JSONB,
  notice_fields   JSONB,          -- 상품정보제공고시
  extra_fields    JSONB,          -- 마켓 고유 필드
  missing_fields  JSONB,          -- 필수필드 검사 결과
  is_valid        BOOLEAN DEFAULT FALSE,
  enabled         BOOLEAN DEFAULT TRUE,
  UNIQUE (draft_id, market_type)
);

-- 설정
CREATE TABLE sb_sourcing_config (
  id                   BIGSERIAL PRIMARY KEY,
  recommend_count      INT DEFAULT 20,       -- 한 번에 추천할 개수 (10~30)
  categories           JSONB,                -- 크롤 대상 카테고리 slug
  pages_per_category   INT DEFAULT 3,
  score_weights        JSONB,                -- 서브스코어 가중치
  min_margin_price     NUMERIC(15,2),
  target_margin_rate   NUMERIC(5,2),
  profit_guard_enabled BOOLEAN DEFAULT TRUE,
  reject_cooldown_days INT DEFAULT 90,
  schedule_enabled     BOOLEAN DEFAULT TRUE,
  schedule_cron        VARCHAR(50) DEFAULT '0 0 3 * * *',
  updated_at           TIMESTAMP
);
```

---

## 6. 컴포넌트 배치

```
scraper/ (Python · Scrapling)
  scrapers/iherb.py             ★신규  IherbScraper — 목록/상세
  app.py                        수정   POST /discover/bestsellers
                                       POST /scrape/product-detail

backend/core/
  domain/sourcing/
    SourcingCandidate.java      ★  BannedIngredient.java  ★
    ProductDraft.java           ★  MarketDraft.java       ★
    SourcingConfig.java         ★
    enums/{CustomsVerdict, CandidateStatus, DraftStatus}.java  ★
    port/
      BestsellerCrawlerPort.java      ★  ProductDetailCrawlerPort.java ★
      DemandSignalPort.java           ★  TextGenerationPort.java       ★
      BannedIngredientSourcePort.java ★
  application/sourcing/
    discovery/SourcingDiscoveryUseCase.java   ★  S0~S3 오케스트레이션
    discovery/CandidateScoringService.java    ★  S3
    customs/CustomsEligibilityService.java    ★  S2
    customs/BannedIngredientSyncService.java  ★  식약처 동기화
    enrich/DraftEnrichmentUseCase.java        ★  S4
    enrich/MarketDraftBuilder.java            ★  마켓별 초안
    enrich/BundleQuantityOptimizer.java       ★  묶음수량 최적화
    publish/DraftPublishUseCase.java          ★  S6

backend/infrastructure/client/
  sourcing/ScraplingIherbClient.java      ★  사이드카 호출 (목록/상세)
  demand/NaverShoppingSearchClient.java   ★
  demand/NaverKeywordToolClient.java      ★
  llm/OpenCodeZenClient.java              ★  무료모델 + 폴백 체인
  customs/MfdsBannedIngredientClient.java ★  data.go.kr
  {coupang,smartstore,elevenst,cafe24}/…  수정  publish payload 전면 보강

backend/api/controller/
  SourcingDiscoveryController.java  ★
  ProductDraftController.java       ★
  SourcingConfigController.java     ★

backend/worker/scheduler/
  SourcingDiscoveryScheduler.java   ★  매일 03:00
  BannedIngredientScheduler.java    ★  매일 02:30

frontend/src/
  pages/sourcing/DiscoveryPage.tsx        ★  추천 목록
  pages/sourcing/DraftReviewPage.tsx      ★  검수 UI
  pages/sourcing/SourcingSettings.tsx     ★  설정
  api/sourcingDiscoveryApi.ts             ★
  pages/ProductRegisterPage.tsx           유지 (URL 직접입력 경로는 남김)
```

### API 계약

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/sourcing/discovery/run` | 수동 재수집 (비동기, `batchId` 반환) |
| GET | `/api/v1/sourcing/candidates` | 추천 목록 (점수순, 통관/카테고리/점수 필터) |
| GET | `/api/v1/sourcing/candidates/{id}` | 후보 상세 (점수근거, 성분, 통관사유) |
| POST | `/api/v1/sourcing/candidates/{id}/reject` | 거절 (쿨다운 등록) |
| POST | `/api/v1/sourcing/drafts` | 후보 N개 → 초안 생성 (S4, 비동기) |
| GET | `/api/v1/sourcing/drafts/{id}` | 초안 조회 (마켓별 포함) |
| PATCH | `/api/v1/sourcing/drafts/{id}` | 검수 수정 (공통 + 마켓별) |
| POST | `/api/v1/sourcing/drafts/{id}/publish` | 상품 생성 + 마켓 등록 |
| GET/PUT | `/api/v1/sourcing/config` | 설정 |
| POST | `/internal/customs/sync-banned` | 식약처 성분 수동 동기화 |

---

## 7. 구현 순서

각 페이즈 끝에 컴파일·테스트·커밋한다. **배포(`git push`)는 배치 미실행 시점에만.**

| P | 범위 | 산출물 |
|---|---|---|
| **1** | Scrapling iHerb 스크래퍼 (목록/상세) + 사이드카 엔드포인트 | `scrapers/iherb.py`, `/discover/bestsellers`, `/scrape/product-detail` |
| **2** | 도메인 엔티티 + DDL + 리포지토리 | 5개 테이블, 엔티티, 수동 DDL 스크립트 |
| **3** | S0 발굴 + S1 중복제외 + `ScraplingIherbClient` | 후보 풀이 채워짐 |
| **4** | S2 통관 게이트 + 식약처 동기화 | 반입차단 성분 대조 동작 |
| **5** | 국내 수요 어댑터 (네이버 2종) + S3 스코어링 | 점수 + 근거 산출 |
| **6** | OpenCode Zen LLM 어댑터 + 폴백 체인 | 상품명·키워드 생성 |
| **7** | S4 인리치먼트 + 마켓별 초안 빌더 | 4마켓 초안 자동 생성 |
| **8** | 마켓 publish 전면 재작성 (스토어→11번가→Cafe24→쿠팡) | 필수필드 완전 충족 |
| **9** | S6 등록 오케스트레이션 + 진행률 | 병렬 publish + SSE |
| **10** | 프론트 3화면 | 추천/검수/설정 |
| **11** | 스케줄러 2종 + 설정 연동 | 자동화 완성 |

### 필요 환경변수

```bash
# 네이버 검색API (쇼핑) — 경쟁상품수·최저가
NAVER_OPENAPI_CLIENT_ID=
NAVER_OPENAPI_CLIENT_SECRET=
# 네이버 검색광고API — 월간 검색량·연관 키워드
NAVER_SEARCHAD_API_KEY=
NAVER_SEARCHAD_SECRET_KEY=
NAVER_SEARCHAD_CUSTOMER_ID=
# OpenCode Zen (무료모델)
ZEN_API_KEY=
```

전부 **없어도 파이프라인이 도는** 선택적 의존이다. 미설정 시 해당 신호는 가중치에서 빠지고
LLM은 규칙기반으로 폴백한다. 식약처 반입차단 성분 목록은 **인증키가 필요 없다**.

### 마켓 계정 고정값 (2026-07-26 확정)

| 값 | 처리 |
|---|---|
| 쿠팡 출고지 `1206157` / 반품지 `1000519746` | 기존 `CoupangProductPayload` 하드코딩값 승계 |
| 스마트스토어 A/S 전화 `010-2597-2480` | 설정 기본값 |
| 스마트스토어 출고지·반품지 주소록 ID | **커머스API 자동 조회 + 캐시** (`SmartstoreAddressBookResolver`) |
| 11번가 출고지 `addrSeqOut=5`(미국) / 반품지 `addrSeqIn=3`(국내) | D-092 라이브 검증값 승계 |
| 11번가 택배사 `00034`(CJ) / 반품·교환배송비 `7000` / 원산지 `1405`(미국) | D-092 승계 |
| Cafe24 진열 분류번호 | **`GET /admin/categories` 자동 조회 + 이름 매칭**, 실패 시 최소 번호 폴백 |

> ⚠️ 11번가 출고지는 **주소 시퀀스코드 `addrSeqOut`** 이다. `dlvCnAreaCd`(=01 전국)는
> *배송가능지역* 코드지 주소코드가 아니다. D-092에서 이 혼동으로 "출고지 주소를 확인해주세요"
> 벽에 부딪힌 전례가 있어 `MarketRequiredFieldValidatorTest`로 키 이름을 고정해 두었다.

---

## 8. 설계 원칙 (기존 코드베이스 규율 승계)

- **외부 I/O를 트랜잭션이 감싸지 않는다** — `ProductCreateUseCase`/`ProductPublishUseCase`의
  "외부호출 → 짧은 트랜잭션 커밋" 패턴을 그대로 따른다 (F-PSRC-8/14).
- **실패를 조용히 삼키지 않는다** — 크롤 실패·LLM 실패·성분추출 실패는 전부 사유와 함께 표면화.
  특히 성분 추출 실패는 `PASS`가 아니라 `REVIEW`.
- **멱등** — 재수집은 upsert, 재등록은 기존 `MarketRegistration` 행 재사용.
- **점진적 성능 저하** — 선택적 의존이 죽어도 파이프라인 전체는 계속 돈다.
- **근거 저장** — 점수·통관 판정은 근거를 JSON으로 남겨 UI에서 설명 가능하게.
