# 분석 계약 v2

VisionResult 끝에 기본값이 있는 필드를 추가했다. 기존 Kotlin 생성자 호출은 재컴파일할 수 있다. LOW_CONFIDENCE 상태는 그대로 유지하며 기존 confidence를 새 측정 품질로 대체하지 않는다.

| 필드 | 기본값 | 의미 |
|---|---|---|
| colorMeasurementConfidence: Float? | null | 차량·불빛 제외 후 색감 측정 품질 |
| lightMeasurementConfidence: Float? | null | 차량·불빛 제외 후 광량 측정 품질 |
| semanticConfidence: Float? | null | 지원 라벨에 대한 장소/물체 의미 해석 근거 수준 |
| vehicleMaskApplied: Boolean | false | 정상적인 차량·불빛 제외 처리 수행 여부 |

품질은 finite [0,1]만 유효하고 null은 미평가/미지원이다. 검증된 확률이 아니다. semanticConfidence가 높아도 영업 여부나 상권 활력을 입증하지 않는다. 정상 검출 0건도 제외 처리 수행 후 true일 수 있지만 실패·미실행은 false다. 마스크 처리 성공은 모든 차량/반사 제거를 보장하지 않는다.

공용 구현: `app/src/main/java/kr/urbanmood/contracts/VisionMeasurementPolicy.kt`.

- usableColor / usableLight는 상태 OK 또는 LOW_CONFIDENCE, 실제 측정 방식, 제외 처리 수행, 10초 이내의 과거/현재 프레임, finite 차량 제외 비율 [0,0.4), 해당 품질 [0.5,1]을 요구한다. 미래·누락 시각은 거부한다.
- 색감은 finite hue [0,360), saturation (0.2,1]이 필요하다. 광량은 실제 brightness와 luminousAreaRatio가 각각 finite [0,1]이어야 한다.
- 색감·광량 품질은 독립적이다. 의미 신뢰나 기존 전체 confidence를 대신 사용하지 않는다. 이용 가능한 색감은 제한된 음악 분위기에 활용할 수 있으며 도로/장면에 따른 추가 억제가 가능하다. 밝기만으로 상권·활력을 추론하지 않는다.
- .5/.4/10초는 검증된 정확도 보장이 아닌 초기 이용 정책이다. 분석기는 실제 표본·포화·가림·반사 등의 근거로 품질을 계산하고 reasons에 설명한다. 오래된 측정을 새 시각으로 전달하지 않는다.

앱은 세 품질 항목과 제외 처리 여부를 나눠 표시한다. 계약 확장 검증과 실제 분석→추천 연결 검증은 [검증 기록](VALIDATION.md)에서 구분한다.

## 출처·재사용

MediaPipe의 [ImageSegmenterResult](https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/tasks/java/com/google/mediapipe/tasks/vision/imagesegmenter/ImageSegmenterResult.java)를 확인했다. 저장소 [google-ai-edge/mediapipe](https://github.com/google-ai-edge/mediapipe), Apache-2.0. 선택적 category/confidence 결과와 별도 품질 구조를 참고했으며 외부 코드 복사·새 의존성 도입은 없다. 기존 MediaPipe 의존성과 원본 고지는 유지한다. 원문의 qualityScores는 미출력 시 1을 기본값으로 쓸 수 있으므로 이를 앱 측정 품질로 가져오지 않는다.
