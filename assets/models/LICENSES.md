# assets/models

| 파일 | 용도 | 출처·라이선스 |
|---|---|---|
| selfie_multiclass_256x256.tflite | PersonSegmenter (인물 분리: L1 인물 레이어, L2 누끼) | Google MediaPipe `selfie_multiclass_256x256` (float32), https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite — Apache License 2.0 |

모델 선택(11/9 실측, 에뮬레이터 실내 장면): `selfie_segmenter` 는 넓은 실내 장면에서 좌석·창까지 인물로 잡아 L1 창문이 덮였고,
`deeplab_v3` 는 경계가 흐렸다. `selfie_multiclass` 가 인물 윤곽이 가장 깨끗해서 채택 (배경 클래스 0 의 반대 = 인물).
