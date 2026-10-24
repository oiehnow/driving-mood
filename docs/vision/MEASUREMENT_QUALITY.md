# 조명 측정 품질

`LightMeasurer`는 차량과 교통 시설, 반사 가능 영역, 구분하기 어려운 광원을 제외한 뒤 남은 픽셀의 색감과 밝기를 측정한다. 측정 품질은 이 통계를 얼마나 참고할 수 있는지 나타내는 지표다. 간판·상점 식별 정확도나 보정된 확률은 아니다. 밝기는 카메라 RGB의 상대값이며 lux가 아니다.

## 결과 필드

| 필드 | 의미 |
|---|---|
| `colorMeasurementConfidence` | 제외 후 색감·채도 측정 품질 |
| `lightMeasurementConfidence` | 제외 후 밝기·발광 면적 측정 품질 |
| `semanticConfidence` | 장소·물체 의미 해석의 근거 수준. 현재 차량 검출 모델은 상점 의미를 평가하지 않아 `null` |
| `vehicleMaskApplied` | 차량 검출과 제외 처리를 정상 수행했는지. 검출 0건도 성공한 처리라면 `true` |

현재 분석기는 성공해도 `LOW_CONFIDENCE`를 유지한다. 색감·광량 품질은 기존 전체 지표인 `confidence`와 별도로 계산한다. 모델 미준비 또는 추론 실패 시 두 품질은 `null`, `vehicleMaskApplied`는 `false`다. `null`은 미평가 상태이며 측정값 0과 구별한다.

시간 필터는 조명 점수의 상승을 완화하지만 현재 프레임의 색감·품질을 이전 프레임 값으로 대체하지 않는다. 실패하거나 관측이 없어지면 이전의 유효성을 이어 붙이지 않는다.

구현: [LightMeasurement.kt](../../app/src/main/java/kr/urbanmood/vision/LightMeasurement.kt), [UrbanLightAnalyzer.kt](../../app/src/main/java/kr/urbanmood/vision/UrbanLightAnalyzer.kt), [TemporalLightFilter.kt](../../app/src/main/java/kr/urbanmood/vision/TemporalLightFilter.kt).

## 광량 품질 계산

광량 품질은 `0.85 × 관측 조건별 계수의 최솟값`이다. 해당하지 않는 조건의 계수는 1이다. 임계값은 보수적인 측정 이용 정책이며 검증된 인식 정확도 기준이 아니다.

| 관측 조건 | 계수 또는 처리 |
|---|---|
| 채택 조명 픽셀 24개 미만, 전체 대비 0.1% 미만, 입력의 짧은 변 32픽셀 미만, 제외 후 영역 15% 미만 중 하나에 해당 | 0 |
| 채택 조명 면적 | `min(1, 전체 대비 면적 / 0.005)` |
| 제외 후 픽셀 영역 | `min(1, 전체 대비 영역 / 0.35)` |
| 평균 gamma 밝기 < 0.045 또는 HSV max < 0.12인 픽셀 비중 > 90% | 0.25 |
| 위 어둠 조건에 해당하지 않고 평균 gamma 밝기 < 0.08 | 0.55 |
| 전체 흰색 포화 비중 > 25% 또는 평균 gamma 밝기 > 0.8 | 0 |
| 전체 흰색 포화 비중 > 8% | 0.4 |
| 위 8% 포화 조건에 해당하지 않고 채택 조명 중 한 채널이라도 > 0.98인 픽셀 비중 > 20% | 0.55 |
| 차량 제외 영역 | `1 − vehicleRatio` |
| 차량 검출 결과가 모델 출력 상한에 도달 | 0.35 |
| 연결된 광점 영역이 128개 초과 | 0.25 |
| 전체 발광 후보 중 제외·거부된 비중 > 70% | 0.4 |
| 차량 불빛으로 의심되는 광점 쌍 또는 강한 포화 광원 발견 | 0.55 |
| 검출 좌표·신뢰도에 비유한 값이 있거나 신뢰도 범위·영역 크기가 잘못됨 | 0 |

RGB 채널은 0~1로 정규화한다. 평균 gamma 밝기는 `0.2126R + 0.7152G + 0.0722B`의 프레임 평균이다. 흰색 포화는 세 채널 모두 0.98을 넘는 픽셀이다. 후보 채널 포화는 채택된 조명 픽셀 중 어느 한 채널이라도 0.98을 넘는 경우다.

`vehicleRatio`는 차량 제외 마스크의 합집합 면적을 전체 프레임 면적으로 나눈 값이다. 차량 아래 반사 가능 영역과 기하적으로 제외하는 영역은 차량 면적으로 더하지 않는다. 제외 후 영역은 차량·기타 제외 마스크에 포함되지 않은 픽셀 비율이다. 채택 조명 표본은 이 영역에서도 광원 형태·국소 대비 검사를 통과한 픽셀만 포함한다. 따라서 밝은 사각형의 내부 전체가 항상 표본에 포함되는 것은 아니다.

## 색감 품질과 이용 조건

색감 품질은 광량 품질과 같은 관측 조건을 사용한다. 평균 채도가 0.12 이상인데 여러 색의 원형 평균이 상쇄되어 대표 hue가 정의되지 않으면 추가로 0.5를 곱한다. 무채색의 hue를 임의의 따뜻한 색으로 대체하지 않는다. 유효한 hue는 0 이상 360 미만이며, 부동소수점 반올림으로 360이 되는 경우 0으로 정규화한다.

[VisionMeasurementPolicy](../../app/src/main/java/kr/urbanmood/contracts/VisionMeasurementPolicy.kt)는 측정 품질과 별도로 이용 가능 여부를 확인한다.

- 결과 시각과 현재 시각이 양수이고, 결과가 미래가 아니며 10초 이내여야 한다.
- 상태가 `OK` 또는 `LOW_CONFIDENCE`, 분석 방법이 `NONE`이 아니어야 한다.
- `vehicleMaskApplied=true`이고 차량 제외 비율이 유한한 `[0, 0.4)` 범위여야 한다.
- 사용할 측정 품질이 유한한 `[0.5, 1]` 범위여야 한다.
- 색감 이용에는 유효한 hue와 0.2를 넘는 채도가 필요하다.
- 광량 이용에는 유효한 밝기와 발광 면적 비율이 필요하다.

기존 `confidence`나 `semanticConfidence`를 누락된 측정 품질의 대체값으로 사용하지 않는다. 이용 가능한 색감·광량도 상점 영업 여부나 상권 활력의 증거가 아니다. 놓친 차량 불빛, 노면·유리 반사, 잘못된 촬영 각도는 여전히 결과에 영향을 줄 수 있다. 도로·위치 맥락에 따라 추천 단계에서 추가 제한할 수 있다.

## 재현 가능한 분석 결과 fixture

[VisionV2FixtureTest.kt](../../app/src/test/java/kr/urbanmood/vision/VisionV2FixtureTest.kt)는 실제 `UrbanLightAnalyzer`에 200×120 합성 픽셀과 명시적인 가짜 객체 검출 결과를 입력한다. 출력은 [v2-analyzer-fixtures.tsv](v2-analyzer-fixtures.tsv)에 저장된다. 이 fixture는 분석기와 이용 정책의 회귀 검증 자료이며 실카메라·네이티브 모델 정확도 평가 자료가 아니다.

| fixture | 색감 / 광량 품질 | 상태 / 제외 처리 | 색감 / 광량 이용 |
|---|---|---|---|
| `usable_patch` | 0.85 / 0.85 | LOW_CONFIDENCE / true | 허용 / 허용 |
| `empty`, `masked` | 0 / 0 | LOW_CONFIDENCE / true | 거부 / 거부 |
| `dark` | 0.2125 / 0.2125 | LOW_CONFIDENCE / true | 거부 / 거부 |
| `clipped` | 약 0.4675 / 0.4675 | LOW_CONFIDENCE / true | 거부 / 거부 |
| `capacity` | 0.2975 / 0.2975 | LOW_CONFIDENCE / true | 거부 / 거부 |
| `missing_model` | null / null | MODEL_NOT_READY / false | 거부 / 거부 |
| `inference_failed` | null / null | FAILED / false | 거부 / 거부 |

결과 시각과 판정 시각은 모두 1000ms다. 모든 fixture에 대해 판정 시각 11001ms에서 이용이 거부되는지도 검사한다. `usable_patch`도 의미 신뢰는 `null`, 기존 전체 신뢰 지표는 0.55다. 테스트는 출력에 `usableColor`와 `usableLight`를 직접 호출하며 상태를 `OK`로 바꾸지 않는다.

저장소 루트에서 실행한다. `ANDROID_HOME`이 이미 설정되어 있다면 첫 줄은 생략한다.

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
./gradlew.bat -p docs/vision/verification -I ../v2-fixture-export.init.gradle testDebugUnitTest --tests kr.urbanmood.vision.LightMeasurerTest --tests kr.urbanmood.vision.TemporalLightFilterTest --tests kr.urbanmood.vision.PixelMeasurementQualityTest --tests kr.urbanmood.vision.UrbanLightAnalyzerTest --tests kr.urbanmood.vision.VisionV2FixtureTest assembleDebug lintDebug --console=plain
```

이 명령은 합성 픽셀·시간 필터·분석기 수명·측정 품질·fixture 검사 35개를 선택한다. 별도 사진 평가 테스트는 실행하지 않는다. 검증 프로젝트의 산출물은 Android library AAR이며 설치 APK가 아니다.

2026-10-24 검증에서는 선택 검사 35개 통과, 실패·오류·건너뜀 0개, 독립 AAR 빌드 성공, Lint 오류 0개·경고 4개였다. 경고는 검증 프로젝트 의존성의 새 버전 안내다. [검증 요약](v2-verification-summary.json)에 결과를 기록했다. 이 결과만으로 실제 기기에서의 정확도·지연·발열이나 추천기 전체의 동작을 보장하지 않는다.
