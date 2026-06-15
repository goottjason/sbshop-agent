const mysql = require('mysql2/promise');

async function checkVendors() {
  const connection = await mysql.createConnection({
    host: 'goottjason.cafe24.com',
    user: 'goottjason',
    password: 'mimi1570!!@',
    database: 'goottjason'
  });

  const [rows] = await connection.query('SELECT DISTINCT vendor FROM sb_product');
  console.log("Unique vendors in DB:", rows.map(r => r.vendor));
  await connection.end();
}

checkVendors().catch(console.error);
