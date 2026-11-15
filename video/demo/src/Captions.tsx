import React from 'react';
import { AbsoluteFill, Audio, interpolate, Sequence, staticFile, useCurrentFrame } from 'remotion';
import { C, FONT } from './theme';
import { Narr, NARR_RATE, narrSeconds, narrText, sec } from './timeline';

type Kind = 'voice' | 'tts' | 'narr';

/** 긴 자막은 두 줄로: 가운데에 가장 가까운 문장·쉼표 경계(없으면 띄어쓰기)에서 끊는다. 최대 2줄 */
export const wrap2 = (text: string, one = 34): string => {
  if (text.includes('\n') || text.length <= one) return text; // 직접 줄바꿈한 문장은 그대로
  const mid = text.length / 2;
  const cands: { i: number; w: number }[] = [];
  for (let i = 1; i < text.length - 1; i++) {
    if (text[i] !== ' ') continue;
    const prev = text[i - 1];
    const w = '.?!'.includes(prev) ? 0 : prev === ',' ? 4 : 10; // 문장 끝 > 쉼표 > 띄어쓰기
    cands.push({ i, w });
  }
  if (!cands.length) return text;
  const best = cands.reduce((a, c) => (Math.abs(c.i - mid) + c.w < Math.abs(a.i - mid) + a.w ? c : a));
  return text.slice(0, best.i) + '\n' + text.slice(best.i + 1);
};

export const CaptionView: React.FC<{ kind: Kind; text: string; dur: number }> = ({ kind, text, dur }) => {
  const f = useCurrentFrame();
  const d = sec(dur);
  const opacity = interpolate(f, [0, 6, d - 6, d], [0, 1, 1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const y = interpolate(f, [0, 8], [12, 0], { extrapolateRight: 'clamp' });
  const icon = kind === 'voice' ? '🗣 동승자' : kind === 'tts' ? '🔊 차~즈' : '';
  const bg = kind === 'voice' ? C.accent : kind === 'tts' ? 'rgba(22,24,27,0.94)' : 'rgba(11,12,14,0.88)';
  return (
    <AbsoluteFill style={{ justifyContent: 'flex-end', alignItems: 'center', paddingBottom: 26 }}>
      <div
        style={{
          opacity,
          transform: `translateY(${y}px)`,
          maxWidth: 1560,
          background: bg,
          border: kind === 'tts' ? `2px solid ${C.accent}` : 'none',
          color: C.text,
          fontFamily: FONT,
          fontSize: kind === 'narr' ? 36 : 38,
          fontWeight: kind === 'voice' ? 800 : 600,
          lineHeight: 1.4,
          padding: '14px 30px',
          borderRadius: 18,
          boxShadow: '0 8px 30px rgba(0,0,0,0.45)',
          display: 'flex',
          gap: 14,
          alignItems: 'center',
          textAlign: 'center',
        }}
      >
        {icon && <span style={{ fontSize: 28, opacity: 0.85, whiteSpace: 'nowrap' }}>{icon}</span>}
        <span style={{ whiteSpace: 'pre-line' }}>{wrap2(text)}</span>
      </div>
    </AbsoluteFill>
  );
};

/** 나레이션: 음성(비즈라우터 TTS) + 같은 문장 자막 */
export const NarrLayer: React.FC<{ narr?: Narr[] }> = ({ narr }) => (
  <>
    {(narr ?? []).map((n) => (
      <Sequence key={n.id} from={sec(n.at)} durationInFrames={sec(narrSeconds(n.id) + 0.25)} layout="none">
        <Audio src={staticFile(`audio/narr/${n.id}.mp3`)} playbackRate={NARR_RATE} />
        <CaptionView kind="narr" text={narrText(n.id)} dur={narrSeconds(n.id) + 0.25} />
      </Sequence>
    ))}
  </>
);
