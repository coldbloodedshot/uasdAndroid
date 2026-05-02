const fs = require('fs');
const data = JSON.parse(fs.readFileSync('db_dump.json', 'utf8'));

let stringsFound = [];
for (const nrc in data) {
    const notas = data[nrc].notas;
    if (notas) {
        for (const evalId in notas) {
            for (const matricula in notas[evalId]) {
                const val = notas[evalId][matricula];
                if (typeof val === 'string') {
                    stringsFound.push({ nrc, evalId, matricula, val });
                }
            }
        }
    }
}
console.log("STRING NOTAS:", JSON.stringify(stringsFound, null, 2));
