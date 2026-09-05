-- 읽기 전용 조사. 숫자 일치는 검토 단서이며 단위 확인이나 자동 변환 승인이 아니다.
-- 초안이 나중에 수정됐을 수 있어 원본/게시 시점/상품 변경 기록을 대조해야 한다.
-- 실행 결과에 상품 원본 URL이 포함된다. 검토 대상 상품 자료로만 사용한다.
BEGIN TRANSACTION READ ONLY;

SELECT table_schema, numeric_precision, numeric_scale
FROM information_schema.columns
WHERE table_name = 'sb_product' AND column_name = 'weight';

SELECT p.id AS product_id, p.sb_code, p.vendor, p.source_url,
       p.weight AS current_value_unit_unverified,
       p.created_at AS product_created_at, p.updated_at AS product_updated_at,
       d.id AS draft_id, d.weight_g AS draft_weight_g,
       d.weight_g / 1000 AS draft_weight_kg,
       d.source_url AS draft_source_url, d.updated_at AS draft_updated_at,
       CASE
         WHEN p.weight IS NULL OR p.weight <= 0 THEN 'NO_USABLE_WEIGHT'
         WHEN d.id IS NULL THEN 'NO_LINKED_DRAFT'
         WHEN p.weight = d.weight_g AND d.weight_g > 0 THEN 'MATCHES_DRAFT_GRAMS_REVIEW_REQUIRED'
         WHEN p.weight = d.weight_g / 1000 AND d.weight_g > 0 THEN 'MATCHES_DRAFT_KG_REVIEW_REQUIRED'
         ELSE 'UNIT_REVIEW_REQUIRED'
       END AS review_hint
FROM sb_product p
LEFT JOIN sb_product_draft d ON d.product_id = p.id
WHERE p.deleted_at IS NULL
ORDER BY p.sb_code, p.id, d.id;

COMMIT;
