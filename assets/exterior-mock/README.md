# assets/exterior-mock — 외부 카메라 시뮬레이션 소스

에뮬레이터에 차량 전·후방 카메라가 없어서(docs/spike.md) CameraSource 의 '사전 녹화 주행 영상' 후보로 쓴다.
화면·결과물에는 '시뮬레이션'으로 표기하고 sourceKinds 에 RECORDED_VIDEO 를 남긴다.

| 파일 | 내용 | 출처·라이선스 |
|---|---|---|
| front_01.jpg | 전방 카메라 시점 제주 해안도로 (1280x720) | AI 생성 — 비즈라우터 `openai/gpt-image-1-mini`, `server/scripts/make_exterior_mock.py` |
| rear_01.jpg | 후방 카메라 시점 (1280x720) | 위와 같음 |

파일 이름 규칙: `front_*.jpg|png`, `rear_*.jpg|png`. 앱은 이미지를 천천히 패닝·줌해 주행 영상처럼 보여 준다.
실제 주행 영상(`front_*.mp4`, 720p·30초 이내, 라이선스 확인)을 넣으면 TODO(T05+) 영상 재생 소스로 교체한다.
