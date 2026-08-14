# 상품 그리드 마켓 배지 클릭 등록 — 설계

- 날짜: 2026-08-13
- 대상: 상품 관리 그리드(마켓 컬럼), 마켓 등록 경로, Cafe24 마켓플러스 자동화
- 상태: 승인됨(사용자 확인 2026-08-13) · **§5.2·§5.3·§6·§8은 2026-08-14 실측 결과로 개정됨**
  (Phase 0 스파이크에서 자동 전송이 성립하지 않는다고 확인되어 딥링크 핸드오프로 설계 변경 —
  실측 근거는 `docs/normalize/working_history/20260813_marketplus_스파이크.md` 참조)

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

### 5.2 G마켓 · 옥션 — 딥링크 핸드오프 (2026-08-14 개정)

**Phase 0 스파이크(`docs/normalize/working_history/20260813_marketplus_스파이크.md`) 실측 결과,
당초 계획한 Playwright 자동 전송은 만들지 않기로 했다.** 이유:

- 마켓플러스 전송 팝업(`/mp/product/front/registerall`)은 상품마다 **마켓 카테고리 4단계를
  사람이 골라야** 한다(템플릿은 계정 단위 자동 선택이지만 카테고리는 매번 미선택 상태).
- 그 팝업에 **reCAPTCHA**(`recaptcha/api.js`, `#grecaptcha_v2_dialog`)가 로드된다.
- 헤드리스 사이드카가 연 팝업(`context.expect_page()`로 받는 새 창)은 사람이 이어받아
  카테고리를 고르고 캡차를 풀 수 없다 — 자동화와 사람 개입이 같은 세션에서 만날 수 없는 구조다.

대신 서버는 **어디서 무엇을 찾으면 되는지**까지만 책임지고, 나머지는 사람이 자기 브라우저로 한다.

1. 프론트가 배지를 클릭하면 `GET /api/v1/products/{id}/markets/{marketType}/handoff`
   (`MarketPlusHandoffService.resolve`, `ProductController`)를 호출한다.
2. 서버는 해당 상품의 CAFE24 등록행 존재를 확인한다. 없으면 `IllegalStateException` →
   `GlobalExceptionHandler`가 400으로 변환해 "카페24 등록이 먼저 필요합니다"로 거부한다
   (프론트는 애초에 배지를 비활성화하지만, 서버도 독립적으로 막는다). API 4마켓 publish 경로도
   `ProductPublishUseCase`가 GMARKET/AUCTION을 같은 방식으로 독립 거부한다(등록 경로가
   조용히 실패하지 않도록).
3. 서버는 Cafe24 등록행의 `product_code`(마켓플러스 검색에 쓰이는 Cafe24 자체 상품코드,
   sbCode와는 다르다)와 마켓플러스 URL, 안내 문구를 담은 `MarketPlusHandoff`를 반환한다.
   **서버는 이 시점에 아무것도 저장하지 않는다** — 사용자가 실제로 마켓플러스에서
   전송했는지, 심지어 그 화면을 열었는지조차 서버는 알 방법이 없다.
4. 프론트는 확인 다이얼로그에서 `product_code`를 클립보드에 복사하고 마켓플러스
   `noSaleAll`(일괄보내기 미판매 목록)을 새 탭으로 연다. 사람이 그 코드로 검색해 행을
   찾고, 대상 마켓을 체크하고, 카테고리를 고르고, 직접 전송 버튼을 누른다.
5. **미판매 목록 검색은 Cafe24 `product_code` 완전일치로만 매칭된다**(실측: `P000BGOU`
   완전일치 1건, 앞자리 부분일치 `P000BGO` 0건). **sbCode(자체상품코드)로는 검색되지
   않고 그 화면 어디에도 노출조차 되지 않는다** — 그래서 핸드오프 응답은 sbCode가 아니라
   Cafe24 `product_code`를 넘긴다.
6. 실제 전송 여부·성공 여부·마켓 상품번호는 서버가 즉시 알 수 없다(전송은 마켓플러스
   내부 큐로 처리되고 완료까지 지연이 있음, 스파이크 §5 참조). 기존 ESM 엑셀 백필
   경로가 `gmarket_goodsNo`/`auction_goodsNo`를 채우면 그때 배지가 링크 상태로 바뀐다.

### 5.3 자격증명

**쓰지 않는다.** 마켓플러스 로그인은 사람이 자기 브라우저·자기 세션으로 한다(딥링크 핸드오프는
로그인을 대행하지 않는다). 당초 계획했던 사이드카용 `CAFE24_MP_USERNAME`/`CAFE24_MP_PASSWORD`
환경변수, `.env.example`/`sync-env.sh` 반영, `503 credentials_missing` 응답은 전부 폐기됐다 —
스파이크 중 만들었던 사이드카 코드(`scraper/marketplus.py`, `/cafe24/mp/probe` 엔드포인트,
`docker-compose.yml`/`.env.example`/`sync-env.sh`의 자격증명 배선)는 딥링크 핸드오프에는
필요 없어 되돌렸다(커밋 `feat(product): G마켓·옥션은 마켓플러스 딥링크 핸드오프로 넘긴다`).

## 6. 실패 처리

| 실패 | 응답 | 배지 |
|---|---|---|
| 어댑터 없는 마켓 | 400 | 애초에 클릭 불가 |
| 마켓 API 등록 거부 | 502 + 마켓 사유 | 빨간 점선 + 사유 툴팁 |
| 카페24 선행조건 미충족(핸드오프 요청 시) | 400 (`IllegalStateException`) | 비활성(툴팁 안내) |
| 카페24 선행조건 미충족(API publish 직접 시도 시) | 400 (`IllegalStateException`, `ProductPublishUseCase`가 독립 거부) | 프론트는 이 경로를 안 타므로 해당 없음 |
| 카페24 상품코드 미확보(핸드오프 요청 시) | 400 (`IllegalStateException`) | 빨간 점선 + "카페24 재등록이 필요합니다" |

모든 등록 시도는 기존 `ActionLogService`(`PRODUCT_PUBLISH`)에 성공/실패가 기록된다(이미 구현됨).
핸드오프는 아무것도 저장하지 않으므로(§5.2) 실제 마켓플러스 전송 성공/실패는 서버가 알 수 없다 —
그 실패는 사람이 마켓플러스 화면에서 직접 마주한다(스파이크 §C: G마켓 계정 상품 수 초과 등).

## 7. 테스트

- **Core**: 마켓별 산정가가 `MarketPublishContext.salePrice`에 실리는지 / 동기화·등록이 같은 resolver를 쓰는지 /
  Cafe24 등록행 없이 GMARKET publish 시 거부하는지 / 핸드오프가 CAFE24 등록행·`product_code` 없이는
  거부하고 있으면 그 코드를 그대로 반환하는지(`MarketPlusHandoffServiceTest`, 구현됨).
- **API**: `marketRegistrations` 응답 계약(객체 승격, CAFE24 키 포함) / publish 응답 DTO 계약 /
  `GET .../markets/{marketType}/handoff` 응답 계약.
- **프론트**: `npx tsc -p tsconfig.app.json` 통과.

## 8. 단계

- **Phase 0 — 스파이크(마켓플러스 실측).** 로그인 흐름, `noSaleAll` 목록의 검색 키(sbCode가 자체상품코드로
  검색되는지), 일괄 보내기 다이얼로그가 G마켓/옥션 개별 선택을 지원하는지, 전송 후 상품번호 노출 여부.
  **완료(2026-08-13) — 결과는 자동 전송 폐기(§5.2 참조).**
- **Phase 1 — 배지 UI + API 4마켓 클릭 등록.** 6슬롯 배지, 응답 계약 확장, publish 응답 DTO.
  이 단계만으로 쿠팡·N스토어·11번가·카페24가 동작한다. **완료.**
- **Phase 2 — 마켓별 판매가 산정을 publish 경로에 연결.** **완료.**
- **Phase 3 — G마켓·옥션 딥링크 핸드오프 배지 활성화.** 당초 "마켓플러스 사이드카"였던 계획을
  Phase 0 결과에 따라 딥링크 핸드오프로 대체해 완료. **완료.**

Phase 1·2는 Phase 0과 무관하게 진행할 수 있었다. Phase 3만 스파이크 결과에 의존했고,
그 의존이 자동 전송에서 딥링크 핸드오프로 설계를 바꾸는 근거가 됐다.
