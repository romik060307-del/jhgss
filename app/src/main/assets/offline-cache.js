/*
 * OHLI Android client: local read cache for small, authenticated JSON API responses.
 * This deliberately does not cache media/once-view content or queue mutations.
 */
(function () {
  'use strict';
  if (window.__ohliClientBridgeInstalled) return;
  window.__ohliClientBridgeInstalled = true;

  var SERVER = (window.OHLIAndroid && window.OHLIAndroid.getServerUrl) ? window.OHLIAndroid.getServerUrl() : 'https://bonds-kick-graduates-include.trycloudflare.com/';
  SERVER = String(SERVER || '').replace(/\/$/, '');
  var DB_NAME = 'ohli-social-client-cache-v1';
  var DB_STORE = 'responses';
  var MAX_CACHE_ENTRIES = 80;
  var MAX_CACHE_BYTES = 20 * 1024 * 1024;
  var MAX_RESPONSE_BYTES = 1200000;
  var dbPromise;
  var nativeFetch = window.fetch.bind(window);

  function absoluteApiUrl(value) {
    if (typeof value !== 'string') return value;
    if (value.indexOf('/api/') === 0 || value === '/api') return SERVER + value;
    return value;
  }

  function openDb() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise(function (resolve, reject) {
      try {
        var req = indexedDB.open(DB_NAME, 1);
        req.onupgradeneeded = function () {
          var db = req.result;
          if (!db.objectStoreNames.contains(DB_STORE)) db.createObjectStore(DB_STORE, { keyPath: 'key' });
        };
        req.onsuccess = function () { resolve(req.result); };
        req.onerror = function () { reject(req.error || new Error('IndexedDB tidak tersedia')); };
      } catch (e) { reject(e); }
    });
    return dbPromise;
  }

  function tokenHash(token) {
    var h = 2166136261;
    token = String(token || '');
    for (var i = 0; i < token.length; i++) { h ^= token.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0).toString(16);
  }

  function cacheKey(url, token) {
    return tokenHash(token) + '|' + url.pathname + url.search;
  }

  function isCacheable(url) {
    var p = url.pathname;
    if (!p.startsWith('/api/')) return false;
    if (/\/(image|media|voice|avatar|social-avatar)(\/|$)/i.test(p)) return false;
    if (/\/once(?:\/|$)/i.test(p) || /onceKey=/i.test(url.search)) return false;
    if (/\/(auth|performance)(\/|$)/i.test(p)) return false;
    if (/\/(calls|live)\/(signals|invite|heartbeat|join|start|end)(\/|$)/i.test(p)) return false;
    return true;
  }

  async function readCached(key) {
    var db = await openDb();
    return new Promise(function (resolve) {
      try {
        var tx = db.transaction(DB_STORE, 'readonly');
        var req = tx.objectStore(DB_STORE).get(key);
        req.onsuccess = function () { resolve(req.result || null); };
        req.onerror = function () { resolve(null); };
      } catch (e) { resolve(null); }
    });
  }

  async function writeCached(record) {
    var db = await openDb();
    await new Promise(function (resolve, reject) {
      try {
        var tx = db.transaction(DB_STORE, 'readwrite');
        tx.objectStore(DB_STORE).put(record);
        tx.oncomplete = resolve;
        tx.onerror = function () { reject(tx.error || new Error('Cache gagal disimpan')); };
      } catch (e) { reject(e); }
    });
    // Keep total cached JSON under 20 MB; media is never copied into this store.
    try {
      var all = await new Promise(function (resolve) {
        var tx = db.transaction(DB_STORE, 'readonly');
        var req = tx.objectStore(DB_STORE).getAll();
        req.onsuccess = function () { resolve(req.result || []); };
        req.onerror = function () { resolve([]); };
      });
      all.sort(function (a, b) { return (a.savedAt || 0) - (b.savedAt || 0); });
      var total = all.reduce(function (sum, item) { return sum + (Number(item.bytes) || String(item.body || '').length); }, 0);
      var remove = [];
      while (all.length - remove.length > MAX_CACHE_ENTRIES || total > MAX_CACHE_BYTES) {
        var item = all[remove.length];
        if (!item) break;
        remove.push(item);
        total -= Number(item.bytes) || String(item.body || '').length;
      }
      if (remove.length) await new Promise(function (resolve) {
        var tx = db.transaction(DB_STORE, 'readwrite');
        remove.forEach(function (item) { tx.objectStore(DB_STORE).delete(item.key); });
        tx.oncomplete = resolve; tx.onerror = resolve;
      });
    } catch (e) { /* cache cleanup is best-effort */ }
  }

  function requestParts(input, init) {
    var originalUrl = input instanceof Request ? input.url : String(input);
    var url = new URL(originalUrl, location.href);
    if (url.origin === location.origin && url.pathname.indexOf('/api/') === 0) {
      url = new URL(SERVER + url.pathname + url.search + url.hash);
    }
    var method = String((init && init.method) || (input instanceof Request ? input.method : 'GET')).toUpperCase();
    var headers = new Headers(input instanceof Request ? input.headers : undefined);
    if (init && init.headers) new Headers(init.headers).forEach(function (v, k) { headers.set(k, v); });
    return { url: url, method: method, headers: headers };
  }

  window.fetch = async function (input, init) {
    var parts;
    try { parts = requestParts(input, init); } catch (e) { return nativeFetch(input, init); }
    var rewrittenInput = input;
    if (input instanceof Request) {
      try { rewrittenInput = new Request(parts.url.toString(), input); } catch (e) { rewrittenInput = parts.url.toString(); }
    } else if (typeof input === 'string' || input instanceof URL) {
      rewrittenInput = parts.url.toString();
    }

    var eligible = parts.method === 'GET' && isCacheable(parts.url);
    var key = eligible ? cacheKey(parts.url, parts.headers.get('X-RKM-Token') || parts.url.searchParams.get('token') || '') : '';
    try {
      var response = await nativeFetch(rewrittenInput, init);
      if (eligible && response.ok && (response.headers.get('content-type') || '').indexOf('json') >= 0) {
        // Save after returning the live response path so cache I/O does not slow normal scrolling/API use.
        (async function () {
          try {
            var body = await response.clone().text();
            if (body.length <= MAX_RESPONSE_BYTES) {
              await writeCached({ key: key, body: body, bytes: body.length, savedAt: Date.now(), contentType: response.headers.get('content-type') || 'application/json; charset=utf-8' });
              if (parts.url.pathname === '/api/notifications' && window.OHLIAndroid) {
                try {
                  var obj = JSON.parse(body), items = obj.notifications || [];
                  var unread = items.filter(function (n) { return n && !n.read; }).length;
                  window.OHLIAndroid.onSocialUnreadCount(unread);
                } catch (ignore) { }
              }
            }
          } catch (ignore) { }
        })();
      }
      if (eligible && response.status >= 500) {
        var stale = await readCached(key);
        if (stale) return new Response(stale.body, { status: 200, headers: { 'Content-Type': stale.contentType, 'X-OHLI-Offline-Cache': '1' } });
      }
      return response;
    } catch (networkError) {
      if (eligible) {
        var cached = await readCached(key);
        if (cached) return new Response(cached.body, { status: 200, headers: { 'Content-Type': cached.contentType, 'X-OHLI-Offline-Cache': '1' } });
      }
      throw networkError;
    }
  };

  // Media elements use URL attributes rather than fetch(). Rewrite API URLs to the live server.
  var originalSetAttribute = Element.prototype.setAttribute;
  Element.prototype.setAttribute = function (name, value) {
    var n = String(name || '').toLowerCase();
    if ((n === 'src' || n === 'href' || n === 'poster' || n === 'action') && typeof value === 'string') value = absoluteApiUrl(value);
    return originalSetAttribute.call(this, name, value);
  };

  [['HTMLImageElement', 'src'], ['HTMLVideoElement', 'src'], ['HTMLAudioElement', 'src'], ['HTMLSourceElement', 'src'], ['HTMLAnchorElement', 'href'], ['HTMLFormElement', 'action']].forEach(function (entry) {
    try {
      var C = window[entry[0]], proto = C && C.prototype, desc = proto && Object.getOwnPropertyDescriptor(proto, entry[1]);
      if (desc && desc.get && desc.set && desc.configurable) {
        Object.defineProperty(proto, entry[1], {
          configurable: true, enumerable: desc.enumerable,
          get: desc.get,
          set: function (value) { return desc.set.call(this, absoluteApiUrl(value)); }
        });
      }
    } catch (ignore) { }
  });

  // Some HTML is inserted with innerHTML; catch any API media URLs created that way.
  try {
    var observer = new MutationObserver(function (records) {
      records.forEach(function (record) {
        if (record.type === 'attributes' && record.target && record.attributeName) {
          var val = record.target.getAttribute(record.attributeName);
          var rewritten = absoluteApiUrl(val);
          if (rewritten !== val) originalSetAttribute.call(record.target, record.attributeName, rewritten);
        }
        (record.addedNodes || []).forEach(function (node) {
          if (node.nodeType !== 1) return;
          var nodes = [node];
          if (node.querySelectorAll) nodes = nodes.concat(Array.prototype.slice.call(node.querySelectorAll('[src^="/api/"],[href^="/api/"],[poster^="/api/"]')));
          nodes.forEach(function (el) {
            ['src', 'href', 'poster'].forEach(function (attr) {
              var val = el.getAttribute && el.getAttribute(attr), rewritten = absoluteApiUrl(val);
              if (rewritten && rewritten !== val) originalSetAttribute.call(el, attr, rewritten);
            });
          });
        });
      });
    });
    observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'href', 'poster'] });
  } catch (ignore) { }

  window.open = function (url) {
    if (url) { try { location.href = new URL(String(url), location.href).toString(); } catch (e) { } }
    return window;
  };
  window.__OHLI_CLIENT_OFFLINE_CACHE__ = true;
})();
