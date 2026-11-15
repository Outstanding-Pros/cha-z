// 차~즈 로고 (1:1, 플랫). 반원 꽃잎 네 장의 바람개비, 파랑 단색 계열 네 가지 톤.
// 같은 도형을 public/logo/chaz-logo.svg 로도 둔다.
import React from 'react';

export const LOGO_SVG_BODY = `
  <rect width="512" height="512" rx="116" fill="#FFFFFF"/>
  <path d="M256 256 L60 256 A98.0 98.0 0 0 1 256 256 Z" fill="#1F5BD6"/>
  <path d="M256 256 L256 60 A98.0 98.0 0 0 1 256 256 Z" fill="#3D8BFF"/>
  <path d="M256 256 L452 256 A98.0 98.0 0 0 1 256 256 Z" fill="#79B0FF"/>
  <path d="M256 256 L256 452 A98.0 98.0 0 0 1 256 256 Z" fill="#B5D3FF"/>`;

export const Logo: React.FC<{ size: number; style?: React.CSSProperties }> = ({ size, style }) => (
  <svg width={size} height={size} viewBox="0 0 512 512" style={style} dangerouslySetInnerHTML={{ __html: LOGO_SVG_BODY }} />
);
