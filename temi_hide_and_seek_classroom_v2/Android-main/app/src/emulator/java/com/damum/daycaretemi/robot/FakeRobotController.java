package com.damum.daycaretemi.robot;

import com.damum.daycaretemi.dev.DevLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * emulator 빌드용 가짜 로봇. 호출 내용을 Logcat(FakeRobot)과 화면 하단 로그에 남기고 이벤트를 흉내 낸다.
 * <ul>
 *   <li>start 0.5초 뒤 연결됨</li>
 *   <li>goTo: START → GOING → 2초 뒤 COMPLETE(도착). 없는 위치면 ABORT</li>
 *   <li>turnBy: START → 1초 뒤 COMPLETE</li>
 *   <li>speak: STARTED → 글자 수에 비례한 시간 뒤 COMPLETED</li>
 *   <li>사람 감지는 개발 패널에서 {@link #simulatePersonDetected}로 발생</li>
 * </ul>
 */
public final class FakeRobotController extends BaseRobotController {

    private static final String TAG = "FakeRobot";
    private static final long READY_DELAY_MS = 500;
    private static final long GOTO_DURATION_MS = 2000;
    private static final long TURN_DURATION_MS = 1000;

    /** 응급 시나리오 기준 가짜 저장 위치 */
    private static final List<String> LOCATIONS = Collections.unmodifiableList(
            Arrays.asList("home base", "블록 코너", "식탁", "교사 책상", "놀이방"));

    private Runnable pendingMove;      // 메인 스레드에서만 접근
    private String movingTo;           // null이면 회전 중(또는 정지)
    private long speechEndsAt;
    private boolean detectionConfigured = true;
    private final List<Runnable> pendingSpeeches = new ArrayList<>();

    private static void log(String msg) {
        DevLog.add(TAG, msg);
    }

    @Override
    public void start() {
        log("start() → " + READY_DELAY_MS + "ms 뒤 연결됨");
        main.postDelayed(() -> {
            log("연결됨 (가짜)");
            notifyReady(true);
            notifyLocations(new ArrayList<>(LOCATIONS));
        }, READY_DELAY_MS);
    }

    @Override public boolean isDetectionConfigured() { return detectionConfigured; }
    @Override public String detectionStatus() { return "가상 감지 준비됨. 개발 패널에서 사람 감지를 시험하세요."; }
    @Override public void requestDetectionPermission() {
        detectionConfigured = true;
        notifyDetectionConfiguration(true, detectionStatus());
    }
    @Override public void cancelSpeech() {
        for (Runnable callback : pendingSpeeches) main.removeCallbacks(callback);
        pendingSpeeches.clear();
        speechEndsAt = android.os.SystemClock.uptimeMillis();
        log("cancelSpeech()");
    }

    @Override
    public void speak(String text) {
        log("speak(\"" + text + "\")");
        // 실제 TTS처럼 순서대로: 앞 문장이 끝난 뒤 시작
        long now = android.os.SystemClock.uptimeMillis();
        long startAt = Math.max(now, speechEndsAt);
        long duration = Math.max(800, text.length() * 150L);
        speechEndsAt = startAt + duration;
        Runnable onStart = new Runnable() {
            @Override public void run() {
                pendingSpeeches.remove(this);
                notifySpeech(text, SpeechStatus.STARTED);
            }
        };
        Runnable onComplete = new Runnable() {
            @Override public void run() {
                pendingSpeeches.remove(this);
                log("말하기 끝: \"" + text + "\"");
                notifySpeech(text, SpeechStatus.COMPLETED);
            }
        };
        pendingSpeeches.add(onStart);
        pendingSpeeches.add(onComplete);
        main.postAtTime(onStart, startAt);
        main.postAtTime(onComplete, speechEndsAt);
    }

    @Override
    public void goTo(String location) {
        main.post(() -> {
            log("goTo(\"" + location + "\")");
            cancelPendingMove("새 이동 명령");
            if (!LOCATIONS.contains(location)) {
                log("없는 위치: " + location + " → ABORT");
                notifyGoTo(location, MoveStatus.ABORT, "unknown location");
                return;
            }
            movingTo = location;
            notifyGoTo(location, MoveStatus.START, "");
            notifyGoTo(location, MoveStatus.GOING, "");
            pendingMove = () -> {
                pendingMove = null;
                movingTo = null;
                log("도착: " + location);
                notifyGoTo(location, MoveStatus.COMPLETE, "");
            };
            main.postDelayed(pendingMove, GOTO_DURATION_MS);
        });
    }

    @Override
    public void turnBy(int degrees) {
        main.post(() -> {
            log("turnBy(" + degrees + ")");
            cancelPendingMove("새 회전 명령");
            notifyTurn(MoveStatus.START);
            pendingMove = () -> {
                pendingMove = null;
                log("회전 완료: " + degrees + "°");
                notifyTurn(MoveStatus.COMPLETE);
            };
            main.postDelayed(pendingMove, TURN_DURATION_MS);
        });
    }

    @Override
    public void tiltHead(int degrees) {
        int a = clampTilt(degrees);
        log("tiltHead(" + degrees + ")" + (a != degrees ? " → " + a + "°로 제한" : ""));
    }

    @Override
    public void stopMovement() {
        main.post(() -> {
            log("stopMovement()");
            cancelPendingMove("정지");
        });
    }

    @Override
    public List<String> getLocations() {
        log("getLocations() → " + LOCATIONS);
        return new ArrayList<>(LOCATIONS);
    }

    /** 개발 패널용: 사람 감지 이벤트 발생 */
    public void simulatePersonDetected(boolean detected) {
        log("사람 감지 " + (detected ? "DETECTED" : "LOST") + " (시뮬레이션)");
        notifyPerson(detected);
    }

    /** 진행 중인 이동·회전이 있으면 ABORT로 끝낸다. 메인 스레드에서 호출. */
    private void cancelPendingMove(String why) {
        if (pendingMove == null) return;
        main.removeCallbacks(pendingMove);
        pendingMove = null;
        log("진행 중 동작 취소(" + why + ") → ABORT");
        if (movingTo != null) notifyGoTo(movingTo, MoveStatus.ABORT, why);
        else notifyTurn(MoveStatus.ABORT);
        movingTo = null;
    }
}
