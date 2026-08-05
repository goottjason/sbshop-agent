-- 묶음배송·다품목 주문 모델 1단계 (설계: docs/superpowers/specs/2026-08-05-bundle-shipment-order-model-design.md)
--
-- ddl-auto=update가 테이블·컬럼은 만들지만 UNIQUE 제약은 만들지 않는다.
-- 배포 전에 이 스크립트를 먼저 적용한다.
--
-- 안전성: 신설 테이블 1개 + nullable 컬럼 2개. 기존 행은 두 컬럼이 NULL로 남고,
-- PostgreSQL은 UNIQUE 인덱스에서 NULL끼리 충돌로 보지 않으므로 기존 데이터에 영향이 없다.

CREATE TABLE IF NOT EXISTS sb_shipment (
    id                      BIGSERIAL PRIMARY KEY,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMP,
    updated_at              TIMESTAMP,
    order_id                BIGINT       NOT NULL,
    market_shipment_no      VARCHAR(100) NOT NULL,
    tracking_no             VARCHAR(100),
    shipping_carrier        VARCHAR(30),
    delivery_status         VARCHAR(30),
    tracking_sent_to_market BOOLEAN,
    shipped_at              TIMESTAMP,
    CONSTRAINT uk_shipment_order_market_no UNIQUE (order_id, market_shipment_no)
);

CREATE INDEX IF NOT EXISTS ix_shipment_order_id ON sb_shipment (order_id);

ALTER TABLE sb_order_line_item ADD COLUMN IF NOT EXISTS market_line_item_no VARCHAR(100);
ALTER TABLE sb_order_line_item ADD COLUMN IF NOT EXISTS shipment_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_line_item_order_market_no
    ON sb_order_line_item (order_id, market_line_item_no);

CREATE INDEX IF NOT EXISTS ix_line_item_shipment_id ON sb_order_line_item (shipment_id);
