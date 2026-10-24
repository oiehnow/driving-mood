# 영상 분석

## 처리 구조

`UrbanLightAnalyzer`는 카메라 프레임의 차량 영역을 제외하고 남은 조명 후보의 색감·밝기를 측정합니다. MediaPipe와 EfficientDet-Lite0를 사용하며, 건물·간판·창문을 구분하는 의미 분할 모델은 포함하지 않습니다.

1. EfficientDet-Lite0로 차량과 교통 시설을 검출합니다. 입력은 320×320, 검출 임계값은 0.25이며 모델 출력은 최대 25개입니다.
2. 차량 박스와 주변 번짐 여유를 제외합니다. 도로·반사·작은 광점·큰 포화 광원은 추가 규칙으로 억제합니다.
3. 국소 대비와 밝기로 조명 후보를 추출하고 HSV와 발광 면적을 측정합니다. 색상은 채도 가중 원형 평균이며 무채색·상쇄 분포에서는 값이 없을 수 있습니다.
4. 조명 지표의 상승을 시간 평활화합니다. 장면 전환·긴 공백·가림 변화에는 초기화하며, 이전 프레임의 마스크는 재사용하지 않습니다.

발광 면적과 차량 제외 비율의 분모는 전체 축소 프레임입니다. 차량 검출 0건은 차량이 없다는 뜻이 아닙니다. 광량 지표와 품질 수치는 보정된 인식 확률이 아니며 영업 여부나 상권 활력을 나타내지 않습니다. 측정 품질과 장소 의미 해석은 [분석 데이터 계약](../ANALYSIS_CONTRACT_V2.md)에서 구분합니다.

0.2.1의 색감·광량 품질 계산과 실패 조건은 [측정 품질](MEASUREMENT_QUALITY.md)에 정리했습니다. 품질 기준을 통과한 색감만 음악 분위기에 제한적으로 전달하며, 밝기 하나로 상권이나 음악 에너지를 결정하지 않습니다.

카메라 입력은 종횡비·방향을 보정한 긴 변 최대 640px입니다. 모델과 후처리는 직렬 작업자에서 실행하고 원본 Bitmap을 저장·전송하거나 호출 종료 뒤 보관하지 않습니다. 최신 프레임을 우선 처리하며 표시된 처리 시간은 전체 카메라 지연이나 FPS가 아닙니다.

## 모델과 라이선스

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
