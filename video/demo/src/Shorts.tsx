// 숏폼 3편 (9:16, 1080×1920, 30초~1분). 공모전 숏폼 규정: 세로, #HMG차량용앱개발공모전 포함 해시태그 2~3개(업로드 설명에).
// 릴스 안전 영역(위 270 · 아래 670 · 좌우 65px) 안에 제목·화면·자막을 둔다. 결과를 먼저 보여 주고(훅) 과정을 잇는다.
import React from 'react';
import { AbsoluteFill, Audio, interpolate, OffthreadVideo, Sequence, Series, staticFile, useCurrentFrame } from 'remotion';
import { wrap2 } from './Captions';
import { Intro, INTRO_FRAMES } from './Intro';
import { Logo } from './Logo';
import { C, FONT } from './theme';
import { lineSeconds, NARR_RATE, narrSeconds, narrText, sec } from './timeline';

const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;

type Crop = { x: number; y: number; w: number; h: number }; // 원본 2560×1440 기준
// 폰 화면에서 읽히도록 장면마다 필요한 부분만 크게 (좌표는 원본 프레임 격자로 실측)
const DRIVE: Crop = { x: 1300, y: 330, w: 800, h: 680 }; // 주행 화면: 버튼·진행·상태 (클러스터·디버그 문구 제외)
const PREVIEW: Crop = { x: 995, y: 111, w: 1163, h: 900 }; // 정차 미리보기 카메라 영역
const CONSOLE: Crop = { x: 990, y: 100, w: 1180, h: 1170 }; // 미리보기 + 아래 레이아웃·필터 버튼
const REEL_RESULT: Crop = { x: 2140, y: 170, w: 370, h: 600 }; // 릴스 만들기 결과(세로)
const REEL_STYLE: Crop = { x: 1460, y: 100, w: 1060, h: 900 }; // 스타일 고르기 + 결과
const FOURCUT: Crop = { x: 1300, y: 100, w: 1210, h: 1010 }; // 네 컷 스트립 + 편집 패널
const ALBUM: Crop = { x: 1580, y: 150, w: 920, h: 1120 }; // 앨범에서 재생 중인 릴스

type Cap = { at: number; dur: number; text: string; kind: 'tts' | 'voice'; audio?: { id: string; from?: number; rate?: number } };
type Seg = {
  clip: string;
  from: number;
  to: number;
  rate?: number;
  crop: Crop;
  title?: string; // 위 제목 (세그먼트마다 바뀜)
  narr?: { id: string; at: number }[];
  caps?: Cap[];
  volume?: number;
};
type Short = { id: string; segs: Seg[] };

const segSeconds = (s: Seg) => (s.to - s.from) / (s.rate ?? 1);
const END_CARD = 2.6;

export const SHORTS: Short[] = [
  {
    id: 'ShortShoot',
    segs: [
      // 훅: 결과 먼저
      { clip: '06_정차중_미리보기_레이아웃_필터_조명', from: 10.2, to: 15.2, crop: PREVIEW, title: '차 안에서 이런 사진,\n말 한마디로', narr: [{ id: 's_hook_shoot', at: 0.1 }], volume: 0 },
      {
        clip: '05_스팟가이드_추천촬영', from: 11.2, to: 17.0, crop: DRIVE, title: '명소가 가까워지면\n차가 먼저 알려 줘요',
        caps: [{ at: 0.4, dur: 5.2, kind: 'tts', text: '곧 애월 해안도로 전망대예요. 창밖 풍경과 함께 찍으려면 "차~즈"라고 말해 주세요.' }],
      },
      {
        clip: '05_스팟가이드_추천촬영', from: 20.6, to: 39.6, rate: 1.35, crop: DRIVE, title: '"차~즈" 한마디면\n하나, 둘에 맞춰 네 컷',
        caps: [
          { at: 0.1, dur: 1.2, kind: 'voice', text: '차~즈!', audio: { id: 'u_chaz', from: 0.25 } },
          { at: 1.35, dur: 1.4, kind: 'tts', text: '네 컷 찍을게요.' },
          { at: 11.5, dur: 2.4, kind: 'tts', text: '네 장 저장했어요. 정차 후 스타일을 골라요.' },
        ],
      },
      { clip: '02_정차후_방금찍은카드', from: 0.8, to: 5.6, crop: CONSOLE, title: '정차하면\n방금 찍은 네 컷', narr: [{ id: 'card', at: 0.2 }] },
      { clip: '06_정차중_미리보기_레이아웃_필터_조명', from: 10.2, to: 15.6, crop: PREVIEW, title: '실내 인물 + 창밖 풍경\n한 장에', narr: [{ id: 's_l1', at: 0.2 }], volume: 0 },
    ],
  },
  {
    id: 'ShortStudio',
    segs: [
      { clip: '09_스타일2_오션브릿지', from: 72.5, to: 75.5, crop: REEL_RESULT, title: '정차하면\n차가 스튜디오가 돼요', narr: [{ id: 's_hook_studio', at: 0.1 }], volume: 0 },
      { clip: '06_정차중_미리보기_레이아웃_필터_조명', from: 9.6, to: 20.0, rate: 1.5, crop: CONSOLE, title: '차창 합성 ·\n필름 스트립', narr: [{ id: 'preview2', at: 0.2 }], volume: 0 },
      {
        clip: '06_정차중_미리보기_레이아웃_필터_조명', from: 20.8, to: 37.1, rate: 1.65, crop: CONSOLE, title: '필터 · 차 안 조명',
        narr: [{ id: 'preview3', at: 0.1 }, { id: 'preview4', at: 4.95 }], volume: 0,
      },
      { clip: '15_네컷편집_프레임_필터_저장', from: 0.5, to: 20.0, rate: 2.4, crop: FOURCUT, title: '네 컷 프레임 편집', narr: [{ id: 'fourcut2', at: 0.2 }], volume: 0 },
      { clip: '09_스타일2_오션브릿지', from: 55.0, to: 80.0, rate: 2.7, crop: REEL_STYLE, title: '스타일 프리셋으로\n세로 릴스 완성', narr: [{ id: 'style', at: 0.2 }], volume: 0 },
      { clip: '07_앨범_릴스모음', from: 2.5, to: 30.0, rate: 3.6, crop: ALBUM, title: '앨범에 모아\n폰으로 공유', narr: [{ id: 'album', at: 0.2 }], volume: 0 },
    ],
  },
];

export const shortFrames = (s: Short) => s.segs.reduce((a, g) => a + sec(segSeconds(g)), 0) + sec(END_CARD);

// ---------- 화면 ----------
const BOX = { top: 490, w: 1000, maxH: 790 }; // 앱 화면 자리 (위 제목 아래 ~ 아래 안전 영역)

const SegView: React.FC<{ seg: Seg }> = ({ seg }) => {
  const f = useCurrentFrame();
  const c = seg.crop;
  const bh = Math.min(BOX.maxH, (BOX.w * c.h) / c.w);
  // 세로로 긴 크롭은 높이에 맞춰 폭이 줄어든다
  const bw = (bh * c.w) / c.h;
  const k = bw / c.w;
  const fade = interpolate(f, [0, 5], [0, 1], clamp);
  // 나레이션·대사가 나오는 동안 클립 소리를 낮춘다
  const spans = [
    ...(seg.narr ?? []).map((n) => [sec(n.at), sec(n.at + narrSeconds(n.id))]),
    ...(seg.caps ?? []).filter((x) => x.audio).map((x) => [sec(x.at), sec(x.at + 1.2)]),
  ];
  const vol = (fr: number) => (seg.volume ?? 1) * (spans.some(([a, b]) => fr >= a - 5 && fr <= b + 5) ? 0.3 : 1);
  return (
    <AbsoluteFill>
      {seg.title && (
        <div
          style={{
            position: 'absolute',
            top: 300,
            left: 65,
            right: 65,
            textAlign: 'center',
            fontFamily: FONT,
            fontSize: 66,
            fontWeight: 900,
            lineHeight: 1.22,
            color: 'white',
            whiteSpace: 'pre-line',
            opacity: fade,
            transform: `translateY(${(1 - fade) * 14}px)`,
            textShadow: '0 4px 20px rgba(0,0,0,.5)',
          }}
        >
          {seg.title}
        </div>
      )}
      <div
        style={{
          position: 'absolute',
          top: BOX.top,
          left: (1080 - bw) / 2,
          width: bw,
          height: bh,
          overflow: 'hidden',
          borderRadius: 28,
          boxShadow: '0 30px 80px rgba(0,0,0,.6)',
          opacity: fade,
        }}
      >
        <OffthreadVideo
          src={staticFile(`clips/${seg.clip}.mp4`)}
          trimBefore={sec(seg.from)}
          trimAfter={sec(seg.to)}
          playbackRate={seg.rate ?? 1}
          volume={vol}
          style={{ position: 'absolute', width: 2560 * k, height: 1440 * k, left: -c.x * k, top: -c.y * k, maxWidth: 'none' }}
        />
      </div>
      {(seg.narr ?? []).map((n) => (
        <Sequence key={n.id} from={sec(n.at)} durationInFrames={sec(narrSeconds(n.id) + 0.2)} layout="none">
          <Audio src={staticFile(`audio/narr/${n.id}.mp3`)} playbackRate={NARR_RATE} />
          <VCap text={narrText(n.id)} kind="narr" dur={narrSeconds(n.id) + 0.2} top={BOX.top + bh - 60} />
        </Sequence>
      ))}
      {(seg.caps ?? []).map((x, i) => (
        <Sequence key={i} from={sec(x.at)} durationInFrames={sec(x.dur)} layout="none">
          {x.audio && <Audio src={staticFile(`audio/narr/${x.audio.id}.mp3`)} trimBefore={sec(x.audio.from ?? 0)} playbackRate={x.audio.rate ?? 1} />}
          <VCap text={x.text} kind={x.kind} dur={x.dur} top={BOX.top + bh - 60} />
        </Sequence>
      ))}
    </AbsoluteFill>
  );
};

// 자막: 앱 화면 아랫단에 걸쳐서 (아래 안전 영역 1250px 근처)
const VCap: React.FC<{ text: string; kind: 'narr' | 'tts' | 'voice'; dur: number; top: number }> = ({ text, kind, dur, top }) => {
  const f = useCurrentFrame();
  const d = sec(dur);
  const o = interpolate(f, [0, 5, d - 5, d], [0, 1, 1, 0], clamp);
  const bg = kind === 'voice' ? C.accent : kind === 'tts' ? 'rgba(22,24,27,0.95)' : 'rgba(255,255,255,0.95)';
  const color = kind === 'narr' ? '#111' : 'white';
  const who = kind === 'voice' ? '🗣 동승자  ' : kind === 'tts' ? '🔊 차~즈  ' : '';
  return (
    <div
      style={{
        position: 'absolute',
        left: 65,
        right: 65,
        top,
        display: 'flex',
        justifyContent: 'center',
        opacity: o,
      }}
    >
      <div
        style={{
          background: bg,
          color,
          border: kind === 'tts' ? `3px solid ${C.accent}` : 'none',
          fontFamily: FONT,
          fontSize: 38,
          fontWeight: 800,
          lineHeight: 1.35,
          padding: '14px 26px',
          borderRadius: 20,
          textAlign: 'center',
          whiteSpace: 'pre-line',
          boxShadow: '0 10px 30px rgba(0,0,0,.45)',
        }}
      >
        {who}
        {wrap2(text, 24)}
      </div>
    </div>
  );
};

const EndCard: React.FC = () => {
  const f = useCurrentFrame();
  const o = interpolate(f, [0, 8], [0, 1], clamp);
  return (
    <AbsoluteFill style={{ background: C.bg, justifyContent: 'center', alignItems: 'center', fontFamily: FONT, color: 'white', textAlign: 'center', opacity: o }}>
      <div style={{ marginTop: -200, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        <Logo size={220} style={{ borderRadius: 50 }} />
        <div style={{ fontSize: 120, fontWeight: 900, marginTop: 40 }}>차~즈</div>
        <div style={{ fontSize: 46, color: C.text2, marginTop: 10 }}>달리는 차를 포토부스로</div>
        <div style={{ fontSize: 40, fontWeight: 800, marginTop: 70, background: C.accent, padding: '18px 36px', borderRadius: 40 }}>전체 시연 영상은 채널에서 ▶</div>
        <div style={{ fontSize: 30, color: C.text2, marginTop: 40 }}>현대자동차그룹 차량용 앱 개발 공모전 출품작</div>
      </div>
    </AbsoluteFill>
  );
};

const Bg: React.FC = () => <AbsoluteFill style={{ background: `radial-gradient(900px 1200px at 50% 45%, #15233d 0%, ${C.bg} 75%)` }} />;

export const ShortView: React.FC<{ id: string }> = ({ id }) => {
  const s = SHORTS.find((x) => x.id === id)!;
  const total = shortFrames(s);
  return (
    <AbsoluteFill>
      <Bg />
      <Audio src={staticFile('audio/bgm.wav')} volume={(f) => 0.16 * interpolate(f, [total - 30, total], [1, 0], clamp)} />
      <Series>
        {s.segs.map((g, i) => (
          <Series.Sequence key={i} durationInFrames={sec(segSeconds(g))}>
            <SegView seg={g} />
          </Series.Sequence>
        ))}
        <Series.Sequence durationInFrames={sec(END_CARD)}>
          <EndCard />
        </Series.Sequence>
      </Series>
    </AbsoluteFill>
  );
};

// ① 인트로 숏폼: 스타일 시안 인트로 + 엔드카드
export const SHORT_INTRO_FRAMES = INTRO_FRAMES + sec(END_CARD);
export const ShortIntro: React.FC = () => (
  <AbsoluteFill>
    <Audio src={staticFile('audio/bgm.wav')} volume={(f) => 0.6 * interpolate(f, [SHORT_INTRO_FRAMES - 30, SHORT_INTRO_FRAMES], [1, 0], clamp)} />
    <Series>
      <Series.Sequence durationInFrames={INTRO_FRAMES}>
        <Intro />
      </Series.Sequence>
      <Series.Sequence durationInFrames={sec(END_CARD)}>
        <EndCard />
      </Series.Sequence>
    </Series>
  </AbsoluteFill>
);

export const lineLen = lineSeconds; // (편집표 점검용)
