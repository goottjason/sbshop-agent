const mysql = require('mysql2/promise');
async function run() {
  const connection = await mysql.createConnection({
    host: 'goottjason.cafe24.com',
    user: 'goottjason',
    password: 'mimi1570!!@',
    database: 'goottjason',
    charset: 'utf8mb4'
  });
  
  const [dummy] = await connection.query("SELECT * FROM sb_product WHERE sb_code = 'SB-IHERB-001';");
  console.log("Dummy product:", dummy);

  const [p1] = await connection.query("SELECT * FROM sb_product WHERE id = 1;");
  console.log("Product id 1:", p1);

  await connection.end();
}
run();
