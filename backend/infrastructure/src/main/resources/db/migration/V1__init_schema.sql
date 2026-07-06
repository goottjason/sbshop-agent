-- V1 Init Schema (PostgreSQL)

CREATE TABLE IF NOT EXISTS sb_product (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    
    sb_code VARCHAR(50) NOT NULL UNIQUE,
    category VARCHAR(50),
    vendor VARCHAR(50),
    barcode VARCHAR(100),
    
    brand VARCHAR(100),
    original_name VARCHAR(255),
    base_name VARCHAR(255),
    product_name VARCHAR(255),
    
    capacity DECIMAL(10,2),
    measure_unit VARCHAR(20),
    weight DECIMAL(10,2),
    bundle_quantity INT,
    
    cost_price DECIMAL(10,2),
    exchange_rate DECIMAL(10,4),
    margin_rate DECIMAL(5,2),
    sale_price DECIMAL(10,2),
    
    sourcing_url VARCHAR(1000),
    manufacturer VARCHAR(100),
    origin VARCHAR(100),
    hs_code VARCHAR(50),
    stock INT,
    
    source_images TEXT,
    hosted_images TEXT,
    search_keywords VARCHAR(500),
    detail_html TEXT,
    memo VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS sb_order (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    
    market_type VARCHAR(50) NOT NULL,
    market_order_no VARCHAR(100) NOT NULL UNIQUE,
    order_date TIMESTAMP NOT NULL,
    buyer_name VARCHAR(100),
    buyer_phone VARCHAR(50),
    zipcode VARCHAR(20),
    address VARCHAR(500),
    message VARCHAR(1000),
    
    sourcing_account VARCHAR(100),
    sourcing_order_no VARCHAR(100),
    sourcing_amount DECIMAL(10,2),
    discount_code VARCHAR(50),
    
    customs_clearance_no VARCHAR(50),
    customs_status VARCHAR(20),
    
    tracking_no VARCHAR(100),
    is_unipass_done BOOLEAN,
    shipping_status VARCHAR(20)
);

CREATE TABLE IF NOT EXISTS sb_order_line_item (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    
    order_id BIGINT NOT NULL,
    product_id BIGINT,
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2),
    
    CONSTRAINT fk_line_item_order FOREIGN KEY (order_id) REFERENCES sb_order(id),
    CONSTRAINT fk_line_item_product FOREIGN KEY (product_id) REFERENCES sb_product(id)
);

CREATE TABLE IF NOT EXISTS sb_fee_policy (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    
    market_type VARCHAR(50) NOT NULL,
    category_name VARCHAR(100) NOT NULL,
    fee_rate DECIMAL(5,2) NOT NULL
);
