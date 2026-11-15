import React from 'react';
import { Composition } from 'remotion';
import { Demo } from './Demo';
import { INTRO_FRAMES, IntroReel } from './Intro';
import { FPS, totalFrames } from './timeline';

export const Root: React.FC = () => (
  <>
    <Composition id="Demo" component={Demo} durationInFrames={totalFrames()} fps={FPS} width={1920} height={1080} />
    {/* 인트로 단독 9:16 릴스 (숏폼·시연 앞 인트로, 스타일 기획서 P1) */}
    <Composition id="IntroReel" component={IntroReel} durationInFrames={INTRO_FRAMES} fps={FPS} width={1080} height={1920} />
  </>
);
