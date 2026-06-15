const https = require('https');
const crypto = require('crypto');

function getHmacSignature(method, path, accessKey, secretKey) {
    const datetime = new Date().toISOString().replace(/[:-]/g, '').replace(/\..+/, '') + 'Z';
    const message = datetime + method + path;
    const signature = crypto.createHmac('sha256', secretKey).update(message).digest('hex');
    return `CEA algorithm=HmacSHA256, access-key=${accessKey}, signed-date=${datetime}, signature=${signature}`;
}

async function fetchCoupang() {
    const mysql = require('mysql2/promise');
    const connection = await mysql.createConnection({
        host: 'goottjason.cafe24.com',
        user: 'goottjason',
        password: 'mimi1570!!@',
        database: 'goottjason',
        charset: 'utf8mb4'
    });

    const [creds] = await connection.query("SELECT * FROM sb_market_credential WHERE market_type = 'COUPANG'");
    await connection.end();

    if (!creds.length) { console.log("No coupang creds"); return; }
    
    const cred = creds[0];
    const vendorId = cred.client_id;
    const accessKey = cred.access_key;
    const secretKey = cred.secret_key;
    
    const fromDate = new Date(Date.now() - 30 * 86400000).toISOString().split('T')[0];
    const toDate = new Date().toISOString().split('T')[0];
    
    // Omit the status parameter
    const path = `/v2/providers/openapi/apis/api/v4/vendors/${vendorId}/ordersheets?createdAtFrom=${fromDate}&createdAtTo=${toDate}&maxPerPage=50&searchType=timeframe`;
    const auth = getHmacSignature('GET', path, accessKey, secretKey);

    const options = {
        hostname: 'api-gateway.coupang.com',
        port: 443,
        path: path,
        method: 'GET',
        headers: {
            'Authorization': auth,
            'X-Requested-By': vendorId,
            'Content-Type': 'application/json'
        }
    };

    const req = https.request(options, res => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
            console.log(JSON.stringify(JSON.parse(data), null, 2));
        });
    });
    req.on('error', e => console.error(e));
    req.end();
}

fetchCoupang();
