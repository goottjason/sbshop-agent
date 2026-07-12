# SP-E Order-Sync Defect Fixes — Report

Branch: `fix-live-defects-b-c-e`. TDD (reproduction → fix → green). All facts taken as authoritative from the live Cafe24 response + official PDF.

## Test command & result
```
cd backend && ./gradlew :core:test --tests '*Cafe24OrderSync*' --tests '*Cafe24GmarketOrderAdapter*' --tests '*Cafe24AuctionOrderAdapter*'
```
BUILD SUCCESSFUL. Counts (from JUnit XML): Cafe24OrderSyncServiceTest tests=11 skipped=0 failures=0 errors=0; Cafe24GmarketOrderAdapterTest tests=3/0/0/0; Cafe24AuctionOrderAdapterTest tests=4/0/0/0. Total 18, all green.

Pre-existing unrelated failures (`SmartStoreOrderFetchFailureTest`, infra `ImageDownloadServiceCharacterizationTest` SIGABRT) were NOT run in this targeted scope and are not mine.

---

## FIX 1 — marketOrderNo = market_order_no (Cafe24 order_id → marketSpecific)
**File:** `core/.../order/service/Cafe24OrderSyncService.java`

**Root cause:** The entity's `marketOrderNo` (unique key + displayed number) was set to Cafe24's internal `order_id` (date-prefixed, e.g. `20260708-0000011`), while the real G마켓/옥션 native number (`market_order_no`, e.g. `4466411168`) was only stashed in the `marketSpecificData` JSON. Consequences: (a) the UI showed the Cafe24 number instead of the market number the seller references; (b) legacy rows stored by the old path under the native market number never matched `findByMarketOrderNo(order_id)`, producing duplicate rows and stale status.

**Fix:**
- `resolveMarketOrderNo(o)`: returns `market_order_no`, falling back to `order_id` if blank (defensive — open-market orders always have `market_order_no`).
- `createOrder`: `.marketOrderNo(resolveMarketOrderNo(o))`.
- `persistOrder`: blank-check and `findByMarketOrderNo(...)` now use `resolveMarketOrderNo(o)`.
- `buildMarketSpecific`: adds `cafe24_order_id` (the Cafe24 internal `order_id`) so accept/cancel can still target Cafe24.
- `refreshMarketSpecific(order, o)` (new helper, uses `Order.setMarketSpecificDataFromMap` with a `LinkedHashMap` of the same 4 keys) is called in `updateOrder` before `orderRepository.save` so existing rows gain `cafe24_order_id` on next sync.

**updateOrder marketSpecificData refresh — FEASIBLE and landed.** `Order.update(...)` does not carry marketSpecificData, so I mutate the entity directly via the existing `setMarketSpecificDataFromMap(Map)` setter before save. `getMarketSpecificDataMap()` + `setMarketSpecificDataFromMap()` both exist; no hack needed.

**TDD evidence:** `mapsGmarketAndSkipsOthers` now asserts `marketOrderNo == "GM123"` (was `20240711-0000001`) and `marketSpecificDataMap.get("cafe24_order_id") == "20240711-0000001"`, plus `never().findByMarketOrderNo("20240711-0000001")`. New `updatesExistingFoundByMarketOrderNo` asserts the existing row is found by market number and not re-created. Update-path test asserts `cafe24_order_id` gets backfilled on the existing entity.

**Commit 1:** `1362cb9` — fix(SP-E): 주문번호를 마켓 원본번호(market_order_no)로 저장·조회 — Cafe24 order_id는 marketSpecific에 보관

---

## FIX 2 — accept/cancel target Cafe24 order_id (pair of FIX 1)
**Files:** `Cafe24GmarketOrderAdapter.java`, `Cafe24AuctionOrderAdapter.java`

**Root cause:** Both adapters called `acceptOrder(order.getMarketOrderNo())` / `cancelOrder(...)`. After FIX 1 `marketOrderNo` is the G마켓/옥션 native number, but Cafe24 order-mutation APIs (`PUT /admin/orders/{order_id}`) require Cafe24's own `order_id`.

**Fix:** Added `cafe24OrderId(order)` to both adapters — reads `cafe24_order_id` from `marketSpecificData`, falls back to `marketOrderNo` for legacy rows synced before FIX 1. accept/cancel now pass `cafe24OrderId(order)`.

**TDD evidence:** Both adapter tests rewritten: given `marketSpecificData` containing `cafe24_order_id=20260708-0000011` (marketOrderNo=4466411168), assert the port is called with `20260708-0000011` (NOT the market number). Added fallback test (empty map → uses marketOrderNo `4466411168`).

**Commit 2:** `faea962` — fix(SP-E): 발주확인·취소는 marketSpecific의 cafe24_order_id로 Cafe24 타깃(마켓번호 전환 대응)

---

## FIX 3 — status mapping (N10 must not read as 결제완료) + multi-item
**File:** `Cafe24OrderSyncService.java` — `mapStatus` and `updateOrder`.

**Root cause A:** `mapStatus` mapped `case "N00","N10" -> NEW`. N10(상품준비중) is post-발주확인 and must be PREPARING(구매준비), never NEW(결제완료) — already-ordered items were showing as new. Also unknown codes silently defaulted to NEW.

**Root cause B:** `updateOrder` took `firstOf(items)` status and applied it to ALL lineItems, inconsistent with the create path which maps per-item.

**Fix:**
- N-code switch: `N00/N02→NEW`, `N10/N20/N21/N22→PREPARING`, `N30→SHIPPED`, `N40/N50→DELIVERED`, default → WARN log + NEW fallback. C*/R*/E* prefix handling above the switch unchanged.
- `updateOrder`: per-item mapping by index when `items.size() == lineItems.size()`, else the defensive first-item-to-all fallback (sbshop lineItems don't persist `order_item_code` for stable pairing).

**TDD evidence:** `mapsGmarketAndSkipsOthers` lineItem now asserts `PREPARING` (was the `NEW` bug). New parameterized-style helper tests: N10→PREPARING, N20→PREPARING, N30→SHIPPED, N40→DELIVERED, N00→NEW. `updatesExistingFoundByMarketOrderNo` also asserts the update-path lineItem transitions N10→PREPARING.

**Commit 3:** `d3d40dc` — fix(SP-E): 상품준비중(N10)→구매준비 매핑 교정 + 미매핑코드 loud 로그 + 다중아이템 상태 아이템별 반영

---

## Files changed
- `core/.../order/service/Cafe24OrderSyncService.java` (FIX 1 + FIX 3)
- `core/.../order/adapter/Cafe24GmarketOrderAdapter.java`, `Cafe24AuctionOrderAdapter.java` (FIX 2)
- `core/.../order/service/Cafe24OrderSyncServiceTest.java`, `.../adapter/Cafe24GmarketOrderAdapterTest.java`, `.../adapter/Cafe24AuctionOrderAdapterTest.java`

## Commit-split note (self-review)
Production code split cleanly: FIX 1 hunks and FIX 3 hunks of the service file were staged separately (`git apply --cached` of a FIX-1-only patch for commit 1; remainder in commit 3). The **sync test file** is heavily interleaved between FIX 1 and FIX 3 assertions (e.g. one hunk flips N10→NEW→PREPARING while adjacent lines carry FIX-1 marketOrderNo assertions), so splitting it mid-hunk would be fragile. I placed the entire `Cafe24OrderSyncServiceTest.java` in commit 3 rather than risk a broken partial patch. All three commits' code is coherent; the full suite is green at HEAD. Adapter tests are cleanly in commit 2.

## Self-review
- `resolveMarketOrderNo` fallback to `order_id` is safe: if fallback triggers, cafe24_order_id in marketSpecific still equals order_id, so accept/cancel remain correct.
- `getMarketSpecificDataMap()` uses a naive `split(",")`/`split(":")` parser — values must not contain commas/colons. The 4 stored values (place id/name, two numeric ids) don't, so round-trips are safe. Not touched here (pre-existing).
- `refreshMarketSpecific` overwrites the whole marketSpecific map on every update. That's intended (all 4 keys come from the same Cafe24 payload each sync); no other keys are stored on these G마켓/옥션 orders.

## Concerns (⚠ data migration needed — controller handles)
- **Transitional duplicates:** rows currently keyed by Cafe24 `order_id` in `marketOrderNo` will NOT be matched by the new `findByMarketOrderNo(market_order_no)` lookup, so the next sync may CREATE a second row under the native market number, leaving the old `order_id`-keyed row orphaned/stale. A one-time data migration is required to (a) rewrite existing `marketOrderNo` from Cafe24 order_id → the native market number and (b) backfill `cafe24_order_id` into marketSpecificData for rows that will not be re-synced. Per instructions I wrote NO migration SQL — flagging for the controller.
- Legacy rows lacking `cafe24_order_id` rely on the accept/cancel fallback to `marketOrderNo`; that fallback is correct ONLY until the migration rewrites marketOrderNo to the native number. After migration, any un-backfilled legacy row would send the native number to Cafe24 (wrong). The migration must backfill `cafe24_order_id` in the same pass it rewrites `marketOrderNo`.
