-- Read only. Candidate residual listings, NOT proof of current live marketplace presence.
-- Includes legacy soft deletions whose registration identifiers were already preserved.
WITH candidates AS (
 SELECT p.id, p.sb_code, p.product_name, p.deleted_at,
        x->>'market' AS market, x->>'listingId' AS listing_id,
        x->>'reason' AS reason, x->>'recordedAt' AS recorded_at,
        'DELETE_SNAPSHOT' AS evidence
 FROM sb_product p
 CROSS JOIN LATERAL jsonb_array_elements(COALESCE(p.deletion_followup,'[]')::jsonb) x
 WHERE p.deleted_at IS NOT NULL
 UNION ALL
 SELECT p.id,p.sb_code,p.product_name,p.deleted_at,
        v.market,v.listing_id,'기존 등록 이력: 외부 삭제 확인 필요',p.deleted_at::text,'LEGACY_REGISTRATION'
 FROM sb_product p JOIN sb_market_registration r ON r.product_id=p.id
 CROSS JOIN LATERAL (VALUES
   ('ELEVEN_STREET',CASE WHEN r.market_type='ELEVEN_STREET' AND r.unsync_reason IS DISTINCT FROM 'DELETED_ON_MARKET'
     THEN COALESCE(r.market_identifiers::jsonb->>'prdNo',r.market_identifiers::jsonb->>'elevenstId') END),
   ('GMARKET',CASE WHEN r.market_type='CAFE24' AND r.gmarket_connection_state IS DISTINCT FROM 'DETACHED_DELETED'
     THEN r.market_identifiers::jsonb->>'gmarket_goodsNo' END),
   ('AUCTION',CASE WHEN r.market_type='CAFE24' AND r.auction_connection_state IS DISTINCT FROM 'DETACHED_DELETED'
     THEN r.market_identifiers::jsonb->>'auction_goodsNo' END)
 ) v(market,listing_id)
 WHERE p.deleted_at IS NOT NULL AND p.deletion_followup IS NULL AND v.listing_id IS NOT NULL
)
SELECT * FROM candidates ORDER BY deleted_at DESC,sb_code,market;
