'use strict';

// 最小验证版：不依赖任何第三方库，只验证「Node 能否在 APK 内作为独立进程跑起来」。
// 不监听端口、不做 mDNS、不需要 node_modules —— 仅为确认原生运行时可用。
console.log('hello world from node inside apk');
console.error('stderr ok: node is running as a separate process');
console.log('node version = ' + process.version);
console.log('platform = ' + process.platform + ' arch = ' + process.arch);

// 保持进程常驻，便于在 App 的「Node 输出」里持续观察（每 5 秒打一行）。
let i = 0;
setInterval(function () {
  i += 1;
  console.log('tick ' + i + ' ' + new Date().toISOString());
}, 5000);
