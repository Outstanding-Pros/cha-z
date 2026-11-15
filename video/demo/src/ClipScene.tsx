import React from 'react';
import { AbsoluteFill, Audio, interpolate, OffthreadVideo, Sequence, staticFile, useCurrentFrame } from 'remotion';
import { C, FONT } from './theme';
import { CaptionView } from './Captions';
import { ClipScene as ClipSceneT, cutSeconds, lineSeconds, narrSeconds, sec } from './timeline';

const Label: React.FC<{ label: string; state?: string }> = ({ label, state }) => {
  const f = useCurrentFrame();
  const opacity = interpolate(f, [0, 8], [0, 1], { extrapolateRight: 'clamp' });
  const color = state === '주행 중' ? C.rec : C.ok;
  return (
    <div
      style={{
        position: 'absolute',
        top: 22,
        left: 22,
        opacity,
        display: 'flex',
        gap: 12,
        alignItems: 'center',
        background: 'rgba(11,12,14,0.9)',
        color: C.text,
        fontFamily: FONT,
        fontSize: 30,
        fontWeight: 700,
        padding: '10px 20px',
        borderRadius: 14,
      }}
    >
      {state && (
        <span style={{ display: 'flex', alignItems: 'center', gap: 8, color, fontSize: 24 }}>
          <span style={{ width: 12, height: 12, borderRadius: 6, background: color }} />
          {state}
        </span>
      )}
      <span>{label}</span>
    </div>
  );
};

// 컷 전환: 앞 컷 위로 어두워졌다가(전반) 새 컷이 드러난다(후반). 레이아웃이 다른 화면이 겹쳐 보이지 않게
const XFade: React.FC<{ frames: number; children: React.ReactNode }> = ({ frames, children }) => {
  const f = useCurrentFrame();
  if (!frames) return <AbsoluteFill>{children}</AbsoluteFill>;
  const c = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;
  const dark = interpolate(f, [0, frames / 2], [0, 1], c);
  const show = interpolate(f, [frames / 2, frames], [0, 1], c);
  return (
    <>
      <AbsoluteFill style={{ background: 'black', opacity: dark }} />
      <AbsoluteFill style={{ opacity: show }}>{children}</AbsoluteFill>
    </>
  );
};

export const ClipScene: React.FC<{ scene: ClipSceneT }> = ({ scene }) => {
  const f = useCurrentFrame();
  const fadeIn = interpolate(f, [0, 6], [0, 1], { extrapolateRight: 'clamp' });
  const src = staticFile(`clips/${scene.clip}.mp4`);
  // 원본 2560 → 화면 1920. crop 이 있으면 그 영역이 화면을 채우게 확대
  const k = 1920 / 2560;
  const zoom = scene.crop ? 2560 / scene.crop.w : 1;
  const videoStyle: React.CSSProperties = scene.crop
    ? { position: 'absolute', width: 1920 * zoom, height: 1080 * zoom, left: -scene.crop.x * k * zoom, top: -scene.crop.y * k * zoom }
    : { width: '100%', height: '100%' };
  // 나레이션·동승자 대사가 나오는 동안 클립 소리(앱 TTS·BGM)를 낮춘다
  const voiceLen = (c: ClipSceneT['captions'][number]) => (lineSeconds(c.audio!.id) - (c.audio!.from ?? 0)) / (c.audio!.rate ?? 1);
  const duck = [
    ...(scene.narr ?? []).map((n) => [sec(n.at), sec(n.at + narrSeconds(n.id))]),
    ...scene.captions.filter((c) => c.audio).map((c) => [sec(c.at), sec(c.at + voiceLen(c))]),
  ];
  const starts = scene.cuts.map((_, i) => scene.cuts.slice(0, i).reduce((a, c) => a + sec(cutSeconds(c)), 0));
  const vol = (sceneFrame: number) => (scene.volume ?? 1) * (duck.some(([a, b]) => sceneFrame >= a - 6 && sceneFrame <= b + 6) ? 0.3 : 1);
  return (
    <AbsoluteFill style={{ background: C.bg, opacity: fadeIn }}>
      {scene.cuts.map((c, i) => {
        // xfade: 이 컷을 앞 컷 끝에 겹쳐 시작해 서서히 드러낸다 (화면이 툭 끊기지 않게)
        const x = i > 0 ? sec(c.xfade ?? 0) : 0;
        const rate = c.rate ?? 1;
        const from = starts[i] - x;
        return (
          <Sequence key={i} from={from} durationInFrames={sec(cutSeconds(c)) + x} layout="none">
            <XFade frames={x}>
              <OffthreadVideo
                src={src}
                trimBefore={Math.max(0, sec(c.from) - Math.round(x * rate))}
                trimAfter={sec(c.to)}
                playbackRate={rate}
                volume={(f) => vol(from + f) * (x ? Math.min(1, f / x) : 1)}
                style={videoStyle}
              />
            </XFade>
          </Sequence>
        );
      })}
      <Label label={scene.label} state={scene.state} />
      {scene.captions.map((c, i) => (
        <Sequence key={i} from={sec(c.at)} durationInFrames={sec(c.dur)} layout="none">
          <CaptionView kind={c.kind} text={c.text} dur={c.dur} />
          {c.audio && (
            <Audio src={staticFile(`audio/narr/${c.audio.id}.mp3`)} trimBefore={sec(c.audio.from ?? 0)} playbackRate={c.audio.rate ?? 1} />
          )}
        </Sequence>
      ))}
    </AbsoluteFill>
  );
};
