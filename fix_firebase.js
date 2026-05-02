const fs = require('fs');
const https = require('https');
const data = JSON.parse(fs.readFileSync('db_dump.json', 'utf8'));

let fixes = {};

for (const nrc in data) {
    const notas = data[nrc].notas;
    if (notas) {
        for (const evalId in notas) {
            for (const matricula in notas[evalId]) {
                const val = notas[evalId][matricula];
                if (typeof val === 'string') {
                    const num = parseFloat(val);
                    if (!isNaN(num)) {
                        if (!fixes[nrc]) fixes[nrc] = {};
                        if (!fixes[nrc][evalId]) fixes[nrc][evalId] = {};
                        fixes[nrc][evalId][matricula] = num;
                    }
                }
            }
        }
    }
}

for (const nrc in fixes) {
    for (const evalId in fixes[nrc]) {
        const payload = JSON.stringify(fixes[nrc][evalId]);
        console.log(`Fixing for NRC ${nrc}, Eval ${evalId}:`, payload);
        
        const req = https.request({
            hostname: 'uasdmain-default-rtdb.firebaseio.com',
            path: `/seccion_detalles/${nrc}/notas/${evalId}.json`,
            method: 'PATCH',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload)
            }
        }, (res) => {
            console.log(`STATUS: ${res.statusCode}`);
        });
        
        req.on('error', (e) => {
            console.error(`problem with request: ${e.message}`);
        });
        
        req.write(payload);
        req.end();
    }
}
