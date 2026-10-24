# Driving Mood 빌드와 설치

## 환경

- JDK 17, Android SDK 36, build-tools 35.0.0 및 36.0.0
- Windows와 PowerShell 7(릴리스 서명·검증 스크립트)
- 최초 의존성 다운로드를 위한 인터넷 연결

`JAVA_HOME`과 `ANDROID_HOME`을 각 설치 위치로 설정합니다. `local.properties`의 `sdk.dir`에도 SDK 위치를 지정합니다. 이 로컬 설정 파일과 서명키는 Git에 포함하지 않습니다.

## 빌드

저장소 루트에서 실행합니다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
.\scripts\build-release.ps1
.\scripts\verify-apk.ps1
python -m pip install -r scripts/requirements-audit.txt
python scripts/audit-apk-text.py app/build/outputs/apk/release/app-release.apk
```

결과 APK는 `app/build/outputs/apk/release/app-release.apk`입니다. 앱 ID는 `kr.urbanmood`, 최소 API는 26, target/compile API는 36입니다. 빌드·라이브러리 버전은 Gradle 설정과 [런타임 의존성 목록](runtime-dependencies.txt)에서 확인할 수 있습니다.

표시 이름은 Driving Mood입니다. 기존 설치의 업데이트를 위해 앱 ID·패키지·서명키를 유지합니다. 현재 소스 버전은 0.3.0 / versionCode 8이며 배포 APK의 검증 버전은 [검증 기록](VALIDATION.md)을 확인합니다.

APK 문구 검사는 Python 3.11 이상에서 실행합니다. DEX 문자열·자산·리소스와 네이티브 바이너리의 인쇄 가능한 문자열을 검사하며, 글꼴은 fontTools로 이름·저작권 문자열을 정식 인코딩으로 읽습니다. 글자 윤곽 좌표는 UI 문장으로 취급하지 않습니다. 일치한 인증정보나 개인 경로 자체는 출력하지 않습니다. 제작사 원본과 동일한 네이티브 파일의 빌드 경로는 [원본 대조 기록](upstream-native-metadata.json)에 따라 별도 표시합니다. 현재 사용자의 홈/작업 경로와 토큰 패턴은 여전히 오류로 처리합니다.

릴리스 스크립트는 로컬 서명키를 생성하거나 기존 키를 재사용합니다. 키는 저장소 밖의 사용자 설정 폴더에 저장되며 `-SigningDirectory`로 위치를 지정할 수 있습니다. 암호는 Windows 사용자 계정에 연결된 DPAPI로 보호합니다. 앱 업데이트에는 같은 서명키가 필요하므로 키와 암호 파일을 함께 보관해야 합니다.

## 설치

USB 디버깅을 켠 기기를 연결하고 기기에서 연결을 승인합니다.

```powershell
$adbPath = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adbPath devices -l
& $adbPath install -r './app/build/outputs/apk/release/app-release.apk'
& $adbPath shell am start -n kr.urbanmood/.MainActivity
```

기존 앱과 서명이 다르면 업데이트 설치가 거부됩니다. 이 경우 기존 테스트 앱을 삭제한 뒤 설치할 수 있으며, 삭제 시 앱 데이터도 지워집니다.

## 연결 설정

날씨에는 공공데이터포털의 [기상청 초단기실황 서비스](https://www.data.go.kr/data/15084084/openapi.do) 일반/디코딩 키를 사용합니다. 앱의 **기상청 키 설정**에서 입력하며 소스나 APK에 키를 넣지 않습니다.

음원은 기기의 파일 선택기로 추가합니다. 번들 음악은 없으며 YouTube 버튼은 외부 검색을 엽니다. 앱을 종료한 뒤 다시 실행하면 음악 파일을 다시 선택해야 합니다.

## 한 번 분석하기

촬영 길이 **30초 또는 60초**를 선택하고 **무드 분석**을 누릅니다. 권한 확인 후 무음 영상을 촬영하며 위치·날씨를 함께 조회합니다. 저장이 끝나면 실제 영상 시각을 따라 1초 구간별 장면을 분석합니다. 결과를 확정하면 완료음을 한 번 재생하고 무드·근거·음악 추천을 표시합니다. 영상은 앱 내부 캐시에만 잠시 저장하고 분석 종료 또는 취소 후 삭제합니다.

**취소**, 화면 이탈 또는 회전은 진행 중 요청을 중단하고 이전 완료 결과를 유지합니다. 화면 복귀·음악 조작·설정 변경으로 촬영을 다시 시작하지 않습니다. 권한 창은 최대 110초, 환경 조회는 35초, 영상 저장은 촬영 길이+30초, 영상 분석은 180초, 전체 요청은 420초로 제한합니다. 촬영·저장·디코딩 실패 시 미완료 영상을 완료 결과로 표시하지 않습니다. 카메라 권한을 거절한 경우에는 제공된 환경 정보만 사용합니다.

분석 중에는 현재 영상 프레임 위에 실제 검출 영역과 그 프레임의 기여를 표시합니다. 완료 후 **점수와 관측 근거**에서 누적 관측, 반영 여부, 규칙 버전을 확인합니다. 빛의 색별 채도·면적에서 계산한 관측 강도와 측정 품질을 따로 보여 줍니다. 조명 프리셋과 밝기는 **조명 미리보기**에서 선택할 수 있습니다.

Android 관측은 `accepted-light-rgb-v1`의 채택 픽셀을 사용합니다. 작은 간판·넓은 건물 조명 누락은 남아 있으며 데스크톱 의미 분할 실험은 앱에 탑재되지 않았습니다.

날씨는 같은 격자·발표 시각·10분 갱신 구간의 유효 캐시를 재사용합니다. 명시 분석 한 번당 날씨 통신은 최대 한 번이며 실패 시 자동 재시도하지 않습니다. 캐시·재요청 제한은 환경 제공자 인스턴스 메모리 기준이므로 앱 재생성까지 유지되는 영구 쿼터 관리 기능은 아닙니다.

## 원하는 분위기

**원하는 분위기 선택**에서 따뜻한·몽환적인·차분한·잔잔한·편안한·포근한·청량한·차가운·우울·활기 넘침을 여러 개 고를 수 있습니다. 선택값은 사용자가 음악에서 원하는 분위기이며 실제 감정을 인식한 결과가 아닙니다. 적용과 선택 해제는 완료된 같은 분석 결과로 음악 추천만 갱신하며 새 위치·날씨·카메라 수집을 하지 않습니다. 분석 중에는 선택을 잠그고, 화면 회전 시 선택값을 보존합니다.

## Android 녹화 기능 검사

API 29 이상 전용 테스트 기기 또는 에뮬레이터에서 다음을 실행합니다. 카메라·위치 권한을 부여하고 실제 30초·60초 촬영, 디코딩·결과 확정, 취소 후 임시파일 삭제, 완료음 리소스를 검사합니다. 가상 카메라 입력은 주행 정확도·실기기 속도 평가에 사용하지 않습니다.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```
