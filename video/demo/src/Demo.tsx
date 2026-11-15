import React from 'react';
import { AbsoluteFill, Audio, interpolate, Series, staticFile } from 'remotion';
import { NarrLayer } from './Captions';
import { Outro, Problem, Roadmap } from './Cards';
import { ClipScene } from './ClipScene';
import { Intro } from './Intro';
import { C } from './theme';
import { SCENES, sceneFrames, totalFrames } from './timeline';

// 장면 시작 프레임
const STARTS = SCENES.map((_, i) => SCENES.slice(0, i).reduce((a, s) => a + sceneFrames(s), 0));
const startOf = (id: string) => STARTS[SCENES.findIndex((s) => s.id === id)];
const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;

// BGM: 인트로는 크게, 나레이션부터 잔잔하게, 앱 소리가 있는 주행 장면은 더 작게, 끝에서 페이드아웃
const bgmVolume = (f: number) => {
  const introEnd = startOf('problem');
  const driveA = startOf('spot');
  const driveB = startOf('card');
  const total = totalFrames();
  const base = interpolate(f, [introEnd - 20, introEnd + 10], [0.55, 0.13], clamp);
  const drive = f >= driveA && f < driveB ? 0.6 : 1;
  const end = interpolate(f, [total - 60, total], [1, 0], clamp);
  return base * drive * end;
};

export const Demo: React.FC = () => (
  <AbsoluteFill style={{ background: C.bg }}>
    <Audio src={staticFile('audio/bgm.wav')} volume={bgmVolume} />
    <Series>
      {SCENES.map((s) => (
        <Series.Sequence key={s.id} durationInFrames={sceneFrames(s)} name={s.id}>
          {s.type === 'clip' ? (
            <ClipScene scene={s} />
          ) : s.type === 'intro' ? (
            <Intro />
          ) : s.type === 'problem' ? (
            <Problem />
          ) : s.type === 'roadmap' ? (
            <Roadmap />
          ) : (
            <Outro />
          )}
          <NarrLayer narr={s.narr} />
        </Series.Sequence>
      ))}
    </Series>
  </AbsoluteFill>
);
