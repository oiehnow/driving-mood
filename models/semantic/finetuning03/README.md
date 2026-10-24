# 장면분할 파인튜닝 재현 자료

실제 수행한 A(낮은 학습률), B(BN 통계 고정), C(L2-SP 보존 규제)의 설정·학습 기록과 C 학습 코드/가중치다. 각 후보는 공식 모델에서 다시 시작했다. C 단독은 차량 보호 기준을 통과하지 못했으며, 앱에서는 보호 영역을 유지하는 **F2 조합의 건물·창문·간판 역할**로 사용한다. 전체 비교와 판단은 [평가 기록](../../../docs/vision/finetuning03/README.md)에 정리한다.

## 손실과 선택

공식 의미분할 supervised loss를 유지하고 `λ × mean((θ−θ₀)²) × batch size`를 더했다. θ는 학습 가능한 파라미터만, θ₀는 실제 trainer 초기화가 끝난 직후의 FP32 복사본이다. 동결 파라미터는 규제 대상에서 제외한다. 모든 BN running statistics는 고정하고 affine 파라미터의 기존 학습 여부는 유지했다.

공식 초기 모델에서 시작한 별도 train-only probe의 다섯 번째 업데이트 직전, 규제 gradient norm을 supervised gradient norm의 10%로 맞추는 계수를 결정했다. λ=5,535,694.609872891이며 원소 평균 reduction에 대응한다. probe는 폐기했고 본학습은 공식 모델에서 다시 시작했다. 이 비율은 probe 시점 기준으로, 이후 모든 단계에서 10%가 유지된다는 뜻은 아니다. 계수 선택에는 개발검증/회귀/보조평가 자료를 사용하지 않았다.

본학습은 30 epoch이며 개발검증 fitness로 epoch 1을 선택했다. `weights/best.pt`는 선택된 실제 checkpoint의 모든 모델 tensor를 그대로 보존하고 개인 로컬 경로 메타데이터만 정리했다. 원본/배포 SHA와 tensor별 동일성은 `weights/provenance.json`에 있다. `best-fp32-ema-state.pt`는 해당 epoch의 원래 FP32 EMA state다. 재학습이나 수치 개선을 위해 가중치를 수정하지 않았다.

## 재현

실행 환경은 Python 3.14.4, PyTorch 2.11.0+cu128, Ultralytics 8.4.184, NumPy 2.4.6, OpenCV 5.0.0이다. 실제 GPU 실행 환경을 기록한 것이며 모든 OS/GPU에서 bitwise 재현을 보장하지 않는다.

1. [공식 초기 가중치](https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n-sem-ade20k.pt)를 `weights/yolo26n-sem-ade20k.pt`에 둔다. 예상 SHA256은 `68ac71bef2868c987fff7cd7c49cb656922f6d8447ad21af3893305577435000`이다. 다른 위치는 `DM_INITIAL_WEIGHTS` 환경변수로 지정한다.
2. 사용 권한이 있는 동일 staged RGB/마스크 폴더를 준비한다. `python prepare_inputs.py <staged-data-directory>`는 공개된 입력 manifest의 해시를 검증하며 라벨을 생성하지 않는다.
3. `python probe_retention.py`로 고정 train-only probe를 실행한다. 생성된 계수와 제공된 `evidence/coefficient.json`의 차이를 확인한다.
4. `python train_pilot.py`로 공식 초기 모델부터 본학습을 실행한다. 기존 실행 폴더가 있으면 덮어쓰지 않는다.
5. 필요할 때 `python export_checkpoint.py weights/best.pt`로 320×320 uint8 ADE150 ONNX를 만든다. 앱 배포 파일의 정확한 SHA는 `weights/provenance.json`에 별도로 기록했다.

이 패키지에는 입력 원본 영상/사진을 재배포하지 않는다. 실제 차량 주행 영상과 이미지자료를 바탕으로 구성한 AI 보조 부분 주석을 사용했으며, 사람이 전 픽셀을 검수한 정답이 아니다. 미주석 영역은 255 ignore, 기존 ADE150 클래스 ID는 유지했다. 원자료의 저작권과 이용조건은 학습 코드 라이선스와 별개이며, 입력 복제본은 사용 권한을 확보한 범위에서 준비해야 한다. `input-manifest.json`은 기존 train/dev 분리·RGB/마스크 SHA·전처리 계약을 보존한다.

## 출처와 보존

- [Ultralytics](https://github.com/ultralytics/ultralytics) 공식 SemanticSegmentationTrainer, 모델·학습 코드: AGPL-3.0. 라이선스 전문은 `licenses/Ultralytics-AGPL-3.0.txt`.
- L2-SP [원 논문](https://proceedings.mlr.press/v80/li18a.html), [저자 구현](https://github.com/holyseven/TransferLearningClassification)(MIT): 수식 참고. 해당 저장소의 코드를 복사하지 않고 `retention_loss.py`를 독립 구현했다.
- `evidence/`에는 실패한 A/B를 포함한 실제 epoch·optimizer-step 기록을 보존한다. 공개 기록은 개인 경로만 정리했으며 원본 파일 SHA도 남겼다. staged 학습검증 지표와 원본격자 평가 지표는 분모·정렬이 달라 직접 혼용하지 않는다.

실행 코드의 이식 변경은 경로 인자화, 입력 해시의 상대경로화, 손실 설명 정정뿐이다. 이 패키징 작업으로 새 학습을 실행하지 않았다.
