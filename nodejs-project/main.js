'use strict';

// 最小验证版：不依赖任何第三方库，只验证「Node 能否在 APK 内跑起来」。
// 不监听端口、不做 mDNS、不需要 node_modules。
const fs = require('fs');

// Android 不把进程的 stdout 暴露给 App（只会进 logcat），所以除了 console.log，
// 再按 --log-file=<path> 把同一份内容写进文件，界面才能读到。
let logFile = null;
(process.argv || []).forEach(function (a) {
  if (typeof a === 'string' && a.indexOf('--log-file=') === 0) {
    logFile = a.slice('--log-file='.length);
  }
});

function out(line) {
  console.log(line);
  if (logFile) {
    try {
      fs.appendFileSync(logFile, line + '\n');
    } catch (e) {
      // 写不了也不影响主流程
    }
  }
}

out('hello world from node inside apk');
out('node version = ' + process.version);
out('platform = ' + process.platform + ' arch = ' + process.arch);
out('TMPDIR = ' + (process.env.TMPDIR || '(unset)'));
out('NODE_PATH = ' + (process.env.NODE_PATH || '(unset)'));
out('argv = ' + JSON.stringify(process.argv));

// 保持 Node 常驻，便于在 App 界面持续观察（每 5 秒打一行）。
let i = 0;
setInterval(function () {
  i += 1;
  out('tick ' + i + ' ' + new Date().toISOString());
}, 5000);
