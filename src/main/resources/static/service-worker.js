'use strict';

/*
 * PWA 外壳（docs/19）。
 *
 * 缓存红线：/api/、非 GET、带 Authorization、带 Range、跨域请求一律不拦截、不缓存。
 * 页面缓存只存"骨架"；个人数据始终来自 /api/，断网时由 band-core.js 显示既有错误提示。
 * 改缓存策略或预缓存清单时必须同时改 VERSION，否则旧缓存不会被清掉。
 */

const VERSION = 'v9';
const PAGES_CACHE = 'band-pages-' + VERSION;
const ASSETS_CACHE = 'band-assets-' + VERSION;
const OWN_PREFIXES = ['band-pages-', 'band-assets-'];
const OFFLINE_URL = '/offline.html';
const NAV_TIMEOUT_MS = 4000;
const PRECACHE = [
  '/offline.html',
  '/manifest.json',
  '/icons/pwa-rounded-261/icon-192.png',
  '/icons/pwa-rounded-261/icon-512.png',
  '/icons/pwa-252/icon-192.png',
  '/icons/pwa-252/icon-512.png',
  '/icons/pwa-252/icon-maskable-512.png',
  '/icons/pwa-252/apple-touch-icon-180.png',
  '/icons/pwa-252/favicon-32.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(ASSETS_CACHE);
    // 某一项失败不阻断激活：那个资源离线不可用，其余照常
    await Promise.allSettled(PRECACHE.map((url) => cache.add(url)));
    // 规格硬要求：绝不在这里 skipWaiting，等用户点「刷新」
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keep = new Set([PAGES_CACHE, ASSETS_CACHE]);
    const keys = await caches.keys();
    await Promise.all(keys
      .filter((key) => OWN_PREFIXES.some((prefix) => key.startsWith(prefix)) && !keep.has(key))
      .map((key) => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

function isCacheable(response) {
  return !!response && response.ok && response.type === 'basic' && !response.redirected;
}

function navigationKey(request) {
  const url = new URL(request.url);
  return url.origin + url.pathname;
}

async function networkFirst(event, request) {
  const key = navigationKey(request);
  const network = fetch(request).then(async (response) => {
    if (isCacheable(response)) {
      try {
        const cache = await caches.open(PAGES_CACHE);
        await cache.put(key, response.clone());
      } catch (e) {
        // 缓存写失败（配额等）不能连累本次响应
      }
    }
    return response;
  });
  // 超时后网络仍在跑：让它继续，成功时照常写回缓存
  event.waitUntil(network.catch(() => {}));

  let timer;
  try {
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('navigation timeout')), NAV_TIMEOUT_MS);
    });
    return await Promise.race([network, timeout]);
  } catch (e) {
    const cached = await caches.match(key);
    if (cached) return cached;
    return (await caches.match(OFFLINE_URL)) || Response.error();
  } finally {
    clearTimeout(timer);
  }
}

async function staleWhileRevalidate(event, request) {
  const network = fetch(request).then(async (response) => {
    if (isCacheable(response)) {
      try {
        const cache = await caches.open(ASSETS_CACHE);
        await cache.put(request, response.clone());
      } catch (e) {
        // 缓存写失败不能连累本次响应
      }
    }
    return response;
  });
  event.waitUntil(network.catch(() => {}));

  const cache = await caches.open(ASSETS_CACHE);
  const cached = await cache.match(request);
  if (cached) return cached;
  try {
    return await network;
  } catch (e) {
    return Response.error();
  }
}

self.addEventListener('fetch', (event) => {
  const request = event.request;

  // —— 先放行：这些请求绝不拦截、绝不缓存 ——
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/')) return;
  // 安装包与版本清单必须每次问服务器，不能被页面缓存拖成旧版
  if (url.pathname.startsWith('/app/')) return;
  if (request.headers.has('Authorization') || request.headers.has('Range')) return;

  // —— 同源页面导航：网络优先，4 秒/失败回落页面缓存 ——
  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(event, request));
    return;
  }

  // —— 同源 CSS / JS / 字体 / 图片 / 音频：先给缓存，同时后台更新 ——
  // `/sounds/` 是节拍器兼容模式的点击音；媒体元素的 destination 可能是 audio，也可能为空，路径前缀兜底。
  if (url.pathname.startsWith('/sounds/')
      || ['style', 'script', 'font', 'image', 'audio'].includes(request.destination)) {
    event.respondWith(staleWhileRevalidate(event, request));
    return;
  }

  // 其他同源 GET 直接走网络
});
