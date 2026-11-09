// 앱 껍데기만 캐시한다 (사진은 서명 URL 이라 캐시하지 않음). HTTPS 에서만 등록된다.
const SHELL = 'chaz-shell-v1';
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(['./', 'index.html', 'app.js', 'style.css', 'icon.svg'])));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.pathname.startsWith('/sync/')) return; // API·이벤트·사진은 네트워크로
  e.respondWith(caches.match(e.request).then((r) => r || fetch(e.request)));
});
