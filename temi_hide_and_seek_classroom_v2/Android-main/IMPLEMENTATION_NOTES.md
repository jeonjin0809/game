# 변경 사항 및 검증 기록

## 이전 간단 버전과 달라진 부분

- `HideAndSeekGame.java`: 장소 자동 순찰, 도착 후 정지 감지, 발견 후 대기, 5/10분 시간 로직, TTS 순차 처리, 이동 중 오류 시 교사 확인, 단계별 상태.
- `MainActivity.java`, `activity_main.xml`: 권한 준비, 사람 감지 사전 확인, 저장 위치 2~3곳 다중 선택, 안전 확인 대화상자, 자동 순찰 상태, 고정형 긴급 중단 버튼.
- `RobotController.java` 및 `BaseRobotController.java`: 감지 권한 상태 / 음성 취소 기능 공통 계약.
- `TemiRobotController.java`: SETTINGS 권한 요청 및 결과 처리, 1.5m 감지 요청, 느린 이동 속도와 높은 장애물 회피 설정, `cancelAllTtsRequests`.
- `FakeRobotController.java`: 새 인터페이스에 맞춘 에뮬레이터 흉내, 음성 취소.
- `AndroidManifest.xml`: Kiosk 및 SDK SETTINGS 권한 메타데이터, 가로 화면 고정.
- `dev_panel.xml`: 하단 로그가 게임 중단 버튼을 덮지 않도록 이동.

## 수행한 검증

- Java `javac` 컴파일: **게임 상태 머신, 안전 이벤트, 공통 로봇 인터페이스, BaseRobotController, FakeRobotController** 성공.
- 시뮬레이션: 자동 순찰, 음성 카운트, 도착 후 감지, 중복 인식 잠금, 발견 후 대기, 전원 발견 시 조기 종료 성공.
- 시뮬레이션: 5분 멘트 정확히 1회, 10분 종료, 종료 후 추가 이동 없음 성공.
- 시뮬레이션: 이동 ABORT/수동 재개/긴급 중단, 금지 장소 및 권한 없을 때 게임 시작 거부 성공.
- 시뮬레이션: 안전 이벤트 발생 시 게임 중단 성공.
- 모든 XML 문서 파싱 성공.

## 아직 확인하지 못한 것

- 실제 temi 장비의 Launcher / TTS / 감지 센서 / 지도 저장 상태.
- temi SDK 0.10.80 의존성 포함 **실제 Android Gradle 전체 빌드**. 현재 머신은 Gradle ZIP 다운로드 DNS 실패.
- 강의실의 계단, 장애물, 유리 벽, 학생 접근에 대한 안전한 실제 이동.

이 코드는 실기기 시연을 위한 **시험용 프로젝트**입니다. 테스트를 통과하지 않은 상태에서 미취학 아동과 함께 자동 주행시키지 마세요.
