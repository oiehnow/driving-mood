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

표시 이름은 Driving Mood입니다. 기존 설치의 업데이트를 위해 앱 ID·패키지·서명키 저장 위치와 키는 유지합니다. 0.2.3의 versionCode는 5입니다.

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

**무드 분석**을 누르면 권한 확인 → 위치·날씨 → 최신 카메라 한 프레임 → 무드 키워드 순서로 진행하고 수집을 종료합니다. 완료 결과와 분석 시각은 화면 회전 시에도 메모리에 유지합니다. 다시 누를 때에만 새 정보를 수집하며 음악 조작·키 설정·수동 설정·화면 복귀는 수집을 시작하지 않습니다.

진행 중에는 중복 분석을 막으며 **취소** 또는 화면 이탈 시 이전 완료 결과를 유지합니다. 시스템 권한 창 때문에 잠깐 일시중지되는 경우는 요청을 유지하되, 실제 백그라운드 이동과 전체 60초 초과는 취소합니다. 뒤늦은 권한 응답으로 재시작하지 않습니다. 위치 대기 20초, 날씨 통신 12초, 환경 단계 35초, 카메라 단계 8초 제한이 있습니다. 권한 거절·날씨 누락·카메라 실패는 해당 이유를 표시하고 사용 가능한 정보만 반영합니다.

날씨는 같은 격자·발표 시각·10분 갱신 구간의 유효 캐시를 재사용합니다. 명시 분석 한 번당 날씨 통신은 최대 한 번이며 실패 시 자동 재시도하지 않습니다. 캐시·재요청 제한은 환경 제공자 인스턴스 메모리 기준이므로 앱 재생성까지 유지되는 영구 쿼터 관리 기능은 아닙니다.

## 원하는 분위기

**원하는 분위기 선택**에서 따뜻한·몽환적인·차분한·잔잔한·편안한·포근한·청량한·차가운·우울·활기 넘침을 여러 개 고를 수 있습니다. 선택값은 사용자가 음악에서 원하는 분위기이며 실제 감정을 인식한 결과가 아닙니다. 적용과 선택 해제는 완료된 같은 분석 결과로 음악 추천만 갱신하며 새 위치·날씨·카메라 수집을 하지 않습니다. 분석 중에는 선택을 잠그고, 화면 회전 시 선택값을 보존합니다.
