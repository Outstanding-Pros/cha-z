// 시연 영상 한국어 자막(SRT): 편집표(timeline.ts)의 나레이션·대사 시간을 그대로 옮긴다.
//   npx tsx scripts/srt.ts  → subtitles/chaz-demo.ko.srt
import { writeFileSync } from 'node:fs';
import { wrap2 } from '../src/Captions';
import { FPS, SCENES, narrSeconds, narrText, sceneFrames } from '../src/timeline';

type Cue = { start: number; end: number; text: string };
const cues: Cue[] = [];
let at = 0; // 장면 시작(초)
for (const s of SCENES) {
  for (const n of s.narr ?? []) cues.push({ start: at + n.at, end: at + n.at + narrSeconds(n.id) + 0.25, text: narrText(n.id) });
  if (s.type === 'clip')
    for (const c of s.captions)
      cues.push({ start: at + c.at, end: at + c.at + c.dur, text: c.kind === 'voice' ? `[동승자] ${c.text}` : `[차~즈] ${c.text}` });
  at += sceneFrames(s) / FPS;
}
cues.sort((a, b) => a.start - b.start);
// 겹치면 앞 자막을 다음 자막 시작 직전에 끊는다
for (let i = 0; i < cues.length - 1; i++) cues[i].end = Math.min(cues[i].end, cues[i + 1].start - 0.05);
const ts = (t: number) => {
  const ms = Math.round(t * 1000);
  const p = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${p(Math.floor(ms / 3600000))}:${p(Math.floor(ms / 60000) % 60)}:${p(Math.floor(ms / 1000) % 60)},${p(ms % 1000, 3)}`;
};
const srt = cues.map((c, i) => `${i + 1}\n${ts(c.start)} --> ${ts(c.end)}\n${wrap2(c.text)}\n`).join('\n');
writeFileSync('subtitles/chaz-demo.ko.srt', srt);
console.log(`${cues.length} cues, last ends ${ts(cues[cues.length - 1].end)}`);
