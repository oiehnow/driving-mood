# 역할별 장면 관측

**0.3.9 앱 분석기에 F2 의미분할과 YOLO26n 객체 검출기를 적용했다.** 이전 배포 기준은 공식 n320 의미분할과 EfficientDet-Lite0이다. 최종 실행 정책의 Android 정합·제품 경로 비용 검사를 통과했으며 최종 서명 APK의 설치·검사도 통과했다. [배포 검증](../VALIDATION-0.3.9.md) 모델은 물체·표면의 위치를 제공하고, 색감과 밝기는 실제 RGB에서 측정한다. 무드 점수는 그 관측값에 별도 규칙을 적용한다.

## 모델 역할

| 역할 | 모델 | 사용하는 결과 |
|---|---|---|
| 보호 영역과 기본 분할 | 공식 YOLO26n-sem-ADE20K, 320 입력 | 사람·차량·도로·보도·교통시설 보존, 나머지 기본값 |
| 하늘 보완 | 공식 YOLO26n-sem-ADE20K, 640 입력 | 보호 영역 밖에서 하늘로 분류한 픽셀 |
| 구조 보완 | 실제 파인튜닝한 C, 320 입력 | 보호 영역 밖의 건물·창문·간판 |
| 사람·차량 관측 | YOLO26n 객체 검출 | 사람 박스 수·면적, 차량·교통시설 제외 근거 |

합성 우선순위는 **기존 보호 영역 → n640 하늘 → C 구조 → 기본 분할**이다. 영역마다 정답을 보고 모델을 고르는 방식이 아니라 모든 입력에 같은 고정 규칙을 적용한다. 사람 검출 박스 수와 의미분할 사람 면적을 더하지 않으며, 프레임별 박스 수를 영상 전체의 고유 인원수로 표시하지 않는다.

## 입력과 좌표

- 방향을 보정한 분석 프레임은 긴 변 최대640이다. 이 프레임에서 각 모델의 320·640 입력을 독립적으로 만든다. 이미320으로 축소한 텐서를640으로 확대하지 않는다.
- 의미분할 입력은 RGB/255, FP32 NCHW `[1,3,S,S]`, S는320 또는640이다. 종횡비를 유지하고114 회색으로 중앙 패딩한다.
- 출력은 uint8 `[1,S,S]`, ADE20K150 클래스 ID다. 클래스 확률로 해석하지 않는다.
- 패딩을 제거한 출력을 같은 분석 프레임 격자로 되돌려 합친다. 평가 환경 OpenCV5.0.0 `INTER_NEAREST_EXACT`의 좌표 누적·반올림을 맞췄다.
- 하늘색과 광원 관측에는 동일한 합성 지도를 사용한다. 합성 출처는 규칙과 모델 SHA를 결합해 기록한다.

입력 축소 전의 카메라 전체 해상도를 복원하는 기능은 아니다. 실제 Kotlin 입력 텐서로 배포 모델을 재추론한 개발 프레임과 Android 에뮬레이터 정합 검사에서 기준 결과와 픽셀 차이가 0이었다. [전처리·병합 검사](../../app/src/test/java/kr/urbanmood/vision/F2DevelopmentParityTest.kt), [최종 Android 원장](android-f2-039/native-parity-final.json)으로 계산 정합을 확인하며, 이를 새 장면의 정확도와 구분한다.

## 빛과 색 측정

같은 분석 RGB 격자에서 국소 대비와 넓은 밝은 면을 관측한다. 겹친 픽셀은 한 번만 센다. 검출 차량·교통시설, 의미분할 차량·도로 등과 차량 주변 반사 후보를 제외한다. 통계는 `semantic-contrast-mobile-v1`에서 무드 점수 규칙으로 전달하며 관측 강도와 품질을 구분한다.

현재 앱의 밝은 영역 support는 건물·창문·간판이다. 가로등은 기존 제외 규칙을 유지한다. 별도 도로 조명으로 관측하는 연구안과 앱 구현을 구분한다. 간판 클래스는 실제 자발광 여부를, 창문 클래스는 영업중 상점을 의미하지 않는다. 작은 차량광 누락·반사·아파트 창문과 상점 구분은 별도 주석과 오류 평가가 필요하다. 창문 IoU는 현재 비교 범위에서 개선되지 않았다.

320에서 원본640으로 관측격자가 커지면 같은 분할도 밝은 면적과 영역 수가 달라질 수 있다. [격자 효과와 모델 효과를 나눈 검사](../../app/src/test/java/kr/urbanmood/vision/F2ObservationGridDiagnosticTest.kt)를 유지한다. 타원형 최소·최대 연산과 Gaussian 계산은 수치를 바꾸지 않는 방식으로 최적화했고, [기존 계산과 전픽셀·통계 비교](../../app/src/test/java/kr/urbanmood/vision/SemanticContrastKernelParityTest.kt)를 통과했다.

## 실패 처리와 실행 범위

모델·버퍼는 직렬 분석기에서 재사용한다. 보조 의미분할 모델이 실패하면 같은 프레임의 기본320 모델 결과로 돌아가며 이를 출처에 남긴다. 기본 모델이 실패하면 실패 상태를 반환한다. 과거 프레임의 마스크로 채우지 않는다. 모델 사이에서 취소를 확인하고 종료 시 세션을 닫는다. 진행 중인 단일 ORT 호출을 즉시 중단하는 기능은 별도 구현하지 않았다.

ORT 텔레메트리는 초기화 전에 비활성화한다. 의미분할과 객체 검출의 세션에는 `setCPUArenaAllocator(false)`와 `setMemoryPatternOptimization(false)`를 적용한다. 여러 세션이 유지하는 재사용 버퍼 비용을 줄이기 위한 실행 정책이며 모델·정밀도·격자·임계값은 바꾸지 않는다. 최종 정책에서도 동일 입력의 모델 입출력·합성 지도 정합 검사를 통과했다.

[최적화 전](android-f2-039/parity-before-optimization.json)·[후](android-f2-039/parity-after-optimization.json) 정합 원장의 `pipelineMs`는 hook 내부 파일 대조·SHA 계산·기록 작업을 포함한다. 별도 [실제 앱 분석기 측정](android-f2-039/product-final.json)은 이 작업을 제외한 `AppServices` 경로를 사용했다. Android 15 x86_64 에뮬레이터에서 첫 호출 2.117초, 재사용 중앙값 1.652초(범위 1.597–2.153초), 분석 직후 PSS 표본 약 167–179MiB였다. 첫 호출은 모델 로딩·검사를 포함하지만 OS 캐시를 초기화하지 않았다. 촬영·디코딩·화면 표시·전체 촬영 구간 누적과 순간 최대 메모리는 측정 범위 밖이다. S25+·Pleos·실차 성능과 취소 응답은 별도 검증한다. [실패와 최적화 기록](MODEL_IMPROVEMENT_LOG.md)에 이전 실행도 보존한다.

## 근거와 라이선스

- [전체 개선 과정](MODEL_IMPROVEMENT_LOG.md), [평가 원장·고정 규칙](finetuning03/README.md), [실제 파인튜닝 코드·가중치](../../models/semantic/finetuning03/README.md).
- [Ultralytics 공식 구현](https://github.com/ultralytics/ultralytics): 의미분할·객체 검출 모델과 학습 코드, AGPL-3.0. 앱 자체 소스는 AGPL-3.0-only로 공개한다. ORT 변환은 모델 라이선스를 바꾸지 않는다.
- [ONNX Runtime Android 예제](https://github.com/microsoft/onnxruntime-inference-examples/tree/main/mobile/examples/image_classification/android)와 [실행기](https://github.com/microsoft/onnxruntime): 세션·텐서 수명주기 참고 및 실행 의존성, MIT.
- [ORT 1.31 Java 세션 옵션](https://github.com/microsoft/onnxruntime/blob/v1.31.0/java/src/main/java/ai/onnxruntime/OrtSession.java): CPU arena·memory pattern 설정의 공식 구현, 기존 MIT 의존성 활용.
- [OpenCV5.x 정렬](https://github.com/opencv/opencv/blob/5.x/modules/imgproc/src/resize.cpp): 최근접 좌표 계산 참고·이식, 해당 파일의 BSD 고지 보존. [외부 고지](../../THIRD_PARTY_NOTICES.md).
