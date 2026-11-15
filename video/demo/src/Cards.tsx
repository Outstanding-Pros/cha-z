import React from 'react';
import { AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
import { C, FONT } from './theme';

const fade = (f: number, total: number) =>
  interpolate(f, [0, 8, total - 8, total], [0, 1, 1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });

const Base: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const f = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  return (
    <AbsoluteFill
      style={{
        background: `radial-gradient(1200px 700px at 50% 40%, #13213a 0%, ${C.bg} 70%)`,
        color: C.text,
        fontFamily: FONT,
        justifyContent: 'center',
        alignItems: 'center',
        opacity: fade(f, durationInFrames),
      }}
    >
      {children}
    </AbsoluteFill>
  );
};

const Photo: React.FC<{ src: string; label: string; top?: boolean; style?: React.CSSProperties }> = ({ src, label, top, style }) => (
  <div style={{ position: 'relative', borderRadius: 24, overflow: 'hidden', boxShadow: '0 20px 60px rgba(0,0,0,.5)', ...style }}>
    <Img src={staticFile(src)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
    <div
      style={{
        position: 'absolute',
        left: 16,
        ...(top ? { top: 16 } : { bottom: 16 }),
        background: 'rgba(11,12,14,.85)',
        padding: '8px 16px',
        borderRadius: 10,
        fontSize: 26,
        fontWeight: 700,
      }}
    >
      {label}
    </div>
  </div>
);

export const Problem: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  // 설명하는 동안 두 화면이 같은 시간 보이도록 장면 절반(5.9초)에서 합쳐짐
  const merge = spring({ frame: f - 5.9 * fps, fps, config: { damping: 16 } });
  const split = 1 - merge;
  return (
    <Base>
      <div style={{ position: 'absolute', top: 150, width: '100%', textAlign: 'center' }}>
        <div style={{ fontSize: 56, fontWeight: 800, opacity: split }}>
          차 안에서는 사람과 풍경을 한 장에 담기 어렵다
        </div>
        <div style={{ fontSize: 56, fontWeight: 800, marginTop: -72, opacity: merge, color: C.accent }}>
          "차~즈" 한마디면 실내 인물 + 창밖 풍경을 한 장에
        </div>
      </div>
      <div style={{ position: 'absolute', top: 290, width: 1600, height: 700 }}>
        <Photo
          src="stills/indoor.jpg"
          label="실내"
          style={{ position: 'absolute', width: 720, height: 520, left: 20 + 400 * merge, top: 30, opacity: split, transform: `rotate(${-3 * split}deg)` }}
        />
        <Photo
          src="stills/road.jpg"
          label="창밖"
          style={{ position: 'absolute', width: 720, height: 520, left: 860 - 400 * merge, top: 30, opacity: split, transform: `rotate(${3 * split}deg)` }}
        />
        {/* 실내 + 창밖 */}
        <div
          style={{
            position: 'absolute',
            left: 800 - 46,
            top: 30 + 260 - 46,
            width: 92,
            height: 92,
            borderRadius: 46,
            background: C.accent,
            color: 'white',
            fontSize: 64,
            fontWeight: 800,
            lineHeight: '86px',
            textAlign: 'center',
            opacity: split,
            transform: `scale(${0.6 + 0.4 * split})`,
            boxShadow: '0 10px 30px rgba(0,0,0,.5)',
            zIndex: 2,
          }}
        >
          +
        </div>
        <Photo
          src="stills/composite.jpg"
          label="차~즈 L1 차창 합성 (외부 영상 시뮬레이션)"
          top
          style={{ position: 'absolute', width: 880, height: 570, left: 360, top: 10, opacity: merge, transform: `scale(${0.85 + 0.15 * merge})` }}
        />
      </div>
    </Base>
  );
};

const ROADMAP = [
  ['🎨', '스타일 퀄리티 고도화', '스타일마다 연출·색감·편집을 더 정교하게'],
  ['💳', '유료 스타일 제작', '시즌·테마별 스타일 팩을 유료로 제공'],
  ['🗺️', '관광 API 연동', '유명 스팟에 도착하면 촬영 포인트를 안내'],
];

export const Roadmap: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <Base>
      <div style={{ width: 1500 }}>
        <div style={{ fontSize: 60, fontWeight: 800, marginBottom: 50 }}>다음 단계</div>
        {ROADMAP.map(([icon, title, desc], i) => {
          const s = spring({ frame: f - 8 - i * 8, fps, config: { damping: 18 } });
          return (
            <div
              key={title}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 30,
                background: C.surface,
                border: `1px solid ${C.line}`,
                borderRadius: 20,
                padding: '26px 34px',
                marginBottom: 22,
                opacity: s,
                transform: `translateX(${(1 - s) * 60}px)`,
              }}
            >
              <span style={{ fontSize: 54 }}>{icon}</span>
              <span style={{ fontSize: 40, fontWeight: 800, width: 360 }}>{title}</span>
              <span style={{ fontSize: 32, color: C.text2 }}>{desc}</span>
            </div>
          );
        })}
      </div>
    </Base>
  );
};

export const Outro: React.FC = () => (
  <Base>
    <div style={{ textAlign: 'center' }}>
      <div style={{ fontSize: 110, fontWeight: 800 }}>차~즈</div>
      <div style={{ fontSize: 40, color: C.text2, marginTop: 24 }}>
        음성으로 찍고 · 정차하면 편집하고 · 도착하면 폰으로
      </div>
      <div style={{ fontSize: 30, color: C.text2, marginTop: 70 }}>github.com/Outstanding-Pros/cha-z</div>
    </div>
  </Base>
);
