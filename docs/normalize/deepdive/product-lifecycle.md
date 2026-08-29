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

> **⚠ 이 장의 6.1 은 틀렸다.** 2026-08-29 배지 수정에 착수하면서 실제 호출부를 읽어보니, 백엔드는 `is_synced` 를 **보지 않고** 있었다. 정정 내용은 6.5 에 있다. 아래 6.1~6.4 는 당시 조사 기록으로 남긴다.

### 6.1 백엔드는 제대로 내려보낸다 (오진 — 6.5 참조)

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

### 6.5 정정 — 백엔드가 `is_synced` 를 보지 않고 있었다 (2026-08-29)

6.1 에서 `MarketBadgeState.of(synced, url)` 의 **선언**만 보고 "여기까진 정확하다" 고 썼다. **호출부를 안 봤다.**

```java
// ProductController.buildMarketMap — 실제 호출부
boolean synced = reg.hasIdentifiers();   // ← is_synced 가 아니다
...
MarketBadgeState.of(synced, reg.buildMarketUrl());
```

`synced` 는 **"우리가 식별자를 갖고 있는가"** 였다. `is_synced` 는 배지 판정에 아예 참여하지 않았다.

그래서 상품 44 처럼 **쿠팡에서 삭제됐지만 `sellerProductId` 는 남아 있는** 행은 언제나 `SYNCED` 로 내려갔다. 6.3 이 "pending 의 스타일이 registered 와 비슷해서" 라고 진단한 것도 빗나갔다 — 애초에 `pending` 이 나오지 않았다. `PENDING` 은 식별자가 통째로 빈 행(`{}`)에서만 나온다.

**교훈: 함수 선언을 읽고 "정확하다" 고 판정하지 마라.** 인자에 무엇이 들어오는지는 호출부에만 있다. 8장의 함정 패턴과 같은 종류의 실수다 — 껍데기를 보고 내용을 단정했다.

**수정 후 판정 규칙** (`MarketBadgeState.of(hasIdentifiers, isSynced, reason, url)`):

| 조건 | status | 배지 | 클릭 |
|------|--------|------|------|
| `unsync_reason = DELETED_ON_MARKET` | `DELETED` | 빨강 | **재등록** (D-223 가드가 통과시킨다) |
| `unsync_reason = VALIDATION_FAILED` / `TRANSIENT_ERROR` | `FAILED` | 주황 + 사유 툴팁 | 마켓 페이지 열기 (재등록 안 함 — 중복 위험) |
| 사유 없음 + 식별자 있음 | `SYNCED` | 기존 | 마켓 페이지 열기 |
| 사유 없음 + 식별자 없음 | `PENDING` | 주황(기존) | 없음 |

**사유가 없으면 기존 판정을 그대로 둔 이유**는 6.6 에 있다.

### 6.6 왜 미분류 행을 경고로 물들이지 않았나

`is_synced=false` 인 행이 2,021 건이고 대부분 사유가 없다. 이걸 전부 "문제 있음" 으로 그리면 화면이 경고로 뒤덮인다. 그리고 **그 경고의 대부분이 거짓일 가능성이 높다** — 3장에서 봤듯 `is_synced=false` 는 "마지막 작업이 실패함" 이지 "마켓에 없음" 이 아니다.

기존 테스트 하나가 이 결정을 이미 박아 두고 있었다.

> "식별자가 있으면 isSynced 가 false 여도 SYNCED — **레거시 임포트 행을 거짓 미완료로 경고하지 않는다**"

같은 판단을 유지했다. **확정된 사유가 있을 때만 갈라 그린다.** 모르는 것을 아는 척하지 않는 쪽이다.

### 6.7 그런데 재료가 생기지 않는다 — 죽은 등록의 자기분류 불가

배지를 갈라 그릴 수 있게 만들고 나서 실제 데이터를 보니 **`DELETED_ON_MARKET` 을 가진 행이 0 건**이었다. 새 배지가 아예 나타나지 않는다는 뜻이다.

원인은 두 수정이 맞물린 곳에 있다.

```
D-225: is_synced=false 인 등록은 전송에서 건너뛴다   (죽은 등록에 API 낭비를 막으려고)
   ↓
죽은 등록은 마켓 응답을 받을 기회가 없다
   ↓
"이미 삭제된 상품입니다" 를 들을 수 없다
   ↓
DELETED_ON_MARKET 이 기록될 수 없다
   ↓
배지가 영원히 갈리지 않는다
```

**건너뛰기가 옳고, 그 옳음이 분류를 막는다.** 상품 44 도 여기 걸려서, 배지 수정 후에도 여전히 `SYNCED` 로 그려졌다.

그래서 `MarketRegistrationProbeService` 를 함께 만들었다. 미분류 미동기 행을 **마켓 단건 조회**로 확인해 판정한다.

| 조회 결과 | 조치 |
|-----------|------|
| 삭제 확인 (`"이미 삭제된 상품"` 등) | `DELETED_ON_MARKET` 기록 |
| 살아 있음 | **아무것도 쓰지 않음** |
| 판정 불가 (타임아웃·5xx) | `INCONCLUSIVE` — 아무것도 쓰지 않음 |

**한 방향으로만 쓴다.** 살아 있다고 `is_synced=true` 로 되돌리지 않는 이유는, 그 행이 미동기인 데는 우리가 모르는 이력이 있을 수 있고 프로브는 "존재" 만 확인하기 때문이다. 추측으로 상태를 만들지 않는다 — 11.4 에서 배운 것이다.

트리거: `POST /internal/registrations/probe?market=COUPANG&limit=20&throttleMs=300&dryRun=true`

이것은 [[D-227]] 의 축소판이다. D-227 은 카탈로그 전수 대조이고, 이쪽은 등록행 단건 프로브다. 대상이 좁은 대신 **지금 당장 돌릴 수 있다.**

## 7. 발견된 결함 (원장 연계)

### D-222 — 배지가 "삭제됨"과 "등록 대기"를 구분 못 함 — **✅ 2026-08-29 수정**
6장 참조. 근본 원인은 3장의 사유 부재였고, **직접 원인은 백엔드가 `is_synced` 를 보지 않은 것**이었다(6.5). 배지를 `DELETED`/`FAILED`/`SYNCED`/`PENDING` 으로 갈랐고, 재료를 만들 `MarketRegistrationProbeService` 를 함께 붙였다(6.7).

### D-223 — 재등록 시 중복 리스팅 생성 (가드 부재) — **✅ 2026-08-29 수정**
4장 참조. **심각도 높음.** 마켓에 유령 리스팅을 만들고 기존 식별자를 잃었다.
- 수정 방향: `publishToMarket` 진입 시 **기존 등록행의 식별자로 마켓 단건 조회**를 먼저 한다. 살아 있으면 게시 대신 **재게시(update) 경로**로 보내거나 명시적으로 거부한다. 쿠팡은 `GET seller-products/{id}`, 스토어는 `GET origin-products/{no}` 로 확인 가능하며 이미 구현되어 있다.
- 최소 조치: `is_synced=true` 이면서 식별자가 있으면 **기본 거부**하고, 강제 재등록은 별도 플래그로만 허용한다.
- **실제 적용**: `ProductPublishUseCase.guardAgainstDuplicatePublish` 신설. 살아있는 등록(`is_synced=true` + 조회용 식별자 보유)에 게시를 걸면 `DuplicatePublishException`(HTTP 409)으로 거부한다. 예외는 두 가지 — `unsync_reason=DELETED_ON_MARKET` 이면 그게 정상 재등록이므로 통과시키고, `force=true` 를 명시하면 강제 진행한다. 더불어 식별자를 덮어쓸 때 이전 값을 `previousIdentifiers` 배열로 **보존**하므로, 설령 유령이 생겨도 추적할 근거가 남는다. 11장 참조.

### D-226 — 게시 성공 후 DB 갱신 실패의 자동 복구 부재
2장 참조. 마켓에는 있고 우리는 모르는 상태가 로그로만 남는다.
- 수정 방향: 식별자를 먼저 별도 테이블/컬럼에 적재한 뒤 등록행을 갱신하거나, 기동 시 PENDING 행을 마켓 조회로 화해시키는 복구 배치를 둔다.

### D-224 — 실패 사유가 유실된다 (모든 문제의 근본) — **✅ 2026-08-29 수정**
3장 참조. `markSyncFailed()` 가 예외 종류를 구분하지 않았다.
- 수정 방향: **D-222 의 사유 컬럼과 같은 작업이다.** `unsync_reason`(`DELETED_ON_MARKET` / `VALIDATION_FAILED` / `TRANSIENT_ERROR` / `NEVER_SYNCED`)을 추가하고, catch 에서 마켓 응답 문구로 분류해 기록한다. 쿠팡 `"이미 삭제된 상품입니다"` 는 확정 신호로 쓸 수 있다.
- **실제 적용**: `unsync_reason VARCHAR(32)` 컬럼 신설(수동 DDL 완료), `UnsyncReason` enum + `UnsyncReasonClassifier` 추가. 실패를 잡는 두 경로(`ProductMarketSyncService`·`ProductBarcodeSyncUseCase`)가 예외의 **원인 사슬 전체**를 훑어 분류해 기록한다. 성공하면 `markSynced()` 가 사유를 지운다. 11장 참조.

### D-228 — 마켓에 보낸 값을 우리 등록행에 남기지 않는다 — **✅ 2026-08-29 수정** (신규)

쿠팡 경고 화면의 6건을 실측하다 드러났다. 바코드를 마켓에 성공적으로 PUT 했는데도 `market_identifiers` 의 `barcode` 는 **6건 모두 빈 문자열**이었다.

```json
{"sellerProductId":"11898889204","vendorItemId":"74409528805","barcode":"", ...}
```

- 증상: "이 상품의 바코드를 마켓에 반영했는가" 를 **DB 만 봐서는 알 수 없다.** 확인하려면 매번 마켓 API 를 다시 때려야 하고, 그 호출은 요청 한도를 잠식한다.
- 이것은 3장의 `is_synced` 문제와 **같은 병**이다. 로컬 상태가 마켓 상태를 반영하지 않는다.
- 수정: `ProductBarcodeSyncUseCase.push` 가 전송 성공 직후 `reg.enrichIdentifier("barcode", …)` 로 실제 보낸 값을 남긴다. `dryRun` 은 건드리지 않는다.

### D-225 — 죽은 등록에도 전송을 시도한다
`ProductBarcodeSyncUseCase` 는 `findByProductId` 로 **모든 등록행**을 꺼내 `is_synced` 를 보지 않고 전송한다. 대상 선정 SQL 은 "상품에 동기화된 마켓이 하나라도 있으면" 통과시키므로, 스토어만 살아있는 상품의 **죽은 쿠팡 등록까지** 건드린다.
- 실측: 바코드 확대 670건 표본에서 쿠팡 실패 64건 중 상당수가 이 경우.
- 수정 방향: 전송 루프에서 `is_synced=false` 는 `SKIPPED("미동기 등록")` 로 건너뛴다. 같은 패턴이 `ProductMarketSyncService`·`ProductManageUseCase` 에도 있는지 함께 점검한다.

### D-227 — 카탈로그 대조 결과가 휘발한다
5장 참조. D-210 리포트가 "우리에만 101건" 을 세지만 어디에도 남기지 않는다.
- 수정 방향: 리포트가 `DELETED_ON_MARKET` 사유를 기록하도록 쓰기 경로를 붙인다(D-224 의 컬럼 재사용). 읽기 전용 원칙을 깨는 것이므로 **명시 옵션(`persist=true`)으로만** 동작하게 한다.

---

## 8. 마켓 API 를 다룰 때 반복해서 밟은 함정

이 장은 결함 목록이 아니라 **패턴**이다. 2026-08-29 바코드 확대 작업 하루 동안 같은 함정을 세 번 밟았다. 다음 사람이 또 밟지 않도록 남긴다.

### 8.1 조회 스키마와 수정 스키마는 같지 않다

마켓 상품을 수정하는 표준 절차는 이렇게 생겼다.

```
GET 상품 → 바꿀 필드만 교체 → 같은 몸통을 PUT
```

합리적으로 보이고, 대부분 동작한다. 그래서 위험하다. **GET 이 돌려준 몸통을 그대로 PUT 하면 거부되는 경우**가 마켓마다 있고, 세 가지 서로 다른 얼굴로 나타났다.

| 함정 | 필드 | GET 결과 | PUT 결과 | 정체 |
|------|------|----------|----------|------|
| **읽기 전용 값** | 네이버 `statusType` | `OUTOFSTOCK` | 400 "Enum값을 입력하지 않았거나 허용되지 않은" | 마켓이 **계산해서 내려주는** 값. 되돌려 보내면 거부. 그런데 **필수**라 빼도 거부 |
| **짝 필드 불일치** | 네이버 `consumptionDateText` | 구상품엔 아예 **없음** | 400 (모든 수정 실패) | GET 에 없는 필드가 PUT 엔 필수. 옛 상품일수록 잘 걸린다 |
| **쓰기 불가 필드** | 카페24 `gtin` | 값이 보임 | 422 (단일상품) | 조회에는 있는데 쓰기는 옵션 상품만 받는다 |

### 8.2 왜 진단이 어긋났나 — `statusType` 사례

이 건은 **두 번 틀리고 세 번째에 맞췄다.** 기록해 둘 값어치가 있다.

1. **1차 진단**: "읽기 전용 필드니까 PUT 몸통에서 빼자." 배포했다. 제거 로그가 9번 찍혔는데 **에러는 그대로**였다.
2. **재독**: 에러 문구를 다시 읽었다 — "Enum값을 **입력하지 않았거나** 허용되지 않은". `또는` 이었다. 빼도 틀리고 잘못 넣어도 틀린다. **필수이면서 값이 제한된 필드**였다.
3. **실측**: 정상 동작하는 상품들의 `statusType` 을 표본 조사했다. `{SUSPENSION: 7, SALE: 1}` — 둘 다 받아들여진다.
4. **정정**: 제거가 아니라 **치환**. `OUTOFSTOCK` → `SALE`.
5. **검증**: 9건 전부 성공. 재조회하니 `statusType` 은 다시 `OUTOFSTOCK` 이었다. **재고가 0 이라 네이버가 스스로 되돌린 것**이고, 이것이 "마켓이 계산하는 값" 이라는 증거다. 바코드는 들어갔고 판매상태는 훼손되지 않았다.

**교훈 셋.**
- 에러 문구는 **끝까지** 읽는다. "않았거나" 같은 접속사 하나가 진단을 뒤집는다.
- 제거로 안 되면 **정상 상품의 값을 표본 조사**한다. 추측보다 싸고 정확하다.
- 배포 후 로그가 "고쳤다"고 말해도 **에러가 그대로면 안 고친 것이다.**

### 8.3 실패가 성공으로 보이는 계열 (D-181 / D-208 / D-212 의 후손)

이 프로젝트가 반복해서 만들어 온 결함 유형이다. **호출은 성공했는데 의도한 변경은 일어나지 않았고, 우리는 성공으로 기록한다.**

이번에 발견한 세 가지:

| 마켓 | 겉보기 | 실제 | 수정 |
|------|--------|------|------|
| 쿠팡 | 바코드 전송 성공 | `syncImagesAndHtml` 로 폴백 — 이미지/HTML 만 덮어쓰고 **바코드는 손도 안 댐** | 전용 `syncBarcode` 신설 |
| 11번가 | 바코드 전송 성공 | 폴백 경로가 다른 걸 수정하고 성공 반환 | 폴백 제거, `UNSUPPORTED` 반환 |
| 카페24 | 오래된 `variant_code` 로 PUT | 낡은 로컬 캐시 값 — 이미 바뀌었을 수 있음 | 라이브 `GET /variants` 로 매번 새로 조회 |

**공통 처방**: 폴백을 만들 때 "이 폴백은 원래 하려던 일을 정말 하는가" 를 묻는다. 아니면 폴백하지 말고 **못 한다고 말해야 한다.** 조용한 대체 성공이 가장 비싼 거짓말이다.

### 8.4 그래서 원칙

1. **매번 신선한 GET.** 로컬에 캐시된 마켓 데이터(`market_detailed_info`)로 PUT 몸통을 만들지 않는다. [[D-183]] 에서 배운 것을 이번에 또 배웠다.
2. **바꿀 필드만 손댄다.** 전체 재게시는 관계없는 필드(이미지 등)의 검증 실패로 **전체가 400** 이 된다. 실제로 스마트스토어 이미지 재업로드 거부가 바코드 PUT 전체를 죽였다.
3. **폴백은 같은 일을 할 때만.** 아니면 `UnsupportedOperationException`.
4. **성공 판정은 봉투가 아니라 내용으로.** 200 응답이 곧 반영은 아니다(쿠팡은 PUT 성공 = **심사중 전환**이다).
5. **보냈으면 남긴다.** 마켓에 쓴 값은 우리 등록행에도 기록한다 — D-228 이 이것이다.

---

## 9. 권고 — 무엇부터 고칠 것인가

의존 관계가 있어 순서가 중요하다.

**1단계. 사유 컬럼 신설** (D-224 = D-222 의 선행) — **✅ 완료 2026-08-29**
`sb_market_registration.unsync_reason` 추가. 이것 없이는 4·6장의 어떤 개선도 근거가 없었다. 수동 DDL 적용 완료.

**2단계. 죽은 등록 건너뛰기** (D-225) — **✅ 완료 2026-08-29**
가장 싸고 즉효였다. 바코드 확대 재처리 24건 중 **15건이 SKIPPED** 로 걸러졌다 — 이전에는 전부 "실패" 로 집계되던 것들이다.

**3단계. 중복 등록 가드** (D-223) — **✅ 완료 2026-08-29**
**실질 피해가 가장 큰 항목이었다.** 기본 거부 + `force` 탈출구 + 이전 식별자 보존으로 처리했다.

**4단계. 배지 시각 분리** (D-222) — **✅ 완료 2026-08-29**
`MarketBadgeState` 에 사유를 실었고 프론트가 `deleted`/`failed` 를 갈라 그린다. 착수 과정에서 6.1 의 오진(백엔드가 `is_synced` 를 안 봄)을 발견해 정정했고, 죽은 등록이 스스로 분류될 수 없다는 구조적 함정(6.7)도 드러나 등록행 프로브를 함께 만들었다.

**5단계. 대조 결과 영속화** (D-227) / **게시 복구 배치** (D-226) — **부분 착수**
D-227 의 축소판(등록행 단건 프로브)이 4단계와 함께 들어갔다(6.7). 남은 것은 **카탈로그 전수 대조** — 프로브는 우리 등록행을 하나씩 확인할 뿐이라, "마켓에는 있는데 우리에겐 없는" 방향은 여전히 못 본다. D-226 은 미착수.

---

## 10. 조사에서 확인한 사실 (근거)

- `savePending` 은 기존 행 재사용 — `MarketRegistrationTxService:20-23`
- 중복 등록 가드 부재 — `ProductPublishUseCase` 전체에 `isSynced` 참조 0건
- `markSyncFailed` 호출처는 단 1곳 — `ProductMarketSyncService:101`
- 카탈로그 대조는 읽기 전용 — `MarketCatalogReconciliationService` 에 `save(` 0건
- 우리 삭제는 등록행 완전 제거 — `ProductDeleteTxService:23` `deleteAll`
- 배지 스타일 — `productGridShared.tsx:badgeVisual`, `MarketBadgeState.of`
- 실증 데이터 — 상품 44 쿠팡행 `is_synced=false`, `sellerProductId=11583638672`, 쿠팡 응답 "이미 삭제된 상품입니다"

**식별자는 지우지 않는다.** 과거 주문 추적([[D-218]] 오배송 사고), [[D-210]] 정합성 대조, 재등록 이력의 근거다. 지우면 문제가 해결되는 게 아니라 보이지 않게 된다.

---

---

## 11. 수정 반영 (2026-08-29)

### 11.1 무엇을 고쳤나

| 결함 | 조치 | 핵심 파일 |
|------|------|-----------|
| **D-224** | `unsync_reason` 컬럼 + `UnsyncReason` enum + `UnsyncReasonClassifier`. 실패 catch 두 곳이 원인 사슬을 훑어 분류·기록. 성공 시 자동 소거 | `MarketRegistration`, `UnsyncReasonClassifier`, `ProductMarketSyncService`, `ProductBarcodeSyncUseCase` |
| **D-223** | 살아있는 등록에 게시하면 `DuplicatePublishException`(409). `DELETED_ON_MARKET` 은 통과, `force=true` 는 강제 허용. 식별자 덮어쓸 때 이전 값 보존 | `ProductPublishUseCase`, `MarketRegistrationTxService`, `MarketRegistration` |
| **D-228** | 바코드 전송 성공 시 등록행 식별자에 실제 보낸 값 기록 | `ProductBarcodeSyncUseCase` |

### 11.2 분류 규칙 — 무엇을 보고 무엇으로 판정하나

`UnsyncReasonClassifier` 는 예외 메시지를 **원인 사슬 끝까지** 훑는다. 래핑된 예외의 겉면만 보면 대부분 "전송 실패" 같은 무의미한 문구라서다.

| 사유 | 판정 근거 (부분 문자열) | 운영자가 할 일 |
|------|------------------------|----------------|
| `DELETED_ON_MARKET` | "삭제된 상품", "존재하지 않는 상품", "등록된 상품이 없습니다", `NOT_FOUND`, `404` | **재등록.** 가드도 이 값을 보고 통과시킨다 |
| `VALIDATION_FAILED` | "유효하지 않", "허용되지 않", "입력하지 않", "필수", "올바르지 않", "파싱", `400` | **데이터를 고쳐야 한다.** 재시도로는 안 풀린다 |
| `TRANSIENT_ERROR` | 위 어디에도 안 걸리는 나머지 (타임아웃·5xx·429) | **그냥 재시도** |
| `NEVER_SYNCED` | 한 번도 성공한 적 없음 | **최초 등록** |

문자열 매칭이라 완벽하지 않다. 다만 **"모르는 것을 모른다고 남기는" 쪽**으로 기울어 있다 — 분류에 실패하면 `TRANSIENT_ERROR`(=재시도해도 안전)로 떨어진다. 마켓이 문구를 바꾸면 `DELETED_ON_MARKET` 이 `TRANSIENT_ERROR` 로 새는 방향이지, 살아있는 상품을 삭제로 오판하는 방향이 아니다.

### 11.3 데이터 현황 — 과거는 아직 비어 있다

컬럼을 추가한다고 과거가 채워지지는 않는다. 백필은 **확실히 판별 가능한 행만** 보수적으로 했다.

| 구분 | 건수 |
|------|------|
| `is_synced=false` 전체 | 2,024 |
| ↳ `NEVER_SYNCED` 로 백필 (식별자 없음 + 동기화 이력 없음) | 3 |
| ↳ **미분류(NULL)** | **2,021** |

2,021건을 추측으로 채우지 않은 이유는 단순하다. **틀린 사유는 사유가 없는 것보다 나쁘다.** `DELETED_ON_MARKET` 으로 잘못 찍으면 D-223 가드가 그 상품의 중복 등록을 **허용**해 버린다 — 정확히 막으려던 사고를 부른다.

이 2,021건은 두 경로로 채워진다.
- **수동적**: 해당 등록행에 다음 작업(가격·재고 동기화, 바코드 전송 등)이 걸릴 때 실패하면 그 자리에서 분류된다.
- **능동적**: D-227(카탈로그 대조 영속화)이 마켓 카탈로그와 대조해 "우리에만 있음" 을 `DELETED_ON_MARKET` 으로 기록한다. **이것이 D-227 의 우선순위가 올라간 이유다.**

### 11.4 라이브 검증이 잡아낸 자책 결함 — 사유 기록과 상태 전환을 묶으면 안 된다

배포 후 실제 데이터로 확인하다 **이번 수정 자체가 만든 결함**을 발견했다. 단위 테스트는 전부 통과했는데도 잡히지 않은 종류다.

**무슨 일이 있었나.** 실패 사유를 기록하려고 catch 에서 `markSyncFailed(reason)` 을 불렀다. 그런데 이 메서드는 사유만 쓰는 게 아니라 `is_synced` 도 `false` 로 뒤집는다. 쿠팡 바코드 전송 8건이 이렇게 실패했다.

```
message=해당 상품은 심사가 진행중입니다.
```

이건 **상품이 마켓에 멀쩡히 있다**는 뜻이다. 심사가 끝나면 반영된다. 그런데 우리는 이걸 "미동기" 로 기록해 버렸다.

**파급이 왜 큰가.** 세 갈래로 번진다.

1. 배지가 **미등록**으로 보인다 (6장 문제로 되돌아감)
2. D-225 스킵 로직이 앞으로 이 등록행을 **영구히 건너뛴다** — 바코드가 영영 안 들어간다
3. **D-223 가드가 뚫린다** — 미동기 행은 가드를 통과하므로, 이 상품에 재등록을 걸면 **유령 리스팅이 생긴다**

세 번째가 치명적이다. 같은 커밋에서 만든 가드를 같은 커밋의 다른 변경이 무력화했다.

**교훈.** `is_synced` 는 "마켓에 이 상품이 있는가" 이고, `unsync_reason` 은 "없다면 왜 없는가" 다. **후자를 쓰겠다고 전자를 건드리면 판정 자체가 오염된다.** 사유를 남기는 것과 존재를 부정하는 것은 다른 일이다.

**수정.** 바코드 경로에서 `is_synced` 를 뒤집는 것은 `DELETED_ON_MARKET` 일 때 **뿐**이다 — 마켓에 없다는 증거가 실제로 있을 때만. 검증 실패·일시 오류는 응답과 로그로만 보고한다. 훼손된 8건은 복구했다.

**왜 단위 테스트가 못 잡았나.** 내가 쓴 테스트는 "삭제 실패 시 사유가 기록되는가" 만 물었다. **"삭제가 아닌 실패에는 무엇이 일어나면 안 되는가"** 를 묻지 않았다. 부정 명제를 빠뜨린 것이다. 회귀 테스트 2건(`transientFailure_doesNotFlipIsSynced`, `validationFailure_doesNotFlipIsSynced`)을 추가했다.

### 11.5 가드가 정상 흐름을 막지 않는가

D-223 가드는 게시 경로에 **거부**를 새로 넣는 변경이라, 멀쩡한 작업을 막으면 그게 더 큰 사고다. 세 경우로 나눠 확인했다.

| 상황 | `is_synced` | 식별자 | 결과 |
|------|-------------|--------|------|
| 최초 등록 | false | 없음 | **통과** — 가드는 식별자가 없으면 아예 관여하지 않는다 |
| 마켓에서 삭제된 상품 재등록 | false | 있음 + `DELETED_ON_MARKET` | **통과** — 이게 정상 재등록이다 |
| 살아있는 상품에 배지 클릭 | true | 있음 | **거부 (409)** — 유령 리스팅을 만들 뻔한 경우 |
| 사유 미분류 + 미동기 | false | 있음 | **통과하되 경고 로그** — 2,021건이 여기 해당하므로 막으면 운영이 멈춘다 |

마지막 행이 판단이 갈린 지점이다. 미분류를 **막는** 쪽이 안전해 보이지만, 그러면 사유가 채워지기 전까지 2,021건의 재등록이 전부 불가능해진다. **통과시키되 경고를 남기는** 쪽을 택했다. 실제 피해가 큰 케이스(`is_synced=true`)는 확실히 막히므로 D-223 의 목적은 달성된다.

---

## 12. 이어서 볼 문서

고친 뒤 **어떻게 흘러야 하는지**는 [[product-lifecycle-target]] 에 정리했다. 목표 데이터 모델(ER), 상태 기계, 흐름 5종(등록·동기화·삭제감지·재등록·폐기)을 머메이드로 담았고, 이행 순서와 의존 관계도 그 문서 9장에 있다.
