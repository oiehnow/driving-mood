# 외부 코드·라이브러리 출처와 고지

UrbanMood에서 사용하는 외부 코드·모델·라이브러리의 출처와 라이선스입니다. 라이선스 원문은 [라이선스 자산](app/src/main/assets/licenses/)과 [모델 고지](app/src/main/assets/vision/THIRD-PARTY-NOTICES.md)에 보존합니다.

| 구성요소 | 출처·활용 | 라이선스 |
|---|---|---|
| AndroidX CameraX 1.6.2 | https://github.com/androidx/androidx — 카메라·변환·미리보기·수명주기 의존성 | Apache-2.0 |
| AndroidX Core/Activity/Lifecycle 및 전이 의존성 | https://github.com/androidx/androidx — 권한 결과·전경 수명주기·상태 수집 | Apache-2.0 |
| Kotlin / kotlinx.coroutines / atomicfu | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines / https://github.com/Kotlin/kotlinx-atomicfu — 언어 런타임·직렬 작업·StateFlow | Apache-2.0 |
| Gradle wrapper 8.13 | https://github.com/gradle/gradle/tree/v8.13.0 — 원본 wrapper 스크립트/JAR, 헤더·LICENSE·NOTICE 보존 | Apache-2.0 및 배포물의 포함 라이선스 |
| Android platform-samples | https://github.com/android/platform-samples — CameraX/권한 공식 패턴 참고. 소스 파일 직접 복사 없음 | Apache-2.0 |
| android/camera-samples | https://github.com/android/camera-samples — 공식 예제 조사. 직접 코드 병합 없음 | Apache-2.0 |
| Guava / failureaccess / listenablefuture | https://github.com/google/guava — CameraX 전이 의존성 | Apache-2.0 |
| Dagger / AutoValue / Error Prone / J2ObjC annotations | https://github.com/google/dagger / https://github.com/google/auto / https://github.com/google/error-prone / https://github.com/google/j2objc — 전이 의존성 | Apache-2.0 |
| JetBrains annotations | https://github.com/JetBrains/java-annotations — 전이 의존성 | Apache-2.0 |
| JSpecify | https://github.com/jspecify/jspecify — 전이 nullness 어노테이션 | Apache-2.0 |
| Checker Framework checker-qual | https://github.com/typetools/checker-framework — 전이 타입 어노테이션 | MIT (checker-qual) |
| JSR305 | https://github.com/findbugsproject/findbugs — 전이 어노테이션. 3.0.2 원본 소스의 Brian Goetz 저작권 헤더 보존 | 배포 POM: Apache-2.0, concurrent 소스 헤더: CC-BY-2.5 |
| Jakarta Inject | https://github.com/jakartaee/inject — Dagger 전이 의존성 | Apache-2.0, 원본 NOTICE 보존 |
| JUnit 4.13.2 (테스트 전용) | https://github.com/junit-team/junit4 — JVM 검사, APK 런타임 아님 | EPL-1.0 |
| MediaPipe tasks-vision/core 1.1.1 | https://github.com/google-ai-edge/mediapipe — CPU 객체 검출. 공식 예제 API 패턴 참고, 예제 소스 복사 없음 | Apache-2.0 |
| EfficientDet-Lite0 metadata v1 | https://www.kaggle.com/models/tensorflow/efficientdet/TfLite/lite0-detection-metadata/1 — 모델 내용 무변경, 파일명만 변경. 공식 메타데이터는 docs/vision/model-source.json | Apache-2.0 |
| Flogger 0.6 | https://github.com/google/flogger — MediaPipe 전이 의존성 | Apache-2.0 |
| Protobuf javalite 4.26.1 | https://github.com/protocolbuffers/protobuf — MediaPipe 전이 직렬화 런타임 | BSD-3-Clause |
| Robolectric 4.16.1 (테스트 전용) | https://github.com/robolectric/robolectric — Android API shadow로 단위/UI 연결 검사. APK 런타임 아님 | MIT |
| Pretendard Variable | https://github.com/orioncactus/pretendard — 사용자 제공 원본 글꼴을 `res/font/pretendard_variable.ttf`에 변경 없이 포함. Copyright (c) 2021, Kil Hyung-jin; Reserved Font Name Pretendard | SIL OFL-1.1, 원문 `assets/licenses/Pretendard-OFL.txt` |
| fontTools 4.63.0 (검사 전용) | https://github.com/fonttools/fonttools — APK 글꼴의 이름·저작권 문자열을 정식 인코딩으로 읽는 호스트 검사 의존성. Android APK에는 미포함 | MIT |

AndroidX 저작권자는 Android Open Source Project, Kotlin 계열은 JetBrains 및 해당 기여자, Guava/MediaPipe 계열은 Google/TensorFlow Authors 및 해당 기여자이며 상세 원문 고지는 포함된 라이선스 파일을 따른다. 런타임에 실제 선택된 버전과 전이 그래프는 `docs/runtime-dependencies.txt`에 기록되어 있다. Gradle/AGP/JUnit/Robolectric은 빌드·테스트 도구입니다.

평가용 사진 5장은 APK에 포함하지 않는다. 각각의 저작자·원본·CC 라이선스는 `docs/vision/evaluation/ATTRIBUTION.md`를 그대로 보존한다. 기상청 데이터의 출처와 공공누리 제1유형 조건은 `docs/context-audio/SOURCES.md`에 기록되어 있다.

앱 자체 소스·빌드 스크립트·테스트는 [AGPL-3.0-only](LICENSE)로 공개한다. 외부 코드·모델·글꼴은 각각의 원래 라이선스와 고지를 유지한다. 보고서와 주행 영상·음악·스크린샷에 이 소스 라이선스를 일괄 적용하지 않는다.

| 추가 구성요소 | 출처·활용 | 라이선스 |
|---|---|---|
| YOLO26n-sem-ADE20K | https://github.com/ultralytics/ultralytics — 건물·창문·간판 의미분할. 320 ONNX/ORT 변환, 비실행 설명 경로만 정리 | AGPL-3.0 |
| ONNX Runtime Android 1.31.0 | https://github.com/microsoft/onnxruntime — 기기 내 의미분할 추론 | MIT 및 포함 ThirdPartyNotices |
| Skia SkRGBToHSV | https://github.com/google/skia/blob/main/src/core/SkColor.cpp — 하늘 RGB의 HSV 변환을 Kotlin으로 옮겨 활용. Skia 런타임 의존성은 추가하지 않음 | BSD-3-Clause; Copyright 2006 The Android Open Source Project / Copyright (c) 2011 Google Inc.; 원문 `app/src/main/assets/licenses/Skia-LICENSE.txt` |

[모델 소스와 변환](models/semantic/README.md), [앱 내 고지](app/src/main/assets/licenses/SEMANTIC-SOURCES.txt), [전체 AGPL 원문](app/src/main/assets/licenses/ultralytics-LICENSE.txt)을 보존한다.
