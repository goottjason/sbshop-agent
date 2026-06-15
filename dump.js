const mysql = require('mysql2/promise');
const fs = require('fs');

async function dump() {
  const connection = await mysql.createConnection({
    host: 'goottjason.cafe24.com',
    user: 'goottjason',
    password: 'mimi1570!!@',
    database: 'goottjason',
    charset: 'utf8mb4'
  });

  const [productsFields] = await connection.query('DESCRIBE products');
  const [sbProductFields] = await connection.query('DESCRIBE sb_product');

  console.log("=== products TABLE SCHEMA ===");
  console.log(productsFields.map(f => `${f.Field} (${f.Type})`).join(', '));
  console.log("\n=== sb_product TABLE SCHEMA ===");
  console.log(sbProductFields.map(f => `${f.Field} (${f.Type})`).join(', '));

  // Dump products to CSV
  const [rows] = await connection.query('SELECT * FROM products');
  if (rows.length > 0) {
    const fields = Object.keys(rows[0]);
    const csvRows = [fields.join(',')];
    for (const row of rows) {
      csvRows.push(fields.map(f => {
        let val = row[f];
        if (val === null || val === undefined) return '';
        if (typeof val === 'string') return `"${val.replace(/"/g, '""')}"`;
        return val;
      }).join(','));
    }
    fs.writeFileSync('products_dump.csv', csvRows.join('\n'), 'utf8');
    console.log(`\nDumped ${rows.length} rows to products_dump.csv`);
  }

  await connection.end();
}

dump().catch(console.error);
