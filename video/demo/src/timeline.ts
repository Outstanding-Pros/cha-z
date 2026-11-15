// 시연 영상 편집표. 여기 숫자만 고치면 컷·속도·자막·나레이션이 바뀐다.
// 시간 단위는 초. cuts 의 from/to 는 원본 클립 기준, captions·narr 의 at 은 장면 시작 기준(편집 후 시간).
// 나레이션 문장은 src/narration.json (scripts/gen_narration.py 로 음성 생성, 길이 자동 기록).

import { INTRO_FRAMES } from './Intro';
import NARRATION from './narration.json';

export const FPS = 30;
export const NARR_RATE = 1.08; // TTS 가 조금 느려서 살짝 빠르게 (피치 유지)

// xfade: 앞 컷과 겹쳐 서서히 바뀌는 길이(초)
export type Cut = { from: number; to: number; rate?: number; xfade?: number };
export type Caption = {
  at: number;
  dur: number;
  text: string;
  // voice = 동승자가 한 말(녹화엔 소리가 없어 자막으로), tts = 앱이 실제로 말한 문장(클립 소리에 있음)
  kind: 'voice' | 'tts';
  // 동승자 대사 음성 (narration.json 의 u_*). from: 앞 무음 건너뛰기(초), rate: 배속
  audio?: { id: string; from?: number; rate?: number };
};
// 나레이션: 자막과 음성을 함께. 길이는 narration.json 의 seconds
export type Narr = { id: string; at: number };

export type ClipScene = {
  type: 'clip';
  id: string;
  clip: string; // public/clips/<clip>.mp4
  label: string; // 왼쪽 위 장면 이름
  state?: '주행 중' | '정차 중';
  cuts: Cut[];
  captions: Caption[];
  narr?: Narr[];
  volume?: number;
  // 원본(2560x1440) 픽셀 기준 잘라 보기. 16:9 로. 주행 장면은 클러스터(기어 P 표시)와 디버그 문구를 가린다
  crop?: { x: number; y: number; w: number };
};

// 앱 창만 (클러스터·하단 공조 바 제외)
export const APP_CROP = { x: 864, y: 210, w: 1672 };

export type CardScene = { type: 'intro' | 'problem' | 'roadmap' | 'outro'; id: string; seconds: number; narr?: Narr[] };

export type Scene = ClipScene | CardScene;

export const SCENES: Scene[] = [
  { type: 'intro', id: 'intro', seconds: INTRO_FRAMES / 30 }, // 스타일 시안 판 (src/Intro.tsx), 나레이션 없이 BGM
  { type: 'problem', id: 'problem', seconds: 11.8, narr: [{ id: 'problem', at: 0.6 }] },
  {
    type: 'clip',
    id: 'spot',
    clip: '05_스팟가이드_추천촬영',
    label: '스팟 가이드 → 음성 촬영',
    state: '주행 중',
    crop: APP_CROP,
    cuts: [
      { from: 11.2, to: 17.0 },
      // "차~즈!" 뒤에 앱이 답하기까지 1초 남짓 틈을 둔다 (원본 22.4초 "네 컷 찍을게요")
      { from: 20.6, to: 40.0, rate: 1.2 },
    ],
    captions: [
      { at: 0.4, dur: 5.2, kind: 'tts', text: '곧 애월 해안도로 전망대예요. 창밖 풍경과 함께 찍으려면 "차~즈"라고 말해 주세요.' },
      { at: 5.9, dur: 1.2, kind: 'voice', text: '차~즈!', audio: { id: 'u_chaz', from: 0.25 } },
      { at: 7.3, dur: 1.6, kind: 'tts', text: '네 컷 찍을게요.' },
      { at: 19.05, dur: 2.6, kind: 'tts', text: '네 장 저장했어요. 정차 후 스타일을 골라요.' },
    ],
    narr: [{ id: 'spot', at: 9.8 }],
  },
  {
    type: 'clip',
    id: 'voice',
    clip: '03_촬영중_음성명령',
    label: '촬영 중 음성 명령 · 차 안 BGM',
    state: '주행 중',
    crop: APP_CROP,
    // 컷 지점은 말이 없는 틈: 원본 3.3~6.6 노래 안내, 6.8~8.6 "네 컷 찍을게요"(쓰지 않음), 8.5~17.5 하나·둘, 17.6~19.3 "멈췄어요" (11/15 STT 실측)
    // 노래 안내 → (서서히 전환) 촬영 중 화면에서 "차즈 그만"(느리게 늘려 말할 틈) → "멈췄어요"
    cuts: [
      { from: 1.7, to: 6.8 },
      { from: 16.85, to: 17.55, rate: 0.55, xfade: 0.5 },
      { from: 17.55, to: 25.0 },
    ],
    captions: [
      { at: 0.0, dur: 1.5, kind: 'voice', text: '차즈 노래 틀어줘', audio: { id: 'u_music', rate: 1.25 } },
      { at: 1.6, dur: 3.3, kind: 'tts', text: '산뜻한 드라이브 1번 곡 틀어드릴게요.' },
      { at: 5.1, dur: 1.3, kind: 'voice', text: '차즈 그만', audio: { id: 'u_stop', from: 0.18, rate: 1.3 } },
      { at: 6.42, dur: 1.7, kind: 'tts', text: '멈췄어요.' },
    ],
    narr: [{ id: 'voice', at: 8.2 }],
  },
  {
    type: 'clip',
    id: 'card',
    clip: '02_정차후_방금찍은카드',
    label: '정차하면 방금 찍은 컷 카드',
    state: '정차 중',
    cuts: [{ from: 0.8, to: 5.6 }],
    captions: [],
    narr: [{ id: 'card', at: 0.3 }],
  },
  {
    type: 'clip',
    id: 'preview',
    clip: '06_정차중_미리보기_레이아웃_필터_조명',
    label: '정차 중 미리보기 · 레이아웃 · 필터 · 조명',
    state: '정차 중',
    cuts: [
      { from: 2.3, to: 6.0, rate: 1.5 },
      { from: 9.6, to: 20.0, rate: 1.5 },
      { from: 20.8, to: 37.1, rate: 1.45 },
    ],
    captions: [],
    narr: [
      { id: 'preview1', at: 0.2 },
      { id: 'preview2', at: 6.6 },
      { id: 'preview3', at: 10.9 },
      { id: 'preview4', at: 15.6 },
    ],
  },
  {
    type: 'clip',
    id: 'fourcut',
    clip: '14_네컷프레임_만들기',
    label: '네컷 프레임 만들기',
    state: '정차 중',
    cuts: [{ from: 1.5, to: 12.5, rate: 1.4 }],
    captions: [],
    narr: [{ id: 'fourcut', at: 0.3 }],
  },
  {
    type: 'clip',
    id: 'fourcut-edit',
    clip: '15_네컷편집_프레임_필터_저장',
    label: '네컷 편집 · 저장',
    state: '정차 중',
    cuts: [{ from: 0.5, to: 20.0, rate: 2 }],
    captions: [],
    narr: [{ id: 'fourcut2', at: 0.3 }],
  },
  {
    type: 'clip',
    id: 'reel-direct',
    clip: '08_릴스_직접연출_쌓아서공개',
    label: '릴스 직접 연출',
    state: '정차 중',
    cuts: [{ from: 1.0, to: 30.0, rate: 2.5 }],
    captions: [],
    narr: [{ id: 'reel', at: 0.3 }],
  },
  {
    type: 'clip',
    id: 'reel-style',
    clip: '09_스타일2_오션브릿지',
    label: '릴스 스타일 · 오션 브릿지',
    state: '정차 중',
    cuts: [{ from: 55.0, to: 80.0, rate: 2 }],
    captions: [],
    narr: [{ id: 'style', at: 0.3 }],
  },
  {
    type: 'clip',
    id: 'album',
    clip: '07_앨범_릴스모음',
    label: '앨범 · 릴스 모음',
    state: '정차 중',
    cuts: [{ from: 2.5, to: 30.0, rate: 2 }],
    captions: [],
    narr: [{ id: 'album', at: 0.3 }],
  },

  { type: 'roadmap', id: 'roadmap', seconds: 10.0, narr: [{ id: 'roadmap', at: 0.5 }] },
  { type: 'outro', id: 'outro', seconds: 6.2, narr: [{ id: 'outro', at: 0.4 }] },
];

const NARR = Object.fromEntries((NARRATION as { id: string; text: string; seconds: number }[]).map((n) => [n.id, n]));
export const narrText = (id: string) => NARR[id].text;
/** 음성 파일 원래 길이(초) */
export const lineSeconds = (id: string) => NARR[id].seconds;
/** 편집 후 시간 기준 나레이션 길이 (배속 반영) */
export const narrSeconds = (id: string) => NARR[id].seconds / NARR_RATE;

export const cutSeconds = (c: Cut) => (c.to - c.from) / (c.rate ?? 1);
export const sceneSeconds = (s: Scene) =>
  s.type === 'clip' ? s.cuts.reduce((a, c) => a + cutSeconds(c), 0) : s.seconds;
export const sec = (s: number) => Math.round(s * FPS);
export const sceneFrames = (s: Scene) =>
  s.type === 'clip' ? s.cuts.reduce((a, c) => a + sec(cutSeconds(c)), 0) : sec(s.seconds);
export const totalFrames = () => SCENES.reduce((a, s) => a + sceneFrames(s), 0);

/** 장면보다 긴 나레이션 찾기 (편집표 점검용) */
export const narrOverflows = () =>
  SCENES.flatMap((s) => (s.narr ?? []).map((n) => ({ scene: s.id, id: n.id, end: n.at + narrSeconds(n.id), len: sceneSeconds(s) }))).filter(
    (x) => x.end > x.len - 0.2,
  );
