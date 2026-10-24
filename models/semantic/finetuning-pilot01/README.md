# Driving Mood — 실제 파인튜닝 pilot 01 재현 기록

**결론: 실제 학습은 완료했지만 독립 시험 평균 IoU가60.44%→58.76%로 낮아져 기본 모델로 채택하지 않는다.** 개선과 퇴행을 함께 보존하며, 추가 원본 영상과 새로운 그룹 분할 전에는 재학습하지 않는다.

기존 YOLO26n-sem ADE20K 모델의 150개 분류를 유지한 소규모 도메인 적응이다. Android용320 입력·정수 클래스맵과 호환되며, 앱 기본 모델 교체나 전체 차량광 제외 완료를 뜻하지 않는다.

## 실제 수행

- 원본 영상 단위로 train3개/12장, validation1개/4장, test2개/8장을 분리했다. 미주석은255이며 모델 예측을 정답으로 복사하지 않았다. 주석은 **AI 보조 부분 폴리곤으로, 사람 검수 완료 정답이 아니다**.
- 공식 Ultralytics8.4.184 trainer, RTX5080,320입력,seed20261024,AdamW,30epochs/180updates. 초기1,649,932parameters 중 앞11layers를 동결하고284,460parameters를 학습했다. best/last 각각104텐서가 변경됐고260개는 같았다.
- 검증 최고점의2epoch checkpoint를 미리 정한 방식으로 선택했다. 학습CE는1.69656→.01821이지만30epoch 검증 mIoU는.60697로 최고점보다 낮아졌다. 학습손실 감소가 일반화 개선을 보장하지 않는다.
- 검증4장의 부분주석 mIoU: 초기.553722,학습중2epoch .769787,저장모델 공식재검증 .762520. 저장반올림/추론층병합의 차이를 같은seed로2epoch 진단재현했다. 본학습30epochs와 이 별도진단은 구분한다. 차량 주석32픽셀 중1개 변화도 macro평균에 영향을 줬다.
- 이 패키지는 독립test 결과를 모델 선택에 사용하지 않았다. 독립 평가 보고서를 함께 읽어야 한다. 미주석 경계·먼 후미등·전체장면 정확도는 측정하지 않았다.

## 왜 이 모델인가

기존 앱의 building/windowpane/signboard/car 등 ID와 모바일 ONNX/ORT 경로를 유지할 수 있는 작은 semantic 모델을 선택했다. 객체 검출만으로는 간판색과 도로반사 픽셀을 나누기 어렵고, 기존 nano/small 비교에서도 크기 증가만으로 주요 실패가 사라지지 않았다. 최상위 모델이라는 주장이 아니라 현재 계약·실행자원에 맞춘 검증 가능한 선택이다. 발광 여부나 점포 영업상태를 새 클래스로 만들지 않았다.

## 재현

Python3.14.4를 사용했다. GPU wheel과 나머지 고정 의존성을 먼저 설치한다.

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install torch==2.11.0+cu128 torchvision==0.26.0+cu128 --index-url https://download.pytorch.org/whl/cu128
.venv/Scripts/python.exe -m pip install -r requirements-lock.txt --extra-index-url https://download.pytorch.org/whl/cu128
.venv/Scripts/python.exe prepare_data.py --source ./private-dataset --out ./prepared-data
.venv/Scripts/python.exe train.py --weights ./weights/yolo26n-sem-ade20k.pt --data ./prepared-data/dataset.yaml --output ./runs
```

초기 가중치는 [공식 배포](https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n-sem-ade20k.pt)를 사용하며 SHA256은 `68ac71bef2868c987fff7cd7c49cb656922f6d8447ad21af3893305577435000`이다. 데이터는 외부 재배포 권리가 확인되지 않은 사용자 제공 영상이므로 **이미지·주석 마스크·원본 영상·가중치 바이너리를 이 패키지에 포함하지 않았다**. 동일 실험 재현에는 권한 있는 원본 데이터와 동결 manifest가 필요하다. 코드만으로 비공개 데이터를 복원할 수 없다. 다른 데이터를 쓰면 같은 실험의 재현으로 표시하지 않는다.

`prepare_data.py`는320정사각형으로 비율을 보존해 RGB114/정답255로 패딩한다. RGB는bilinear,정답은nearest-exact이다. ID0=wall을ignore로 바꾸지 않는다. test 픽셀은 준비/학습에서 읽지 않는다. `train.py`는 공개용 경로 인자를 받는 재현 진입점이며, 실제 수행한 원본 driver는 `audit-sources/`에 있다. 설정을 바꾸거나test결과를 보고checkpoint를 재선정하지 않는다. 장치/라이브러리 차이와 체크포인트 시각 메타데이터 때문에 파일 전체 SHA의 재생성을 보장하지 않으므로 실제 tensor 증거도 함께 보존했다.

## 증거와 모바일 산출물

`evidence/epochs.csv`, `optimizer-and-epoch-events.jsonl`, `weight-deltas.json`, tensor hashes가 실제 수행 기록이다. `training-curves.png`는 원시CSV에서 생성했다. `model-manifest.json`에 초기/학습/ONNX/ORT 해시와 크기·계약을 기록했다. ONNX→ORT16장 비교의2픽셀 차이는 둘 다 패딩에 있었고 실제 영상 내용영역은 같았다. Android 실기기 검증과 모델 채택은 별도다.

## 출처·이용 조건

- [Ultralytics 공식 학습기](https://github.com/ultralytics/ultralytics/blob/main/ultralytics/models/yolo/semantic/train.py), [semantic 문서](https://docs.ultralytics.com/tasks/semantic): 공식 모델/학습 API 재사용,AGPL-3.0/Enterprise 조건 유지.
- [PyTorch](https://github.com/pytorch/pytorch): 공식 CUDA wheel,BSD-style 고지 보존.
- [ONNX Runtime](https://github.com/microsoft/onnxruntime):1.31.0 ARM Fixed ORT 변환,MIT.
- [OpenCV](https://github.com/opencv/opencv): 영상/마스크 축소와 비교,Apache-2.0 및 배포물 제3자 고지 보존.

원본 코드·모델의 출처/저작권/라이선스는 `licenses/`와 함께 유지한다. 파인튜닝이 원래 이용 조건을 없애지 않는다. 전체 외부 저장소 이력은 병합하지 않았다.

## 독립 평가 결과와 다음 실험

[독립 평가 요약](independent-evaluation/SUMMARY.md)에 개선과 퇴행을 함께 기록했다. 시험 8장, 주석 100,927픽셀의 평균 IoU는 60.44%에서 58.76%로 낮아졌다. 공식 모델을 유지하며, 이 시험 결과로 설정이나 체크포인트를 다시 선택하지 않았다.

후속 학습은 추가 원본 영상의 장면 다양성과 클래스별 주석을 확보한 뒤 진행한다. 같은 출처의 짧은 클립과 풀버전은 같은 그룹으로 묶고, 영상별 표본 수를 제한한다. 기존 시험 영상과 그 풀버전은 회귀 확인용이며 새로운 독립 평가로 세지 않는다.
