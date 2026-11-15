// 시연 영상 BGM. 차량 앱 car-app/.../media/BgmSynth.kt 를 그대로 옮긴 합성기 → 차~즈 자체 제작 음원(저작권 문제 없음).
//   node scripts/bgm.mjs [mood=BRIGHT] [seconds=200] [variant=0] [out=public/audio/bgm.wav]
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

const [mood = 'BRIGHT', seconds = '200', variant = '0', out = 'public/audio/bgm.wav'] = process.argv.slice(2);
const RATE = 44100;
const BPM = { BRIGHT: 120, OCEAN: 120, NIGHT: 80, TRAILER: 90, PLAYFUL: 120 }[mood];
const chord = (...f) => f;
const POP = [chord(261.63, 329.63, 392.0), chord(196.0, 246.94, 293.66), chord(220.0, 261.63, 329.63), chord(174.61, 220.0, 261.63)];
const NIGHT = [chord(220.0, 261.63, 329.63), chord(174.61, 220.0, 261.63), chord(261.63, 329.63, 392.0), chord(196.0, 246.94, 293.66)];
const DARK = [chord(110.0, 130.81, 164.81), chord(87.31, 110.0, 130.81)];
const KEYS = [0, 2, -3, 5, -5];
const PI = Math.PI;
const mod = (a, b) => ((a % b) + b) % b;

const pad = (ch, t, a) => ch.reduce((s, f) => s + a * Math.sin(2 * PI * f * t) * (0.8 + 0.2 * Math.sin(2 * PI * 0.5 * t)), 0);
const env = (x, att, dec) => (x < att ? x / att : Math.exp((-(x - att) / dec) * 3));
const kick = (x) => (x > 0.25 ? 0 : 0.55 * Math.sin(2 * PI * (50 + 70 * Math.exp(-x * 30)) * x) * Math.exp(-x * 14));
const noise = (i) => {
  let h = Math.imul(i, 374761393) + 668265263;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) & 0xffff) / 32768 - 1;
};
const hat = (i, x) => (x > 0.05 ? 0 : 0.06 * noise(i) * Math.exp(-x * 80));
const tri = (x) => 4 * Math.abs(x - Math.floor(x) - 0.5) - 1;

const n = Math.round(Number(seconds) * RATE);
const v0 = Number(variant);
const key = Math.pow(2, KEYS[mod(v0, 5)] / 12);
const arp = mod(v0, 2) === 0 ? [0, 1, 2] : [2, 1, 0];
const beat = 60 / BPM;
const bar = beat * 4;
const prog = mood === 'NIGHT' ? NIGHT : mood === 'TRAILER' ? DARK : POP;
const buf = new Float32Array(n);
for (let i = 0; i < n; i++) {
  const t = i / RATE;
  const ch = prog[Math.floor(t / bar) % prog.length].map((f) => f * key);
  const inBeat = t % beat;
  const beatIdx = Math.floor(t / beat);
  let v = 0;
  if (mood === 'BRIGHT' || mood === 'OCEAN' || mood === 'PLAYFUL') {
    v += pad(ch, t, 0.1);
    v += 0.22 * Math.sin(((2 * PI * ch[0]) / 2) * t) * env(inBeat, 0.02, 0.5);
    if (beatIdx % 2 === 0) v += kick(inBeat);
    v += hat(i, (t + beat / 2) % beat) * 0.6;
    const eighth = Math.floor(t / (beat / 2));
    const note = ch[arp[eighth % 3]] * (mood === 'PLAYFUL' ? 4 : 2);
    v += 0.07 * tri(note * t) * env(t % (beat / 2), 0.005, 0.18);
    if (mood === 'OCEAN') v += 0.05 * noise(i) * (0.5 + 0.5 * Math.sin((2 * PI * t) / (bar * 2)));
  } else if (mood === 'NIGHT') {
    v += pad(ch, t, 0.14);
    const q = Math.floor(t / beat);
    v += 0.08 * Math.sin(2 * PI * ch[q % 3] * 2 * t) * env(inBeat, 0.01, 0.9);
  } else {
    v += 0.18 * Math.sin(2 * PI * 55 * t) + 0.08 * Math.sin(2 * PI * 110 * t + Math.sin(t));
    v += pad(ch, t, 0.08);
    const barT = t % (bar * 2);
    if (barT < 0.6) v += 1.4 * kick(barT) + 0.25 * noise(i) * Math.exp(-barT * 8);
    v += 0.05 * Math.sin(2 * PI * (220 + 40 * (t % bar)) * t) * ((t % bar) / bar);
  }
  buf[i] = v;
}
const fadeIn = Math.round(RATE * 0.3);
const fadeOut = Math.round(RATE * 0.8);
let peak = 0;
for (let i = 0; i < n; i++) {
  buf[i] *= Math.max(0, Math.min(1, i / fadeIn, (n - i) / fadeOut));
  peak = Math.max(peak, Math.abs(buf[i]));
}
const k = (0.8 / (peak || 1)) * 32767;
const wav = Buffer.alloc(44 + n * 2);
wav.write('RIFF', 0);
wav.writeUInt32LE(36 + n * 2, 4);
wav.write('WAVEfmt ', 8);
wav.writeUInt32LE(16, 16);
wav.writeUInt16LE(1, 20); // PCM
wav.writeUInt16LE(1, 22); // mono
wav.writeUInt32LE(RATE, 24);
wav.writeUInt32LE(RATE * 2, 28);
wav.writeUInt16LE(2, 32);
wav.writeUInt16LE(16, 34);
wav.write('data', 36);
wav.writeUInt32LE(n * 2, 40);
for (let i = 0; i < n; i++) wav.writeInt16LE(Math.max(-32767, Math.min(32767, Math.round(buf[i] * k))), 44 + i * 2);
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, wav);
console.log(`ok ${out} ${mood} ${seconds}s`);
