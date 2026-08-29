# 딥다이브 — 상품 생명주기: 등록부터 마켓 삭제·재등록까지

작성 2026-08-29. 목적은 **코드를 읽지 않고도 흐름을 이해**하고, 그 과정에서 드러난 모순을 고치기 위한 근거를 남기는 것이다.

---

## 1. 전체 지도

```
 ① 상품 생성          ProductCreateUseCase        → sb_product 1행
        │
 ② 마켓 게시          ProductPublishUseCase       → sb_market_registration 마켓별 1행
        │                                            (savePending → publish → markPublished)
        ├─ ③ 가격·재고 동기화   ProductMarketSyncService    (반복)
        ├─ ④ 재게시(이미지/HTML) ProductManageUseCase        (반복)
        ├─ ⑤ 바코드 전송        ProductBarcodeSyncUseCase   (반복)
        └─ ⑥ 고시정보 보정      MarketNoticeRepairUseCase   (반복)
        │
 ⑦ 마켓에서 삭제됨    (감지 경로 없음 — 3장 참조)
        │
 ⑧ 재등록             ProductPublishUseCase 재호출 (가드 없음 — 4장 참조)
```

핵심 테이블 둘.

**`sb_product`** — 상품의 원본. 33컬럼.
`sb_code`(우리 품번, unique) · `product_name` · `barcode` · `capacity`/`measure_unit` · `cost_price`/`margin_rate`/`sale_price` · `stock`/`stock_status` · `bundle_quantity` · `hs_code` · `hosted_images` · `detail_html` 등.

**`sb_market_registration`** — 마켓별 등록 상태. `(product_id, market_type)` unique.
```
product_id            우리 상품
market_type           COUPANG / SMART_STORE / ELEVEN_STREET / CAFE24 / GMARKET / AUCTION
market_identifiers    마켓이 준 식별자 JSON  ← 마켓별로 키가 다르다
market_detailed_info  마켓 원본 응답 캐시(rawData)
is_synced             boolean
last_synced_at        마지막 성공 시각
```

`market_identifiers` 예(쿠팡):
```json
{"externalVendorSku":"200907WA006","sellerProductId":"11583638672",
 "vendorItemId":"73310987596","productId":"6754963940","barcode":""}
```

---

## 2. 등록 흐름 상세

### ② 마켓 게시 — `ProductPublishUseCase.publishToMarket`

```
1. productReader.findById(productId)                     상품 로드
2. G마켓·AUCTION 이면 즉시 예외                          "마켓플러스에서 전송해야 합니다"
3. marketClientRouter.hasClient(marketType) 확인
4. productSanitizer.sanitizeForPublish(product)          금칙어·형식 정리
5. productValidator.validateForPublish(product)          필수값 검증
6. registrationTxService.savePending(...)                등록행 확보 (REQUIRES_NEW)
7. marketSalePriceResolver.resolveForProduct(...)        마켓별 판매가 산정
8. client.publish(product, context)                      ★ 마켓 API 호출 → 식별자 수신
9. registrationTxService.markPublished(reg, json)        식별자 저장 + is_synced=true
```

**6번 `savePending`이 중요하다.**
```java
findByProductIdAndMarketType(productId, marketType)
    .orElseGet(() -> insertPending(...));   // 없을 때만 새로 만든다
```
**기존 행이 있으면 그대로 재사용한다.** 재등록이 별도 경로가 아니라 이 한 흐름에 얹혀 있다는 뜻이다.

**8→9 사이가 취약하다.** 마켓 게시는 성공했는데 DB 갱신이 실패하면 로그만 남기고 예외를 던진다.
```
[게시-복구필요] 마켓 게시는 성공했으나 등록행 갱신 실패 — PENDING 행 존재, 수동/재시도 복구 필요
```
마켓에는 상품이 생겼는데 우리는 식별자를 모르는 상태다. 자동 복구가 없다.

---

## 3. `is_synced` 의 의미가 흔들린다 ← 이번 조사의 핵심

플래그를 건드리는 곳은 두 군데뿐이다.

| 위치 | 동작 |
|---|---|
| `ProductMarketSyncService:96` | 가격·재고 동기화 성공 → `markSynced()` |
| `ProductMarketSyncService:101` | 가격·재고 동기화 **실패 → `markSyncFailed()`** |
| `MarketRegistrationTxService:28` | 게시 성공 → `markSynced()` |
| `ProductManageUseCase:176` | 재게시 성공 → `markSynced()` |
| `ProductBarcodeSyncUseCase:96` | 바코드 전송 성공 → `markSynced()` |

`markSyncFailed()`는 **가격·재고 동기화 실패 한 곳에서만** 호출된다. 그리고 그 catch 는 **모든 예외를 같게 취급한다** — 네트워크 순단, 429 요청한도, 검증 400, "이미 삭제된 상품" 이 전부 같은 결과를 만든다.

그래서 `is_synced=false` 는 실제로 이런 것들의 합집합이다.

| 실제 상황 | 필요한 조치 | 현재 표현 |
|---|---|---|
| 마켓에서 **삭제됨** | **재등록** | `false` |
| 검증 실패로 미반영 (소비기한 결손 등) | 데이터 고치고 재시도 | `false` |
| 일시 오류(429·순단) | 그냥 재시도 | `false` |
| 한 번도 성공 못 함 | 최초 등록 | `false` |
| 게시 직후 PENDING 상태 | 대기 | `false` |

**즉 `is_synced` 는 "마켓에 존재하는가"가 아니라 "마지막 작업이 성공했는가"다.** 이름과 실제 의미가 어긋나 있고, 이 하나의 어긋남에서 아래 결함 대부분이 파생된다.

### 실증

상품 44(`200907WA006`) 쿠팡 등록행:
```
is_synced      = false
last_synced_at = 2026-03-01
sellerProductId= 11583638672
```
바코드 PUT 시 쿠팡 응답: `code=ERROR, "해당 상품은 이미 삭제된 상품입니다"`
→ 데이터는 삭제 사실을 알 수 있는 상태였지만, **`false` 라는 값만으로는 삭제인지 실패인지 구분 불가**.

---

## 4. 재등록 흐름 — 전용 경로가 없다

**재등록은 최초 등록과 같은 함수를 다시 부르는 것이다.** 진입점은 `POST /api/v1/products/{id}/markets/{market}` (상품 그리드의 마켓 배지 클릭).

문제는 **중복 등록 가드가 하나도 없다는 것**이다. `publishToMarket` 어디에도 `isSynced` 검사나 "이미 마켓에 있는가" 확인이 없다.

```
상품이 마켓에 살아있음 (sellerProductId=A, is_synced=true)
    ↓ 사용자가 배지를 다시 클릭
savePending → 기존 행 재사용
client.publish(...) → 마켓에 **새 상품 생성** (sellerProductId=B)
markPublished → market_identifiers 를 B 로 **덮어씀**
    ↓
마켓에는 A 와 B 두 개가 존재. 우리는 B 만 안다.
**A 는 추적 불가능한 유령 리스팅이 된다.**
```

이건 [[D-215]] 임시저장 1,029건, [[D-210]] "쿠팡 우리에만 101건" 과 같은 계열의 원인일 가능성이 있다.

---

## 5. 삭제 감지 — 능동 경로가 없다

| 경로 | 동작 |
|---|---|
| `ProductDeleteTxService.deleteWithRegistrations` | **우리가** 삭제할 때. 등록행을 `deleteAll` 로 완전 제거 |
| `MarketCatalogReconciliationService` (D-210 리포트) | 마켓 카탈로그와 우리 DB를 대조해 **"우리에만 있음"을 세지만 DB 에 쓰지 않는다** — 읽기 전용 |
| 각종 sync 실패 | `is_synced=false` 로만 남는다. 사유 없음 |

**마켓이 상품을 삭제했을 때 그것을 우리 DB 에 반영하는 코드가 없다.** 리포트는 그 사실을 알고 있는데 기록되지 않으므로, 매번 다시 조회해야 알 수 있다.

---

## 6. 화면이 상태를 구분해 보여주지 못한다

```java
// MarketBadgeState.of
return new MarketBadgeState(synced ? "SYNCED" : "PENDING", url);
```
```typescript
// badgeVisual (productGridShared.tsx)
if (state) {
    if (state.status === 'PENDING') return 'pending';
    if (state.url) return 'registered';
    return 'linkless';
}
return 'missing';   // 등록행 자체가 없을 때만
```

백엔드는 구분해 내려보내고 프론트도 분기는 한다. 그런데 **`pending` 의 시각 스타일이 `registered` 와 거의 같아 눈으로 갈리지 않는다.** 상품 그리드에서 삭제된 쿠팡 상품의 배지가 정상처럼 켜져 보이는 이유다(등록행이 없는 G마켓만 흐리게 대비된다).

시각을 고쳐도 절반만 해결된다 — **3장의 사유 부재 때문에 "삭제됨"과 "등록 중"을 가를 근거가 없다.**

---

## 7. 발견된 결함 (원장 연계)

### D-222 — 배지가 "삭제됨"과 "등록 대기"를 구분 못 함 (등재 완료)
6장 참조. 근본 원인은 3장의 사유 부재.

### 신규 ① — 재등록 시 중복 리스팅 생성 (가드 부재)
4장 참조. **심각도 높음.** 마켓에 유령 리스팅을 만들고 기존 식별자를 잃는다.
- 수정 방향: `publishToMarket` 진입 시 **기존 등록행의 식별자로 마켓 단건 조회**를 먼저 한다. 살아 있으면 게시 대신 **재게시(update) 경로**로 보내거나 명시적으로 거부한다. 쿠팡은 `GET seller-products/{id}`, 스토어는 `GET origin-products/{no}` 로 확인 가능하며 이미 구현되어 있다.
- 최소 조치: `is_synced=true` 이면서 식별자가 있으면 **기본 거부**하고, 강제 재등록은 별도 플래그로만 허용한다.

### 신규 ② — 게시 성공 후 DB 갱신 실패의 자동 복구 부재
2장 참조. 마켓에는 있고 우리는 모르는 상태가 로그로만 남는다.
- 수정 방향: 식별자를 먼저 별도 테이블/컬럼에 적재한 뒤 등록행을 갱신하거나, 기동 시 PENDING 행을 마켓 조회로 화해시키는 복구 배치를 둔다.

### 신규 ③ — 실패 사유가 유실된다
3장 참조. `markSyncFailed()` 는 예외 종류를 구분하지 않는다.
- 수정 방향: **D-222 의 사유 컬럼과 같은 작업이다.** `unsync_reason`(`DELETED_ON_MARKET` / `VALIDATION_FAILED` / `TRANSIENT_ERROR` / `NEVER_SYNCED`)을 추가하고, catch 에서 마켓 응답 문구로 분류해 기록한다. 쿠팡 `"이미 삭제된 상품입니다"` 는 확정 신호로 쓸 수 있다.

### 신규 ④ — 죽은 등록에도 전송을 시도한다
`ProductBarcodeSyncUseCase` 는 `findByProductId` 로 **모든 등록행**을 꺼내 `is_synced` 를 보지 않고 전송한다. 대상 선정 SQL 은 "상품에 동기화된 마켓이 하나라도 있으면" 통과시키므로, 스토어만 살아있는 상품의 **죽은 쿠팡 등록까지** 건드린다.
- 실측: 바코드 확대 670건 표본에서 쿠팡 실패 64건 중 상당수가 이 경우.
- 수정 방향: 전송 루프에서 `is_synced=false` 는 `SKIPPED("미동기 등록")` 로 건너뛴다. 같은 패턴이 `ProductMarketSyncService`·`ProductManageUseCase` 에도 있는지 함께 점검한다.

### 신규 ⑤ — 카탈로그 대조 결과가 휘발한다
5장 참조. D-210 리포트가 "우리에만 101건" 을 세지만 어디에도 남기지 않는다.
- 수정 방향: 리포트가 `DELETED_ON_MARKET` 사유를 기록하도록 쓰기 경로를 붙인다(신규 ③ 의 컬럼 재사용). 읽기 전용 원칙을 깨는 것이므로 **명시 옵션(`persist=true`)으로만** 동작하게 한다.

---

## 8. 권고 — 무엇부터 고칠 것인가

의존 관계가 있어 순서가 중요하다.

**1단계. 사유 컬럼 신설** (신규 ③ = D-222 의 선행)
`sb_market_registration.unsync_reason` 을 추가한다. 이것 없이는 4·6장의 어떤 개선도 근거가 없다. 스키마 변경이므로 수동 DDL 선행.

**2단계. 죽은 등록 건너뛰기** (신규 ④)
가장 싸고 즉효다. 코드 한 곳, 회귀 위험 낮음. 불필요한 마켓 호출과 거짓 실패가 사라진다.

**3단계. 중복 등록 가드** (신규 ①)
**실질 피해가 가장 큰 항목.** 유령 리스팅은 되돌리기 어렵다. 1단계 없이도 착수 가능.

**4단계. 배지 시각 분리** (D-222)
1단계가 끝나야 의미가 산다.

**5단계. 대조 결과 영속화** (신규 ⑤) / **게시 복구 배치** (신규 ②)
앞 단계가 자리 잡은 뒤.

---

## 9. 조사에서 확인한 사실 (근거)

- `savePending` 은 기존 행 재사용 — `MarketRegistrationTxService:20-23`
- 중복 등록 가드 부재 — `ProductPublishUseCase` 전체에 `isSynced` 참조 0건
- `markSyncFailed` 호출처는 단 1곳 — `ProductMarketSyncService:101`
- 카탈로그 대조는 읽기 전용 — `MarketCatalogReconciliationService` 에 `save(` 0건
- 우리 삭제는 등록행 완전 제거 — `ProductDeleteTxService:23` `deleteAll`
- 배지 스타일 — `productGridShared.tsx:badgeVisual`, `MarketBadgeState.of`
- 실증 데이터 — 상품 44 쿠팡행 `is_synced=false`, `sellerProductId=11583638672`, 쿠팡 응답 "이미 삭제된 상품입니다"

**식별자는 지우지 않는다.** 과거 주문 추적([[D-218]] 오배송 사고), [[D-210]] 정합성 대조, 재등록 이력의 근거다. 지우면 문제가 해결되는 게 아니라 보이지 않게 된다.
