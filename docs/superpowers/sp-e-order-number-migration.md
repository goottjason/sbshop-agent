# SP-E 배포 전 1회성 데이터 마이그레이션 — G마켓/옥션 주문번호 재키잉

- 작성일: 2026-07-12
- 대상 커밋: `fix-live-defects-b-c-e` (SP-E FIX 1~3)
- 실행 주체: **사람(운영 DB 수동 관리)** — 재배포와 같은 시점 또는 직전에 1회 실행.

## 왜 필요한가

SP-E FIX 1 이후 Cafe24 동기화는 주문을 **마켓 원본번호(`market_order_no`, 예 `4462952064`)**로 저장·조회한다. 그러나 기존에 저장된 G마켓/옥션 주문들은 **Cafe24 자체 주문번호(`order_id`, 예 `20260630-0000017`)**를 `market_order_no` 컬럼에 담고 있다.

마이그레이션 없이 배포하면:
1. 동기화가 `4462952064`로 조회 → 기존 행(`20260630-0000017` 키)을 못 찾음 → **새 중복행 생성**.
2. 기존 행은 옛 번호·스테일 상태로 그대로 남음.

따라서 배포 전에 기존 행의 `market_order_no`를 마켓 원본번호로 바꾸고, 기존에 담겨 있던 Cafe24 `order_id`는 발주확인/취소 API가 쓸 수 있도록 `market_specific_data` JSON의 `cafe24_order_id` 키로 옮긴다.

> 라이브 조사 결과(2026-07-12): 대상은 **GMARKET 3건**뿐이며 중복행은 없다. 각 행의 `market_specific_data`에 이미 올바른 `market_order_no`가 들어 있어 아래 UPDATE 한 방으로 안전하게 전환된다. (옥션 주문은 현재 0건이나, 조건에 AUCTION을 포함해 향후분까지 커버.)

| 현재 `market_order_no` (Cafe24 order_id) | → 전환될 값 (마켓 원본번호) | 주문자 |
|---|---|---|
| `20260708-0000011` | `4466411168` | 김우영 |
| `20260630-0000017` | `4462952064` | 씨피에스아이내담(곽금희) |
| `20260623-0000011` | `4460696482` | 씨피에스아이내담 |

## 마이그레이션 SQL

`sb_order.market_specific_data`는 TEXT(JSON 문자열)이므로 `::jsonb` 캐스팅으로 다룬다.
Postgres는 단일 UPDATE의 모든 SET 우변을 **갱신 전(pre-update) 행 값**으로 평가하므로, `market_order_no`(옛 Cafe24 id)를 `cafe24_order_id`로 보존하는 동시에 `market_order_no`를 원본번호로 덮는 것이 한 문장에서 안전하다.

```sql
-- 실행 전 백업 권장:  CREATE TABLE sb_order_bak_20260712 AS SELECT * FROM sb_order;

BEGIN;

-- 1) 전환 대상 미리보기(반드시 먼저 확인)
SELECT id, market_type, market_order_no AS old_key,
       market_specific_data::jsonb ->> 'market_order_no' AS new_key
FROM sb_order
WHERE market_type IN ('GMARKET','AUCTION')
  AND market_order_no ~ '^[0-9]{8}-[0-9]+$'
  AND COALESCE(market_specific_data::jsonb ->> 'market_order_no','') <> '';

-- 2) 재키잉 + cafe24_order_id 백필
UPDATE sb_order
SET market_specific_data = jsonb_set(
        market_specific_data::jsonb,
        '{cafe24_order_id}',
        to_jsonb(market_order_no)                    -- 옛 값(= Cafe24 order_id) 보존
    )::text,
    market_order_no = market_specific_data::jsonb ->> 'market_order_no'  -- 마켓 원본번호로 전환
WHERE market_type IN ('GMARKET','AUCTION')
  AND market_order_no ~ '^[0-9]{8}-[0-9]+$'           -- Cafe24 날짜형 키인 행만
  AND COALESCE(market_specific_data::jsonb ->> 'market_order_no','') <> '';

-- 3) 검증: 원본번호 키 + cafe24_order_id 백필 확인
SELECT id, market_order_no,
       market_specific_data::jsonb ->> 'cafe24_order_id' AS cafe24_order_id
FROM sb_order
WHERE market_type IN ('GMARKET','AUCTION');

COMMIT;   -- 결과 이상 없으면 COMMIT, 아니면 ROLLBACK;
```

## 실행 순서

1. `sb_order` 백업.
2. 위 SQL의 **1) 미리보기**로 대상 3건과 new_key(순수숫자)를 눈으로 확인.
3. **2) UPDATE** 실행 → **3) 검증**에서 `market_order_no`가 순수숫자, `cafe24_order_id`가 옛 날짜형인지 확인.
4. 이상 없으면 `COMMIT`. 그 후(또는 동시에) 앱을 `fix-live-defects-b-c-e` 병합본으로 재배포.
5. 재배포 후 다음 Cafe24 동기화 사이클에서 곽금희(=`4462952064`) 행이 **중복 없이** 갱신되고, 상태가 Cafe24 실제코드(N10 상품준비중→구매준비 등)로 반영되는지 확인.

## 롤백

`market_specific_data`에 옛 Cafe24 id가 `cafe24_order_id`로 남으므로 필요 시 역전환 가능:
```sql
UPDATE sb_order
SET market_order_no = market_specific_data::jsonb ->> 'cafe24_order_id'
WHERE market_type IN ('GMARKET','AUCTION')
  AND market_specific_data::jsonb ? 'cafe24_order_id';
```
(백업 테이블 복원이 가장 확실.)
