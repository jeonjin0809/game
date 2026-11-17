# 어린이집 보조 temi (팀 '담음')

한양대 ERICA 인공지능학과 캡스톤(5명). temi 로봇을 어린이집 보조 로봇으로 만든다.

## 기능과 담당
| # | 기능 | 담당 |
|---|---|---|
| 1 | 게임: 숨바꼭질, 영어 동작 게임 | 전진 |
| 2 | 부모 모니터링 + temi 원격 제어 | 허양은·박소민·박기용 |
| 3 | 식판 전달·배부 | 허양은·박소민·박기용 |
| 4 | 간식 배부 | 허양은·박소민·박기용 |
| 5 | 응급 상황 알림: 울음소리·비상 버튼 감지 → 이동 → 넘어진 자세 확인 → 알림음·빨간 화면·교사/원장 앱 알림 → 교사가 "테미, 괜찮아"로 해제 → 부모 앱에 확인 완료 알림 | 허양은·박소민·박기용 |
| 6 | 교사용 에이전틱 AI: 교사 음성 지시 → LLM이 계획 → temi 기능을 도구로 호출, 사고 기록·알림장 초안 작성 | 함승열 |

함승열 담당: **LLM 에이전트 서버**, **temi 카메라 → 노트북 MediaPipe 스트리밍 서버**(현재 `Temi_test_1`에 있음, 아래 참고).

## 설계 원칙 (반드시 지킬 것)
- **응급 대응(감지 → 알림)은 LLM을 거치지 않고 규칙으로 바로 실행한다.** LLM은 사고 기록 작성 같은 사후 작업만 한다.
- **부모에게 가는 메시지는 교사가 확인한 뒤에만 보낸다.**
- **아이 영상은 노트북에서만 처리하고 저장하지 않는다.** 영상·좌표 기록(`*.npz`)은 git에 올리지 않는다.

## 작업 규칙
- 아래 고정 버전·설정은 **팀(함승열) 허락 없이 올리거나 바꾸지 않는다.**
- 새 기능은 새 파일·새 모듈로 추가한다. 기존 동작을 바꿔야 하면 먼저 물어본다.
- 의존성 버전은 `gradle/libs.versions.toml`에만 적고 `app/build.gradle.kts`는 `libs.*`로 참조한다.
- **temi 기능은 항상 `RobotController`로만 쓴다.** `src/main`에서 `com.robotemi.sdk.*`(Robot 등)를 import하지 않는다.
  - temi SDK는 `temiImplementation`이라 main에서 쓰면 emulator 빌드가 컴파일 에러로 막는다.
- **새 temi 기능이 필요하면:** ① `RobotController`(인터페이스)에 메서드·이벤트 추가 → ② `TemiRobotController`(SDK 구현)와 ③ `FakeRobotController`(흉내 구현) **둘 다** 채운다 → ④ `assembleTemiDebug`와 `assembleEmulatorDebug` 둘 다 빌드되는지 확인한다.
- `RobotController`는 `DaycareApp`이 앱 전체에 하나만 만든다. Activity에서는 `DaycareApp.robot(this)`로 받아서 **onStart에서 `addListener`, onStop에서 `removeListener`만** 한다. `start()`는 Activity에서 부르지 않는다.
- 응급 감지 이벤트(울음·비상 버튼·넘어짐·해제 음성)는 `SafetyEventBus.get().post(SafetyEvent.now(...))`로 보낸다. 감지 출처와 무관하게 같은 경로로 들어오고, 응급 대응은 여기서 규칙으로 바로 실행한다(LLM 거치지 않음).

## 대상 기기와 고정 버전
temi 태블릿: **32비트 ARM(armeabi-v7a, RK3288), Android 6.0 (API 23)**

| 항목 | 값 | 이유 |
|---|---|---|
| MediaPipe `tasks-vision` | **0.10.21** | armeabi-v7a 네이티브 라이브러리를 포함하는 마지막 버전. 올리면 temi에서 크래시 |
| CameraX | **1.2.3** | 1.3+는 compileSdk 34 필요 |
| compileSdk / targetSdk | **33 / 33** | CameraX 1.2.x와 맞춤 |
| minSdk | **23** | temi |
| `desugar_jdk_libs` | 2.1.5 | MediaPipe가 쓰는 `java.util.Optional` 등 API 24 클래스를 API 23에서 사용 |
| temi SDK | 0.10.80 | `temiImplementation` (temi 빌드에만 포함) |
| OkHttp | 4.12.0 | 노트북 서버 WebSocket |
| appcompat / material / constraintlayout | 1.6.1 / 1.5.0 / 2.1.4 | compileSdk 33 호환 |
| AGP / Gradle | 9.4.1 / 9.6.0 | |
| Gradle 실행 JDK | 25 (`gradle/gradle-daemon-jvm.properties`, foojay 툴체인) | Android Studio JBR(25)로 빌드 |
| Java 소스/타깃 | 11 | |

MediaPipe·CameraX는 MainActivity에서 아직 쓰지 않지만, 온디바이스 Pose lite·카메라 옵션용으로 **의존성과 overrideLibrary를 유지**한다.

## 건드리면 안 되는 설정
- `app/build.gradle.kts`
  - `ndk { abiFilters += "armeabi-v7a" }` — temi는 32비트 ARM만
  - `androidResources { noCompress += "task" }` — `.task` 모델 압축 금지
  - `isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring(...)`
- `AndroidManifest.xml`
  - `<uses-sdk tools:overrideLibrary="com.google.mediapipe.tasks.vision,com.google.mediapipe.tasks.core" />` — MediaPipe AAR이 minSdk 24로 선언됨. temi 실기기에서 동작 확인함
  - `android:usesCleartextTraffic="true"` — 노트북 서버와 `ws://`(비암호화) 통신
  - `CAMERA`, `INTERNET` 권한
  - `<meta-data android:name="com.robotemi.sdk.metadata.SKILL" .../>` — temi 런처에 앱 표시
  - `android:name=".DaycareApp"` — RobotController 단일 인스턴스
- temi 빌드의 병합된 manifest에는 temi SDK가 넣는 `READ/WRITE_EXTERNAL_STORAGE` 권한과 `TemiSdkContentProvider`도 들어간다(정상).

## 폴더 구조 (현재)
```
Android/                         ← git 저장소 루트 (C:\Users\hamse\TemiProject\Android)
  app/                           temi 앱 (패키지 com.damum.daycaretemi, 앱 이름 "어린이집 temi")
    src/main/                    ← 두 빌드 공통. temi SDK import 금지
      java/com/damum/daycaretemi/
        DaycareApp.java            Application. RobotController 1개 생성·start() → DaycareApp.robot(ctx)
        MainActivity.java          연결 상태 표시, 연결되면 "안녕하세요", SafetyEvent 로그
        robot/RobotController.java     로봇 제어 인터페이스 (팀 공통)
        robot/BaseRobotController.java 리스너 관리·메인 스레드 전달 (두 구현 공통)
        safety/SafetyEvent.java        응급 이벤트 (type, source, timestamp, location?, confidence?)
        safety/SafetyEventBus.java     앱 내부 이벤트 버스 (메인 스레드 전달)
      keepRules/rules.keep       R8 keep 규칙 (AGP 9 방식, 기존 proguard-rules.pro 역할)
      res/layout/activity_main.xml   상태 텍스트 + dev_container(빌드별 영역)
    src/temi/                    ← temi 빌드에만
      java/.../FlavorModule.java       createRobot → TemiRobotController, attachDevTools → 없음
      java/.../robot/TemiRobotController.java   temi SDK 구현
    src/emulator/                ← emulator 빌드에만
      java/.../FlavorModule.java       createRobot → FakeRobotController, attachDevTools → DevPanel
      java/.../robot/FakeRobotController.java   흉내 구현 (Logcat FakeRobot + 화면 로그)
      java/.../dev/DevPanel.java       오른쪽 위 시뮬레이션 버튼 + 하단 로그
      java/.../dev/DevLog.java
      res/layout/dev_panel.xml, res/values/dev_styles.xml
  gradle/libs.versions.toml      모든 의존성 버전 (단일 출처)
  gradle/gradle-daemon-jvm.properties   Gradle JDK 25 툴체인
  CLAUDE.md
```
`FlavorModule`은 `src/temi`와 `src/emulator`에 **같은 이름·같은 메서드 시그니처**로 하나씩 있다. Gradle이 빌드에 맞는 쪽만 컴파일해서 구현이 정해진다.

## 빌드 종류 (productFlavors `device`)
| | **temi** (기본값) | **emulator** |
|---|---|---|
| 용도 | temi 실기기 | 에뮬레이터·폰에서 UI 개발 (temi 없이) |
| temi SDK | 포함 (`temiImplementation`) | **없음** |
| RobotController | `TemiRobotController` | `FakeRobotController` |
| 개발 패널 | 없음 | 있음 (응급 이벤트 4개, 사람 감지, 이동/회전/정지, 로그) |
| APK | `app/build/outputs/apk/temi/debug/app-temi-debug.apk` | `app/build/outputs/apk/emulator/debug/app-emulator-debug.apk` |

- emulator 빌드에 temi SDK를 넣지 않는 이유: SDK는 앱 시작 시 `TemiSdkContentProvider`로 temi 런처에 붙는데, 실패하면(`bindService=false`) `Process.killProcess`로 **앱을 강제 종료**한다 → temi가 아닌 기기에서는 흰 화면 재시작 반복.
- Android Studio: **Build Variants** 창에서 `temiDebug` / `emulatorDebug` 선택 후 Run.
- 에뮬레이터 AVD는 **32비트 ARM 번역을 지원하는 이미지**여야 설치됨 (예: API 30 Google APIs x86_64 — abilist에 `armeabi-v7a` 있음. API 37 이미지는 없음).

FakeRobotController 동작: 0.5초 뒤 연결됨 / goTo 2초 뒤 COMPLETE(없는 위치는 ABORT) / turnBy 1초 뒤 COMPLETE / speak 글자 수×150ms(최소 0.8s) 뒤 COMPLETED / 이동 중 stopMovement → ABORT / 위치 목록 `["home base", "블록 코너", "식탁", "교사 책상", "놀이방"]`.

## 빌드·설치
```bash
# JDK: Android Studio 내장 JBR 사용
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"     # Git Bash
# $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"     # PowerShell

./gradlew assembleTemiDebug       # temi 실기기용
./gradlew assembleEmulatorDebug   # 에뮬레이터용
./gradlew assembleDebug           # 둘 다
```
`local.properties`(sdk.dir)는 git에 없음 — Android Studio가 자동 생성하거나 직접 `sdk.dir=C\:\\Users\\<사용자>\\AppData\\Local\\Android\\Sdk` 작성.

temi에 설치 (adb는 와이파이 연결):
```bash
adb connect <temi IP>:5555
adb install -r app/build/outputs/apk/temi/debug/app-temi-debug.apk
adb shell am start -S -n com.damum.daycaretemi/.MainActivity
adb logcat -s DaycareTemi TemiRobot     # "onRobotReady: true", "onReadyChanged: true" 확인
```
에뮬레이터: `app-emulator-debug.apk`를 같은 방식으로 설치, `adb logcat -s DaycareTemi FakeRobot SafetyEvent`.

## temi SDK 0.10.80 확인 메모
- 사람 감지: `OnDetectionStateChangedListener` (IDLE=0, LOST=1, DETECTED=2) → `onPersonDetected(true/false)`. **`setDetectionModeOn(true)`를 켜야** 이벤트가 옴 (TemiRobotController가 연결 시 켬). 감지 거리 인자 버전 `setDetectionModeOn(boolean, float)`도 있음. temi 실기기에서 동작 미확인.
- 음성 인식(ASR): `addAsrListener(AsrListener.onAsrResult(String))`, `addWakeupWordListener(onWakeupWord(String, int))`, `addNlpListener`, `askQuestion(String)`, `wakeup()`, `finishConversation()`, `toggleWakeup(boolean)`, `addOnConversationStatusChangedListener`. → "테미, 괜찮아" 해제에 사용 예정 (RobotController 추가는 다음 단계).
- 기타 참고: `addOnUserInteractionChangedListener(boolean)`, `addOnDetectionDataChangedListener(DetectionData)`, `showAlertNotification`, `setKioskModeOn` 등.

### 모델 파일 (`*.task`, git 제외)
필요해지면 `app/src/main/assets/`에 넣는다. `C:\Users\hamse\Temi_test_1\app\src\main\assets\`에서 복사하거나 MediaPipe 공식 모델 페이지에서 받는다.
- `holistic_landmarker.task` (13.7MB), `pose_landmarker_lite.task` (5.8MB)

## 네트워크
- 노트북과 temi가 다른 서브넷이었음 (노트북 172.17.69.x / temi 172.17.71.x).
- 노트북 Windows 방화벽에서 TCP **8765** 인바운드 허용.

## 참고 환경: `C:\Users\hamse\Temi_test_1` (읽기 전용 — 수정 금지, git 아님)
지난 세션의 temi–노트북 연결·MediaPipe 테스트 프로젝트. 이 저장소의 빌드 설정은 여기서 가져왔다.
- `OnDeviceTestActivity`: 태블릿 온디바이스 추론 테스트(Holistic / Pose lite × CPU / GPU). 사람이 잡히면 Holistic ≈ 2 FPS → 노트북 처리로 결정
- `StreamingActivity`: 카메라 → JPEG → WebSocket → 노트북 → 좌표 오버레이. 기본 카메라는 **구형 Camera API1**(`LegacyCameraSource`, CameraX는 temi에서 ~15fps 한계), `--es cam camerax`로 CameraX
  - 실행: `am start -S -n com.example.temi_test_1/.StreamingActivity --es server ws://<노트북IP>:8765`
  - 기본 서버 주소가 `ws://172.17.69.67:8765`로 하드코딩됨
- `TiltController`: 머리 틸트(-25~55°), `am broadcast -a com.example.temi_test_1.TILT --ei angle 10`
- `server/stream_server.py`, `server/test_client.py`

### 스트리밍 서버 (`Temi_test_1/server/stream_server.py`)
- 환경: conda `mediapipe_env` (`C:\Users\hamse\anaconda3\envs\mediapipe_env`) — Python 3.10.19, mediapipe 0.10.21, numpy 1.26.4, opencv 4.11.0, websockets 15.0.1 (requirements 파일 없음)
- 실행: `python server/stream_server.py` (옵션 `--host 0.0.0.0` `--port 8765` `--model-complexity 0` `--save-dir server/recordings` `--save-on-exit`)
- 처리: legacy `mp.solutions.holistic`(Tasks API 아님). 최신 프레임 1장만 처리하고 밀린 프레임은 버림. 이미지는 저장하지 않음
- 프로토콜 (WebSocket, 최대 8MB, ping 10s):
  - `/stream` temi→서버 **바이너리**: 18바이트 big-endian 헤더 `seq u32 | captureTs i64 (temi uptime ms) | rotation u16 | width u16 | height u16` + JPEG (반전 안 한 원본)
  - `/stream` temi→서버 **텍스트**: `{"cmd":"save"}` → `{"type":"saved","path","frames"}` / `{"cmd":"reset"}` → `{"type":"reset"}`
  - `/stream` 서버→temi 결과:
    ```
    {"type":"result","seq","ts","w","h","proc_ms","infer_ms","lh","rh",
     "pose":[x0,y0,…33점],"face":[눈썹·눈·입 부분집합],"left":[21점],"right":[21점],
     "srv":{"recv","dropped","proc_fps"}}
    ```
    좌표는 정규화(0~1) xy 평탄 배열, 미검출 부위는 `null`. left/right는 사람 기준.
  - `/bench`: 대역폭 측정 (바이너리 수신 후 `"up_done"` → `up_result`, `"down"` → 10초 송신 후 `down_done`)
- ⚠ 좌표는 메모리에 누적되고 `save`/`--save-on-exit` 시 `server/recordings/*.npz`로 저장됨 — 실사용 시 저장 정책 결정 필요
- 로컬 점검: `python server/test_client.py --url ws://127.0.0.1:8765 --frames 60 --bench` (save도 보내서 npz 1개 생성됨)
- 측정값(로컬): RTT 평균 ~30ms, 처리 ~18ms

## 참고: 이전 LLM 서버
`C:\Users\hamse\TemiProject\Server` — SonEum 수어 번역 서버 (github.com/MobileRobot-SonEum/Server). FastAPI + 제공자 어댑터(`app/translators/providers.py`). 에이전트 서버 구조 재사용 후보.
