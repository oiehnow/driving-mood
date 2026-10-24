# 그림과 구현 코드

README의 도식은 제품 구조와 현재 알고리즘을 설명합니다. 실제 주행 영상에 모델을 실행한 결과는 [주행 시연](DRIVING_DEMO.md)에 별도로 정리했습니다.

## 제품 구조

![무드 키워드와 현재 활용 및 후속 확장](assets/mood-overview.png)

현재 키워드는 추천기 내부에서 생성하며, 원하는 분위기·선호 장르·곡 태그는 선곡에 사용합니다. 현재 기분과 수정 피드백, 공통 무드 엔진 분리, 내비게이션·실내조명·주변 장소 연결은 후속 개발입니다.

![차가운·우울·활기 넘침 키워드의 사용 방식](assets/mood-keywords.png)

## 요청 흐름

![요청형 분석 처리 순서](assets/request-flow.gif)

설명용 애니메이션입니다. 각 단계의 표시 시간은 읽기 편하게 정했으며 지연·FPS 측정값이 아닙니다. [정지 그림](assets/request-flow-static.png)

실행 흐름: [OneShotAnalysisRunner](../../app/src/main/java/kr/urbanmood/integration/OneShotAnalysis.kt)는 권한 확인, 환경 수집, 카메라 샘플, 추천 순서를 제어합니다. [MainActivity](../../app/src/main/java/kr/urbanmood/MainActivity.kt)는 요청 상태와 취소·화면 생명주기, 완료 결과 표시를 연결합니다.

## 알고리즘

![현재 코드 기준 무드 분석 알고리즘](assets/algorithm-pipeline.png)

| 그림 단계 | 구현과 확인할 내용 |
| --- | --- |
| 위치·날씨 | [AndroidEnvironmentProvider](../../app/src/main/java/kr/urbanmood/context/AndroidEnvironmentProvider.kt), [WeatherRequests](../../app/src/main/java/kr/urbanmood/context/WeatherRequests.kt): 1회 수집, 캐시, 중복 요청·재시도 제한 |
| 영상 입력 | [CameraController](../../app/src/main/java/kr/urbanmood/camera/CameraController.kt): 최신 프레임 수신과 수집 종료 |
| 차량 제외·픽셀 측정 | [영상 분석 설명](../vision/README.md): 차량 검출과 픽셀 측정의 역할·한계 |
| 색감 사용 조건 | [공통 계약](../../app/src/main/java/kr/urbanmood/contracts/Contracts.kt)의 VisionMeasurementPolicy: 최신성, 제외 비율, 측정 품질과 채도 |
| 무드 규칙 | [SceneInterpreter](../../app/src/main/java/kr/urbanmood/recommendation/SceneInterpreter.kt): 도로와 색감의 사용 여부 및 분위기 단서 |
| 키워드·선곡 | [MoodRecommendationEngine](../../app/src/main/java/kr/urbanmood/recommendation/MoodRecommendationEngine.kt): 시간·날씨·도로 결합, 태그 가중치와 최근 곡 순서 |

그림의 10초·40%·0.5·0.2는 현재 코드의 입력 수용 기준입니다. 정확도나 실제 감정 인식 확률이 아닙니다. 생략된 유한값·입력 상태 검사는 연결된 구현을 기준으로 합니다.

## 재생성과 출처

도형과 문구는 이 프로젝트에서 작성했습니다. Pillow가 설치된 환경에서 다음 명령으로 PNG와 GIF를 재생성할 수 있습니다. 앱에 포함된 Pretendard Variable을 함께 사용합니다.

```sh
python scripts/render-project-visuals.py
```

위 렌더러는 설명용 도식만 생성합니다. README의 주행 장면은 사용자 제공 영상에 실제 모델을 실행한 자료이며, 장면 이미지나 분할 마스크를 생성 도구로 만들지 않았습니다.
