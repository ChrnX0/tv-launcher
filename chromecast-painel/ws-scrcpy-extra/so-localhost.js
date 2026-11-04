// Pré-carga do Node para o ws-scrcpy (04/11/2026): faz o servidor dele escutar SÓ em 127.0.0.1.
//
// Por quê: o ws-scrcpy (porta 8000) mostra a tela da TV e repassa comandos adb (proxy-adb), sem senha, e o
// HttpServer dele chama server.listen(porta) sem endereço, ou seja, em TODAS as interfaces. Medido: escutava em
// "::" e o firewall do Windows tem uma regra "Node.js" liberando entrada (perfis Privado e Público). Qualquer
// aparelho da rede (ou de uma rede pública, num notebook) via a tela e mandava comandos para a TV.
//
// Como: em vez de mexer no código do ws-scrcpy (decisão do Paulo: o repositório dele fica intacto), este arquivo é
// carregado antes (`node -r <este arquivo> dist/index.js`, no start.ps1 do ws-scrcpy) e acrescenta o host
// 127.0.0.1 a todo listen(porta[, callback]) que não diz o endereço. Quem já passa um host continua como está.
'use strict';
const net = require('net');
const original = net.Server.prototype.listen;
net.Server.prototype.listen = function (...args) {
    if (typeof args[0] === 'number' && (args.length === 1 || typeof args[1] === 'function')) {
        args.splice(1, 0, '127.0.0.1');
    } else if (args[0] && typeof args[0] === 'object' && !Array.isArray(args[0]) && args[0].port !== undefined && !args[0].host && !args[0].path) {
        args[0] = Object.assign({}, args[0], { host: '127.0.0.1' });
    }
    return original.apply(this, args);
};
