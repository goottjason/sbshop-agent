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

### 2.1 코드가 실제로 하는 일 — `ProductPublishUseCase.publishToMarket`

```
1. productReader.findById(productId)              상품을 DB 에서 꺼낸다
2. G마켓·AUCTION 이면 즉시 예외                   "마켓플러스에서 전송해야 합니다"
3. marketClientRouter.hasClient(marketType)       이 마켓을 다룰 코드가 있나
4. productSanitizer.sanitizeForPublish(product)   위험 문자 제거
5. productValidator.validateForPublish(product)   필수값 확인
6. registrationTxService.savePending(...)         등록행 확보 (REQUIRES_NEW)
7. marketSalePriceResolver.resolveForProduct(...) 이 마켓의 판매가 계산
8. client.publish(product, context)               ★ 마켓 API 호출 → 식별자 수신
9. registrationTxService.markPublished(reg, json) 식별자 저장 + is_synced=true
```

**2번 — G마켓·옥션은 왜 예외인가**
ESM(G마켓·옥션)은 상품 등록 API 를 우리에게 열어주지 않는다. 카페24 마켓플러스를 경유해야만 올릴 수 있어서, 코드가 아예 시도조차 못 하게 막아둔 것이다. 배지를 눌러도 마켓플러스 화면으로 넘겨주는 방식(딥링크 핸드오프)으로 처리한다.

**4번 — 정제(sanitize) 가 하는 일**
```java
product.getProductName().replaceAll("[<>\"'&]", "").trim()
product.getBrand().replaceAll("[<>\"'&]", "").trim()
```
상품명·브랜드에서 **`< > " ' &` 다섯 글자를 지운다.** 이 문자들이 XML·JSON 문법 기호와 겹쳐서, 그대로 보내면 요청 자체가 깨지기 때문이다. 11번가는 XML 을 쓰는데 상품명에 `<` 가 있으면 태그로 오해한다.

정제는 **조용히 값을 바꾼다.** 사용자가 입력한 이름이 마켓엔 다르게 올라갈 수 있는데, 무엇이 지워졌는지 남기지 않는다.

**5번 — 검증(validate) 이 보는 7가지**

| 항목 | 없으면 |
|---|---|
| `sb_code` | 나중에 어느 상품인지 추적 불가 |
| `product_name` | 마켓 등록 자체가 안 됨 |
| `brand` | 대부분 마켓이 필수 요구 |
| `sale_price > 0` | 0원·음수는 마켓이 거부 |
| `hosted_images` 1장 이상 | 대표 이미지 없이 등록 불가 |
| `detail_html` | 상세페이지 내용 없음 |
| `vendor`(소싱처) | 발주·정산 근거 없음 |

여러 개가 없으면 **한꺼번에 모아서** 알려준다.
```
상품 검증 실패: 상품명 없음, 판매가 없음, 대표 이미지 없음
```
이 검사가 마켓 호출 **앞**에 있는 게 중요하다 — 어차피 거부될 요청으로 쿠팡·네이버 요청 한도를 쓰면 정작 필요한 작업이 `429` 로 막힌다.

**7번 — 판매가는 마켓마다 다시 계산된다**
`sb_product.sale_price` 는 **기준가일 뿐**이다. 마켓마다 수수료율이 달라서 `MarketSalePriceResolver` 가 각 마켓의 실수수료·쿠폰율·최소마진을 반영해 다시 산정한다. 같은 상품이 쿠팡과 스토어에서 다른 가격일 수 있는 이유다.

### 2.2 `savePending` 이 기존 행을 재사용한다

```java
findByProductIdAndMarketType(productId, marketType)
    .orElseGet(() -> insertPending(...));   // 없을 때만 새로 만든다
```

**재등록이 별도 기능이 아니라 이 한 흐름에 얹혀 있다는 뜻이다.** 등록행은 `(product_id, market_type)` 조합으로 유일하니, 같은 마켓에 두 번 등록해도 행은 하나다. 문제는 **행은 하나인데 마켓엔 두 개가 생길 수 있다**는 것이다(4장).

### 2.3 8번과 9번 사이가 취약하다

마켓 게시는 성공했는데 DB 갱신이 실패하면 이렇게 된다.

```
[게시-복구필요] 마켓 게시는 성공했으나 등록행 갱신 실패 —
               PENDING 행 존재, 수동/재시도 복구 필요
```

**마켓에는 상품이 만들어졌는데 우리는 그 번호를 모른다.** 로그에는 식별자가 찍히지만 DB 에는 없으니, 사람이 로그를 뒤져 수동으로 복구해야 한다. 자동 복구 경로가 없다.

이 구간에서 "실패했겠지" 하고 재시도하면 **마켓에 상품이 하나 더 생긴다.**

## 3. `is_synced` 의 의미가 흔들린다 ← 이번 조사의 핵심

### 3.1 이 플래그를 누가 건드리나

전체 코드에서 다섯 곳뿐이다.

| 위치 | 언제 | 동작 |
|---|---|---|
| `MarketRegistrationTxService:28` | 최초 게시 성공 | `markSynced()` → `true` |
| `ProductMarketSyncService:96` | 가격·재고 동기화 성공 | `markSynced()` → `true` |
| `ProductManageUseCase:176` | 이미지·HTML 재게시 성공 | `markSynced()` → `true` |
| `ProductBarcodeSyncUseCase:96` | 바코드 전송 성공 | `markSynced()` → `true` |
| **`ProductMarketSyncService:101`** | **가격·재고 동기화 실패** | **`markSyncFailed()` → `false`** |

**`false` 로 만드는 곳이 단 한 군데다.** 그것도 가격·재고 동기화 실패 하나뿐이다.

### 3.2 그 한 군데가 모든 예외를 같게 취급한다

```java
} catch (Exception e) {
    reg.markSyncFailed();          // 무슨 예외든 똑같이 false
    marketRegistrationRepository.save(reg);
    failed.put(marketType, rootMessage(e));
    log.error("[가격재고동기화] 실패(부분 실패로 수집, 롤백하지 않음) ...");
}
```

`catch (Exception e)` — **모든 예외를 한 바구니에 담는다.** 그래서 아래가 전부 같은 `false` 가 된다.

- 네트워크가 잠깐 끊겼다
- 네이버가 `429 요청 한도 초과` 를 줬다
- 마켓이 `400 소비기한을 입력해주세요` 로 거부했다
- 쿠팡이 `이미 삭제된 상품입니다` 라고 했다
- 쿠팡이 `심사가 진행중입니다` 라고 했다

### 3.3 그래서 `false` 하나에 다섯 가지가 섞인다

| 실제 상황 | 필요한 조치 | 현재 표현 |
|---|---|---|
| 마켓에서 **삭제됨** | **재등록** (사람 판단) | `false` |
| 검증 실패로 미반영 | 데이터 고치고 재시도 | `false` |
| 일시 오류(429·순단) | 그냥 재시도 | `false` |
| 한 번도 성공 못 함 | 최초 등록 | `false` |
| 게시 직후 대기 상태 | 잠시 기다림 | `false` |

**대응이 전부 다른데 표현이 하나다.** 화면에서도, 코드에서도 이 다섯을 구분할 방법이 없다.

### 3.4 이름과 실제가 어긋나 있다

`is_synced` 라는 이름은 **"마켓에 반영되어 있는가"**로 읽힌다. 그런데 실제 동작은 **"마지막 작업이 성공했는가"**다.

이 차이가 실제로 문제를 만든다.

> 소비기한 결손 10건은 전부 `is_synced=false` 였다. **그런데 상품은 마켓에 멀쩡히 살아 있었다.** 과거에 PUT 이 400 으로 실패한 흔적이 남아 있었을 뿐이다. 우리 시스템만 "없다"고 생각하고 있었던 것이다.

반대 방향도 있다. 네트워크가 한 번 끊겨 `false` 가 되면, 그 뒤 아무도 안 건드리는 한 **영원히 `false`** 다. 마켓엔 잘 있는데 우리 화면엔 문제가 있는 것처럼 보인다.

### 3.5 실증 — 상품 44

```
sb_market_registration (product_id=44, market_type=COUPANG)
  is_synced       = false
  last_synced_at  = 2026-03-01
  market_identifiers = {"externalVendorSku":"200907WA006",
                        "sellerProductId":"11583638672",
                        "vendorItemId":"73310987596",
                        "productId":"6754963940","barcode":""}
```

바코드 전송을 시도하니 쿠팡이 이렇게 답했다.
```
code=ERROR, message="해당 상품은 이미 삭제된 상품입니다."
```

**데이터에는 삭제 사실을 알 근거가 있었다** — 2026-03-01 이후 다섯 달간 동기화가 없었고, 식별자도 남아 있다. 그런데 **`false` 라는 값만으로는 삭제인지 일시 실패인지 알 수 없다.** 매번 마켓에 물어봐야 안다.

## 4. 재등록 흐름 — 전용 경로가 없다

### 4.1 재등록은 최초 등록을 다시 부르는 것이다

진입점은 상품 그리드의 **마켓 배지 클릭**이고, 그게 `ProductPublishUseCase.publishToMarket` 을 호출한다. **최초 등록과 완전히 같은 함수다.**

문제는 그 함수에 **"이미 마켓에 있는지" 확인하는 코드가 한 줄도 없다**는 것이다. 파일 전체를 뒤져도 `isSynced` 참조가 **0건**이다.

### 4.2 살아있는 상품에 재등록을 걸면

```
[시작]
마켓: 상품 A 판매중 (sellerProductId = 11583638672)
우리: market_identifiers = {"sellerProductId": "11583638672"}
      is_synced = true

[사용자가 배지를 다시 클릭]

savePending    → 기존 등록행을 그대로 재사용한다 (새 행을 만들지 않는다)
client.publish → 마켓 API 에 "새 상품 만들어줘" 를 보낸다
                 → 마켓은 **완전히 새로운 상품 B** 를 만들고 번호를 준다
                 → sellerProductId = 22000000001 (예시)
markPublished  → market_identifiers 를 B 로 **덮어쓴다**

[결과]
마켓: 상품 A 와 B 가 **둘 다 판매중** (같은 상품이 두 번 노출)
우리: B 만 안다
```

### 4.3 왜 되돌리기 어려운가

**A 의 번호를 잃어버렸다.** `market_identifiers` 를 덮어썼기 때문에, A 를 삭제하려 해도 어떤 번호로 요청해야 하는지 모른다. 마켓 관리자 화면에서 사람이 직접 찾아 지워야 한다.

그사이 벌어지는 일들:
- 같은 상품이 마켓에 두 개 노출된다 (고객 혼란·검색 순위 분산)
- **A 로 주문이 들어오면 우리 시스템이 매칭하지 못한다** — 어느 상품인지 모른다
- 재고·가격은 B 만 관리되고, A 는 방치된다

### 4.4 기존 백로그와의 연결

이 경로가 아래 두 항목의 원인일 가능성이 있다.

- **D-215** — 쿠팡 임시저장 1,029건. "과거 승인분이 팔리는 위에 미제출 초안이 얹힌 상태"라고 기록돼 있는데, 재등록이 새 초안을 계속 만들었다면 정확히 이 모양이 된다
- **D-210** — 정합성 리포트의 "쿠팡 우리에만 101건". 우리 DB 에만 있고 마켓엔 없는 식별자들

확증하려면 마켓 카탈로그에서 **같은 `externalVendorSku`(우리 SB코드)를 가진 상품이 둘 이상인지** 세어보면 된다. 그게 곧 중복 리스팅의 수다.

---

## 5. 삭제 감지 — 능동 경로가 없다

### 5.1 세 가지 경로와 각각의 한계

| 경로 | 하는 일 | 한계 |
|---|---|---|
| `ProductDeleteTxService.deleteWithRegistrations` | **우리가** 지울 때. 등록행을 `deleteAll` 로 완전 제거하고 상품도 삭제 | 마켓이 지운 경우엔 호출되지 않는다. 그리고 하드 삭제라 이력이 사라진다 |
| `MarketCatalogReconciliationService` (D-210 리포트) | 마켓 카탈로그와 우리 DB 를 대조해 "우리에만 있음"을 센다 | **읽기 전용.** `save(` 호출이 0건이라 결과가 DB 에 남지 않는다 |
| 각종 sync 실패 | `is_synced=false` 로 기록 | 사유가 없어 삭제인지 실패인지 모른다 |

### 5.2 결과적으로 무슨 일이 생기나

**마켓이 상품을 삭제했을 때 그것을 우리 DB 에 반영하는 코드가 없다.**

D-210 리포트는 "쿠팡에 101건이 없다"는 걸 **이미 알고 있다.** 그런데 그 사실을 어디에도 남기지 않으니:

- 화면은 계속 등록된 것처럼 보여준다
- 동기화는 계속 그 죽은 등록에 요청을 보낸다 (요청 한도 낭비)
- 알고 싶으면 **매번 리포트를 다시 돌려야** 한다

실제로 이번 바코드 확대에서 그 낭비가 관측됐다 — 670건 표본에서 쿠팡 실패 64건, 그중 상당수가 "이미 삭제된 상품"이었다.

### 5.3 하드 삭제의 위험

우리가 상품을 지울 때 등록행까지 통째로 없앤다.

```java
marketRegistrationRepository.deleteAll(registrations);
productWriter.delete(product);
```

그러면 **과거 주문이 참조할 마켓 식별자가 사라진다.** 주문 테이블은 마켓 상품번호로 우리 상품을 찾는데, 그 연결고리를 스스로 끊는 셈이다.

D-218 오배송 사고가 상품 매칭 실패에서 났다는 걸 생각하면 가볍게 볼 일이 아니다. 반품·정산은 판매 후 몇 달 뒤에도 생긴다.

## 6. 화면이 상태를 구분해 보여주지 못한다

### 6.1 백엔드는 제대로 내려보낸다

```java
// MarketBadgeState.of
public static MarketBadgeState of(boolean synced, String url) {
    return new MarketBadgeState(synced ? "SYNCED" : "PENDING",
                                (url == null || url.isBlank()) ? null : url);
}
```

`is_synced` 를 그대로 읽어서 `SYNCED` 또는 `PENDING` 으로 내려보낸다. **여기까진 정확하다.**

### 6.2 프론트도 분기는 한다

```typescript
// productGridShared.tsx : badgeVisual
export function badgeVisual(product, marketKey): BadgeVisual {
    const regs = product.marketRegistrations ?? {};
    const state = regs[marketKey];
    if (state) {                                    // 등록행이 있으면
        if (state.status === 'PENDING') return 'pending';
        if (state.url) return 'registered';
        return NO_LINK_MARKET_KEYS.includes(marketKey)
            ? 'registeredNoLink' : 'linkless';
    }
    if (ESM_MARKET_KEYS.includes(marketKey) && !regs['CAFE24']) return 'blocked';
    return 'missing';                               // 등록행 자체가 없을 때만
}
```

`PENDING` 이면 `'pending'` 을 반환한다. **분기 자체는 되어 있다.**

### 6.3 그런데 눈으로 갈리지 않는다

문제는 `pending` 의 **시각 스타일이 `registered` 와 거의 같다**는 것이다. 상품 그리드에서 삭제된 쿠팡 상품의 배지가 정상처럼 켜져 보이는 이유다.

실제 화면(상품 `200907WA006`)을 보면:
```
쿠팡  N스토어  카페24  [G마켓]  옥션  11번가
 ↑                      ↑
 삭제됐는데 켜져 보임     등록행 없어서 흐림
```

**G마켓만 흐리게 보이는 게 역설적인 증거다.** G마켓은 등록행 자체가 없어서 `missing` 이라 다른 스타일을 타고, 쿠팡은 행이 있으니(비록 `false` 여도) 켜진 것처럼 보인다.

### 6.4 시각만 고쳐선 절반만 해결된다

배지 색을 바꿔서 `pending` 을 눈에 띄게 만들 수는 있다. 그런데 **그 배지가 무슨 뜻인지 사용자에게 말해줄 수 없다.**

3장에서 봤듯 `is_synced=false` 에는 다섯 가지가 섞여 있다. 화면에 "이 마켓은 문제가 있음"이라고 표시해봐야, 사용자는 **재등록해야 하는지 그냥 기다려야 하는지 판단할 수 없다.**

**그래서 사유 컬럼이 배지 개선의 전제조건이다.** 순서를 바꿀 수 없다.

## 7. 발견된 결함 (원장 연계)

### D-222 — 배지가 "삭제됨"과 "등록 대기"를 구분 못 함 (등재 완료)
6장 참조. 근본 원인은 3장의 사유 부재.

### D-223 — 재등록 시 중복 리스팅 생성 (가드 부재)
4장 참조. **심각도 높음.** 마켓에 유령 리스팅을 만들고 기존 식별자를 잃는다.
- 수정 방향: `publishToMarket` 진입 시 **기존 등록행의 식별자로 마켓 단건 조회**를 먼저 한다. 살아 있으면 게시 대신 **재게시(update) 경로**로 보내거나 명시적으로 거부한다. 쿠팡은 `GET seller-products/{id}`, 스토어는 `GET origin-products/{no}` 로 확인 가능하며 이미 구현되어 있다.
- 최소 조치: `is_synced=true` 이면서 식별자가 있으면 **기본 거부**하고, 강제 재등록은 별도 플래그로만 허용한다.

### D-226 — 게시 성공 후 DB 갱신 실패의 자동 복구 부재
2장 참조. 마켓에는 있고 우리는 모르는 상태가 로그로만 남는다.
- 수정 방향: 식별자를 먼저 별도 테이블/컬럼에 적재한 뒤 등록행을 갱신하거나, 기동 시 PENDING 행을 마켓 조회로 화해시키는 복구 배치를 둔다.

### D-224 — 실패 사유가 유실된다 (모든 문제의 근본)
3장 참조. `markSyncFailed()` 는 예외 종류를 구분하지 않는다.
- 수정 방향: **D-222 의 사유 컬럼과 같은 작업이다.** `unsync_reason`(`DELETED_ON_MARKET` / `VALIDATION_FAILED` / `TRANSIENT_ERROR` / `NEVER_SYNCED`)을 추가하고, catch 에서 마켓 응답 문구로 분류해 기록한다. 쿠팡 `"이미 삭제된 상품입니다"` 는 확정 신호로 쓸 수 있다.

### D-225 — 죽은 등록에도 전송을 시도한다
`ProductBarcodeSyncUseCase` 는 `findByProductId` 로 **모든 등록행**을 꺼내 `is_synced` 를 보지 않고 전송한다. 대상 선정 SQL 은 "상품에 동기화된 마켓이 하나라도 있으면" 통과시키므로, 스토어만 살아있는 상품의 **죽은 쿠팡 등록까지** 건드린다.
- 실측: 바코드 확대 670건 표본에서 쿠팡 실패 64건 중 상당수가 이 경우.
- 수정 방향: 전송 루프에서 `is_synced=false` 는 `SKIPPED("미동기 등록")` 로 건너뛴다. 같은 패턴이 `ProductMarketSyncService`·`ProductManageUseCase` 에도 있는지 함께 점검한다.

### D-227 — 카탈로그 대조 결과가 휘발한다
5장 참조. D-210 리포트가 "우리에만 101건" 을 세지만 어디에도 남기지 않는다.
- 수정 방향: 리포트가 `DELETED_ON_MARKET` 사유를 기록하도록 쓰기 경로를 붙인다(D-224 의 컬럼 재사용). 읽기 전용 원칙을 깨는 것이므로 **명시 옵션(`persist=true`)으로만** 동작하게 한다.

---

## 8. 권고 — 무엇부터 고칠 것인가

의존 관계가 있어 순서가 중요하다.

**1단계. 사유 컬럼 신설** (D-224 = D-222 의 선행)
`sb_market_registration.unsync_reason` 을 추가한다. 이것 없이는 4·6장의 어떤 개선도 근거가 없다. 스키마 변경이므로 수동 DDL 선행.

**2단계. 죽은 등록 건너뛰기** (D-225)
가장 싸고 즉효다. 코드 한 곳, 회귀 위험 낮음. 불필요한 마켓 호출과 거짓 실패가 사라진다.

**3단계. 중복 등록 가드** (D-223)
**실질 피해가 가장 큰 항목.** 유령 리스팅은 되돌리기 어렵다. 1단계 없이도 착수 가능.

**4단계. 배지 시각 분리** (D-222)
1단계가 끝나야 의미가 산다.

**5단계. 대조 결과 영속화** (D-227) / **게시 복구 배치** (D-226)
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

---

## 10. 이어서 볼 문서

고친 뒤 **어떻게 흘러야 하는지**는 [[product-lifecycle-target]] 에 정리했다. 목표 데이터 모델(ER), 상태 기계, 흐름 5종(등록·동기화·삭제감지·재등록·폐기)을 머메이드로 담았고, 이행 순서와 의존 관계도 그 문서 9장에 있다.
