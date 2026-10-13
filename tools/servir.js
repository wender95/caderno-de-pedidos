// Servidor de teste: entrega app/assets e troca o firebase-config.js por um que usa o emulador local.
// Uso: node tools/servir.js   (emulador: npx firebase emulators:start, dentro de tools/)
const http = require("http"), fs = require("fs"), path = require("path");
const RAIZ = path.join(__dirname, "..", "app", "assets");
const TIPOS = { ".html": "text/html; charset=utf-8", ".js": "application/javascript", ".json": "application/json" };
const CONFIG_TESTE = `window.FIREBASE_CONFIG={apiKey:"teste",authDomain:"demo-pedidos.firebaseapp.com",projectId:"demo-pedidos"};window.FIREBASE_EMULADOR=true;`;

http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split("?")[0]);
  if (url === "/firebase-config.js") { res.writeHead(200, { "Content-Type": TIPOS[".js"] }); return res.end(CONFIG_TESTE); }
  const arq = path.join(RAIZ, url === "/" ? "index.html" : url);
  if (!arq.startsWith(RAIZ) || !fs.existsSync(arq)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { "Content-Type": TIPOS[path.extname(arq)] || "application/octet-stream" });
  fs.createReadStream(arq).pipe(res);
}).listen(5055, () => console.log("Servindo em http://localhost:5055"));
