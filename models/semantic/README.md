# 의미분할 모델 소스

공식 YOLO26n-sem-ADE20K 가중치와 앱의 ORT 자산을 재생성하는 ONNX를 보존한다. 모델은 AGPL-3.0이며 [원문 고지](../../app/src/main/assets/licenses/ultralytics-LICENSE.txt)를 따른다.

| 파일 | SHA-256 |
|---|---|
| `source/yolo26n-sem-ade20k.pt` | `68ac71bef2868c987fff7cd7c49cb656922f6d8447ad21af3893305577435000` |
| `source/yolo26n-sem-ade20k-320.onnx` | `3af7911808fd81e95982b3541fe4c1151016fc6e596b872d0b62f3874d575dad` |
| 앱의 320 ORT | `6ce74261203e17eeefda81e41dd8e7e764b0dc98368dc9662501a8de12054dfc` |

[원본 가중치](https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n-sem-ade20k.pt), [모델 구현](https://github.com/ultralytics/ultralytics/tree/v8.4.184).

내보내기는 Ultralytics 8.4.184, PyTorch 2.14.1 CPU, ONNX 1.23.2를 사용했다. 설정은 `format=onnx, imgsz=320, dynamic=False, simplify=False, opset=17, nms=False, device=cpu`다. 이 배포용 기준 모델은 재학습·양자화 없이 변환했다. 별도로 수행한 [파인튜닝 후보와 전후 평가](../../docs/vision/FINETUNING.md)는 구분해 보존한다. ONNX description의 로컬 학습 경로 한 항목만 정리했으며, 저작자·라이선스·텐서 데이터는 유지했다. 원본 PT는 변경하지 않았다.

다음 명령은 보존한 ONNX에서 ORT 파일을 생성하고 입력·최적화 그래프 해시와 앱 모델의 출력을 대조한다. ORT 재변환 파일의 직렬화 해시는 달라질 수 있어 파일 동일성과 계산 검증을 별도로 기록한다. 출력 폴더가 이미 있으면 `--output`으로 새 폴더를 지정한다.

```powershell
python -m pip install -r scripts/models/requirements-ort.txt
python scripts/models/convert_semantic_ort.py
```

ORT 변환은 1.31.0의 `Fixed`, `arm`, type reduction 설정을 사용한다. 실제 21개 프레임의 정리 전후 ONNX·ORT 출력 84쌍은 클래스맵이 모두 같았고, Android에서도 12개 입력 경로의 관측 통계와 점수 일치를 확인했다.
