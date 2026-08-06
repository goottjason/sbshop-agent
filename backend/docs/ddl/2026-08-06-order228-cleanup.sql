-- D-136 데이터 교정 — 주문 228(11번가 20260731088778989)의 빈 껍데기 라인아이템 정리
--
-- 배경: D-134 배포 후 첫 동기화에서 이 주문이 2행으로 갈렸으나, 11번가가 어느 API에서도
-- sellerPrdCd를 주지 않아(2026-08-06 라이브 확인) 상품 매핑이 안 됐다. 그 결과
--   459 (레거시) — product 312, PURCHASED, 소싱 344142016, 정산 47314   ← 우리 고유 정보
--   472 (순번1)  — product NULL, NOT_PURCHASED, 정산 0                  ← 빈 껍데기
--   473 (순번2)  — product NULL, NOT_PURCHASED, 정산 0                  ← 빈 껍데기
-- 세 행이 됐다. D-136의 분할 차단 가드가 재발을 막지만, 이미 생긴 행은 자동 복구되지 않는다
-- (472·473이 정확키를 갖고 있어 다음 동기화가 그 둘을 매칭하고 459는 계속 고아로 남는다).
--
-- 교정: 빈 껍데기 둘을 지우고 459에 순번1을 직접 지정한다. 그러면 다음 동기화에서
--   459 → 정확키 매칭으로 순번1 (소싱·구매정보 유지, 상품 312 유지)
--   순번2 → 신규 생성 (마켓 실측 정산액 45,648 · 주문금액 52,800)
-- 이 된다.
--
-- 459가 순번1이라는 근거: product_id=312는 쏜리서치 Calcium Magnesium이고,
-- 라이브 orderlistall의 순번1 prdNm이 "쏜리서치 Calcium Magnesium Malate 240캡슐"이다.
--
-- 지우는 두 행에는 사람이 넣은 정보가 없다(구매·소싱 전부 비어 있음) — 확인 쿼리로 먼저 검증한다.
--
-- ⚠ 새 코드(D-136, trackingBySeq 포함)가 배포된 뒤에 실행할 것.
--   구 코드로 동기화되면 순번2가 또 빈 껍데기로 생긴다.

BEGIN;

-- 1) 지울 두 행이 정말 빈 껍데기인지 확인 (사람이 넣은 정보가 있으면 0행이 아니어야 한다)
SELECT id, market_line_item_no, product_id, purchase_status,
       sourcing_order_no, sourcing_vendor, sourcing_account, sourcing_amount, settlement_amount
FROM sb_order_line_item
WHERE id IN (472, 473);

-- 2) 안전 가드: 두 행 중 하나라도 구매·소싱 정보를 갖고 있으면 여기서 예외로 중단한다.
DO $$
DECLARE
    dirty INT;
BEGIN
    SELECT count(*) INTO dirty
    FROM sb_order_line_item
    WHERE id IN (472, 473)
      AND (purchase_status <> 'NOT_PURCHASED'
           OR sourcing_order_no IS NOT NULL
           OR sourcing_vendor IS NOT NULL
           OR sourcing_account IS NOT NULL
           OR sourcing_amount IS NOT NULL);
    IF dirty > 0 THEN
        RAISE EXCEPTION '중단: 472/473에 사람이 넣은 정보가 있다(%건). 수동 판단이 필요하다.', dirty;
    END IF;
END $$;

-- 3) 빈 껍데기 삭제
DELETE FROM sb_order_line_item WHERE id IN (472, 473);

-- 4) 레거시 행에 상품주문번호 지정 — 다음 동기화가 정확키로 매칭한다
UPDATE sb_order_line_item
SET market_line_item_no = '1',
    shipment_id = (SELECT id FROM sb_shipment
                   WHERE order_id = 228 AND market_shipment_no = '2716448228')
WHERE id = 459;

-- 5) 결과 확인 — 459 한 행만 남고 순번1·배송이 붙어 있어야 한다
SELECT id, market_line_item_no, shipment_id, product_id, purchase_status,
       sourcing_order_no, tracking_no, shipping_status, settlement_amount
FROM sb_order_line_item WHERE order_id = 228 ORDER BY id;

COMMIT;
