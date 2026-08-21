# Salvage — core/product · core/sourcing (2026-08-21)

주석 전량 제거 전, **코드로 표현할 수 없는 "왜"**만 골라 보존한다.
"이 메서드는 X를 한다" 류(코드가 이미 말하는 것)는 옮기지 않았다.

범위: `core/**/product/**`, `core/**/sourcing/**` (main + test).

---

## 1. 마켓 API 함정 (실측으로만 알 수 있는 것)

### 쿠팡 — 재게시·삭제는 `sellerProductId`, 가격/재고는 `vendorItemId`
**파일:** `application/product/ProductManageUseCase#republishToMarkets`, `#deleteProduct`
`extractMarketCode()`는 가격/재고용이라 `vendorItemId`를 우선 반환한다. 그런데 이미지/HTML 재게시와
삭제는 상품 리소스 레벨 API(`seller-products`)를 호출하므로 `sellerProductId`가 필요하다.
여기에 `vendorItemId`를 넘기면 마켓이 `Product(...) data not found`로 거절한다.
→ 그래서 재게시·삭제 경로는 `extractDeleteCode()`(=sellerProductId)를 쓴다.
SMART_STORE/11번가/Cafe24는 `extractDeleteCode == extractMarketCode`라 동작이 같다.

### 11번가 — 출고지/반품지는 `addrSeqOut`/`addrSeqRt`(주소 시퀀스코드)이지 `dlvCnAreaCd`가 아니다
**파일:** `domain/sourcing/component/MarketRequiredFieldValidator#elevenst`,
`application/sourcing/enrich/MarketDraftBuilder`(11번가 extraFields)
`dlvCnAreaCd`는 **배송가능지역**(01=전국)이라 필수 주소코드가 아니다. 둘을 혼동하면 값이 채워져 있는데도
11번가가 "출고지 주소를 확인해주세요"로 거절한다(D-092에서 실제로 막혔던 벽).
검증기가 엉뚱한 키를 보면 **미충족을 통과로 판정**해 등록 시점에야 실패한다.

### G마켓·옥션 — 상품등록 API가 존재하지 않는다
**파일:** `application/product/MarketPlusHandoffService`, `ProductPublishUseCase`,
`application/product/dto/MarketPlusHandoff`
유일 경로는 Cafe24에 등록된 상품을 **마켓플러스 미판매 목록**에서 골라 '일괄 보내기'로 내보내는 것.
그 과정에 상품마다 마켓 카테고리 4단계를 사람이 골라야 하고 전송 팝업에 reCAPTCHA가 걸린다 → 자동화 불가.
**마켓플러스 목록은 Cafe24 `product_code` 완전일치로만 검색된다** — 자체상품코드(sbCode)는 그 화면에
노출조차 되지 않는다(스파이크 실측). 그래서 코드가 없으면 사용자를 헛걸음시키지 말고 이유를 말해야 한다.
또한 `MarketClient` 구현체 자체가 없어(D-044) 가격/재고 동기화·재게시·삭제 경로에서 모두 skip 대상이다.

### 스마트스토어 — 상품검색 API가 `originProductNos` 필터를 무시한다
**파일:** `application/product/MarketLinkIdentifierBackfillService` (MARKET_PLANS 상수)
라이브 확인 결과 필터를 넣어도 전체를 반환한다 → 단건 조회가 불가능하므로 **전체 페이지를 순회해
origin→channel 맵을 한 번에 구축**(bulkScan)하고 페이지 간 500ms를 둔다.
쿠팡 `seller-products` GET은 단건 API라 batch=1 · 요청 간 100ms.

### 스마트스토어 — 판매자 즉시할인은 마켓별 가격산정과 이중으로 겹친다
**파일:** `application/product/SmartstoreSellerDiscountRemovalService`
마켓별 판매가가 이미 스토어 저수수료(8%)에 맞춰 낮게 산정되므로, 상품에 따로 걸린
`customerBenefit.immediateDiscountPolicy`가 남아 있으면 이중할인으로 손해가 난다(D-096).
멱등(할인이 있을 때만 제거). 네이버 API 429를 피하려 항목 간 지연 + 백오프 재시도(2s, 4s)를 둔다 —
동시에 도는 주문 동기화와 API 쿼터를 경합하므로 여유 있게 잡은 값이다.
`InterruptedException`은 재시도하지 않고 즉시 전파한다(중단 신호).

### 마켓별 상품명 길이·키워드 개수 (실 스펙)
**파일:** `domain/sourcing/component/MarketProductRules`
- 쿠팡: `displayProductName` 100자 / `searchTags` 최대 20개
- 스마트스토어: `originProduct.name` 100자 / `sellerTags` 최대 10개
- 11번가: `prdNm` 100자 / 연관검색어 최대 10개
- Cafe24: `product_name` 250자 (자사몰이라 여유가 크다)

### 스마트스토어 등록 필수필드 (공식 스펙)
**파일:** `domain/sourcing/component/MarketRequiredFieldValidator#smartstore`
`POST /v2/products`는 `originProduct.statusType/name/detailContent/images/salePrice/detailAttribute`와
`smartstoreChannelProduct`가 모두 REQUIRED이고 `leafCategoryId`·`stockQuantity`는 "상품 등록 시 필수"다.
과거 구현이 보내던 6필드로는 등록이 성립하지 않는다.
쿠팡은 구매대행(해외직구) 상품이라 원산지·수입 관련 항목이 추가로 필요하다.
Cafe24는 진열 분류가 없으면 등록은 되나 노출되지 않는다.

---

## 2. 순서·트랜잭션 제약 (지키지 않으면 조용히 깨진다)

### 되돌릴 수 없는 외부 게시 → PENDING 선-저장 필수 (F-PSRC-14)
**파일:** `ProductPublishUseCase`, `MarketRegistrationTxService`,
`application/sourcing/publish/DraftPublishUseCase`, `DraftPublishTxService`
`@Transactional` 안에서 `client.publish()` **후** save 하면, save/커밋 실패 시 마켓엔 상품이 올라갔는데
DB엔 등록이 없는 **조용한 고아**가 된다 — 트랜잭션은 외부 게시를 롤백하지 못한다.
그래서 흐름을 고정한다: **PENDING 선-저장(별도 트랜잭션 커밋) → publish(트랜잭션 밖) → identifiers+SYNCED 갱신**.
3단계가 실패해도 1단계 PENDING 행이 이미 커밋돼 있어 고아가 아니라 **복구 가능한 미완료 상태**로 남는다.
이때 마켓 identifiers를 복구용 ERROR 로그로 남기고 실패를 표면화한다(삼키지 않는다).

### 외부 I/O를 트랜잭션이 감싸면 안 된다 (F-PSRC-8)
**파일:** `ProductCreateUseCase`+`ProductPersistTxService`, `ProductManageUseCase#deleteProduct`+`ProductDeleteTxService`,
`SourcingDiscoveryUseCase`+`CandidateIngestTxService`, `CandidateEnrichmentPipeline`+`CandidatePersistTxService`,
`DraftEnrichmentUseCase`+`DraftPersistTxService`
이미지 다운로드·R2 업로드·브라우저 렌더 크롤(후보당 ~10초, 30건이면 5분 이상)·LLM·마켓 API가
트랜잭션 안에 있으면 DB 커넥션을 그 시간 내내 점유한다. 게다가 트랜잭션이 롤백돼도 **R2에 올라간 이미지는
고아로 남는다**(외부 부수효과는 롤백 대상이 아니므로).
→ UseCase는 트랜잭션을 열지 않고, DB 쓰기만 별도 Tx 빈의 짧은 트랜잭션으로 커밋한다.
→ 저장 실패는 **조용히 삼키지 않고 표면화**한다(이미 R2에 업로드된 이미지가 고아가 될 수 있으므로).

**Tx 빈을 별도 클래스로 분리한 이유(전부 동일):** 같은 클래스 내부 self-invocation은 Spring 프록시를
거치지 않아 `@Transactional`(및 `REQUIRES_NEW`)이 **적용되지 않는다**. 한 클래스로 합치면 조용히 무력화된다.

**후보/초안 단위 커밋인 이유:** 전체를 한 트랜잭션으로 묶으면 중간에 하나 터질 때 이미 채점·생성한
앞의 것까지 전부 롤백된다. 건별 독립 커밋으로 부분 진행을 보존한다.

### `savePending` 멱등성 — 유니크 제약 위반은 정상 경로다 (F-PSRC-13)
**파일:** `MarketRegistrationTxService#savePending`
재게시로 이미 행이 있으면 재사용한다(순차 재호출 멱등). 동시 재게시 경쟁으로 두 트랜잭션이 모두
"행 없음"을 관측해 둘 다 insert를 시도하면 `sb_market_registration(product_id, market_type)`
유니크 제약으로 하나만 성공한다. 진 쪽은 `DataIntegrityViolationException`을 받으므로
**이미 커밋된 상대 행을 재조회해 재사용**해야 멱등이 보장된다(예외를 실패로 처리하면 안 된다).

### 마켓 부분 실패는 전체를 롤백하지 않는다
**파일:** `ProductMarketSyncService#syncInternal`, `ProductManageUseCase#republishToMarkets`/`#deleteProduct`,
`DraftPublishUseCase`
라이브 마켓 쓰기라 마켓별 `try`로 감싸 부분 실패를 수집한다 — 한 마켓 실패가 나머지 마켓·자사 DB 갱신을
롤백하면 안 된다. 4마켓 중 1곳 실패로 전부 롤백하면 사용자가 처음부터 다시 해야 한다.
실패는 `MarketDraft.publishError` / `MarketRepublishResult.failed`에 남겨 **그 마켓만 재시도**할 수 있게 한다.

### 삭제는 best-effort(C) — 실패 마켓 로그가 유일한 추적 흔적
**파일:** `ProductManageUseCase#deleteProduct`, `ProductDeleteResult`
마켓 삭제 실패가 DB 삭제를 막지 않는다. **등록행이 지워지므로** 실패 마켓 + `marketItemId`를
ActionLog(PRODUCT_DELETE)에 남긴 것이 사용자가 수동 정리할 유일한 근거다.

### Cafe24 변경감지 스킵의 조건
**파일:** `ProductMarketSyncService`, `BatchPriceStockService`
`changed=false`이고 직전 동기화가 성공(`isSynced=true`)한 Cafe24 등록행만 재전송을 스킵한다
(변경 없는 상품의 불필요한 Cafe24 재전송 → downstream 옥션 연동 노이즈를 줄이려는 것. 우선 Cafe24만).
`changed=true`는 "변경 여부 정보가 없는 호출자(단건 수정 등)는 항상 전송"이라는 현행 동작 보존 값이다.
**실패 시 `isSynced=false`를 영속화**해야 변경없음이어도 다음 배치에서 재시도된다.

---

## 3. 가격·마진 산정 근거 (D-094 계열)

### 마켓별 실수수료로 판매가를 따로 산정한다
**파일:** `MarketSalePriceResolver`(단일 출처), `ProductMarketSyncService`, `ProductPublishUseCase`,
`BatchPriceStockService`, `domain/product/service/MarginCalculator`, `MarketDraftBuilder`
같은 원가·마진·쿠폰·최소마진이라도 수수료가 다르면 목표 마진을 맞추는 판매가가 달라야 한다
(`sb_fee_policy`: 쿠팡 11 · 스토어 8 · G마켓/11번가 18). 기준가(`sb_product.sale_price`)는
**쿠팡 실수수료 기준**으로 산정한 표시·단건용 값이고, 각 마켓 전송가는 따로 재산정한다.
**등록 순간부터 마켓별 가격으로 올려야 한다** — 기준가로 올리면 다음 재가격 배치까지 수수료가 다른
마켓은 목표 마진을 벗어난 가격으로 팔린다.
계산식 순서: 쿠폰 반영(실매입가) → 마진/수수료 divisor → 100원 올림 → 최소마진 보정.
검증 실측값: 원가 31522·묶음2·마진10·쿠폰15·최소3500(실매입 53587.4) →
쿠팡 11%(divisor 0.79) 67,900원 / 스토어 8% 65,400원 / G마켓 18%(divisor 0.72) 74,500원.
기본 채널수수료 18.5%는 **마켓 컨텍스트가 없는 하위호환 시그니처 전용**이다.

**`MarketSalePriceResolver`가 단일 출처여야 하는 이유:** 동기화 경로와 신규 등록 경로가 서로 다른 가격을
만들면 등록 직후와 배치 이후의 가격이 달라져 **원인을 알 수 없는 가격 변동**으로 보인다.

### 신규 등록가가 높게 나가던 편향 (결함 B)
**파일:** `MarketSalePriceResolver#resolveForProduct`, `dto/MarketSalePriceOverrides`
쿠폰율·최소마진은 **배치 실행 파라미터라 상품에 저장되지 않는다**. 그래서 오버라이드 없는 등록 경로는
원가·마진율·묶음수량만으로 계산해 쿠폰 미반영분만큼 보수적으로(높게) 산정된다.
실측: 원가 4만·마진15·쿠폰20 기준 동기화 경로 51,400원 vs 등록 경로 62,200원.
**이 편향을 낮춰줄 정기 재가격 배치는 D-093 사용자 결정으로 비활성**이므로(수동 배치만),
호출자가 오버라이드를 넘기지 않는 한 편향이 등록 시점 그대로 남는다.
원가·마진율이 없으면 기준가를 그대로 쓴다 — 재료가 없다는 이유로 등록 자체를 막지는 않는다.

### `buyPrice`에 배송비를 묶음수량으로 나눠 넣는 이유
**파일:** `BatchPriceStockService`
`buyPrice = 상품원가 + 배송비/묶음수량`(유효단가). 이후 계산이 `×묶음수량` 하므로 결과적으로
`(원가×묶음) + 배송비 1회`가 된다 — **배송비는 묶음수량과 무관하게 주문당 1회**이기 때문이다.
iHerb 등 `shippingCost`가 없는 경로는 원가 그대로라 동작이 불변이다.

### 소스 링크 소멸 시 가격을 재산정하지 않는다
**파일:** `BatchPriceStockService`, `application/product/dto/StockCheckResult`(`sourceGone`)
F&M 404 등으로 소스 페이지가 사라지면 **가격 재산정 없이 재고만 품절 처리**한다(오가격 방지).
기존 가격/원가를 유지한다.

### 크롤 배치에만 항목 간 딜레이가 있다 (의도된 비대칭, F-BATCH-M2)
**파일:** `BatchPriceStockService`, `ProductSyncService`(0.5초)
외부 소싱 사이트 rate-limit(IP 차단) 완화용. 수동(manual) 경로는 외부 크롤이 없어 이 완충이 없다.

---

## 4. 통관·식약처 제약 (규제 — 틀리면 실손실)

### 게이트 대원칙: **모르면 통과시키지 않는다**
**파일:** `application/sourcing/customs/CustomsEligibilityService`, `domain/sourcing/enums/CustomsVerdict`
잘못 차단하면 기회를 잃지만, 잘못 통과시키면 주문받은 상품이 통관에서 **폐기·반송돼 실손실**이 난다.
성분표를 못 읽었거나 애매하면 PASS가 아니라 REVIEW로 올려 사람이 본다.
이 게이트는 1차 스크리닝이며 최종 통관 책임을 대신하지 않는다.

### 키 길이로 BLOCKED / REVIEW를 가른다
**파일:** `CustomsEligibilityService`, `domain/sourcing/BannedIngredient`
- 3글자 이상 키 매칭 → BLOCKED (예: "요힘빈", "시부트라민")
- 2글자 키 매칭 → REVIEW (예: "대마" — "대마씨유"처럼 무관한 성분에 걸릴 수 있다)
- 1글자 키는 아무 성분에나 걸리므로("차", "산") 아예 버린다
- 영문 첫 토큰은 충분히 길 때만 별도 키로 쓴다("kava"(4자)는 오탐 위험이 커서 제외, "ephedra"는 채택)
성분표는 "…, 마황추출물, …"처럼 대표명에 수식어가 붙으므로 매칭은 **정규화 부분일치**다.

### 식약처 원문의 괄호 설명을 그대로 정규화하면 게이트가 무력화된다
**파일:** `BannedIngredient#koKeys`
원문은 "카바카바(뿌리, 잎, 줄기)", "대마(「마약류 관리에 관한 법률」…)" 형태다. 전체를 정규화하면
"카바카바뿌리잎줄기"가 되어 성분표의 "카바카바"와 **절대 매칭되지 않는다**.
→ 괄호 앞 머리부를 반드시 **별도 키**로 넣어야 한다.
정규화는 공백·하이픈·괄호·쉼표·마침표를 지우고 소문자화 — 표기 흔들림("MK-7" vs "MK7",
"요힘빈 추출물" vs "요힘빈추출물")을 흡수하기 위함.

### 동기화 실패 시 기존 목록을 절대 지우지 않는다
**파일:** `application/sourcing/customs/BannedIngredientSyncService`, `CustomsEligibilityService`
목록이 비면 게이트가 **모든 상품을 PASS로 판정**해 차단 성분 상품이 그대로 등록된다 — 조용한 최악의
시나리오다. "받아온 게 없으면 아무것도 하지 않고 실패를 올린다"가 이 서비스의 핵심 규칙.
`CustomsEligibilityService`는 **호출측 가드에 의존하지 않고 자기도 빈 목록을 막는다** —
새 호출 경로가 생길 때 조용히 뚫리는 것을 방지.

### 별칭 보강 시드는 규제 판단이 아니다
**파일:** `application/sourcing/customs/IngredientAliasSeed` (⚠️ 살아있는 코드 — §버그리포트 참조)
식약처 목록은 대표명 하나만 주는데 상품 성분표는 같은 성분을 다른 표기로 적는다
("요힘빈" vs "요힘베" vs "Yohimbe"). 대표명만 대조하면 차단 대상을 상당수 놓친다.
시드는 **목록에 이미 있는 항목의 매칭률만 올린다** — 규제 대상 여부는 전적으로 식약처 목록이 정하고,
`aliasesFor`는 목록에 없는 이름에 아무것도 돌려주지 않는다.
조회는 **부분일치가 아니라 정확 일치**다 — "대마"가 "대마씨유"에 걸려 엉뚱한 별칭이 붙는 것을 막는다.

### 구매대행 표기·수량 제약
**파일:** `application/sourcing/enrich/ProductNoticeBuilder`, `DetailHtmlBuilder`, `MarketDraftBuilder`,
`DraftEnrichmentUseCase`
- 구매대행 고지 표기 누락은 **표시광고법 이슈**가 된다.
- 구매대행은 **판매자가 수입자가 아니다** — 표기를 흐리면 안 된다.
- 마켓 초안은 1인당 구매수량을 제한해 **통관 자가사용 기준 초과**를 예방한다.
- 묶음 수량 상한은 **개인통관 자가사용 인정 기준(건강기능식품 총 6병)** 을 넘지 않도록 잡았다.
- iHerb 상세는 제조국을 일관되게 주지 않는다. 확인 못 하면 "상세설명 참조"로 두고 사람이 채운다 —
  임의로 "미국"이라 적으면 **원산지 허위표기**가 된다.
- 상품정보제공고시는 전자상거래법상 필수 표기이자 마켓 필수필드다. 크롤이 준 게 없으면
  "상세설명 참조"로 채운다 — 빈 값으로 보내면 마켓이 거절하거나 심사에서 반려된다.
- 크롤한 문자열이 그대로 HTML에 들어가므로 **태그 주입을 막아야 한다**(`DetailHtmlBuilder`).

---

## 5. 스코어링·발굴 설계 근거 (실측값 포함)

### 결측 신호는 0점이 아니다
**파일:** `application/sourcing/discovery/CandidateScoringService`
네이버 자격증명이 없거나 iHerb가 판매량을 노출하지 않으면 그 서브스코어는 0이 아니라 **결측**이다.
0으로 치면 신호가 없는 후보가 전부 하위로 밀려 **순위가 신호 가용성에 좌우된다**.
→ 사용 가능한 가중치 합으로 나눠 "가진 신호 기준의 상대 점수"를 낸다.
어떤 신호가 결측이었는지도 근거에 남긴다 — 점수가 낮은 이유를 설명할 수 있어야 한다.

### 수익성은 점수가 아니라 게이트다
**파일:** `CandidateScoringService`
마진 미달 상품은 아무리 인기 있어도 팔면 손해라 순위를 낮추는 게 아니라 **후보에서 뺀다**.

### 마진 가드에 `minMarginPrice`를 일부러 넘기지 않는다
**파일:** `CandidateScoringService#estimateSalePrice` 계열
`MarginCalculator`는 최소 마진에 미달하면 판매가를 그만큼 **끌어올려** 마진을 맞춘다. 그 값을 그대로
쓰면 마진 가드는 **항상 통과하는 죽은 코드**가 된다(계산기가 이미 조건을 만족시켜 버렸으므로).
알고 싶은 건 "목표 마진율로 자연스럽게 붙는 마진이 최소 기준을 넘는가"이므로 **보정 없는 자연가**로
계산한 뒤 그 마진을 기준과 비교해야 한다.

### 가격 경쟁력 기준은 최저가가 아니라 **중앙값**이다
**파일:** `CandidateScoringService`, `domain/sourcing/SourcingCandidate`(`domesticLowestPrice` 주석)
최저가(lprice)로 재면 광범위 키워드의 절대 최저가가 소용량·샘플 같은 비교 불가 상품에 걸려
멀쩡한 후보를 전부 죽인다. **운영 실측: "비타민D3" 최저가 250원 → 240정 제품 25/25 전멸.**
`domesticLowestPrice`는 표시용으로만 남기고 판정은 `domesticMedianPrice`로 한다.

### 시세를 믿어도 되는지 판정하는 기준 (`isBenchmarkReliable`)
**파일:** `CandidateScoringService` (`MIN_PLAUSIBLE_BENCHMARK_RATIO`)
"시세 < 원가"만으로는 **키워드 오매칭**과 **진짜 가격경쟁력 없음**을 구분할 수 없다(후자는 걸러야 하는
정상 케이스다). 구분선은 **격차의 크기**이며, 실측으로 잡았다 (매입원가 대비 국내 중앙값 비율):

| 키워드 | 중앙값 | 원가 | 비율 | 판정 |
|---|---|---|---|---|
| "Gold" | 10원 | 23,172원 | 0.0004 | 명백한 오매칭 |
| "비타민D3" | 2,820원 | 12,763원 | 0.22 | 240정 vs 소용량, 오매칭 |
| "Neuro-Mag" | 43,000원 | 42,327원 | 1.02 | 같은 상품군, 신뢰 가능 |

못 믿는 신호로 후보를 떨어뜨리느니 **판정하지 않는** 편이 낫다(0점으로 깎아도 안 된다).

### 검색량과 가격은 **서로 다른 키워드**로 조회한다
**파일:** `application/sourcing/discovery/CandidateEnrichmentPipeline`,
`domain/sourcing/component/SearchKeywordDeriver`, `application/sourcing/enrich/ProductTextService`
- **검색량**은 카테고리 수요를 재는 것이라 *일반적인* 말이어야 한다("비타민D3")
- **가격**은 같은 상품과 비교해야 하므로 *구체적인* 말이어야 한다(한글브랜드 + 제품 핵심부)

둘을 한 키워드로 묶었더니 실측에서 무너졌다 — 검색량을 최대화하면 "Gold" 같은 일반어가 뽑히고
그 중앙값이 10원이 나와 멀쩡한 후보가 전부 탈락했다.
같은 이유로 **연관 키워드 확장 시드도 구체적이어야 한다** — 일반어 시드 "스포츠"로 확장하면
"축구", "스포츠토토" 같은 무관한 말이 섞인다(실측).

### 시드 검색량은 정확히 일치하지 않으면 **최댓값**을 쓴다
**파일:** `CandidateEnrichmentPipeline`
네이버 키워드도구는 시드를 정규화해 돌려주는 일이 있어, 문자열 일치에만 의존하면 자주 결측이 된다.

### 상세 크롤은 상위 후보에만 (비용 통제)
**파일:** `CandidateEnrichmentPipeline`
발굴 회차마다 500여 건이 올라오는데 전건 상세 크롤은 **2시간이 넘는다**. 그래서 순서를 뒤집는다:
(1) 크롤 없는 신호로 1차 채점 → (2) 상위 N건만 상세 크롤 + 통관 판정 + 수요 조회 → (3) 재채점.
정밀 처리 대상 배수는 추천 목표의 여유분이다(목표 20건이면 60건 — 통관 차단·수요 미달로 빠지는 만큼).
1차 채점은 **정확한 점수가 아니라 정밀 심사 대상을 고르는 것**이 목적이며, 그 신호(판매량·리뷰·평점·랭킹)는
목록 카드에서 이미 다 얻었으므로 추가 비용이 0이다.
정밀 대상 밖으로 밀린 후보도 **사유를 남긴다**(조용히 사라지지 않게).
점수는 후보당 한 번만 계산해 두고 정렬한다 — 비교자 안에서 계산하면 O(n log n)번 재계산된다.

### 단계별 실패를 삼키면 추천 품질이 조용히 무너진다
**파일:** `SourcingDiscoveryUseCase`, `dto/DiscoveryCrawlResult`, `dto/DiscoverySummary`,
`CandidateEnrichmentPipeline`
일부 카테고리가 Cloudflare/봇 차단을 당했는데 "인기 상품이 없네"로 오인하면 알 수 없다.
페이지 단위 실패를 `warnings`로 모아 올려 **후보 수가 적은 이유를 설명할 수 있어야** 한다.
상세 크롤 실패도 마찬가지 — 조용히 넘기면 **성분 미상 상품이 PASS로 흘러간다**.

### 재수집(upsert)은 사용자 판단과 진행 상태를 되돌리지 않는다
**파일:** `domain/sourcing/SourcingCandidate#refresh` 계열, `CandidateIngestTxService`
`(vendor, externalId)` 유니크 키로 upsert하되 가격·랭킹·리뷰수만 갱신한다.
사용자 판단(REJECTED)과 진행 상태(DRAFTED/PUBLISHED)를 건드리면, **매일 도는 스케줄러가 사용자가
거절한 상품을 되살려 같은 상품이 무한히 재추천된다.**
통관 판정도 보존한다(성분은 자주 바뀌지 않고, 재판정은 상세 크롤 비용이 든다).
거절은 "지금은 아니다"이지 영구 차단이 아니므로 **쿨다운이 지나면 다시 발굴 대상으로 되돌린다**.

### 중복 등록 판정은 URL이 아니라 숫자 ID로 한다
**파일:** `domain/sourcing/component/VendorProductIdExtractor`, `CandidateIngestTxService`,
`domain/product/ProductRepository#findAllSourcingUrls` 계열
같은 상품이라도 URL은 여러 형태로 저장돼 있다(`www.iherb.com/pr/slug/124745`,
`kr.iherb.com/pr/other-slug/124745?rcode=ABC`, `iherb.com/product/124745`).
슬러그는 iHerb가 상품명을 바꾸면 같이 바뀌고 도메인/쿼리도 제각각이라 **URL 문자열 비교로는 같은 상품을
다른 상품으로 오인한다.**
⚠️ **Python 쪽 `scrapers/iherb.py:extract_product_id`와 동일한 규칙이어야 한다** —
한쪽만 바뀌면 중복 상품이 조용히 재등록된다.
`ProductRepository`는 엔티티 전체가 아니라 URL 문자열만 가져와 대량 스캔 비용을 낮춘다.

### 랭킹 점수는 sponsored 상품에서 신뢰할 수 없다
**파일:** `SourcingCandidate#sponsored`, `SourcingConfig#excludeSponsored`
광고 노출 상품은 랭킹이 유기적 인기가 아니다.

### 점수 구간 캘리브레이션 근거
**파일:** `CandidateScoringService`
평점은 3.5 미만 0점 · 5.0 만점 — **영양제 평점은 대부분 4점대라 4.0~5.0 구간 해상도가 중요**하다.
할인 30% 이상 만점 — 매입 원가가 낮아 마진 여유가 생긴다.
가격경쟁력은 국내 시세 대비 30% 이상 저렴하면 만점. 경쟁상품 수는 **적을수록** 좋다(역방향).
log 정규화 상한은 각각 10만(판매량/30일 · 리뷰 건 · 검색 회/월 · 경쟁상품 수).
자사 이력 정규화는 "최대값 대비 비율" — 절대 판매량은 사업 규모에 좌우되지만 추천은
"우리 기준으로 잘 팔리는 축인가"만 알면 되므로 상대값이면 충분하다.
가중치 기본값 합계 100 = 국내 수요(검색량·경쟁·가격경쟁력) 40 / iHerb 신호 50 / 자사 이력 10.

---

## 6. 도메인 함정 (조용히 데이터가 오염되는 것들)

### `@Lob` 금지 — PostgreSQL에서 조회 전체가 깨진다 (D-021, 운영 실측)
**파일:** `domain/product/Product`(상세 HTML 필드)
PostgreSQL에서 `@Lob String`은 Large Object(OID)로 매핑돼 text 컬럼을 `getLong()`으로 읽다
`Bad value for type long`으로 **조회 전체가 깨진다**.

### `soldOut = null`은 "변경 없음"이지 false가 아니다 (F-PROD-7)
**파일:** `ProductManageUseCase#updatePriceAndStock`
과거엔 null이 조용히 false→IN_STOCK으로 붕괴돼 **품절 상품이 가격 수정만으로 판매재개 상태가 마켓에
전파**됐다. null이면 재고상태를 변경하지 않고 **현재 상태를 그대로 마켓에 전파**한다.
DB 수량은 건드리지 않는다 — 재고는 판매중/품절 이분법이고, 판매중일 때 마켓에 보내는 수량은 고정 상수다.

### `restockDate` null 소거 방어 (D-065)
**파일:** `ProductSyncService`
크롤이 일시적 파싱 실패로 `restockDate=null`을 줘도, **여전히 OUT_OF_STOCK이면 DB의 기존 재입고일을
덮어쓰지 않는다**(재입고일 "-" 버그). IN_STOCK이면 재입고일이 의미 없으므로 null 반영을 허용해 지운다.

### 이미지 리스트: null = 유지, 빈 리스트 = 전체 제거 (F-PROD-13)
**파일:** `domain/product/Product#updateImages` 계열

### 병렬 배열(index 매핑) 금지 (F-BATCH-M1)
**파일:** `application/product/dto/PriceStockItem`
`productId`/`price`/`stock`을 병렬 배열로 받으면 입력 순서가 어긋날 때 **엉뚱한 상품에 값이 적용되는
데이터 오염**이 발생한다. 쌍(record)으로 묶어 원천 차단. price/stock은 null 허용(부분 수정).

### `ProductUpdateCommand`는 26필드 — 위치기반 생성자는 오배치 위험 (F-PROD-10/14)
**파일:** `domain/product/dto/ProductUpdateCommand`
대부분의 호출부가 1~4개 필드만 채우므로 Builder를 쓴다. 미지정 필드는 null(모든 필드 객체타입)이라
종전 위치기반 null 채움과 동작이 동일하다.

### ProcessStatus 진행현황 행의 KEY는 `productId`(String.valueOf)다
**파일:** `BatchPriceStockService`(startBatch/updateStep)
과거엔 sbCode를 KEY로 써서 `updateStep`의 `productCode.equals(...)` 필터가 매칭되지 않아
**모든 행이 PENDING에 머물렀다.**

### 미존재 리소스 조회는 404여야 한다 (F-PROD-5/26/29)
**파일:** `ProductManageUseCase` 등 상품 조회 경로
`IllegalArgumentException`으로 던지면 GlobalExceptionHandler가 400으로 매핑해 "잘못된 입력"으로 잘못
전달된다. 입력값 자체는 유효하나 대상이 없는 경우이므로 `ResourceNotFoundException`(404)이 옳다.

### 마켓 필터와 키워드는 AND로 결합한다 (F-PROD-1)
**파일:** `domain/product/ProductRepository`, `domain/product/component/ProductReader`, `ProductSearchUseCase`
둘 다 지정하면 keyword가 무시되던 버그가 있었다.

### 대량 등록 시퀀스는 1회만 조회하고 로컬 증가
**파일:** `ProductCreateUseCase`
항목마다 재조회하면 아직 저장 안 된 분을 못 봐 **sbCode 충돌·시퀀스 건너뜀**이 발생한다.

### 이미 호스팅된 이미지는 다시 올리지 않는다
**파일:** `ProductCreateUseCase`, `DraftPublishUseCase`
소싱 초안 경로는 인리치먼트 단계에서 R2 업로드를 이미 끝내고 온다. 여기서 또 올리면 같은 이미지가
R2에 중복 적재되고(고아 사본) 등록도 그만큼 느려진다.
반대로 인리치먼트에서 업로드가 실패했을 때 **조용히 원본 URL로 넘기면 안 된다** —
마켓이 외부 URL을 거부하거나 나중에 깨진다(`DraftEnrichmentUseCase`).

### `@Async("productBatchExecutor")` 한정자와 빈 위치 (D-011)
**파일:** `BatchPriceStockService`, 테스트 `BatchPriceStockAsyncPoolTest`
한정자를 빼면(bare `@Async`) executor 빈이 복수(core의 `syncTaskExecutor` + `productBatchExecutor`)일 때
전용 풀을 특정하지 못해 `SimpleAsyncTaskExecutor`로 폴백한다.
`productBatchExecutor` 빈이 core가 아니라 api 모듈에만 있으면, api 설정을 갖지 않는 컨텍스트에서
한정자가 해소되지 않아 역시 폴백한다. → 빈은 **core에 있어야** 한다.

### 상세 HTML은 템플릿 **안쪽 본문만** 만든다
**파일:** `application/sourcing/enrich/DetailHtmlBuilder`
배너·제목·구성·이미지 나열은 `Product.generateTemplateHtml()`이 이미 붙인다. 여기서 전체 HTML을 만들어
`rawSourceHtml`로 넘기면 **배너 안에 배너가 들어가는 이중 래핑**이 된다.

### 상품명 조립 함정
**파일:** `domain/sourcing/component/ProductNameComposer`, `MarketProductRules#fitName`
LLM이 만든 핵심명은 대개 규격을 포함하는데 거기에 iHerb "상품 수량"을 또 붙이면
**"크레아틴 일수화물 무맛 454g 453개 3개"** 같은 이름이 나온다(실측). → 규격 표기가 이미 있으면 중복 금지.
묶음수 1개는 표기하지 않는다("1개"는 검색에 도움이 안 되고 글자수만 먹는다).
길이 제한은 단순 substring이 아니라 **마지막 공백에서 자른다** — 그러지 않으면 "…비타민D3 프리미"처럼
단어 중간이 끊긴다. 단, 그러면 절반 이상이 날아가는 경우엔 그냥 자른다.

### 키워드 추출 실패는 **조용한** 실패다
**파일:** `SearchKeywordDeriver`
추출이 틀리면 네이버 검색량이 0으로 나와 **잘 팔릴 상품이 조용히 낮은 점수**를 받는다.
그래서 핵심부가 비면 원본 상품명 전체로 폴백한다 — **신호 0보다 부정확한 신호가 낫다.**
말미 괄호 규격을 **먼저 통째로** 제거해야 한다 — "…소프트젤 100정(소프트젤당 1,100mg)"의 괄호 안 쉼표가
남아 있으면 세그먼트 분리가 "1"과 "100mg)"로 쪼개져 숫자 파편이 키워드에 남는다.
괄호 안 규격(MK-7 등)은 노이즈에 가깝지만 떼면 동음이의가 생겨 **괄호만 없애고 내용은 남긴다**.

### 키워드 풀은 순서가 곧 우선순위
**파일:** `application/sourcing/enrich/ProductTextService`
마켓별 상한(쿠팡 20 / 스토어·11번가 10)에서 **앞에서부터 자르므로** 검색량 근거가 있는 키워드가 먼저
와야 한다. LLM 키워드는 근거가 없지만 롱테일 확보용으로 일정 개수까지만 섞는다.
LLM 호출은 **상품 1건당 한 번만** 한다 — 마켓마다 부르면 4배 비싸고 마켓 간 상품명이 제각각이 된다.

### 묶음 수량은 "배송비 희석 효과가 충분히 큰 최소 수량"
**파일:** `application/sourcing/enrich/BundleQuantityOptimizer`
해외직구는 배송비가 주문당 1회 붙는다. 단품 20,000원 + 배송비 6,000원이면 실질 원가 26,000원(+30%)이지만
2개 묶음이면 개당 23,000원(+15%)이 된다. 묶음을 키울수록 개당 원가는 내려가지만 객단가가 올라
판매량이 줄고 무게 상한에 걸린다. → 수량을 하나 더 늘려도 개당 원가가 임계치(2%) 미만으로밖에
안 줄면 거기서 멈춘다. 무게가 불명이면 무게 제한을 적용하지 않는다(일반 상한만).
배대지 기본 배송비는 `MarginCalculator`의 `DELIVERY_FEE`와 **같은 값이어야** 한다.

### 초안을 `sb_product`에 바로 만들지 않는 이유
**파일:** `domain/sourcing/ProductDraft`
미검수 상태 상품을 `sb_product`에 넣으면 **재고 배치·가격 배치가 그걸 실제 판매 상품으로 착각해
마켓에 동기화하려 든다.** 초안을 분리해 그 오염을 막는다.

### 통관 REVIEW 미승인 상태로는 등록하지 않는다
**파일:** `ProductDraft#customsApproved`, `DraftPublishUseCase`, `DraftEnrichmentUseCase`
경고만 띄우고 등록을 허용하면 **경고가 없는 것과 같다.**

### 검수 수정은 판매가를 조용히 덮어쓰지 않는다
**파일:** `application/sourcing/SourcingQueryService#updateDraft`
공통 필드(묶음수량·마진율·원가)가 바뀌면 마켓별 판매가를 다시 계산해야 하지만, 여기서는 **사용자가
명시적으로 넘긴 판매가만** 반영한다. 가격 재계산은 별도 호출(`recalculatePrices`)로 분리했다 —
사용자가 손으로 맞춘 가격을 다른 필드 수정 때문에 조용히 덮어쓰면 안 되기 때문.
수정 후 필수필드를 다시 검사한다(상품명을 지우는 식의 수정으로 등록 불가가 되면 즉시 드러나야 한다).

### 카테고리 해석 실패는 초안 생성을 실패시키지 않는다
**파일:** `MarketDraftBuilder`, `port/MarketCategoryResolverPort`
카테고리는 등록 필수필드이면서 마켓마다 체계가 완전히 다르다. 잘못 넣으면 등록은 되지만 노출이 안 되거나
심사에서 반려되므로, 확신이 없으면 `confident=false`로 돌려 **값이 있어도 사람이 보게** 한다.
해석 실패는 미해결로 두고 사람이 고른다.

### 계정 리소스 조회 실패는 예외가 아니라 빈 맵
**파일:** `port/MarketAccountResourcePort`, `MarketDraftBuilder`
그러면 해당 필드가 비고 `MarketRequiredFieldValidator`가 "필수필드 미충족"으로 잡아 검수 화면에 표시한다 —
조용히 빈 값으로 마켓에 보내 400을 받는 것보다 낫다. 값은 계정마다 다르지만 거의 바뀌지 않으므로
**조회 후 캐시**가 맞다(상품마다 API를 치면 등록 속도만 느려진다).

### 선택적 포트는 없어도 파이프라인을 멈추지 않는다
**파일:** `port/KeywordVolumePort`, `port/ShoppingMarketPort`, `port/ProductTextGenerationPort`,
`port/ProductDetailCrawlerPort`, `port/BannedIngredientSourcePort`
- 검색량/쇼핑: 자격증명이 없으면 `isEnabled()=false`이고 스코어링이 그 가중치를 빼고 정규화한다.
  조회 실패는 예외가 아니라 빈 목록 — 신호 하나가 없다고 후보 전체를 버릴 이유가 없다.
- LLM: **품질 향상 레이어일 뿐**. 구현이 없거나 전부 실패해도 규칙 기반으로 계속 진행한다.
  실패 시 예외가 아니라 빈 `Optional`.
- 상세 크롤: 실패해도 예외를 던지지 않고 `ok=false`를 돌려준다(호출측이 REVIEW로 승격).
- 반입차단 원천은 반대다 — **장애 시 예외를 던져야** 호출측이 마지막 성공본을 유지한다.
  구현은 식품안전나라(foodsafetykorea.go.kr) 공개 목록으로 **인증키가 필요 없다.**

### 인리치먼트는 실패해도 초안을 만든다
**파일:** `DraftEnrichmentUseCase`
이미지 업로드가 실패하거나 LLM이 죽어도 사용자는 검수 화면에서 손으로 채워 등록할 수 있어야 한다.
다만 무엇이 실패했는지 `enrichNote`에 남겨 검수 화면에 띄운다 — 조용히 빈 값으로 두지 않는다.
상세 이미지에 상한을 두는 이유는 R2 비용·상세HTML 길이다.

### `kr.iherb.com`은 원화 표기다 — 환산하지 않는다
**파일:** `SourcingCandidate`(가격 필드), `dto/DiscoveredCandidateDto`
`MarginCalculator`의 `buyPrice`와 단위가 같다. 한글 성분표도 그대로 주므로 식약처 반입차단 목록(한글)과
바로 대조할 수 있다(`dto/ProductDetailDto`).

### `CandidateStatus`는 `BaseEntity.status`(RecordStatus)와 별개다
**파일:** `domain/sourcing/enums/CandidateStatus`
그쪽은 논리삭제용, 이쪽은 발굴 → 검수 → 등록 진행 단계.

### 설정을 DB 단일 행에 두는 이유
**파일:** `domain/sourcing/SourcingConfig`
추천 개수·크롤 범위·스코어 가중치는 운영하면서 계속 조정하게 되는 값이라 **재배포 없이 바꾸려고**
코드가 아니라 DB에 둔다.

---

## 7. 부분 실패 표면화 계약 (조용한 누락 금지)

동일한 규율이 여러 곳에 있다 — "성공 목록만 돌려주고 실패는 드롭"을 금지하는 계약이다.

| 파일 | 무엇을 잃었었나 |
|---|---|
| `dto/BulkProductCreateResult` (F-PSRC-6) | `List<Product>`만 반환해 항목별 생성 실패가 응답에서 누락 → 요청↔결과 매핑 불가. 성공은 요청 index를 보존하고 실패는 index+식별자+사유로 남긴다 |
| `domain/product/client/dto/ImageProcessResult` (F-PROD-16/D-092) | `downloadAndConvert`가 성공 파일만 반환하고 실패 URL을 조용히 드롭 → "성공 N장 / 실패 M장"을 못 실었다 |
| `application/sourcing/dto/SourcingCrawlResult` (F-PSRC-2) | `List<ScrapedProductDto>`만 반환해 어느 URL이 왜 실패했는지 알 수 없었다 |
| `dto/DiscoveryCrawlResult` · `dto/DiscoverySummary` | 페이지/카테고리 차단을 "후보가 적다"로 오인 |
| `SourcingCandidate#excludeReason` | 파이프라인 자동 제외는 **사유를 반드시 남긴다**(조용히 사라지지 않게) |
| `ProductSyncService` (F-MISC-8/9/10) | 컨트롤러의 원시 `new Thread`가 스레드를 고갈시키고 크롤 예외가 조용히 죽었다 → 관리되는 `syncTaskExecutor` + ActionLog(SUCCESS/FAILED) 기록 |

`ProductMarketSyncService`는 예외 체인의 **가장 안쪽 메시지**(래핑된 실 HTTP 오류)를 추출해 표면화한다 —
바깥 래퍼 메시지만 보이면 원인을 알 수 없다.

---

## 8. 기타 보존 값

- **`StockCheckResult`의 하위호환 생성자 2개**: 4-인자(sourceGone=false, shippingCost=null — iHerb 등
  정상 소스 경로), 5-인자(shippingCost=null). 제거하면 호출부가 깨진다.
- **`costPrice` vs `shippingCost`의 의미 차이**: `costPrice`는 묶음수량이 곱해지는 단가,
  `shippingCost`는 주문당 1회 가산되는 부대비용(F&M 배대지 배송비). 묶음수량과 무관.
- **`MarketPublishOutcome.synced`**: 마켓플러스 전송처럼 "접수만 된" 경우 false다.
- **`BatchStartedEvent`(D-089)**: 배치를 개시하지 않은 다른 브라우저도 batchId를 받아 진행바를 공유하기 위함.
- **`StockCrawlerRouter`**: 매칭 없으면 기본(IHB)으로 폴백. 마켓 쪽 `MarketClientRouter`와 동일한 패턴.
  벤더 = IHB(iHerb 내부 API) / FTN(Fortnum&Mason Scrapling 서비스, 원가를 원화로 산출해 반환).
- **`DetailHtmlBuilder`의 `hostedImages` 파라미터는 현재 미사용** — 템플릿이 이미지 배치를 담당한다.
  향후 본문 중간 삽입형 레이아웃을 대비해 시그니처만 유지 중.
- **`GeneratedProductText.generatedBy` / `ProductTextService`의 source**: 어떤 모델이 만들었는지를
  검수 화면에 표시해 사용자가 신뢰 수준을 판단하게 한다. 규칙기반이면 `"rule-based"`.
- **`api/config/AsyncConfig`는 빈 껍데기**(빈 이름 `apiAsyncConfig` + 회귀 테스트 보존 목적).
  ※ 이 파일은 본 담당 범위 밖 — `salvage-backend.md` 담당자가 처리.
