'use strict';

// 局域网发现：mDNS（_couchdb._tcp）宣告自身 + 发现对端。
// 服务类型与 TXT 字段与 pouchdb-server-apk 完全一致（service / version / instance /
// device_id / http_port），因此手机端 App 无需改动即可在「局域网设备」里看到本服务，
// 本服务也能发现手机上的实例。额外带的 scheme 字段 apk 会忽略，向后兼容。
//
// mDNS 可用性不是服务的前提：组播被禁、库缺失、端口占用都只降级（不宣告/不发现），
// 不影响 CouchDB 兼容接口本身。

const os = require('os');

// bonjour-service 是纯 JS 实现（无原生编译），缺失时整体降级而不是让服务起不来。
let BonjourCtor = null;
try {
  const mod = require('bonjour-service');
  BonjourCtor = mod.Bonjour || mod;
} catch (e) {
  BonjourCtor = null;
}

const SERVICE_TYPE = 'couchdb';
const SERVICE_PROTOCOL = 'tcp';
const SERVICE_VERSION = '1.0';

function localIpv4List() {
  const out = [];
  const ifaces = os.networkInterfaces() || {};
  Object.keys(ifaces).forEach((name) => {
    (ifaces[name] || []).forEach((ni) => {
      if (ni && ni.family === 'IPv4' && !ni.internal) out.push(ni.address);
    });
  });
  return out;
}

class Discovery {
  /**
   * @param {object} opts { instance, port, scheme, deviceId, instanceName }
   */
  constructor(opts) {
    this.opts = opts || {};
    this.peers = new Map();  // key -> peer
    this.available = !!BonjourCtor;
    this.error = BonjourCtor ? '' : 'bonjour-service 未安装，mDNS 已降级';
    this.registered = false;
    this._bonjour = null;
    this._service = null;
    this._browser = null;
    this.localIps = localIpv4List();
  }

  start() {
    if (!this.available) return { ok: false, error: this.error };
    try {
      // bonjour-service 默认的 errorCallback 是 `throw err`：在组播不可达（VPN / 虚拟网卡 /
      // 受限网络）时会抛未捕获异常直接拖垮进程。这里改为安全降级——只记录一次并停止 mDNS。
      let errored = false;
      const errorCallback = (err) => {
        if (errored) return;
        errored = true;
        const msg = (err && (err.message || err.code)) ? (err.message || err.code) : String(err);
        this.available = false;
        this.error = msg + '（mDNS 已降级）';
        console.error('[mdns] 多播不可用，发现功能降级: ' + msg);
        this.stop();
      };
      this._bonjour = new BonjourCtor({}, errorCallback);
      this._publish();
      this._browse();
      return { ok: true };
    } catch (e) {
      this.available = false;
      this.error = e && e.message ? e.message : String(e);
      return { ok: false, error: this.error };
    }
  }

  stop() {
    try {
      if (this._browser) this._browser.stop();
      if (this._bonjour) this._bonjour.destroy();
    } catch (e) { /* 退出阶段的清理失败无需处理 */ }
    this._browser = null;
    this._service = null;
    this._bonjour = null;
    this.registered = false;
  }

  _publish() {
    const o = this.opts;
    this._service = this._bonjour.publish({
      name: o.instanceName || o.instance || 'pouchdb-server',
      type: SERVICE_TYPE,
      protocol: SERVICE_PROTOCOL,
      port: Number(o.port),
      txt: {
        service: 'pouchdb-server',
        version: SERVICE_VERSION,
        instance: o.instance || '',
        device_id: o.deviceId || '',
        http_port: String(o.port),
        scheme: o.scheme || 'http',
      },
    });
    this._service.on('up', () => { this.registered = true; });
    this._service.on('error', (err) => {
      this.error = err && err.message ? err.message : String(err);
    });
    this.registered = true;
  }

  _browse() {
    this._browser = this._bonjour.find({ type: SERVICE_TYPE, protocol: SERVICE_PROTOCOL });
    this._browser.on('up', (svc) => this._upsert(svc));
    this._browser.on('down', (svc) => this._remove(svc));
  }

  _ipsOf(svc) {
    const addrs = (svc && svc.addresses) || [];
    const v4 = addrs.filter((a) => typeof a === 'string' && a.indexOf(':') < 0);
    return v4.length ? v4 : addrs;
  }

  _isSelf(svc) {
    const deviceId = txtOf(svc, 'device_id');
    if (deviceId && this.opts.deviceId && deviceId === this.opts.deviceId) return true;
    const port = Number(svc && svc.port);
    if (port && port === Number(this.opts.port)) {
      return this._ipsOf(svc).some((ip) => this.localIps.indexOf(ip) >= 0);
    }
    return false;
  }

  _upsert(svc) {
    if (!svc || this._isSelf(svc)) return;
    const ips = this._ipsOf(svc);
    if (!ips.length) return;
    const host = ips[0];
    const deviceId = txtOf(svc, 'device_id');
    const instance = txtOf(svc, 'instance') || svc.name || '';
    const key = deviceId || (instance + '@' + host + ':' + svc.port);
    this.peers.set(key, {
      key,
      deviceId: deviceId || '',
      instance: instance || host,
      host,
      addresses: ips,
      port: Number(svc.port),
      scheme: txtOf(svc, 'scheme') || 'http',
      version: txtOf(svc, 'version') || '',
      discoveredAt: Date.now(),
    });
  }

  _remove(svc) {
    if (!svc) return;
    const ips = this._ipsOf(svc);
    const deviceId = txtOf(svc, 'device_id');
    const instance = txtOf(svc, 'instance') || svc.name || '';
    const key = deviceId || (instance + '@' + (ips[0] || '') + ':' + svc.port);
    this.peers.delete(key);
  }

  list() {
    return Array.from(this.peers.values()).sort((a, b) => a.instance.localeCompare(b.instance));
  }
}

// TXT 在不同实现里可能是 Buffer / 字符串 / 对象，统一取字符串
function txtOf(svc, key) {
  try {
    const txt = svc && svc.txt;
    if (!txt) return '';
    const v = typeof txt.get === 'function' ? txt.get(key) : txt[key];
    if (v == null) return '';
    if (Buffer.isBuffer(v)) return v.toString('utf8');
    if (typeof v === 'object') return String(v);
    return String(v);
  } catch (e) {
    return '';
  }
}

module.exports = { Discovery, SERVICE_TYPE, SERVICE_PROTOCOL, localIpv4List };
