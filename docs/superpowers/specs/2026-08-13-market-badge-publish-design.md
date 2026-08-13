# 상품 그리드 마켓 배지 클릭 등록 — 설계

- 날짜: 2026-08-13
- 대상: 상품 관리 그리드(마켓 컬럼), 마켓 등록 경로, Cafe24 마켓플러스 자동화
- 상태: 승인됨(사용자 확인 2026-08-13)

## 1. 문제

상품 그리드의 마켓 컬럼은 **등록된 마켓만** 배지로 보여준다(`frontend/src/pages/product/productGridShared.tsx:48`).
그래서 `231211FM017`처럼 N스토어에만 올라간 상품을 보면 "나머지 마켓에 없다"는 사실은 알 수 있어도,
**그 자리에서 등록할 방법이 없다.** 등록하려면 별도 화면·별도 절차로 빠져야 한다.

또한 등록 경로 자체에 두 개의 구멍이 있다.

1. **G마켓·옥션에는 등록 경로가 아예 없다.** 마켓 어댑터는 쿠팡·N스토어·11번가·Cafe24 4종뿐이고,
   지금 그리드의 G마켓/옥션 배지는 Cafe24 등록행에 ESM 엑셀로 백필한 `gmarket_goodsNo`/`auction_goodsNo`에서
   링크만 파생한 것이다(`ProductController:439-450`).
2. **신규 등록 판매가가 마켓별로 산정되지 않는다.** 동기화 경로(`ProductMarketSyncService:75`)는
   `MarginCalculator` + `MarketFeeService`로 마켓별 실수수료 반영가를 계산하는데,
   `ProductPublishUseCase`는 `product.getSalePrice()` 원시값(쿠팡 기준가)을 그대로 올린다.
   등록 직후부터 다음 재가격 배치까지 그 마켓은 틀린 가격으로 팔린다.

## 2. 목표

그리드의 마켓 컬럼에서 **미등록 마켓을 눈으로 식별하고, 그 자리를 클릭해 등록**할 수 있게 한다.
등록 결과(상품번호·링크)는 자동으로 매칭되어 배지에 반영된다.

비목표(이번 범위 밖):
- 다중 상품 선택 후 마켓 일괄 등록(단건 배지 클릭만 다룬다)
- 등록 전 카테고리·키워드 검수 UI(어댑터의 기존 자동 컨텍스트를 그대로 쓴다)
- 등록 취소·마켓 상품 삭제

## 3. 배지 셀 설계

6개 마켓을 **항상 고정 슬롯으로** 렌더링한다. 순서:

```
쿠팡 · N스토어 · 카페24 · G마켓 · 옥션 · 11번가
```

카페24가 G마켓·옥션의 선행조건이므로 그 앞에 둔다. 컬럼 폭은 270 → 340으로 늘려 6배지 nowrap을 유지한다.

| 상태 | 표현 | 클릭 동작 |
|---|---|---|
| 등록 + 링크 확보 | 채색 배지(현행 파스텔 팔레트) | 마켓 상품페이지 새 탭 |
| 등록 + 링크 미확보 | 채색 테두리 + 반투명(현행) | 없음 |
| 미등록 | 점선 테두리 · 흰 배경 · 회색 글씨 | 확인 다이얼로그 → 등록 |
| 미등록 + 선행조건 미충족 | 점선 · 더 옅음 · `cursor: not-allowed` | 툴팁 "카페24 등록 후 가능" |
| 등록 진행중 | `등록중…` 텍스트 + 펄스 | 없음(중복 클릭 차단) |
| 등록 실패 | 빨간 점선 | 툴팁에 실패 사유, 재클릭 시 재시도 |

확인 다이얼로그 문구: **"'쿠팡'에 해당 상품을 등록하시겠습니까?"** (마켓명만 치환).

진행 상태는 그리드 컴포넌트의 로컬 상태다. 페이지를 떠나면 유실되고, 새로고침 시 서버 상태로 복원된다.

## 4. 응답 계약 확장

현재 `marketRegistrations`는 `{ 마켓: URL문자열 }`이라 "미등록"과 "등록됐지만 링크 없음"만 구분한다.
PENDING·FAILED를 표현할 수 없으므로 값을 객체로 승격한다.

```jsonc
"marketRegistrations": {
  "COUPANG":     { "status": "SYNCED",  "url": "https://..." },
  "SMART_STORE": { "status": "PENDING", "url": null },
  "CAFE24":      { "status": "SYNCED",  "url": null }
}
```

- `status`는 `MarketRegistration`의 기존 동기화 상태에서 파생한다(SYNCED/PENDING/FAILED).
- 소비처는 `renderMarketBadges`와 그리드 마켓필터(`ProductGrid.tsx:38-42`) 두 곳뿐이라 파급이 작다.
- `buildMarketMap`에 **CAFE24 키를 추가**한다. 지금은 Cafe24 등록행을 G마켓/옥션 링크 파생용으로만 읽고
  정작 CAFE24 자신은 맵에 넣지 않아, 프론트가 카페24 등록 여부를 알 수 없다.
- G마켓/옥션의 `status`는 Cafe24 등록행의 식별자로 판정한다:
  `gmarket_goodsNo` 있으면 SYNCED, 전송 마커만 있으면 PENDING, 둘 다 없으면 미등록.

등록 엔드포인트 `POST /api/v1/products/{id}/markets/{marketType}`(`ProductSourcingController:137`)의 응답을
`Void` → 등록 결과 DTO(`status`, `url`, `identifiers`)로 바꾼다. 그래야 프론트가 목록 재조회 없이
배지를 즉시 링크 상태로 전환한다.

## 5. 등록 실행 경로

### 5.1 API 4마켓 (쿠팡 · N스토어 · 11번가 · 카페24)

기존 `ProductPublishUseCase`를 그대로 쓴다(PENDING 선저장 → 외부 publish → identifiers+SYNCED 갱신).
여기에 **마켓별 판매가 산정을 주입**한다:

- `ProductMarketSyncService.priceForMarket`의 계산(`MarginCalculator` + `MarketFeeService`)을
  독립 컴포넌트 `MarketSalePriceResolver`로 추출한다.
- 동기화 경로와 등록 경로가 **같은 컴포넌트**를 호출한다(계산식 중복 금지).
- 산정된 가격을 `MarketPublishContext.salePrice`에 실어 `client.publish(product, context)`로 넘긴다.
  네 어댑터 모두 이미 컨텍스트 판매가를 원시값보다 우선하도록 짜여 있어 **어댑터 수정은 불필요하다.**

### 5.2 G마켓 · 옥션 (Cafe24 마켓플러스 경유)

`ProductPublishUseCase`에서 GMARKET/AUCTION을 분기해 새 `MarketPlusPublisher`로 위임한다.

1. 해당 상품의 CAFE24 등록행 존재를 확인한다. 없으면 409 + "카페24 등록이 필요합니다"로 거부한다
   (프론트는 애초에 배지를 비활성화하지만, 서버도 독립적으로 막는다).
2. 사이드카 `POST /cafe24/mp/send` 호출: `{ sbCode, targetMarket: "GMARKET"|"AUCTION" }`.
3. 사이드카(Playwright, 헤드리스)가 수행:
   - `mp.cafe24.com` 로그인
   - `https://mp.cafe24.com/mp/product/front/noSaleAll` 진입
   - `sbCode`(Cafe24 `custom_product_code`)로 검색 → 해당 행 체크
   - 일괄 보내기 다이얼로그에서 대상 마켓 지정 → 전송
   - 결과(성공/실패, 가능하면 마켓 상품번호)를 반환
4. 성공 시 Cafe24 등록행 `market_identifiers`에 전송 마커(`gmarket_sentAt` / `auction_sentAt`)를 남긴다.

**상품번호 매칭에 관한 정직한 한계:** 마켓플러스 "보내기" 직후 ESM 상품번호가 즉시 나오지 않을 가능성이 크다
(ESM 반영 지연). 따라서 전송 성공은 "접수됨"으로 취급하고 배지는 PENDING(채색 테두리·링크 없음)으로 두며,
실제 `gmarket_goodsNo`/`auction_goodsNo`는 기존 ESM 엑셀 백필 경로가 채운다.
Phase 0 스파이크에서 전송 화면이 상품번호를 즉시 노출한다는 사실이 확인되면, 그 값을 바로 저장하도록 바꾼다.

### 5.3 자격증명

마켓플러스 계정은 사이드카에만 필요하다. 공간을 미리 만들어 둔다:

- `.env.example`: `CAFE24_MP_USERNAME=`, `CAFE24_MP_PASSWORD=`
- `docker-compose.yml`의 `sbshop-scraper` 서비스에 `environment` 블록 신설 후 두 키 전달
- `sync-env.sh`의 `SYNC_KEYS`에 두 키 추가
- 값이 비어 있으면 `/cafe24/mp/send`는 `503 credentials_missing`을 반환하고,
  API는 이를 "마켓플러스 계정 미설정"으로 사용자에게 표면화한다(조용한 실패 금지).

## 6. 실패 처리

| 실패 | 응답 | 배지 |
|---|---|---|
| 어댑터 없는 마켓 | 400 | 애초에 클릭 불가 |
| 마켓 API 등록 거부 | 502 + 마켓 사유 | 빨간 점선 + 사유 툴팁 |
| 카페24 선행조건 미충족 | 409 | 비활성(툴팁 안내) |
| 마켓플러스 로그인 실패 | 502 `mp_login_failed` | 빨간 점선 |
| 마켓플러스에서 상품 미발견 | 404 `mp_product_not_found` | 빨간 점선 |
| 자격증명 미설정 | 503 `credentials_missing` | 빨간 점선 + "계정 미설정" |

모든 등록 시도는 기존 `ActionLogService`(`PRODUCT_PUBLISH`)에 성공/실패가 기록된다(이미 구현됨).
사이드카 실패 시에는 스크린샷을 컨테이너 내부에 남겨 DOM 변경 진단을 가능하게 한다.

## 7. 테스트

- **Core**: 마켓별 산정가가 `MarketPublishContext.salePrice`에 실리는지 / 동기화·등록이 같은 resolver를 쓰는지 /
  Cafe24 등록행 없이 GMARKET publish 시 거부하는지 / 사이드카 실패가 조용히 성공으로 처리되지 않는지.
- **API**: `marketRegistrations` 응답 계약(객체 승격, CAFE24 키 포함) / publish 응답 DTO 계약.
- **사이드카**: DOM 셀렉터는 자동 검증 불가 — 셀렉터를 상수로 모으고 실패 시 스크린샷을 남긴다.
- **프론트**: `npx tsc -p tsconfig.app.json` 통과.

## 8. 단계

- **Phase 0 — 스파이크(마켓플러스 실측).** 로그인 흐름, `noSaleAll` 목록의 검색 키(sbCode가 자체상품코드로
  검색되는지), 일괄 보내기 다이얼로그가 G마켓/옥션 개별 선택을 지원하는지, 전송 후 상품번호 노출 여부.
  **마켓플러스 자격증명이 있어야 진행 가능.**
- **Phase 1 — 배지 UI + API 4마켓 클릭 등록.** 6슬롯 배지, 응답 계약 확장, publish 응답 DTO.
  이 단계만으로 쿠팡·N스토어·11번가·카페24가 동작한다.
- **Phase 2 — 마켓별 판매가 산정을 publish 경로에 연결.**
- **Phase 3 — 마켓플러스 사이드카 + G마켓·옥션 배지 활성화.**

Phase 1·2는 Phase 0과 무관하게 진행할 수 있다. Phase 3만 스파이크 결과에 의존한다.
