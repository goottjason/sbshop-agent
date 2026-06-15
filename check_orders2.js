const mysql = require('mysql2/promise');
async function run() {
  const connection = await mysql.createConnection({
    host: 'goottjason.cafe24.com',
    user: 'goottjason',
    password: 'mimi1570!!@',
    database: 'goottjason',
    charset: 'utf8mb4'
  });
  
  const [rows] = await connection.query("SELECT id, market_order_no, market_product_name, product_id FROM sb_order_line_item LIMIT 5;");
  console.log(rows);
  await connection.end();
}
run();
