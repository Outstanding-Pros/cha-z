// 차~즈 폰 컴패니언 (P1 페어링 · P2 받은 사진 · P4 저장·공유). 의존성 없는 바닐라 JS.
// 차량과는 서버(/sync/*)를 통해서만 주고받는다. 실시간 = SSE(/sync/events).
(() => {
  const $ = (id) => document.getElementById(id);
  const KEY_PHONE = 'chaz.phoneDeviceId';
  const KEY_CAR = 'chaz.carDeviceId';

  function storage(k, v) {
    try {
      if (v === undefined) return localStorage.getItem(k);
      localStorage.setItem(k, v);
    } catch (e) { /* 비공개 모드 등 */ }
    return v ?? null;
  }

  function uuid() {
    if (crypto.randomUUID) return crypto.randomUUID();
    return 'p-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  }

  const phoneId = storage(KEY_PHONE) || storage(KEY_PHONE, uuid());

  function show(id) {
    for (const s of ['p1', 'unpaired', 'p2']) $(s).hidden = s !== id;
  }

  function status(text, ok) {
    $('status').textContent = text;
    $('status').classList.toggle('ok', !!ok);
  }

  let toastTimer;
  function toast(text) {
    $('toast').textContent = text;
    $('toast').hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3500);
  }

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      ...opts,
      headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
    });
    let body = null;
    try { body = await res.json(); } catch (e) { /* 본문 없음 */ }
    if (!res.ok) throw new Error((body && body.detail) || ('HTTP ' + res.status));
    return body;
  }

  const LAYOUT = { WINDOW_SWAP: '차창 합성', CUT_FRAME: '컷형 프레임', ROUTE_OVERLAY: '여행 경로', SURROUND_SEQUENCE: '서라운드' };

  function label(r) {
    const d = new Date(r.createdAt * 1000);
    const when = d.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    const sim = (r.sourceKinds || []).includes('RECORDED_VIDEO') ? ' · 시뮬레이션' : '';
    return `${LAYOUT[r.layout] || r.layout} · ${when}${sim}`;
  }

  // ---------- P2 ----------
  const shown = new Map();

  function addTile(r, isNew) {
    if (shown.has(r.id)) return;
    shown.set(r.id, r);
    const b = document.createElement('button');
    b.className = 'tile' + (isNew ? ' new' : '');
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.src = r.url;
    img.alt = label(r);
    const span = document.createElement('span');
    span.textContent = label(r);
    b.append(img, span);
    b.addEventListener('click', () => openViewer(r));
    const grid = $('grid');
    if (isNew) grid.prepend(b); else grid.append(b);
    $('empty').hidden = true;
  }

  async function loadResults() {
    const data = await api('/sync/results?phoneDeviceId=' + encodeURIComponent(phoneId));
    data.results.forEach((r) => addTile(r, false));
    $('empty').hidden = shown.size > 0;
  }

  let es;
  function listen() {
    if (es) es.close();
    es = new EventSource('/sync/events?deviceId=' + encodeURIComponent(phoneId));
    es.addEventListener('ready', () => status('차량과 연결됨', true));
    es.addEventListener('result.delivered', (e) => {
      const r = JSON.parse(e.data);
      addTile(r, true);
      toast('차에서 새 사진이 도착했어요');
      if (navigator.vibrate) navigator.vibrate(80);
    });
    es.onerror = () => status('다시 연결 중…', false); // EventSource 가 알아서 재연결
  }

  // ---------- P4 ----------
  let current = null;
  function openViewer(r) {
    current = r;
    $('viewer-img').src = r.url;
    $('viewer-meta').textContent = label(r);
    $('save').href = r.url;
    $('save').download = `chaz_${r.id}.jpg`;
    $('link-out').textContent = '';
    $('share').hidden = !navigator.share; // Web Share API 는 HTTPS(보안 컨텍스트)에서만
    $('viewer').showModal();
  }

  $('close').addEventListener('click', () => $('viewer').close());

  $('share').addEventListener('click', async () => {
    if (!current) return;
    try {
      const blob = await (await fetch(current.url)).blob();
      const file = new File([blob], `chaz_${current.id}.jpg`, { type: blob.type || 'image/jpeg' });
      if (navigator.canShare && navigator.canShare({ files: [file] })) {
        await navigator.share({ files: [file], title: '차~즈', text: '차~즈로 찍은 드라이브 사진' });
      } else {
        const { url } = await makeLink();
        await navigator.share({ title: '차~즈', url });
      }
    } catch (e) {
      if (e.name !== 'AbortError') toast('공유하지 못했어요: ' + e.message);
    }
  });

  async function makeLink() {
    return api('/sync/share', { method: 'POST', body: JSON.stringify({ resultId: current.id, phoneDeviceId: phoneId }) });
  }

  $('link').addEventListener('click', async () => {
    if (!current) return;
    try {
      const { url, expiresInDays } = await makeLink();
      $('link-out').textContent = `${url} (${expiresInDays}일 동안)`;
      try { await navigator.clipboard.writeText(url); toast('링크를 복사했어요'); } catch (e) { /* http 에서는 클립보드 제한 */ }
    } catch (e) {
      toast('링크를 만들지 못했어요: ' + e.message);
    }
  });

  // ---------- P1 + 시작 ----------
  async function claim(token) {
    show('p1');
    try {
      const r = await api('/sync/pairing-claim', { method: 'POST', body: JSON.stringify({ token, phoneDeviceId: phoneId }) });
      storage(KEY_CAR, r.carDeviceId);
      $('p1-title').textContent = '차량과 연결됐어요';
      $('p1-text').textContent = '이제 차에서 보낸 사진이 여기로 와요. 이 페이지를 홈 화면에 추가해 두면 편해요.';
      $('p1-go').hidden = false;
      status('차량과 연결됨', true);
    } catch (e) {
      $('p1-title').textContent = '연결하지 못했어요';
      $('p1-text').textContent = e.message;
    } finally {
      history.replaceState(null, '', location.pathname); // 토큰을 주소창에서 지운다
    }
  }

  async function home() {
    try {
      await loadResults();
      show('p2');
      listen();
    } catch (e) {
      show('unpaired');
      status('연결 안 됨', false);
    }
  }

  $('p1-go').addEventListener('click', home);

  if ('serviceWorker' in navigator && window.isSecureContext) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }

  const token = new URLSearchParams(location.search).get('pair');
  if (token) claim(token); else home();
})();
