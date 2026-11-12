// 차~즈 폰 컴패니언 (P1 페어링 · P2 받은 사진 · P4 저장·공유 · P5 실내 카메라). 의존성 없는 바닐라 JS.
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
    for (const s of ['p1', 'unpaired', 'p2', 'p5']) $(s).hidden = s !== id;
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
    es.addEventListener('capture.shot', (e) => onShot(JSON.parse(e.data)));
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

  // ---------- P5 실내 카메라 모드 ----------
  // 영상 스트림을 켜 두고, 차량이 capture.shot 을 보내면 그 순간 프레임을 떠서 올린다(지연 0.1초 안팎).
  // 0.5초마다 저해상 프레임도 올린다 → 차량 미리보기·클립, 그리고 '카메라 켜짐' 판정.
  const cam = { stream: null, facing: 'user', timer: null, busy: false, wake: null, shots: 0, offset: 0, syncTimer: null, live: false };

  // 서버 시계 맞추기: 서버시각 - 내시각. 왕복이 가장 짧았던 측정을 쓴다 (오차 ≈ 왕복/2).
  async function syncClock() {
    let best = null;
    for (let i = 0; i < 5; i++) {
      try {
        const t0 = Date.now();
        const r = await fetch('/sync/time', { cache: 'no-store' });
        const { t } = await r.json();
        const t1 = Date.now();
        if (!best || t1 - t0 < best.rtt) best = { rtt: t1 - t0, offset: t - (t0 + t1) / 2 };
      } catch (e) { /* 다음 시도 */ }
    }
    if (best) cam.offset = best.offset;
  }
  const serverNow = () => Date.now() + cam.offset;
  const video = $('cam-video');

  function camBadge(text, ok) {
    $('cam-badge').textContent = text;
    $('cam-badge').classList.toggle('ok', !!ok);
  }

  async function openStream() {
    if (cam.stream) cam.stream.getTracks().forEach((t) => t.stop());
    cam.stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      // 16:9 로 고정하면 4:3 센서에서 위아래가 잘려 확대돼 보인다 → 센서 원래 비율(보통 4:3)을 우선
      video: { facingMode: cam.facing, width: { ideal: 2560 }, aspectRatio: { ideal: 4 / 3 } },
    });
    video.srcObject = cam.stream;
    // 미리보기 칸도 실제 찍히는 비율로 (잘린 미리보기로 착각하지 않게)
    video.onloadedmetadata = () => {
      if (video.videoWidth && video.videoHeight) video.parentElement.style.aspectRatio = `${video.videoWidth} / ${video.videoHeight}`;
    };
    video.classList.toggle('mirror', cam.facing === 'user'); // 화면만 거울처럼, 올리는 사진은 원본 방향
    await video.play().catch(() => {});
  }

  function grab(maxW, quality) {
    const w0 = video.videoWidth, h0 = video.videoHeight;
    if (!w0 || !h0) return Promise.resolve(null);
    const k = Math.min(1, maxW / Math.max(w0, h0)); // 긴 변 기준으로 줄인다 (세로로 들어도 같은 화질)
    const c = document.createElement('canvas');
    c.width = Math.round(w0 * k); c.height = Math.round(h0 * k);
    c.getContext('2d').drawImage(video, 0, 0, c.width, c.height);
    return new Promise((res) => c.toBlob(res, 'image/jpeg', quality));
  }

  async function upload(kind, blob, shotId, takenAtMs) {
    const f = new FormData();
    f.append('phoneDeviceId', phoneId);
    f.append('kind', kind);
    if (shotId) f.append('shotId', shotId);
    if (takenAtMs) f.append('takenAtMs', String(Math.round(takenAtMs)));
    f.append('file', blob, kind + '.jpg');
    const res = await fetch('/sync/camera/frame', { method: 'POST', body: f });
    if (!res.ok) {
      let d = null; try { d = (await res.json()).detail; } catch (e) { /* 본문 없음 */ }
      const err = new Error(d || ('HTTP ' + res.status));
      err.status = res.status;
      throw err;
    }
  }

  async function sendPreview() {
    if (cam.busy || !cam.stream || !cam.live) return;
    cam.busy = true;
    try {
      const b = await grab(480, 0.6);
      if (b) { await upload('preview', b); camBadge('차량과 연결됨 · 대기 중', true); }
    } catch (e) {
      camBadge(e.message, false);
    } finally {
      cam.busy = false;
    }
  }

  async function onShot(d) {
    if (!cam.stream || !cam.live) return; // 실시간 연결을 켰을 때만 차의 '차즈' 촬영에 응한다
    // 예약 촬영(atMs): 서버 시계 기준 그 순간까지 기다렸다가 찍는다. 이미 지났으면 바로.
    if (d.atMs) {
      const wait = d.atMs - serverNow();
      if (wait > 0) await new Promise((r) => setTimeout(r, Math.min(wait, 15000)));
    }
    try {
      const takenAt = serverNow();
      const b = await grab(1920, 0.92);
      if (!b) return;
      $('cam-flash').classList.add('on');
      setTimeout(() => $('cam-flash').classList.remove('on'), 60);
      if (navigator.vibrate) navigator.vibrate(40);
      await upload('shot', b, d.shotId, takenAt);
      cam.shots++;
      $('cam-count').textContent = `${cam.shots}장 찍음`;
    } catch (e) {
      if (e.status !== 410) toast('사진을 보내지 못했어요: ' + e.message); // 410 = 다른 폰이 먼저 보냄
    }
  }

  // 직접 촬영 묶음: 실내(셀카 쪽) → 전방 → 후방(뒷면 카메라) 순서로 3장을 모은 뒤 한 번에 보낸다.
  // 차는 바로 합성하지 않고 갤러리에 묶음(앨범)으로 저장 → 차 화면에서 자리마다 사진을 골라 편집한다.
  // 칸을 누르면 그 칸만 다시 찍는다.
  const ROLES = ['INTERIOR', 'FRONT', 'REAR'];
  const ROLE_LABEL = { INTERIOR: '실내', FRONT: '전방', REAR: '후방' };
  const trio = { blobs: {}, urls: {}, step: 0, busy: false };

  function trioRender() {
    document.querySelectorAll('#trio .slot').forEach((el) => {
      const role = el.dataset.role;
      const img = el.querySelector('img');
      if (trio.urls[role]) img.src = trio.urls[role]; else img.removeAttribute('src');
      el.classList.toggle('now', ROLES[trio.step] === role);
    });
    const n = ROLES.filter((r) => trio.blobs[r]).length;
    const role = ROLES[trio.step];
    const btn = $('cam-shutter');
    btn.textContent = role ? `📸 ${ROLE_LABEL[role]} 찍기 (${trio.step + 1}/3)` : '✓ 3장 다 찍었어요';
    btn.disabled = !role || trio.busy;
    $('trio-send').disabled = n < 3 || trio.busy;
    $('trio-send').textContent = `📨 ${n}장 차로 보내기`;
  }

  // 실내 = 전면(셀카), 전방·후방 = 후면 카메라
  async function trioFace(role) {
    const want = role === 'INTERIOR' ? 'user' : 'environment';
    if (cam.stream && cam.facing !== want) {
      cam.facing = want;
      try { await openStream(); } catch (e) { toast('카메라를 바꾸지 못했어요'); }
    }
  }

  function trioGo(step) {
    trio.step = step;
    trioRender();
    if (ROLES[step]) trioFace(ROLES[step]);
  }

  function trioReset() {
    Object.values(trio.urls).forEach((u) => URL.revokeObjectURL(u));
    trio.blobs = {}; trio.urls = {};
    trioGo(0);
  }

  // 사진 한 장을 '찍힌 프레임 그대로' 얻는다: 되는 기기는 카메라 사진 촬영(ImageCapture.takePhoto — 카메라 앱과 같은 전체 프레임),
  // 안 되면 화면 영상의 한 장면. 자르지 않고 긴 변 2560px 까지만 줄인다. 방향(EXIF)은 그림에 반영해 둔다.
  async function takeStill() {
    const track = cam.stream && cam.stream.getVideoTracks()[0];
    if (track && 'ImageCapture' in window) {
      try {
        const raw = await new ImageCapture(track).takePhoto();
        const bmp = await createImageBitmap(raw, { imageOrientation: 'from-image' });
        const k = Math.min(1, 2560 / Math.max(bmp.width, bmp.height));
        const c = document.createElement('canvas');
        c.width = Math.round(bmp.width * k); c.height = Math.round(bmp.height * k);
        c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
        bmp.close();
        const out = await new Promise((res) => c.toBlob(res, 'image/jpeg', 0.92));
        if (out) return out;
      } catch (e) { /* 지원 안 하는 기기·카메라 → 화면 프레임 */ }
    }
    return grab(2560, 0.92);
  }

  async function trioShot() {
    const role = ROLES[trio.step];
    if (!cam.stream || !role || trio.busy) return;
    trio.busy = true; trioRender();
    let b = null;
    try { b = await takeStill(); } finally { trio.busy = false; trioRender(); }
    if (!b) { toast('카메라가 아직 준비 중이에요'); return; }
    $('cam-flash').classList.add('on');
    setTimeout(() => $('cam-flash').classList.remove('on'), 60);
    if (navigator.vibrate) navigator.vibrate(40);
    if (trio.urls[role]) URL.revokeObjectURL(trio.urls[role]);
    trio.blobs[role] = b;
    trio.urls[role] = URL.createObjectURL(b);
    const next = ROLES.findIndex((r) => !trio.blobs[r]);
    trioGo(next === -1 ? ROLES.length : next);
  }

  async function trioSend() {
    if (trio.busy || ROLES.some((r) => !trio.blobs[r])) return;
    trio.busy = true; trioRender();
    try {
      const f = new FormData();
      f.append('phoneDeviceId', phoneId);
      ROLES.forEach((r) => f.append(r.toLowerCase(), trio.blobs[r], r.toLowerCase() + '.jpg'));
      const res = await fetch('/sync/camera/bundle', { method: 'POST', body: f });
      let d = {}; try { d = await res.json(); } catch (e) { /* 본문 없음 */ }
      if (!res.ok) throw new Error(d.detail || ('HTTP ' + res.status));
      if (!d.delivered) { toast('차량 앱이 꺼져 있어 전달하지 못했어요'); return; }
      cam.shots += 3;
      $('cam-count').textContent = `${cam.shots}장 보냄`;
      toast('3장을 차로 보냈어요. 차 화면 갤러리에서 골라 꾸밀 수 있어요');
      trioReset();
    } catch (e) {
      toast('보내지 못했어요: ' + e.message);
    } finally {
      trio.busy = false; trioRender();
    }
  }

  document.querySelectorAll('#trio .slot').forEach((el) =>
    el.addEventListener('click', () => trioGo(ROLES.indexOf(el.dataset.role))));
  $('trio-send').addEventListener('click', trioSend);
  $('trio-reset').addEventListener('click', trioReset);
  trioRender();

  async function keepAwake() {
    try { if ('wakeLock' in navigator && !cam.wake) cam.wake = await navigator.wakeLock.request('screen'); } catch (e) { /* 지원 안 함 */ }
    if (cam.wake) cam.wake.addEventListener('release', () => { cam.wake = null; });
  }

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && cam.stream) keepAwake();
  });

  async function camStart() {
    if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      toast('카메라는 https 주소에서만 켜져요. 차량 화면의 QR로 다시 접속해 주세요');
      return;
    }
    show('p5');
    camBadge('카메라 준비 중…', false);
    try {
      await openStream();
    } catch (e) {
      camBadge('카메라를 열 수 없어요', false);
      $('cam-text').textContent = '브라우저의 카메라 권한을 허용해 주세요. (' + e.name + ')';
      return;
    }
    listen();
    keepAwake();
    setLive(false); // 기본은 사진 모드: 찍은 사진만 보낸다
  }

  // 실시간 연결(선택): 0.5초마다 저해상 화면을 올려 차가 이 폰을 실내 카메라로 쓰게 한다
  // (차에서 '차즈'라고 하면 이 폰이 찍음, T11). 끄면 아무것도 보내지 않는다.
  function setLive(on) {
    cam.live = on;
    clearInterval(cam.timer); cam.timer = null;
    clearInterval(cam.syncTimer); cam.syncTimer = null;
    if (on) {
      syncClock();
      cam.syncTimer = setInterval(syncClock, 60000);
      cam.timer = setInterval(sendPreview, 500);
      sendPreview();
    } else {
      camBadge('사진 모드 · 찍은 사진만 보내요', true);
    }
    $('cam-live').textContent = on ? '실시간 연결 끄기' : '실시간 연결 켜기';
    $('cam-text').textContent = on
      ? '실시간 연결 중: 차가 이 화면을 실내 카메라로 써요. 차에서 “차즈”라고 하면 이 폰이 찍어요.'
      : '찍은 사진만 차로 보내요 (실시간 화면은 보내지 않아요).';
  }

  function camStop() {
    setLive(false);
    if (cam.stream) cam.stream.getTracks().forEach((t) => t.stop());
    cam.stream = null;
    video.srcObject = null;
    if (cam.wake) cam.wake.release().catch(() => {});
    cam.wake = null;
    show('p2');
  }

  $('cam-go').addEventListener('click', camStart);
  $('cam-stop').addEventListener('click', camStop);
  $('cam-live').addEventListener('click', () => setLive(!cam.live));
  $('cam-shutter').addEventListener('click', trioShot);
  $('cam-flip').addEventListener('click', async () => {
    cam.facing = cam.facing === 'user' ? 'environment' : 'user';
    try { await openStream(); } catch (e) { toast('카메라를 바꾸지 못했어요'); }
  });

  // ---------- 음성 리모컨 ----------
  // 폰 브라우저 음성 인식(Web Speech API)으로 글자로 바꿔 서버 → 차량으로 보낸다. 지원 안 되면 빠른 명령 버튼만.
  async function say(text) {
    try {
      const r = await api('/sync/voice', { method: 'POST', body: JSON.stringify({ phoneDeviceId: phoneId, text }) });
      toast(r.delivered ? `차에 전달했어요 · “${text.replace(/^차즈\s*/, '')}”` : '차량 앱이 꺼져 있어요');
    } catch (e) {
      toast('보내지 못했어요: ' + e.message);
    }
  }
  document.querySelectorAll('[data-say]').forEach((b) => b.addEventListener('click', () => say(b.dataset.say)));

  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const mic = $('mic');
  if (!SR) {
    mic.hidden = true;
    $('voice-heard').textContent = '이 브라우저는 음성 인식을 지원하지 않아요. 아래 버튼으로 조작하세요.';
  } else {
    let rec = null;
    mic.addEventListener('click', () => {
      if (rec) { rec.stop(); return; }
      rec = new SR();
      rec.lang = 'ko-KR';
      rec.interimResults = true;
      rec.maxAlternatives = 1;
      let finalText = '';
      mic.classList.add('on');
      $('voice-heard').textContent = '듣는 중…';
      rec.onresult = (ev) => {
        let t = '';
        for (const r of ev.results) t += r[0].transcript;
        $('voice-heard').textContent = `“${t}”`;
        if (ev.results[ev.results.length - 1].isFinal) finalText = t.trim();
      };
      rec.onerror = (ev) => {
        $('voice-heard').textContent = ev.error === 'not-allowed' ? '마이크 권한을 허용해 주세요' : '잘 못 들었어요. 다시 눌러 주세요';
      };
      rec.onend = () => {
        mic.classList.remove('on');
        rec = null;
        if (finalText) say(finalText);
      };
      try { rec.start(); } catch (e) { mic.classList.remove('on'); rec = null; }
    });
  }

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
