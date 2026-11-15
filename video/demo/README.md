# 시연 영상 (Remotion)

차~즈 시연 영상을 코드로 편집한다. 원본 클립·렌더 결과(mp4)는 용량 때문에 git에 올리지 않는다 (`.gitignore`).

## 구조
- `public/clips/` — 에뮬레이터 화면 녹화 원본 16개 (2560×1440, H.264, 가변 프레임). git 제외
- `public/stills/` — 카드 장면용 스틸 (클립에서 잘라 냄)
- `src/timeline.ts` — **편집표**. 장면 순서, 컷(원본 몇 초~몇 초, 배속), 자막을 여기서 고친다
- `src/ClipScene.tsx` — 클립 장면 (컷 이어 붙이기, 장면 이름, 자막, 주행 장면 크롭)
- `src/Intro.tsx` — 인트로: 9:16 스타일 시안 판. 대교 드라이브 영상에 줌인된 채로 시작(터널 → 대교 → 파노라마 + 친구들 영상 폴라로이드, 10.5초) → 줌아웃 → 가족 여행 → 반려견 벚꽃 드라이브. 카드 배치·문구는 `CARDS`, 줌 일정은 `FOCUS`
- `public/intro/` — 시안 이미지(`scripts/gen_intro.py`)와 영상(`scripts/gen_videos.py`, mp4 는 git 제외). 모두 비즈라우터 AI 생성이라 화면에 'AI' 표기
  - 영상: 대교는 Seedance 2.5, 사람이 나오는 영상은 Grok Imagine Video 1.5 (Seedance 는 사람 얼굴 첫 프레임을 거절)
- `src/narration.json` — 나레이션(해요체 설명 자막 = 음성). `scripts/gen_narration.py` 가 비즈라우터 TTS 로 `public/audio/narr/*.mp3` 를 만들고 길이를 적는다. 장면에서는 `timeline.ts` 의 `narr`
- `public/audio/bgm.wav` — 차량 앱 BgmSynth 를 옮긴 `scripts/bgm.mjs` 로 합성 (자체 제작 음원, git 제외). 인트로는 크게, 나레이션부터 잔잔하게
- `src/Cards.tsx` — 문제 제기 · 로드맵 · 엔딩
- `analysis/` — 클립 접촉 시트·길이·무음 구간 (편집표 만들 때 참고)
- `scripts/ff.sh` — Remotion 에 들어 있는 ffmpeg 호출 (`npx remotion ffmpeg` 는 필터 인자를 깨뜨림. 빌드에 tile·fps 필터 없음)

## 실행
```bash
npm install
npm run studio                 # 브라우저 미리보기 (타임라인에서 장면별 확인)
npm run render                 # out/chaz-demo.mp4 (1920×1080, 30fps)
npx remotion render IntroReel out/chaz-intro-reel.mp4      # 인트로 단독 9:16 (1080×1920)
../../server/.venv/bin/python scripts/gen_intro.py pet_swap   # 시안 이미지 다시 생성 (인자 없으면 없는 것만)
../../server/.venv/bin/python scripts/gen_videos.py v_pet      # 시안 영상 다시 생성 (유료, 영상당 약 1~2천 원)
../../server/.venv/bin/python scripts/gen_narration.py         # 나레이션 문장을 고쳤으면 다시 (바뀐 것만)
node scripts/bgm.mjs BRIGHT 200                                # BGM 합성
npx remotion still Demo out/f.jpg --frame=300 --scale=0.5   # 한 프레임만
```

## 자막 규칙
- 설명 자막 = 나레이션 (해요체). 나레이션이 나오는 동안 클립 소리는 30%로 낮춘다
- 🗣 동승자: 녹화엔 사용자 목소리가 없다(adb 로 명령 주입). 앱이 실제로 받는 문구(`IntentParser`)만 쓴다: "차~즈", "차즈 노래 틀어줘", "차즈 그만"
- 🔊 차~즈: 클립에 실제로 녹음된 앱 TTS 문장
- 외부 카메라는 시뮬레이션, AI 결과물은 "AI" 표기 (SPEC 규칙 5)
- 주행 장면은 앱 화면만 크롭 — 녹화 때 개발용 오버라이드(`--es drive on`)로 주행 상태를 만들어 클러스터 기어가 P 로 보이기 때문
