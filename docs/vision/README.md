# 영상 분석

## 처리 구조

0.3.2의 `SemanticLightAnalyzer`는 **YOLO26n-sem-ADE20K 의미분할과 EfficientDet-Lite0 객체 검출**을 결합합니다. 건물·창문·간판 등의 표면에서 조명 후보를 측정하고 차량 영역을 제외합니다. [모델 입출력과 변환 소스](SEMANTIC_MODEL.md)

1. 저장한 30초·60초 영상에서 실제 시각 기준으로 1초마다 대표 프레임을 디코딩합니다.
2. 같은 RGB에 객체 검출과 320 입력 의미분할을 실행합니다. 차량·교통 시설·도로 등의 제외 영역을 결합합니다.
3. 국소 대비와 넓은 밝은 면을 구하고, 겹치지 않는 픽셀의 색 분포·채도 합과 영역별 근거를 보존합니다.
4. 공용 점수기가 따뜻함·차가움·빛의 면적과 사람 근거를 계산하고, 공간 분포와 구간 지속성을 해석합니다.

빛의 면적과 색감 강도의 분모는 전체 축소 프레임입니다. 측정 강도와 품질은 별도이며, 실제 영업 여부는 영상 관측만으로 확정하지 않습니다. 차량광 제외의 누락 여부는 최종 선택 픽셀과 마스크를 대조해 평가합니다. [분석 데이터 계약](../ANALYSIS_CONTRACT_V2.md)

기존 `UrbanLightAnalyzer`와 0.2.1의 [측정 품질](MEASUREMENT_QUALITY.md)은 비교 기준으로 보존합니다. 새 방식은 대표 색조 하나 대신 색 분포를 전달합니다.

0.3.2에는 [하늘색 관측과 무드 해석](SKY_COLOR.md)을 추가했습니다. 하늘 마스크 안의 색 분포·채도·밝기와 시간 조건으로 공통 키워드를 계산해 음악·조명 미리보기에 연결합니다. 이전 APK와 실험 결과는 보존합니다.

후속 개선에는 [차량 주행 영상과 이미지 자료의 장면 다양성](DATA_COVERAGE.md)을 함께 사용합니다. 자료 확보, 주석 준비, 실제 학습 결과를 구분해 기록합니다.

모델과 후처리는 직렬 작업자에서 실행합니다. 추론은 기기 안에서 수행하며 촬영 영상은 분석·취소 후 삭제합니다. 모델 버퍼와 프레임 참조를 구간 원장에 쌓지 않습니다.

## 모델과 라이선스

- [Ultralytics YOLO26n-sem-ADE20K](https://github.com/ultralytics/ultralytics): AGPL-3.0. [원본 가중치·변환 과정과 해시](../../models/semantic/README.md).
- [ONNX Runtime Android 1.31.0](https://github.com/microsoft/onnxruntime): MIT. CPU 추론, 초기화 전 텔레메트리 비활성화.

- [TensorFlow EfficientDet-Lite0 metadata v1](https://www.kaggle.com/models/tensorflow/efficientdet/TfLite/lite0-detection-metadata/1): Apache-2.0. 가중치와 내장 라벨은 수정하지 않았습니다.
- 자산: `app/src/main/assets/vision/efficientdet-lite0-metadata-v1.tflite`, 4,563,519 bytes.
- SHA-256: `2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b`.
- [MediaPipe](https://github.com/google-ai-edge/mediapipe) tasks-vision/core 1.1.1: Apache-2.0.
- [공식 ObjectDetectorHelper 예제](https://github.com/google-ai-edge/mediapipe-samples/blob/1675dd30f602848d0674de5c88dbc395f0877e59/examples/object_detection/android/app/src/main/java/com/google/mediapipe/examples/objectdetection/ObjectDetectorHelper.kt): 초기화·detect API 참고. Copyright 2022 The TensorFlow Authors, Apache-2.0. 파일을 복사하지 않았습니다.

모델 출처 메타데이터는 [model-source.json](model-source.json), 라벨은 [model-labels.txt](model-labels.txt)에 있습니다. 모델·라이브러리 원문 고지는 [모델 자산](../../app/src/main/assets/vision/THIRD-PARTY-NOTICES.md)과 [외부 라이선스](../../THIRD_PARTY_NOTICES.md)를 참고하세요.

## 정지사진 평가

아래는 0.2.0 기준선의 Windows CPU 추론과 제품 Kotlin 후처리 결과입니다. Android 네이티브 경로의 실측 결과가 아닙니다.

| 사진 | 차량 검출 수 | 차량 제외 면적비 | 조명 지표 | 관찰 |
|---|---:|---:|---:|---|
| retail-wet | 0 | 0 | 0.1969 | 일부 간판과 반사가 후보로 남음 |
| highway-night | 0 | 0 | 0.0261 | 먼 차량 누락, 계기판 오검출 |
| rain-street | 0 | 0 | 0.0849 | 화면에 차량이 있지만 검출하지 못함 |
| dark-lamp | 0 | 0 | 0.0243 | 가로등 주변 객체 오검출 |
| highway-dense | 3 | 0.16185 | 0.1205 | 안내판·교각 조명이 후보에 남음 |

사진은 후처리 조정에도 사용했으며 정답 마스크와 독립 평가셋이 없습니다. 이 수치로 정확도나 고속도로 오인 감소율을 주장할 수 없습니다. [사진 저작권·출처](evaluation/ATTRIBUTION.md), [모델 출력](evaluation/model-results.json), [픽셀 측정](evaluation/pixel-results.tsv)을 보존합니다.

## 평가 재현

전체 앱 검사는 [빌드 안내](../BUILD.md)를 따릅니다. 별도 영상 분석 모듈은 제품 소스와 테스트를 직접 읽습니다.

```powershell
.\gradlew.bat -p docs/vision/verification testDebugUnitTest assembleDebug lintDebug --console=plain
```

사진 평가에는 Python 환경과 LiteRT·Pillow가 필요합니다. 아래 스크립트는 공개 라이선스 사진을 내려받고 데스크톱 추론 입력을 생성합니다.

```powershell
python docs/vision/verification/fetch-evaluation.py
python -m venv docs/vision/verification/build/litert-env
& 'docs/vision/verification/build/litert-env/Scripts/python.exe' -m pip install 'ai-edge-litert==2.3.0' 'Pillow==12.0.0'
& 'docs/vision/verification/build/litert-env/Scripts/python.exe' docs/vision/verification/desktop-model-evaluation.py
.\gradlew.bat -p docs/vision/verification testDebugUnitTest --rerun-tasks --console=plain
```

검증 도구: [JUnit4](https://github.com/junit-team/junit4) (EPL-1.0), [Robolectric](https://github.com/robolectric/robolectric) (MIT), [LiteRT](https://github.com/google-ai-edge/LiteRT) (Apache-2.0), [Pillow](https://github.com/python-pillow/Pillow) (HPND). 다운로드 제한 응답을 받으면 중단하고 기존 파일을 재사용합니다.

작은 야간 차량 누락, 반사광·안내판 혼입, 흰색·세로 간판 제외가 알려진 한계입니다. 실제 주행 영상과 단말 연속 실행 성능은 [검증 기록](../VALIDATION.md)에서 구분합니다.
