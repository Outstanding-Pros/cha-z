// 인트로: 9:16 스타일 시안 판.
// ① 대교 드라이브가 화면을 가득 채운 채 시작: 터널이 4초쯤 환해지며 대교 → 운전자가 사라지고 화면이 위아래로 좁아지며
//    좌우 파노라마 → 왼쪽 위·오른쪽 아래에 실내(친구들) 영상 → 가운데 입력창에 문구 타이핑 → 릴스 규격으로 줄어들며 줌아웃
// ② 가족 여행 → ③ 반려견 벚꽃 드라이브 순으로 하나씩 건너뛰며 줌인. 줌인 중엔 주변 카드를 어둡게,
//    줌아웃은 위아래 줄이 절반씩 보이는 정도까지만.
// 화면 크기에서 배율을 계산하므로 9:16(IntroReel)과 16:9(Demo 첫 장면) 모두 이 컴포넌트를 쓴다.
import React from 'react';
import { AbsoluteFill, Audio, Easing, Img, interpolate, OffthreadVideo, Sequence, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
import { Logo } from './Logo';
import { C, FONT } from './theme';

const W = 540; // 카드(9:16) 월드 크기
const H = 960;
const GAP = 110; // 줌인 때 옆 카드가 화면 가장자리에 살짝 걸리도록 넉넉히
const file = (name: string) => staticFile(`intro/${name}`);
const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;
const ease = { ...clamp, easing: Easing.inOut(Easing.cubic) };

type Vid = 'bridge' | 'family' | 'pet';
type Card = { col: number; row: number; copy: string; style: string; image?: string; vid?: Vid; place?: string };

// 가운데 줄: 대교(-2) · 건너뜀(-1) · 가족(0) · 건너뜀(1) · 반려견(2). 정지 시안은 반대편에서 다시 쓴다
const S = {
  camp: { image: 'night_camp', style: '차박 나이트', copy: '별 아래 차박, 둘만의 밤', place: '양양 오토캠핑장' },
  blossom: { image: 'blossom_friends', style: '트립 로그', copy: '벚꽃 터널을 친구들과 함께', place: '경주 보문로' },
  snow: { image: 'snow_couple', style: '차박 나이트', copy: '첫눈 오는 날, 둘이서', place: '대관령 옛길' },
  sunset: { image: 'sunset_solo', style: '트립 로그', copy: '퇴근길 노을, 나를 위한 한 컷', place: '올림픽대로' },
  movie: { image: 'movie_couple1', style: '로드 무비', copy: '우리 둘의 드라이브 예고편', place: '7번 국도' },
};
const CARDS: Card[] = [
  { col: -2, row: 0, vid: 'bridge', style: '오션 브릿지', copy: '친구들과 바다 위를 달리다' },
  { col: -1, row: 0, ...S.camp },
  { col: 0, row: 0, vid: 'family', style: '트립 로그', copy: '제주 해안도로에서 가족과 함께', place: '애월 해안도로' },
  { col: 1, row: 0, ...S.movie },
  { col: 2, row: 0, vid: 'pet', style: '펫 드라이버', copy: '초코가 말아주는 벚꽃 드라이브' },
  { col: -3, row: 0, ...S.sunset },
  { col: 3, row: 0, ...S.snow },
  { col: -2.5, row: -1, ...S.blossom },
  { col: -1.5, row: -1, ...S.snow },
  { col: -0.5, row: -1, ...S.sunset },
  { col: 0.5, row: -1, ...S.camp },
  { col: 1.5, row: -1, ...S.blossom },
  { col: 2.5, row: -1, ...S.movie },
  { col: -2.5, row: 1, ...S.movie },
  { col: -1.5, row: 1, ...S.camp },
  { col: -0.5, row: 1, ...S.blossom },
  { col: 0.5, row: 1, ...S.snow },
  { col: 1.5, row: 1, ...S.sunset },
  { col: 2.5, row: 1, ...S.camp },
];
const pos = (col: number, row: number) => ({ x: col * (W + GAP), y: row * (H + GAP) });

// ---------- 일정 (30fps) ----------
// 대교(히어로)
const DASH_RATE = 1.35; // 대교 영상: 터널이 4초 부근에서 환해지며 대교가 보이도록 빠르게 (원본 5초 → 3.7초)
const MOVE = [148, 196]; // 대교를 1초쯤 보여 준 뒤 카메라가 앞 유리 너머로 밀고 나가며 파노라마로 (운전자 사라짐)
const PANO = [186, 236]; // 위아래로 좁아지며 좌우 파노라마
const PANO_RATE = 0.75; // 파노라마 영상(8초)을 천천히 길게
const INSET = [242, 270]; // 실내 영상: 가로 33% 지점(위), 66% 지점(아래)
const PILL_IN = 298; // 입력창 등장, 이어서 타이핑
const MORPH = [362, 394]; // 릴스 규격(카드)으로 줄어듦
const Z = 30; // 줌아웃 또는 줌인 한 번
const FOCUS: { vid: Vid; col: number; in: number; hold: number; start: number }[] = [
  { vid: 'bridge', col: -2, in: 0, hold: MORPH[1], start: 0 },
  { vid: 'family', col: 0, in: MORPH[1] + 2 * Z, hold: 150, start: MORPH[1] + Z },
  { vid: 'pet', col: 2, in: MORPH[1] + 4 * Z + 150, hold: 155, start: MORPH[1] + 3 * Z + 150 },
];
// 영상 카드는 두 컷을 잇는다 (초, 영상 시작 기준). enter: 뒤 컷이 들어오는 방식
type Seg = { src: string; at: number; len: number; enter?: 'roof'; flashAt?: number };
const SEGS: Record<'family' | 'pet', Seg[]> = {
  // 일반적인 차 안 → 위에서 아래로 쓸어내리며 주변 풍경(앱 L1 차창 합성처럼)
  // 일반적인 차 안 → 3초에 천장 부분부터 풍경으로 바뀌고(위→아래) 이어서 차 프레임 없는 열린 풍경 (아이들은 앉은 채 만세)
  family: [
    { src: 'v_family_in', at: 0, len: 4.0 },
    { src: 'v_family_open2', at: 3.0, len: 4.0, enter: 'roof' },
  ],
  // 사람이 운전 → 오른손가락을 튕기는 순간 번쩍 → 반려견이 운전석
  pet: [{ src: 'v_pet7', at: 0, len: 7, flashAt: 3.42 }], // 3초에 '딱' → 자리 바뀌는 순간(3.2~3.6초, 영상이 뭉개지는 구간)을 번쩍임으로 덮음
};
const END = FOCUS[2].in + FOCUS[2].hold + Z + 6; // 마지막 줌아웃 끝
export const INTRO_FRAMES = END + 60; // + 로고
const focusOf = (v: Vid) => FOCUS.find((f) => f.vid === v)!;

// ---------- 카메라 ----------
type Key = { f: number; x: number; z: number }; // z=1: 줌인
const keys = (zOut: number): Key[] => {
  const k: Key[] = [];
  FOCUS.forEach((fc, i) => {
    const x = pos(fc.col, 0).x;
    k.push({ f: fc.in, x, z: 1 });
    k.push({ f: fc.in + fc.hold, x, z: 1 });
    const next = FOCUS[i + 1];
    if (next) k.push({ f: fc.in + fc.hold + Z, x: (x + pos(next.col, 0).x) / 2, z: zOut });
  });
  k.push({ f: END, x: pos(1, 0).x, z: zOut });
  return k;
};
const camera = (k: Key[], f: number) => {
  const i = k.findIndex((q) => q.f > f);
  if (i === -1) return k[k.length - 1];
  if (i === 0) return k[0];
  const a = k[i - 1];
  const b = k[i];
  const t = interpolate(f, [a.f, b.f], [0, 1], ease);
  return { f, x: a.x + (b.x - a.x) * t, z: Math.exp(Math.log(a.z) + (Math.log(b.z) - Math.log(a.z)) * t) };
};

// ---------- 공통 ----------
// u: 카드(540×960) 대비 배율. 히어로는 화면 크기, 카드는 1
const Pill: React.FC<{ text: string; typed?: number; u?: number; style?: React.CSSProperties }> = ({ text, typed, u = 1, style }) => {
  const shown = typed === undefined ? text : text.slice(0, Math.floor(typed));
  return (
    <div
      style={{
        position: 'absolute',
        left: `max(${24 * u}px, calc(50% - ${300 * u}px))`,
        right: `max(${24 * u}px, calc(50% - ${300 * u}px))`,
        top: `calc(50% - ${44 * u}px)`,
        height: 88 * u,
        borderRadius: 44 * u,
        background: 'rgba(255,255,255,0.5)',
        backdropFilter: `blur(${14 * u}px)`,
        display: 'flex',
        alignItems: 'center',
        padding: `0 ${14 * u}px 0 ${30 * u}px`,
        boxShadow: `0 ${10 * u}px ${30 * u}px rgba(0,0,0,.35)`,
        ...style,
      }}
    >
      <span style={{ flex: 1, color: '#16181B', fontFamily: FONT, fontSize: 25 * u, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden' }}>
        {shown}
        {typed !== undefined && typed < text.length + 6 && <span style={{ color: C.accent }}>|</span>}
      </span>
      <Logo size={58 * u} style={{ borderRadius: 14 * u, flexShrink: 0 }} />
    </div>
  );
};

const Chip: React.FC<{ children: React.ReactNode; style?: React.CSSProperties }> = ({ children, style }) => (
  <div style={{ position: 'absolute', background: 'rgba(11,12,14,.72)', color: 'white', fontFamily: FONT, fontSize: 22, fontWeight: 700, padding: '7px 14px', borderRadius: 10, ...style }}>
    {children}
  </div>
);
const cover: React.CSSProperties = { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' };
const Shade = () => (
  <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(180deg, rgba(0,0,0,.35) 0%, rgba(0,0,0,0) 30%, rgba(0,0,0,0) 70%, rgba(0,0,0,.45) 100%)' }} />
);

// ---------- ① 대교 (화면 크기 w×h 에 맞춰 배치. 히어로와 카드가 같은 컴포넌트) ----------
const Bridge: React.FC<{ w: number; h: number; t: number; copy: string }> = ({ w, h, t, copy }) => {
  const u = Math.min(w / W, h / H);
  const bandMin = Math.min(h * 0.42, (w * 9) / 21); // 21:9 파노라마 띠
  const band = interpolate(t, PANO, [h, bandMin], ease);
  const bandTop = (h - band) / 2;
  // 카메라 이동: 앞 유리 가운데(대교)로 밀고 들어가며 운전석 뷰가 사라지고, 같은 방향의 파노라마가 이어받는다
  const push = interpolate(t, MOVE, [0, 1], ease);
  const dash = interpolate(t, [MOVE[0] + 10, MOVE[1]], [1, 0], ease);
  const dashLen = Math.floor((10.05 * 30) / DASH_RATE) - 1;
  const panoFrom = MOVE[0] - 30; // 보이기 전부터 재생해 둬서 전환 때 멈칫하지 않게
  const panoLen = Math.floor((8 * 30) / PANO_RATE) - 1;
  // 실내 영상(세로 컷)
  const iw = Math.min(w * 0.44, h * 0.3);
  const ih = iw * 1.3;
  const inset = (at: number, src: string, left: number, top: number, rot: number) => {
    const p = interpolate(t, [at, at + 12], [0, 1], { ...clamp, easing: Easing.out(Easing.back(1.4)) });
    return (
      <div
        style={{
          position: 'absolute',
          left,
          top,
          width: iw,
          height: ih,
          borderRadius: 22 * u,
          overflow: 'hidden',
          border: `${5 * u}px solid white`,
          opacity: Math.min(1, p * 1.5),
          transform: `scale(${0.6 + 0.4 * p}) rotate(${rot}deg)`,
          boxShadow: `0 ${14 * u}px ${40 * u}px rgba(0,0,0,.5)`,
        }}
      >
        <Img src={file(`${src}_first.jpg`)} style={cover} />
        <Sequence from={at} durationInFrames={150} layout="none">
          <OffthreadVideo src={file(`${src}.mp4`)} muted style={cover} />
        </Sequence>
        {t >= at + 150 && <Img src={file(`${src}_last.jpg`)} style={cover} />}
      </div>
    );
  };
  const typed = interpolate(t, [PILL_IN + 4, PILL_IN + 44], [0, copy.length + 6], clamp);
  const pill = interpolate(t, [PILL_IN, PILL_IN + 10], [0, 1], clamp);
  return (
    <div style={{ position: 'absolute', inset: 0, background: '#0B0C0E', overflow: 'hidden' }}>
      {/* 띠 밖: 같은 파노라마를 흐리고 어둡게 */}
      <Img src={file('v_pano_last.jpg')} style={{ ...cover, filter: `blur(${24 * u}px) brightness(0.45)`, transform: 'scale(1.15)' }} />
      {/* 파노라마 띠 */}
      <div style={{ position: 'absolute', left: 0, right: 0, top: bandTop, height: band, overflow: 'hidden' }}>
        <Img src={file('v_pano_first.jpg')} style={cover} />
        <Sequence from={panoFrom} durationInFrames={panoLen} layout="none">
          <OffthreadVideo src={file('v_pano.mp4')} playbackRate={PANO_RATE} muted style={cover} />
        </Sequence>
        {t >= panoFrom + panoLen && <Img src={file('v_pano_last.jpg')} style={cover} />}
      </div>
      {/* 운전석 앞 유리 뷰 (터널 → 대교) */}
      {dash > 0 && (
        <div style={{ position: 'absolute', inset: 0, opacity: dash }}>
          <Sequence durationInFrames={dashLen} layout="none">
            <OffthreadVideo
              src={file('v_bridge.mp4')}
              playbackRate={DASH_RATE}
              muted
              style={{ ...cover, transformOrigin: '50% 32%', transform: `scale(${1 + 0.9 * push})` }}
            />
          </Sequence>
        </div>
      )}
      {inset(INSET[0], 'v_friends', w * 0.33 - iw / 2, Math.max(h * 0.04, bandTop - ih * 0.72), -3)}
      {inset(INSET[1], 'v_friends2', w * 0.66 - iw / 2, Math.min(h * 0.96 - ih, bandTop + band - ih * 0.28), 3)}
      {pill > 0 && <Pill text={copy} typed={typed} u={u} style={{ opacity: pill, transform: `scale(${0.92 + 0.08 * pill})` }} />}
    </div>
  );
};

// ---------- ② ③ 영상 카드 ----------
const VideoCard: React.FC<{ card: Card; f: number }> = ({ card, f }) => {
  const fc = focusOf(card.vid!);
  const segs = SEGS[card.vid as 'family' | 'pet'];
  const t = f - fc.start;
  const last = segs[segs.length - 1];
  const endF = Math.round((last.at + last.len) * 30);
  return (
    <>
      {t < 0 && <Img src={file(`${segs[0].src}_first.jpg`)} style={cover} />}
      {t >= endF && <Img src={file(`${last.src}_last.jpg`)} style={cover} />}
      {segs.map((sg) => {
        const from = fc.start + Math.round(sg.at * 30);
        const k = f - from; // 이 컷 안에서 지난 프레임
        // roof: 위에서부터 천장 높이(40%)까지 먼저 풍경이 드러나고, 잠깐 뒤 나머지가 이어진다 (경계는 부드럽게)
        const edge = sg.enter === 'roof' ? interpolate(k, [0, 16, 22, 34], [-20, 40, 40, 120], ease) : 120;
        const mask = `linear-gradient(to bottom, #000 ${edge}%, transparent ${edge + 20}%)`;
        return (
          <Sequence key={sg.src} from={from} durationInFrames={Math.round(sg.len * 30)} layout="none">
            <OffthreadVideo src={file(`${sg.src}.mp4`)} muted style={{ ...cover, maskImage: mask, WebkitMaskImage: mask }} />
          </Sequence>
        );
      })}
      {segs.map((sg) => {
        if (sg.flashAt === undefined) return null;
        const k = f - (fc.start + Math.round((sg.at + sg.flashAt) * 30));
        const flash = interpolate(k, [-6, -1, 3, 10], [0, 1, 1, 0], clamp);
        const burst = interpolate(k, [-4, 8], [0, 1], clamp);
        return (
          <React.Fragment key={'fx' + sg.src}>
            <div style={{ position: 'absolute', inset: 0, background: 'white', opacity: flash }} />
            {burst > 0 && burst < 1 && (
              <div
                style={{
                  position: 'absolute',
                  left: '46%',
                  top: '44%',
                  width: 160,
                  height: 160,
                  marginLeft: -80,
                  marginTop: -80,
                  borderRadius: 80,
                  border: '8px solid white',
                  opacity: 1 - burst,
                  transform: `scale(${0.3 + 1.6 * burst})`,
                }}
              />
            )}
          </React.Fragment>
        );
      })}
      <Shade />
      <Chip style={{ left: 26, top: 120 }}>{card.style}</Chip>
      {card.place && <Chip style={{ left: 26, bottom: 300 }}>📍 {card.place}</Chip>}
      {card.vid === 'pet' && <Chip style={{ left: 26, bottom: 300 }}>🐾 초코</Chip>}
    </>
  );
};

const StaticCard: React.FC<{ card: Card }> = ({ card }) => (
  <>
    <Img src={file(`${card.image}.jpg`)} style={cover} />
    <Shade />
    <Chip style={{ left: 26, top: 120 }}>{card.style}</Chip>
    {card.place && <Chip style={{ left: 26, bottom: 300 }}>📍 {card.place}</Chip>}
  </>
);

const CardView: React.FC<{ card: Card; f: number; dim: number }> = ({ card, f, dim }) => {
  const p = pos(card.col, card.row);
  const fc = card.vid && card.vid !== 'bridge' ? focusOf(card.vid) : undefined;
  const typed = fc ? interpolate(f, [fc.in - 6, fc.in + 40], [0, card.copy.length + 6], clamp) : undefined;
  return (
    <div
      style={{
        position: 'absolute',
        left: p.x - W / 2,
        top: p.y - H / 2,
        width: W,
        height: H,
        borderRadius: 30,
        overflow: 'hidden',
        background: C.surface,
        boxShadow: '0 30px 80px rgba(0,0,0,.55)',
      }}
    >
      {card.vid === 'bridge' ? (
        <Bridge w={W} h={H} t={f} copy={card.copy} />
      ) : (
        <>
          {card.vid ? <VideoCard card={card} f={f} /> : <StaticCard card={card} />}
          <Pill text={card.copy} typed={typed} />
        </>
      )}
      <div style={{ position: 'absolute', inset: 0, background: 'black', opacity: dim }} />
    </div>
  );
};

export const Intro: React.FC = () => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  // 줌인 배율: 9:16 은 카드가 폭의 62% (옆 카드가 가장자리에 걸림), 16:9 는 높이의 86%
  const sIn = Math.min((width * 0.62) / W, (height * 0.86) / H);
  // 줌아웃 배율: 가운데 줄 전체 + 위아래 줄 절반씩
  const zOut = Math.min(1, height / (2 * H + 2 * GAP) / sIn);
  const cam = camera(keys(zOut), Math.min(Math.max(f, MORPH[1]), END));
  const s = sIn * cam.z;
  const zoomed = interpolate(cam.z, [zOut, 1], [0, 1], clamp);
  // 히어로(대교) 사각형: 화면 전체 → 줌인 상태의 카드 자리
  const m = interpolate(f, MORPH, [0, 1], ease);
  const hw = width + (W * sIn - width) * m;
  const hh = height + (H * sIn - height) * m;
  const heroOn = f < MORPH[1];
  const logo = interpolate(f, [END - 12, END + 8], [0, 1], clamp);
  const fadeOut = interpolate(f, [INTRO_FRAMES - 8, INTRO_FRAMES], [1, 0], clamp);
  return (
    <AbsoluteFill style={{ background: '#101114', overflow: 'hidden', opacity: fadeOut }}>
      <div style={{ position: 'absolute', left: width / 2, top: height / 2, transform: `scale(${s}) translate(${-cam.x}px, ${-pos(-2, 0).y}px)` }}>
        {CARDS.map((c, i) => {
          if (c.vid === 'bridge' && heroOn) return null; // 히어로가 대신 그린다
          // 화면 가운데에 가장 가까운 카드가 메인. 나머지는 줌인할수록 어둡게
          const isMain = Math.abs(pos(c.col, c.row).x - cam.x) + Math.abs(pos(c.col, c.row).y) < (W + GAP) / 2;
          return <CardView key={i} card={c} f={f} dim={isMain ? 0 : 0.15 + 0.5 * zoomed} />;
        })}
      </div>
      {heroOn && (
        <div
          style={{
            position: 'absolute',
            left: (width - hw) / 2,
            top: (height - hh) / 2,
            width: hw,
            height: hh,
            borderRadius: 30 * sIn * m,
            overflow: 'hidden',
            boxShadow: m > 0 ? `0 30px 80px rgba(0,0,0,${0.55 * m})` : undefined,
          }}
        >
          <Bridge w={hw} h={hh} t={f} copy={CARDS[0].copy} />
        </div>
      )}
      <AbsoluteFill style={{ background: `rgba(11,12,14,${0.9 * logo})`, justifyContent: 'center', alignItems: 'center', fontFamily: FONT, color: 'white', textAlign: 'center' }}>
        <div style={{ opacity: logo, transform: `scale(${0.94 + 0.06 * logo})`, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
          <Logo size={Math.min(width, height) * 0.2} style={{ borderRadius: Math.min(width, height) * 0.045, marginBottom: Math.min(width, height) * 0.04 }} />
          <div style={{ fontSize: Math.min(width, height) * 0.11, fontWeight: 900 }}>차~즈</div>
          <div style={{ fontSize: Math.min(width, height) * 0.038, color: C.text2, marginTop: 18 }}>달리는 차를 포토부스로</div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

// 9:16 단독 인트로 (숏폼): BGM 포함. Demo 에서는 BGM 을 전체에 따로 깐다
export const IntroReel: React.FC = () => (
  <>
    <Intro />
    <Audio src={staticFile('audio/bgm.wav')} volume={(f) => 0.6 * interpolate(f, [INTRO_FRAMES - 30, INTRO_FRAMES], [1, 0], clamp)} />
  </>
);
