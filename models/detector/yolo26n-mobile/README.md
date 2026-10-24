# YOLO26n 모바일 객체 검출 재현 자료

사람·차량 등 COCO 객체를 검출하는 YOLO26n 원본 가중치와 Android용 ONNX 변환 절차다. 공식 모델을 재학습하거나 양자화하지 않았다. 별도의 의미분할 모델과 함께 사용한다.

## 소스와 라이선스

- `source/yolo26n.pt`: [Ultralytics 공식 v8.4.0 배포 가중치](https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n.pt), 원본 그대로 보존.
- `source/ultralytics-8.4.184.tar.gz`: [PyPI의 공식 소스 배포](https://pypi.org/project/ultralytics/8.4.184/#files). 모델 구조·전처리·exporter 구현을 함께 재현할 수 있도록 소스 아카이브를 보존했다. [GitHub 해당 버전](https://github.com/ultralytics/ultralytics/tree/v8.4.184), [export 사용법](https://docs.ultralytics.com/modes/export/).
- 모델과 Ultralytics 구현은 **AGPL-3.0**이다. 원문은 `licenses/ultralytics-LICENSE.txt`, 원본 배포 URL·파일 해시는 `package-manifest.json`에 있다. 외부 저작자·라이선스는 유지한다.
- Android 실행은 기존 [ONNX Runtime Java API](https://onnxruntime.ai/docs/get-started/with-java.html)를 사용한다. ORT는 MIT이며 앱의 [라이선스 원문](../../../app/src/main/assets/licenses/onnxruntime-LICENSE.txt)을 따른다.

| 파일 | 크기 | SHA-256 |
|---|---:|---|
| 원본 PT | 5,544,453 bytes | `9b09cc8bf347f0fc8a5f7657480587f25db09b34bf33b0652110fb03a8ad4fef` |
| 원본 변환 ONNX | 9,893,948 bytes | `b7bdfacd8bedbf2f679fc1959a2315dcc0e6e1c3b01ea26012be3f0b5c0eab06` |
| 경로 metadata를 정리한 앱 ONNX | 9,893,909 bytes | `6236c6b2a47706c21edb508ca8a66d9b2f3772fe2b1410fee3cd70896ac0277a` |

앱 자산은 [`app/src/main/assets/vision/yolo26n-detect-640.onnx`](../../../app/src/main/assets/vision/yolo26n-detect-640.onnx)이며 같은 바이너리를 이 폴더에 중복 보관하지 않는다.

## 다시 생성하기

저장소 루트에서 새 Python 환경을 준비한다. 검증 환경은 Python 3.14.4, PyTorch 2.14.1+cpu, Ultralytics 8.4.184, ONNX 1.23.2, ORT 1.31.0이었다.

```powershell
python -m venv ../detector-export-env
..\detector-export-env\Scripts\python -m pip install torch==2.14.1 torchvision==0.29.1 --index-url https://download.pytorch.org/whl/cpu
..\detector-export-env\Scripts\python -m pip install -r models/detector/yolo26n-mobile/requirements.txt
..\detector-export-env\Scripts\python models/detector/yolo26n-mobile/export_and_verify.py --output ../detector-reproduction
```

출력 폴더는 새 경로여야 한다. 가중치는 복사본에서 변환하며 원본과 앱 자산을 덮어쓰지 않는다. 날짜 metadata를 기존 생성 시각으로 고정한 원본 변환 파일과, 설명의 머신 경로만 정리한 배포 파일을 각각 생성하고 해시를 확인한다. 모델 그래프·가중치·외부 저작자·라이선스는 변경하지 않는다. 최초 재현 기록은 `evidence/reproduction.json`, 배포 파일까지 확인한 기록은 `evidence/reproduction-packaged.json`에 보존한다.

경로 정리 도구와 원본→배포 해시·동일 추론 증거는 [`models/tools`](../../tools/sanitization-provenance.json)에 있다. 숫자 가중치의 우연한 문자 패턴을 없애려고 가중치를 변경하지 않는다. `evidence/export-verification.json`과 기존 Android 원장의 모델 해시는 실행 당시 원본 ONNX 해시를 그대로 유지한다.

개별 RGB 이미지로 PT와 ONNX 계산을 대조하려면 위 명령에 `--images image-a.png image-b.png`를 붙인다. 결과에는 이미지 해시와 차이만 기록한다. 검증에 사용한 원본 영상·이미지·음원은 이 패키지에 포함하지 않았다.

## 입력과 후처리

고정 FLOAT `[1,3,640,640]`, RGB/255, 중앙 letterbox 및114 회색 여백을 사용한다. 출력은 FLOAT `[1,300,6]`의 `x1,y1,x2,y2,score,class`이며 metadata의 `end2end=True`를 확인한다. 별도 NMS를 다시 적용하지 않는다. 변환은 opset17, FP32, dynamic=false, simplify=false, nms=false다.

Android는 여백을 제거하고 원본 크기의0..1 좌표로 복원한다. `score≥0.25` 유효 박스를 점수순 최대25개 반환해 기존 앱 계약을 유지하고, 상한 도달은 `atCapacity`에 반영한다. 사람 점수의 별도 채택 기준은0.5다. 잘못된 shape·class·NaN을 정상 결과로 처리하지 않는다.

## 확인한 범위

- 같은 RGB 입력에서 PT와 ONNX의 채택 박스 수·클래스가 일치했고 좌표 차이는0.0001픽셀 미만이었다. `evidence/export-verification.json`에 설정·입력 해시·박스를 보존했다.
- Android x86-64 에뮬레이터의 native CPU 검사에서도 데스크톱 기준과 정규화 박스 차이가0이었다. `evidence/android-detector-verification.json`은 해당 실행 원장이다. 실휴대폰 성능이나 전체 장면 정확도를 뜻하지 않는다.
- 첫 직접 PT 비교에서는 기본 head가 `[84,8400]`을 반환해 형태 검사에서 중단했다. 공식 export와 같은 `end2end=True` head를 명시한 뒤 `[300,6]`끼리 대조했다. 다른 형태의 출력을 임의 변환해 통과시키지 않았다.
- F2와 결합한 전체 분석 시간은 검출기 단독 시간과 별개다. 전체 파이프라인 최적화 결과로 이 원장의 단독 측정값을 바꿔 쓰지 않는다.

앱 검사는 [`OrtObjectDetectorTest`](../../../app/src/test/java/kr/urbanmood/vision/OrtObjectDetectorTest.kt)와 [`OrtObjectDetectorDeviceTest`](../../../app/src/androidTest/java/kr/urbanmood/vision/OrtObjectDetectorDeviceTest.kt)에 있다. 디바이스 검사는 별도로 준비한 원본 입력 SHA를 확인하며 UI를 실행하지 않는다.
