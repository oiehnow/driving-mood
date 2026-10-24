# 실제 주행 영상 분석

사용자 제공 영상 6개에서 실제 프레임 4,757개를 추출해 의미 분할을 실행했다. 각 영상은 대표 90초이며, 90초보다 짧은 영상은 실제 길이를 사용했다. 원본은 구간 추출만 하고, 같은 구간을 분할·계산 화면과 무드 결과 화면으로 만들었다.

## 결과를 읽는 방법

원본 RGB와 분할 ID 마스크는 같은 디코딩 프레임이다. 모델은 **YOLO26n-sem ADE20K**, ONNX Runtime CPU 경로로 실행했다. 150개 클래스의 실제 예측을 색으로 표시한다. 클래스 면적은 정확도나 확률이 아니다.

차량 클래스의 연결 영역을 상자로 변환해 기존 Kotlin 영상 측정기와 `SceneInterpreter`에 연결했다. 차량 주변·반사 영역을 추가로 제외하므로 화면의 차량 제외 비율은 분할 차량 픽셀 면적과 다르다. 기준을 통과한 색감 후보만 결과 화면에 표시하며, 부족하면 판단을 보류한다. 음원이나 감정 결과를 임의로 채우지 않았다.

이 시연은 **데스크톱에서 수행한 실제 분석**이다. 현재 APK의 EfficientDet 경로와 다르며 휴대폰 화면 녹화가 아니다. 원본의 GPS·도로 종류·날씨·촬영 시각은 미제공이다. 프레임 시간 코드는 영상 안의 위치이며 촬영 날짜나 시각을 뜻하지 않는다.

이번 실행에서 색감 사용 조건을 통과한 프레임은 **4,757개 중 2개**였다. 두 프레임 모두 야간 도심 구간에서 ‘몽환적인’ 후보를 반환했으며, 나머지는 판단 보류다. 이는 정확도 평가가 아니라 현재 분할·차량 제외·색감 규칙을 연결했을 때의 결과다. 분할 모델과 측정 조건 모두 개선·검증이 필요하며, 무드 인식이 완성됐다는 근거로 사용하지 않는다.

## 장면별 결과

### 야간 차량과 거리 조명

![실제 야간 차량 영상과 분할·계산](assets/driving/clip-01-analysis.gif)

[무드 결과 화면](assets/driving/clip-01-result.png) · [프레임 근거](assets/driving/clip-01-evidence.json)

실내가 크게 보이는 구도에서 전방 도로와 일부 차량이 벽·바닥·천장으로 잘못 나뉜다. 차량 클래스 면적이 작다는 이유로 차량이 없다고 판단할 수 없다.

### 야간 빗길과 반사광

![실제 빗길 영상과 분할·계산](assets/driving/clip-02-analysis.gif)

[무드 결과 화면](assets/driving/clip-02-result.png) · [프레임 근거](assets/driving/clip-02-evidence.json)

도로 안내판도 `signboard`로 예측된다. 이를 상점 간판으로 단정하지 않는다. 빗방울과 강한 반사광, 실내 계기판에서 오분류가 관측됐다.

### 야간 도심과 상가 조명

![실제 야간 도심 영상과 분할·계산](assets/driving/clip-03-analysis.gif)

[무드 결과 화면](assets/driving/clip-03-result.png) · [프레임 근거](assets/driving/clip-03-evidence.json)

### 주간 도심 주행

![실제 주간 도심 영상과 분할·계산](assets/driving/clip-04-analysis.gif)

[무드 결과 화면](assets/driving/clip-04-result.png) · [프레임 근거](assets/driving/clip-04-evidence.json)

### 터널에서 주간 도로로

![실제 터널 출구 영상과 분할·계산](assets/driving/clip-05-analysis.gif)

[무드 결과 화면](assets/driving/clip-05-result.png) · [프레임 근거](assets/driving/clip-05-evidence.json)

### 어두운 도로의 야간 주행

![실제 어두운 도로 영상과 분할·계산](assets/driving/clip-06-analysis.gif)

[무드 결과 화면](assets/driving/clip-06-result.png) · [프레임 근거](assets/driving/clip-06-evidence.json)

## 처리 범위와 출처

| 구간 | 원본에서 사용한 범위 | 길이 | 분석 프레임 |
| --- | --- | --- | --- |
| clip-01 | 0–25.64초 | 25.64초 | 257 |
| clip-02 | 48–138초 | 90초 | 900 |
| clip-03 | 90–180초 | 90초 | 900 |
| clip-04 | 0–90초 | 90초 | 900 |
| clip-05 | 0–90초 | 90초 | 900 |
| clip-06 | 70–160초 | 90초 | 900 |

분석·결과 영상은 초당 10개 실제 추론 프레임을 사용한다. 25.64초 구간의 분석·결과 영상은 마지막 실제 프레임을 포함해 25.7초이며, 원본과의 끝 시각 차이는 0.06초다. GIF는 각 구간의 중간 6초를 초당 5프레임으로 요약했다. 입력 영상의 표시와 워터마크는 유지했고 GIF에는 음원을 넣지 않았다. 원본 영상과 음악의 권리가 이 프로젝트로 이전되거나 공개 소스 라이선스로 재허가된 것은 아니다.

분할 모델: [공식 구현](https://github.com/ultralytics/ultralytics), [공식 가중치](https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n-sem-ade20k.pt). AGPL-3.0 또는 별도 Enterprise 조건이며 현재 APK에 포함하지 않았다. 실행 ONNX의 SHA-256은 `f5b30d45110a4fd31ebdbcb61567def157e3d3b8f6a8d3387e038e3a7b2ef4f7`이다. 정답 마스크가 없으므로 분할 정확도는 평가하지 않았다.
