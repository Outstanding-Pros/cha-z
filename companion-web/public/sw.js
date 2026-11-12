// 앱 껍데기만 캐시한다 (사진·카메라 프레임은 캐시하지 않음). HTTPS 에서만 등록된다.
// 네트워크 우선: 서버가 켜져 있으면 항상 최신 app.js, 끊겼을 때만 캐시로 화면을 띄운다.
const SHELL = 'chaz-shell-v4';
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(['./', 'index.html', 'app.js', 'style.css', 'icon.svg'])));
  self.skipWaiting();
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== SHELL).map((k) => caches.delete(k)))));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.pathname.startsWith('/sync/') || url.pathname.startsWith('/s/')) return;
  e.respondWith(
    fetch(e.request)
      .then((r) => {
        const copy = r.clone();
        caches.open(SHELL).then((c) => c.put(e.request, copy)).catch(() => {});
        return r;
      })
      .catch(() => caches.match(e.request, { ignoreSearch: true })),
  );
});
