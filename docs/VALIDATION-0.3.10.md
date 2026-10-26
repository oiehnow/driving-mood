# Mobile 0.3.10 / Pleos 0.1.1 release 검증

검증일: 2026-10-26. [두 앱 다운로드](https://github.com/oiehnow/driving-mood/releases/tag/v0.3.10)

## APK 정보

| 항목 | Mobile | Pleos |
|---|---|---|
| 파일명 | 이태현_DrivingMood(Mobile)_release.apk | 이태현_DrivingMood(Pleos)_release.apk |
| 패키지 | `kr.urbanmood` | `kr.drivingmood.pleospreview` |
| 버전 / 코드 | 0.3.10 / 18 | 0.1.1 / 2 |
| 최소 / 대상 API | 26 / 36 | 28 / 36 |
| 크기 | 244,869,311 bytes | 9,958,721 bytes |
| debuggable | false | false |
| 서명 및 ZIP 16KB 정렬 | 통과 | 통과 |
| 네이티브 ELF 정렬 | 20개 라이브러리 모두 16KB 이상 | 네이티브 라이브러리 없음 |

두 APK의 서명 인증서 SHA-256은 `14ec78db3c3f710e2762d478299d5dfca0a0d62b5bb76cc4c1f345bb12b9a513`입니다. 기존 Mobile 0.3.9 release와 일치합니다. Pleos는 과거 debug APK의 이름을 바꾼 것이 아니라 0.1.1 소스의 release 구성으로 새로 빌드·서명했습니다. 검증 스크립트가 과거 debug APK를 거부하는 것도 확인했습니다.

APK SHA-256:

```text
5f74e5370ad5666ed5ef7dab7b996c4c227bd91cb3faf0c5b228430c06ba70c2  이태현_DrivingMood(Mobile)_release.apk
cfbe60b23799007a7a1e1594f676d9d01f762ac0b9c3ab42ce309c2d24bd9a71  이태현_DrivingMood(Pleos)_release.apk
```

GitHub가 개별 Release 자산의 한글·괄호를 자동 치환하므로, 정확한 파일명은 `DrivingMood-release.zip` 내부에 보존합니다. ZIP 내부 두 APK의 이름과 SHA-256을 원본과 대조했습니다. 개별 APK 링크와 SHA256SUMS.txt도 같은 Release에 제공합니다.

## 변경과 자동 검사

- 정밀 위치 권한이 있으면 50m 이하 위치를 우선 선택합니다. 더 최근의 부정확한 네트워크 위치가 GPS를 가로막지 않도록 했습니다. 최대 18초 대기 후 날씨에 사용할 수 있는 위치는 원래 관측 시각을 유지한 채 반환합니다.
- 촬영 시작 때 오래된 위치를 채택해 종료 시 만료되는 경로를 수정했습니다. 새 후보는 30초 이내인지 확인하며, 최종 위치 유효성 검사는 120초 기준입니다.
- 정상 수신한 날씨는 원래 관측·조회 시각으로 독립 검증합니다. 위치의 이후 만료 때문에 제외하지 않습니다. 비·눈·강수 없음·기온은 `WEATHER_RULE` 근거 점수로 반영하고 조명 관측 강도·하늘색 규칙 점수와 단위를 섞지 않습니다. 음악 선택 규칙이며 운전자의 실제 감정이나 정확도 확률을 측정하지 않습니다.
- OpenStreetMap 도로 선분 조회, 위치 정확도·모호성 검사, 수동 우선, 캐시·최소 요청 간격·취소·시간 초과 처리를 연결했습니다. 보행로 등을 차량 도로로 사용하지 않으며, 원래 위치 시각을 추천 및 근거 화면까지 전달합니다.
- 전체 모바일 단위 검사: **386개 중 382개 통과, 4개 건너뜀, 실패·오류 0개**. 포함 범위: 실제 Android 위치 어댑터의 Robolectric 검사 7개, 도로 관련 검사 18개, 날씨 기여·만료·출처·지연 응답·화면 표시 회귀 검사.
- `:app:lintDebug`, `:pleos-preview:lintDebug`: 오류 0개. 경고는 각각 46개, 8개이며 숨기지 않았습니다.
- JDK 17, Gradle 8.13, Android SDK 36으로 두 release 빌드 성공.
- 두 최종 APK의 문구·인증정보 패턴·개인 경로 검사는 Python 3.11에서 통과했습니다. 기존 의존성의 원본 네이티브 빌드 경로는 원본 해시 일치 시 별도 표시하며 저작권·라이선스 고지는 보존했습니다.

## 설치·실행 환경

- Mobile: Android 15/API 35 Google APIs x86_64 에뮬레이터에 최종 release 설치 성공. `kr.urbanmood/.MainActivity` cold start 정상, 앱 프로세스와 시작 화면 확인.
- Pleos: Pleos Connect 2.1.0, Android 14/API 34 x86_64 에뮬레이터에 최종 release 설치 성공. `kr.drivingmood.pleospreview/.PreviewActivity` cold start 정상, 차량 화면 안의 앱 시작 화면 확인.
- 에뮬레이터 실행 확인은 실제 휴대폰 GPS·카메라 속도·발열 또는 차량 장치 제어 검증을 뜻하지 않습니다. Pleos는 수동 무드 시연·로컬 음악·내비 연결·조명 미리보기 앱입니다.

## 외부 서비스 확인 범위

실제 사용자 위치·인증키는 테스트에 사용하지 않았습니다. 기상청 처리는 검증된 응답 픽스처·HTTP 및 점수 회귀 검사로 확인했으며, 실제 키 인증과 휴대폰 위치를 이용한 현장 관측은 별도 확인이 필요합니다.

공개 서울시청 좌표로 공용 Overpass 연결을 시험했으나 12초 제한 안에 성공 응답을 받지 못했습니다. 다른 공개 인스턴스와 IPv4 접근에서도 응답 제한을 확인했습니다. 앱은 이 경우 도로 정보를 꾸며내지 않고 시간 초과 이유를 표시하며, 유효한 날씨와 영상 분석은 계속 사용합니다. 지도 조회 구현과 통제된 응답 검사는 완료했지만 실제 서비스 성공 응답·현장 도로 판별 정확도는 이번 실행에서 검증하지 못했습니다. 공용 서버 가용성은 보장되지 않으며 확대 배포에는 별도 서비스 운영 검토가 필요합니다.
