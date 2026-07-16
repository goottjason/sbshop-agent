# Cafe24 가격/재고 동기화 변경감지 스킵 — 설계

작성: 2026-07-16 · 기준: main (배포됨)

## 배경·목표

크롤 배치(`crawlAndUpdatePriceStock`)는 매 실행마다 **변경 여부와 무관하게** 연동된 모든 마켓에
가격/재고를 push한다. 그 결과 값이 안 바뀐 상품(특히 계속 품절인 상품)도 Cafe24에 동일 상태를
재전송하며, Cafe24를 매개로 미러링되는 **옥션/G마켓 외부 연동에서 불필요한 전송실패**가 누적된다.
(옥션 "진열/판매 상태일 때만 판매중지 해제 가능" 에러 등 — 이 에러 자체는 외부 연동툴 소관이나,
불필요한 Cafe24 재전송을 줄이면 downstream 노이즈가 준다.)

**목표:** 크롤 배치에서 **변경이 없고 직전 동기화가 성공한** Cafe24 호출을 스킵한다. 우선 Cafe24만.

## 핵심 규칙

Cafe24 등록행에 대해 **다음을 모두 만족하면 호출 스킵**, 아니면 호출:
- 이번 배치의 판매가·재고상태가 **이전 DB 값과 동일**(가격 변경 없음 AND 상태 변경 없음), **그리고**
- 해당 Cafe24 등록행의 **직전 동기화가 성공**(`isSynced == true`).

| 상황 | changed | isSynced | Cafe24 |
|------|:---:|:---:|:---:|
| 품절→품절, 가격 동일, 직전 성공 | false | true | **스킵** |
| 판매중→판매중, 가격 동일, 직전 성공 | false | true | **스킵** |
| 판매중→판매중, 가격 변경 | true | — | 호출 |
| 품절↔판매중 전환 | true | — | 호출 |
| 변경 없음이나 직전 동기화 실패 | false | false | 호출(재시도) |

- **비교 기준:** DB의 이전값(우리가 마지막으로 push한 값). 라이브 Cafe24 조회는 하지 않는다(추가 API 비용 회피).
  외부에서 Cafe24를 수동 변경한 드리프트는 감지하지 않음(수용된 트레이드오프).
- **타 마켓(쿠팡·스마트스토어·11번가):** 현행대로 **항상 호출**. 스킵은 Cafe24 한정.

## 구현 (스키마 변경 없음)

### 1. `MarketRegistration` — 실패 표시 추가
`markSyncFailed()` 추가: `isSynced = false`. (기존 `markSynced()`는 `isSynced=true`+`lastSyncedAt=now`.)
- `isSynced`는 현재 성공 시에만 true로 세팅되고 실패 시 리셋되지 않아 "직전 성공"을 신뢰성 있게 못 나타냄.
- `isSynced`는 비즈니스 분기 없이 UI DTO(`MarketRegistrationResponse`)에서만 읽히므로, 실패 시 false 리셋은
  안전하며 "현재 동기화 상태"를 더 정확히 표현한다.

### 2. `ProductMarketSyncService.syncPriceStock` — changed 오버로드 + Cafe24 스킵/실패리셋
- 오버로드 추가: `syncPriceStock(productId, price, stockStatus, boolean changed)`.
  기존 3인자 시그니처는 `changed=true`로 위임(현행 동작 보존 — 다른 호출자 불변).
- `syncInternal` 마켓 루프:
  - **스킵 조건:** `marketType == CAFE24 && !changed && reg.getIsSynced() == Boolean.TRUE`
    → 호출하지 않고 `skipped`에 추가, 로그 `[가격재고동기화] 변경없음 스킵(Cafe24): productId=..`.
  - **성공:** 현행대로 `markSynced()` + 저장.
  - **실패(catch):** `reg.markSyncFailed()` + **저장**(현재는 실패 시 미저장 → 저장 추가해야 isSynced=false 영속).
    `failed`에 수집(현행 유지, 롤백 안 함).

### 3. 크롤 배치 — 변경 계산 후 전달
`BatchPriceStockService.crawlAndUpdatePriceStock`:
- `product.update(command)` 이전에 옛 값 캡처: `oldPrice = product.getSalePrice()`, `oldStatus = product.getStockStatus()`.
- `boolean changed = (salePrice 값이 oldPrice와 다름) || (result.status() != oldStatus)`.
  (BigDecimal 비교는 `compareTo != 0`, null 안전. 상태는 enum `!=`.)
- `productMarketSyncService.syncPriceStock(productId, priceInt, result.status(), changed)` 호출.

### 적용 범위
- 크롤 배치(`crawlAndUpdatePriceStock`) 및 이를 쓰는 소싱업체별 배치(`/by-supplier`)만 `changed`를 전달.
- 수동 배치(`manualUpdatePriceStock`)는 이미 "변경없음 전체 스킵" 보유 → 변경 없음.
- 단건 수정(`ProductManageUseCase.updatePriceStock`)은 현행 유지(3인자 호출 = 항상 호출).

## 테스트 (TDD)

- **`ProductMarketSyncService`**(mock 마켓클라이언트/리포지토리):
  - changed=false + Cafe24 isSynced=true → Cafe24 클라이언트 미호출, skipped에 CAFE24.
  - changed=false + Cafe24 isSynced=false → Cafe24 호출됨(재시도).
  - changed=true → isSynced 무관 Cafe24 호출됨.
  - changed=false여도 **쿠팡 등 타 마켓은 항상 호출**(스킵은 Cafe24 한정).
  - 마켓 호출 실패 시 `reg` isSynced=false로 저장(markSyncFailed).
- **크롤 배치**: 옛값==새값(가격·상태 동일)이면 `changed=false`로 sync 호출됨을 검증(특성 테스트 or 협력자 검증).
- 기존 회귀 전건 통과(동작 불변 경로 보존).

## 비목표(Out of scope)
- 쿠팡/스마트스토어/11번가 스킵(추후 확장 후보 — 쿠팡 "판매재개 실패" 반복 완화).
- 라이브 Cafe24 상태 조회 기반 비교.
- 옥션/G마켓 외부 연동툴 수정(우리 시스템 밖).
- 스키마(컬럼) 추가.
