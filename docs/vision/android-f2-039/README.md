# F2 Android 실행 검증

Android 15 x86_64 에뮬레이터, CPU 실행. 휴대폰 0.3.9의 `AppServices.vision`에 F2 의미분할과 YOLO26n 검출기를 연결했다. 실휴대폰·실차 검증과 구분한다.

## 최종 결과

- [분할·입력 정합](native-parity-final.json): 실제 원본 프레임과 세로 회전 입력에서 계산·출처·자원 해제를 확인했다. 동결 기준 분할맵의 픽셀 차이는 0이다.
- [제품 경로 측정](product-final.json): 초기 호출 2,116.59ms, 이후 중앙값 1,652.19ms, 범위 1,596.97~2,152.83ms. 같은 첫 프레임을 초기/준비 후 한 번씩 실행하고 나머지 고유 입력을 실행했다. 반복 횟수와 입력은 원장에 기록했다.
- 첫 초기화는 세션 생성·자산 검증을 포함한다. OS 캐시·JIT·열 상태는 초기화하지 않았다. 측정 구간은 실제 `analyze()`이며 파일 읽기·정답 검사·PSS 조회·기록은 밖에 있다. 영상 디코딩·촬영·UI·구간 누적은 제외한다.
- 호출 후 PSS는 약 167~179MiB, 최종 호출 뒤 176,882KiB였다. **추론 중 최대 메모리 측정은 아니다.** 닫기 후 151,594KiB, native heap 약 14MB로 돌아왔다.
- [native 정합 검사 로그](native-final-test.txt) 2개와 [제품 경로 검사 로그](product-final-test.txt) 1개 통과. 실제 앱 분석 결과와 동결 분할맵을 이용한 전체 관측 통계도 정확히 일치했다.

위 비용은 개발 빌드의 고정 실행 결과다. 최종 서명 release APK를 다시 설치한 뒤 동일 제품 경로 검사도 [통과](product-release-test.txt)했다. 해당 [release 원장](product-release.json)은 별도로 보존한다. APK SHA-256은 `b460c2b38760ca6c1ed19243adf6b5707eed19c51c34fce34f122054638fe222`이며 실행 차이를 평균해 하나의 성능값으로 만들지 않았다.

## 비용 문제와 수정

초기 후처리는 영상마다 넓은 타원 영역을 반복 탐색했다. 수평 구간의 최솟값·최댓값을 재사용하고 Gaussian 경계 인덱스를 캐시했다. 기존 Float 누적 순서·임계값·경계 규칙은 유지했다. 무작위·경계·signed zero·실제 프레임 비교를 포함한 동일 결과 검사를 통과했다.

[최적화 전 정합 원장](parity-before-optimization.json)과 [최적화 후 정합 원장](parity-after-optimization.json)의 `pipelineMs`에는 검사 훅의 파일 대조·SHA·기록·PSS 조회도 포함된다. 이 값은 제품 처리 시간이나 순수 추론 속도로 인용하지 않는다.

[메모리 설정 전 제품 경로](product-before-memory-policy.json)에서는 마지막 호출 뒤 PSS가 1,625,875KiB였다. 각 모델의 CPU arena와 memory pattern 사전할당을 끄고 [최종 제품 경로](product-final.json)를 실행했다. 두 설정을 하나의 정책으로 바꿨으며 각각의 효과를 분리 측정하지 않았다. CPU 부하·측정 시각이 통제되지 않았으므로 시간 단축률이나 최대 메모리 절감률로 확대하지 않는다. 큰 중간 텐서의 순간 메모리 비용은 남아 있다.

공식 API: [ONNX Runtime SessionOptions](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html), [공식 소스](https://github.com/microsoft/onnxruntime/blob/v1.31.0/java/src/main/java/ai/onnxruntime/OrtSession.java). MIT 의존성의 공개 설정을 사용했다.

## 관측 격자와 배포 파일

[동일 모델·박스의 격자 비교](observation-grid-diagnostic.tsv)를 별도로 보존했다. 320→640 격자 변경에 따른 관측량 변화를 모델 정확도 향상으로 해석하지 않는다. 예를 들어 동일 프레임의 밝은 면적은 baseline320 9.77%, baseline640 11.76%, F2 동일640 11.50%였다.

배포 ONNX는 설명 metadata의 머신 경로만 정리했다. [원본→배포 해시와 계산 불변 증거](../../../models/tools/sanitization-provenance.json)에 그래프·텐서 일치와 동일 입력 출력 byte 차이 0을 기록했다. 앞선 실험 원장의 원본 해시는 그대로 보존하며 최종 앱은 정리한 파일 해시를 검증한다. 숫자 가중치의 우연한 문자 패턴을 경로로 오인하지 않도록 APK 검사는 ONNX의 실제 문자열 필드를 읽는다.

## 남은 검증

카메라 새 촬영, 전체 30초 요청, Galaxy S25+·Pleos 실차의 지연·최대 메모리·발열은 별도 확인이 필요하다. 이 실행 검사는 감성 정확도·광원 종류 정확도·창문 인식 향상을 증명하지 않는다. [모델 성능과 실패 실험](../MODEL_IMPROVEMENT_LOG.md)
