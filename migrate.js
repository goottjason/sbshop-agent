const mysql = require('mysql2/promise');

async function migrate() {
  const connection = await mysql.createConnection({
    host: 'goottjason.cafe24.com',
    user: 'goottjason',
    password: 'mimi1570!!@',
    database: 'goottjason',
    charset: 'utf8mb4'
  });

  try {
    // Optional: Clear existing sb_product table if needed, but let's avoid it unless requested.
    // await connection.query('TRUNCATE TABLE sb_product');

    console.log("Starting migration from 'products' to 'sb_product'...");
    const [result] = await connection.query(`
      INSERT INTO sb_product (
          id, status, created_at, updated_at, sb_code, category, vendor, barcode, brand, 
          original_name, base_name, product_name, capacity, measure_unit, weight, bundle_quantity, 
          cost_price, exchange_rate, margin_rate, sale_price, sourcing_url, manufacturer, origin, 
          hs_code, stock, source_images, hosted_images, search_keywords, detail_html, memo, 
          stock_status, restock_date
      )
      SELECT 
          id, status, created_at, updated_at, sku, category, vendor, barcode, brand, 
          original_name, base_name, name, capacity, measure_unit, weight, bundle_quantity, 
          cost_price, exchange_rate, margin_rate, sale_price, source_url, manufacturer, origin, 
          hs_code, stock, source_images, hosted_images, search_keywords, detail_html, memo, 
          'IN_STOCK', NULL
      FROM products
      ON DUPLICATE KEY UPDATE 
          status=VALUES(status), updated_at=VALUES(updated_at), sb_code=VALUES(sb_code),
          product_name=VALUES(product_name), sale_price=VALUES(sale_price),
          stock=VALUES(stock);
    `);
    
    console.log(`Migration successful! Inserted/Updated ${result.affectedRows} rows.`);

    const [count] = await connection.query('SELECT COUNT(*) as cnt FROM sb_product');
    console.log(`Total rows in sb_product: ${count[0].cnt}`);

  } catch (error) {
    console.error("Migration failed:", error);
  } finally {
    await connection.end();
  }
}

migrate().catch(console.error);
