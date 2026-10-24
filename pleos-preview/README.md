# Driving Mood · Pleos UI prototype

Pleos Connect의 앱 창에서 로고 → 분석 연출 → 무드 결과와 음악·조명 미리보기를 실행하는 독립 Android 앱입니다. 제작자 **oieh**.

## 실행

Android Studio에서 프로젝트를 열고 `pleos-preview` 모듈과 Pleos AVD를 선택합니다.

```powershell
.\gradlew.bat :pleos-preview:assembleDebug
adb -s <Pleos-device-id> install -r pleos-preview/build/outputs/apk/debug/pleos-preview-debug.apk
```

앱 목록의 **Driving Mood Preview**를 엽니다. 하단 바로가기는 [Pleos GNB 편집 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/components/gnb)의 길게 누르기·드래그 방식으로 배치합니다.

## 범위

- 예시 무드와 점수, 음악 표시 상태, 조명 색상·밝기 조작
- 취소·뒤로 가기·재분석, 작은 앱 창의 스크롤
- Android API 28 이상, Java 17

이 모듈은 UI 흐름을 검증합니다. 촬영·모델 추론·위치·날씨·음악 출력·차량 제어는 연결하지 않았으며, 휴대폰의 실제 분석 앱은 `app` 모듈에서 실행합니다.

[화면과 차량 연결 설계](../docs/product/PLEOS_EXPERIENCE.md) · [AGPL-3.0](../LICENSE)

AndroidX Activity는 Apache-2.0, Pretendard는 SIL OFL 1.1을 따릅니다. 고지는 `src/main/assets/licenses`에 포함됩니다.
