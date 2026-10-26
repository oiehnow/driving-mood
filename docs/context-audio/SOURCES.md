# 환경·음악 출처

## 위치와 재생

- [Android LocationManager](https://developer.android.com/reference/android/location/LocationManager): 전경 위치 수신. [위치 권한](https://developer.android.com/develop/sensors-and-location/location/permissions) 정책을 따릅니다.
- [Android MediaPlayer](https://developer.android.com/media/platform/mediaplayer/state-resources): 로컬 음원 재생과 자원 해제. [오디오 포커스](https://developer.android.com/media/optimize/audio-focus)와 출력 장치 분리 시 재생을 일시정지합니다.
- [android/platform-samples의 MovieView.kt](https://github.com/android/platform-samples/blob/177c3b92c387894648d0a2d42d238e265b05bbc3/samples/user-interface/picture-in-picture/src/main/java/com/example/android/pip/widget/MovieView.kt): 콜백·해제 패턴 참고. Copyright (C) 2017 The Android Open Source Project, Apache-2.0. 소스 파일을 복사하지 않았습니다.
- [AndroidX Core](https://github.com/androidx/androidx/tree/androidx-main/core)와 [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines): 권한 확인·수신기 등록·비동기 작업과 상태 전달. Apache-2.0.

## 자동 도로 조회

[OpenStreetMap](https://www.openstreetmap.org/copyright) 도로 선분을 [Overpass API](https://dev.overpass-api.de/overpass-doc/en/)로 조회합니다. 지도 데이터: © OpenStreetMap contributors, ODbL 1.0. [공식 서버 구현](https://github.com/drolbr/Overpass-API)은 AGPL-3.0이며 외부 소스를 복사하지 않고 공개 HTTPS API를 호출합니다. 새 라이브러리는 추가하지 않았습니다. 이는 주변 지도 도로 추정으로, 내비게이션 경로 매칭이나 실제 주행 도로 확정이 아닙니다. 공용 서버에 정밀 좌표를 전달하며 조회는 사용자 분석 요청에 한정합니다.

## 기상청 관측

[기상청 단기예보 조회서비스](https://www.data.go.kr/data/15084084/openapi.do)의 초단기실황을 사용합니다. 출처는 기상청·공공데이터포털이며 공공누리 제1유형(출처표시) 조건을 따릅니다. 앱에도 데이터 출처와 관측 시각을 표시합니다.

좌표→격자 변환식, 조회 시각, T1H/RN1/PTY 코드표는 [공식 활용 가이드](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000007732424&fileDetailSn=1)의 2026-10-19 문서를 참고했습니다. 가이드 ZIP SHA-256은 `9DFD7DC2D422318154D3484FB00142AFA3C5271FC14D079ADF997043A14B86BA`입니다. 격자 변환은 Kotlin으로 구현했으며 서울·부산·제주·기준 원점 사례를 검사했습니다.

## 음악과 검색

번들 음원은 없습니다. [Android 파일 선택기](https://developer.android.com/training/data-storage/shared/documents-files)로 사용자가 선택한 로컬 파일을 재생합니다. 파일 보유 여부와 공개 재생·재배포 권한은 별개입니다.

YouTube는 외부 앱/웹의 공식 검색 화면을 엽니다. 검색 결과를 다운로드하거나 오디오를 추출하지 않습니다.
