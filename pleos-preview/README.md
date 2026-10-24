# Driving Mood · Pleos UI prototype

Pleos Connect에서 무드 시연과 조명 미리보기, 로컬 음악 재생, 기본 내비게이션 연결을 제공하는 독립 Android 앱입니다. 제작자 **oieh**.

**0.1.1 / versionCode 2** · [APK 다운로드](https://github.com/oiehnow/driving-mood/releases/tag/pleos-preview-v0.1.1) · [검증 기록](../docs/VALIDATION-PLEOS-0.1.1.md)

## 실행

Android Studio에서 프로젝트를 열고 `pleos-preview` 모듈과 Pleos AVD를 선택합니다.

```powershell
.\gradlew.bat :pleos-preview:assembleDebug
adb -s <Pleos-device-id> install -r pleos-preview/build/outputs/apk/debug/pleos-preview-debug.apk
```

앱 목록의 **Driving Mood Preview**를 엽니다. 하단 바로가기는 [Pleos GNB 편집 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/components/gnb)의 길게 누르기·드래그 방식으로 배치합니다.

## 범위

- 새벽 고속도로를 가정한 수동 무드·점수 시연과 ‘추천 조명 세팅’의 색상·밝기 미리보기
- 로컬 음원의 MediaPlayer 재생, 내장 앨범 이미지와 확대 보기, 미디어 세션과 재생 서비스
- 음악 재생 시간에 맞춰 밝기를 바꾸는 조명 미리보기
- NaviHelper 3.0.5로 내비 열기·장소 검색·목적지·남은 시간·거리 조회
- 취소·뒤로 가기·재분석, 작은 앱 창의 스크롤
- Android API 28 이상, Java 17

촬영·모델 추론·날씨·차량 조명 제어는 연결하지 않았으며, 휴대폰의 실제 분석 앱은 `app` 모듈에서 실행합니다. 내비 위치 응답은 아직 무드 계산에 사용하지 않습니다. 시연의 색감 28·활기 12·우울감 86은 수동 입력값이며 실제 모델 점수를 바꾸지 않습니다.

실제 음악 재생·앨범 이미지 읽기와 에뮬레이터 출력 녹음을 확인했습니다. 최소화 후에도 재생이 이어지고, 다시 열면 결과·밝기·재생 위치가 유지되며 닫기(×)는 재생 서비스와 미디어 세션을 종료하는 것을 확인했습니다. 원본 음원과 앨범 이미지는 APK·저장소에 넣지 않습니다.

‘음악과 연동’은 재생 시간에 맞춘 화면 미리보기이며 비트 분석이나 차량 조명 제어는 아닙니다.

## 내비게이션 검증 범위

| 단계 | 확인할 내용 |
|---|---|
| 서비스 연결 | 공식 NaviService 설치·권한과 SDK 연결 콜백 |
| 화면·검색 요청 | `requestNavigationScreen` 호출과 내비 앱의 실제 응답 |
| 현재 경로 조회 | `DestinationInfo.remainTime`의 초, `remainDistance`의 미터를 남은 시간·km로 변환. 미수신은 ‘—’ |
| 안내 상태 | `RouteState.isRouting`과 내비 화면이 일치하지 않으면 ‘안내 상태 확인 필요’로 표시 |

SDK 요청 반환을 목적지 설정이나 길 안내 성공으로 간주하지 않습니다. 목적지 전달 도우미는 구현했지만 사용자 목적지 선택 UI에는 아직 연결하지 않았습니다. [공식 NaviHelper](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)

[화면과 차량 연결 설계](../docs/product/PLEOS_EXPERIENCE.md) · [AGPL-3.0](../LICENSE)

AndroidX Activity는 Apache-2.0, Pretendard는 SIL OFL 1.1을 따릅니다. NaviHelper는 Pleos 공식 배포 의존성을 사용합니다. 출처와 고지는 `src/main/assets/licenses`에 포함됩니다.
