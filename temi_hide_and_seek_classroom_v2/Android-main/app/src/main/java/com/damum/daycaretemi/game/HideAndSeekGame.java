package com.damum.daycaretemi.game;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.damum.daycaretemi.robot.RobotController;
import com.damum.daycaretemi.safety.SafetyEvent;
import com.damum.daycaretemi.safety.SafetyEventBus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 어린이집 시연: 교사가 사전 점검한 2~3개의 저장 장소를 temi가 반복 방문하고
 * 도착해 멈춘 뒤 사람을 찾는다. 이동 중 실제 감지 콜백이 오면 즉시 정지 후 발견 처리.
 *
 * 중요: temi 기본 Detection Mode는 움직이지 않을 때 활성화되므로 이동 중 발견은
 * 보장하지 않는다. 신원 인식 없이 감지 이벤트 수를 세는 것으로 중복 카운트가 가능하다.
 * 발견된 참가자는 대기구역으로 이동하도록 진행자가 안내해야 한다.
 */
public final class HideAndSeekGame {
    private static final String TAG = "HideAndSeek";
    private static final long FIVE_MIN = 300_000L;
    private static final long TEN_MIN = 600_000L;
    private static final long MIN_HIDE = 20_000L;
    private static final long COUNT_SPEECH_FAILSAFE = 45_000L;
    private static final long MOVE_TIMEOUT = 75_000L;
    private static final long SCAN_DWELL = 14_000L;
    private static final long FOUND_LEAVE_TIME = 9_000L;
    private static final long PERSON_REARM = 3_000L;
    private static final long FOUND_COOLDOWN = 8_000L;
    private static final String COUNT_SPEECH = "친구들, 숨바꼭질 시작! "
            + "꼭꼭 숨어라, 머리카락 보일라! "
            + "하나, 둘, 셋, 넷, 다섯, 여섯, 일곱, 여덟, 아홉, 열!";

    public enum Phase { IDLE, COUNTING, MOVING, SEARCHING, FOUND_WAIT, PAUSED, FINISHED, STOPPED }

    public static final class Snapshot {
        public final Phase phase;
        public final int totalChildren;
        public final int foundChildren;
        public final int secondsLeft;
        public final String message;
        public final String movingTo;
        public final boolean robotReady;
        public final boolean detectionConfigured;
        public final String detectionStatus;
        public final boolean detectionTestSeen;
        public final List<String> route;
        public final int visits;

        Snapshot(Phase phase, int total, int found, int seconds, String message, String movingTo,
                 boolean robotReady, boolean detectionConfigured, String detectionStatus,
                 boolean detectionTestSeen, List<String> route, int visits) {
            this.phase = phase;
            this.totalChildren = total;
            this.foundChildren = found;
            this.secondsLeft = seconds;
            this.message = message;
            this.movingTo = movingTo;
            this.robotReady = robotReady;
            this.detectionConfigured = detectionConfigured;
            this.detectionStatus = detectionStatus;
            this.detectionTestSeen = detectionTestSeen;
            this.route = Collections.unmodifiableList(new ArrayList<>(route));
            this.visits = visits;
        }
        public boolean isPlaying() {
            return phase == Phase.COUNTING || phase == Phase.MOVING || phase == Phase.SEARCHING
                    || phase == Phase.FOUND_WAIT || phase == Phase.PAUSED;
        }
    }

    public interface Listener {
        void onGameUpdated(Snapshot snapshot);
        void onLocationsUpdated(List<String> locations);
    }

    private static final class Speech {
        final String text;
        final Runnable finished;
        Speech(String text, Runnable finished) { this.text = text; this.finished = finished; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RobotController robot;
    private final Listener ui;
    private final ArrayDeque<Speech> speechQueue = new ArrayDeque<>();
    private Speech speaking;
    private Runnable speechWatchdog;
    private Phase phase = Phase.IDLE;
    private int totalChildren;
    private int foundChildren;
    private int stoppedSecondsLeft = 600;
    private String message = "사람 감지 시험과 안전한 순찰 장소 2~3곳을 준비하세요.";
    private String movingTo;
    private boolean attached;
    private boolean detectionTestSeen;
    private boolean personVisible;
    private boolean personLatched;
    private long lastFoundAt = -1;
    private long startedAt;
    private long deadline;
    private boolean cuckooSaid;
    private final List<String> route = new ArrayList<>();
    private int nextIndex;
    private int visits;

    private final Runnable atFive = () -> {
        if (!isPlaying() || cuckooSaid || foundChildren >= totalChildren) return;
        cuckooSaid = true;
        message = "5분이 지났어요. 못 찾겠다 꾀꼬리! 계속 찾아볼게요.";
        enqueueFirst("못 찾겠다 꾀꼬리!", null);
        publish();
    };
    private final Runnable atTen = () -> { if (isPlaying()) finish(false); };
    private final Runnable countTimeout = this::beginSearching;
    private final Runnable moveTimeout = () -> {
        if (phase == Phase.MOVING) pauseMovement("이동 시간이 초과됐습니다. 경로를 확인하고 재개하세요.");
    };
    private final Runnable nextStop = () -> { if (phase == Phase.SEARCHING) moveNext(); };
    private final Runnable afterFound = () -> { if (phase == Phase.FOUND_WAIT) moveNext(); };
    private final Runnable rearmPerson = () -> { if (!personVisible) personLatched = false; };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!isPlaying()) return;
            if (SystemClock.elapsedRealtime() >= deadline) { finish(false); return; }
            publish();
            main.postDelayed(this, 1000);
        }
    };

    private final RobotController.Listener robotListener = new RobotController.Listener() {
        @Override public void onReadyChanged(boolean ready) {
            if (!ready) {
                detectionTestSeen = false;
                if (isPlaying()) { stop("temi 연결이 끊어져 게임을 중단했습니다."); return; }
            }
            publish();
            if (ready) publishLocations();
        }
        @Override public void onDetectionConfigurationChanged(boolean configured, String detail) {
            if (!configured && isPlaying()) {
                stop("사람 감지 권한을 확인할 수 없어 게임을 중단했습니다.");
                return;
            }
            publish();
        }
        @Override public void onLocationsUpdated(List<String> names) {
            ui.onLocationsUpdated(new ArrayList<>(names));
        }
        @Override public void onSpeechStatus(String text, RobotController.SpeechStatus status) {
            if (speaking == null || !speaking.text.equals(text)) return;
            if (status == RobotController.SpeechStatus.COMPLETED
                    || status == RobotController.SpeechStatus.ERROR
                    || status == RobotController.SpeechStatus.CANCELED) {
                finishSpeaking();
            }
        }
        @Override public void onGoToStatus(String location, RobotController.MoveStatus status, String detail) {
            if (phase != Phase.MOVING || movingTo == null || !movingTo.equals(location)) return;
            if (status == RobotController.MoveStatus.COMPLETE) {
                main.removeCallbacks(moveTimeout);
                movingTo = null;
                visits++;
                phase = Phase.SEARCHING;
                message = "도착했어요! 주변을 14초 동안 찾아볼게요.";
                publish();
                enqueue("어디 숨었을까? 이쪽에 있나?", () -> {
                    if (phase == Phase.SEARCHING) main.postDelayed(nextStop, SCAN_DWELL);
                });
            } else if (status == RobotController.MoveStatus.ABORT) {
                pauseMovement("이동이 중단됐습니다. 장애물과 경로를 확인한 뒤 재개하세요.");
            } else if (status == RobotController.MoveStatus.OBSTACLE) {
                message = "장애물 감지! 길이 비워질 때까지 기다려 주세요.";
                publish();
            }
        }
        @Override public void onPersonDetected(boolean detected) {
            personVisible = detected;
            if (detected && !isPlaying()) {
                detectionTestSeen = true;
                message = "사람 감지 테스트 성공! 참가자는 발견 후 대기구역으로 이동하세요.";
                publish();
            }
            if (!detected) {
                main.removeCallbacks(rearmPerson);
                main.postDelayed(rearmPerson, PERSON_REARM);
                return;
            }
            main.removeCallbacks(rearmPerson);
            if (phase == Phase.COUNTING) {
                personLatched = true; // 숨는 동안 보인 사람은 발견으로 세지 않음
                return;
            }
            if (phase != Phase.SEARCHING && phase != Phase.MOVING) return;
            if (personLatched) return;
            personLatched = true;
            long now = SystemClock.elapsedRealtime();
            if (now >= deadline) { finish(false); return; }
            if (lastFoundAt >= 0 && now - lastFoundAt < FOUND_COOLDOWN) return;
            lastFoundAt = now;
            foundChildren++;
            if (foundChildren >= totalChildren) {
                finish(true);
                return;
            }
            main.removeCallbacks(nextStop);
            main.removeCallbacks(moveTimeout);
            phase = Phase.FOUND_WAIT;
            movingTo = null;
            safeStop();
            clearSpeech();
            message = "찾았다! " + foundChildren + "/" + totalChildren
                    + "명. 발견된 친구는 대기구역으로 이동하세요.";
            publish();
            enqueue("찾았다! 찾은 친구는 대기 구역으로 이동해 주세요.", () -> {
                if (phase == Phase.FOUND_WAIT) main.postDelayed(afterFound, FOUND_LEAVE_TIME);
            });
        }
    };

    private final SafetyEventBus.Listener safetyListener = event -> {
        if (isPlaying() && event.type != SafetyEvent.Type.DISMISS_VOICE) {
            stop("안전 이벤트가 발생해 게임을 중단했습니다. 선생님이 확인해 주세요.");
        }
    };

    public HideAndSeekGame(RobotController robot, Listener ui) {
        this.robot = robot;
        this.ui = ui;
    }

    public void attach() {
        if (attached) return;
        attached = true;
        robot.addListener(robotListener);
        SafetyEventBus.get().subscribe(safetyListener);
        publish();
        if (robot.isReady()) publishLocations();
    }

    public void detach() {
        if (!attached) return;
        if (isPlaying()) stop("앱 화면을 벗어나 게임이 중단됐습니다.");
        clearTimers();
        clearSpeech();
        SafetyEventBus.get().unsubscribe(safetyListener);
        robot.removeListener(robotListener);
        attached = false;
    }

    public void requestDetectionPermission() { robot.requestDetectionPermission(); }

    public void start(int children, List<String> selectedLocations) {
        if (isPlaying()) return;
        if (!robot.isReady()) { message = "temi가 연결되지 않았습니다."; publish(); return; }
        if (!robot.isDetectionConfigured()) {
            message = "사람 감지 준비가 필요합니다. 감지 권한 설정 버튼을 눌러 주세요.";
            publish(); return;
        }
        if (children < 1 || children > 10) {
            message = "참가 인원을 1~10명으로 선택하세요."; publish(); return;
        }
        if (selectedLocations == null || selectedLocations.size() < 2 || selectedLocations.size() > 3) {
            message = "교사가 점검한 이동 장소를 2~3곳 선택하세요."; publish(); return;
        }
        List<String> saved = safeGetLocations();
        Set<String> unique = new HashSet<>();
        for (String location : selectedLocations) {
            if (location == null || location.trim().isEmpty() || !saved.contains(location)
                    || isUnsafeLocationName(location) || !unique.add(location)) {
                message = "안전하지 않거나 temi에 없는 장소가 포함돼 있습니다."; publish(); return;
            }
        }
        clearTimers();
        clearSpeech();
        route.clear();
        route.addAll(selectedLocations);
        nextIndex = 0;
        visits = 0;
        foundChildren = 0;
        totalChildren = children;
        lastFoundAt = -1;
        personLatched = personVisible;  // 시작 시 이미 보이는 교사는 발견으로 세지 않음
        cuckooSaid = false;
        movingTo = null;
        startedAt = SystemClock.elapsedRealtime();
        deadline = startedAt + TEN_MIN;
        stoppedSecondsLeft = 600;
        phase = Phase.COUNTING;
        message = "숨을 시간이에요! 테미가 1부터 10까지 셉니다.";
        publish();
        main.postDelayed(atFive, FIVE_MIN);
        main.postDelayed(atTen, TEN_MIN);
        main.post(ticker);
        enqueue(COUNT_SPEECH, () -> {
            if (phase != Phase.COUNTING) return;
            long wait = Math.max(0L, MIN_HIDE - (SystemClock.elapsedRealtime() - startedAt));
            main.removeCallbacks(countTimeout);
            main.postDelayed(countTimeout, wait);
        });
        main.postDelayed(countTimeout, COUNT_SPEECH_FAILSAFE);
    }

    private void beginSearching() {
        if (phase != Phase.COUNTING) return;
        main.removeCallbacks(countTimeout);
        clearSpeech(); // TTS 완료 콜백이 오지 않는 기기에서도 다음 안내가 겹치지 않게 중단
        if (SystemClock.elapsedRealtime() >= deadline) { finish(false); return; }
        phase = Phase.SEARCHING;
        message = "찾기 시작! 테미가 지정한 장소로 이동합니다.";
        publish();
        enqueue("다 숨었나요? 이제 찾으러 갈게요!", () -> {
            if (phase == Phase.SEARCHING) moveNext();
        });
    }

    private void moveNext() {
        if (!isPlaying() || phase == Phase.COUNTING || phase == Phase.FINISHED) return;
        main.removeCallbacks(nextStop);
        main.removeCallbacks(afterFound);
        if (!robot.isReady() || !robot.isDetectionConfigured()) {
            stop("로봇 연결 또는 감지 권한이 해제되어 게임을 종료했습니다.");
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) { finish(false); return; }
        if (route.isEmpty()) { pauseMovement("이동 경로가 없습니다."); return; }
        String target = route.get(nextIndex);
        nextIndex = (nextIndex + 1) % route.size();
        if (!safeGetLocations().contains(target)) {
            pauseMovement("저장된 장소 '" + target + "'을 찾을 수 없습니다.");
            return;
        }
        phase = Phase.MOVING;
        movingTo = target;
        message = "'" + target + "'(으)로 천천히 이동 중. 이동 경로를 비워 주세요.";
        publish();
        main.removeCallbacks(moveTimeout);
        main.postDelayed(moveTimeout, MOVE_TIMEOUT);
        try {
            robot.goTo(target);
        } catch (RuntimeException e) {
            Log.e(TAG, "이동 명령 실패", e);
            pauseMovement("이동 명령에 실패했습니다. 경로/속도 설정을 확인하세요.");
        }
    }

    private void pauseMovement(String reason) {
        if (!isPlaying()) return;
        main.removeCallbacks(moveTimeout);
        main.removeCallbacks(nextStop);
        main.removeCallbacks(afterFound);
        clearSpeech();
        safeStop();
        if (phase == Phase.MOVING && !route.isEmpty()) {
            // 수동 재개 시 실패 지점부터 한 번 더 시도한다.
            nextIndex = (nextIndex - 1 + route.size()) % route.size();
        }
        phase = Phase.PAUSED;
        movingTo = null;
        message = reason + " 게임 타이머는 계속 진행됩니다.";
        publish();
    }

    public void resumeByTeacher() {
        if (phase != Phase.PAUSED) return;
        message = "교사가 경로를 확인했습니다. 탐색을 재개합니다.";
        phase = Phase.SEARCHING;
        publish();
        enqueue("이제 다시 찾아볼게요!", () -> {
            if (phase == Phase.SEARCHING) moveNext();
        });
    }

    public void stopByTeacher() {
        if (isPlaying()) stop("선생님이 게임을 중단했습니다.");
        else { safeStop(); clearSpeech(); }
    }

    private void finish(boolean allFound) {
        if (!isPlaying()) return;
        stoppedSecondsLeft = allFound ? remainingSeconds() : 0;
        clearTimers();
        clearSpeech();
        safeStop();
        phase = Phase.FINISHED;
        movingTo = null;
        if (allFound) {
            message = "모두 찾았어요! " + foundChildren + "/" + totalChildren + "명";
            enqueue("찾았다! 친구들을 모두 찾았어요! 숨바꼭질 끝!", null);
        } else {
            message = "10분 종료. 찾은 친구 " + foundChildren + "/" + totalChildren + "명";
            enqueue("시간 끝! 모두 나와 주세요. 숨바꼭질 끝!", null);
        }
        publish();
    }

    private void stop(String reason) {
        if (!isPlaying()) return;
        stoppedSecondsLeft = remainingSeconds();
        clearTimers();
        clearSpeech();
        safeStop();
        phase = Phase.STOPPED;
        movingTo = null;
        message = reason;
        publish();
    }

    private void enqueue(String text, Runnable finished) {
        speechQueue.addLast(new Speech(text, finished));
        pumpSpeech();
    }
    private void enqueueFirst(String text, Runnable finished) {
        speechQueue.addFirst(new Speech(text, finished));
        pumpSpeech();
    }
    private void pumpSpeech() {
        if (speaking != null || speechQueue.isEmpty()) return;
        speaking = speechQueue.pollFirst();
        final Speech active = speaking;
        speechWatchdog = () -> {
            if (speaking != active) return;
            // TTS 콜백이 누락되면 현재 음성을 취소하고 다음 단계로 진행.
            try { robot.cancelSpeech(); } catch (RuntimeException ignored) {}
            finishSpeaking();
        };
        main.postDelayed(speechWatchdog, Math.min(45_000L, 6_000L + active.text.length() * 280L));
        try { robot.speak(active.text); }
        catch (RuntimeException e) {
            Log.e(TAG, "TTS 실패", e);
            finishSpeaking();
        }
    }
    private void finishSpeaking() {
        Speech ended = speaking;
        if (ended == null) return;
        if (speechWatchdog != null) main.removeCallbacks(speechWatchdog);
        speaking = null;
        speechWatchdog = null;
        if (ended.finished != null) ended.finished.run();
        pumpSpeech();
    }
    private void clearSpeech() {
        speechQueue.clear();
        speaking = null;
        if (speechWatchdog != null) main.removeCallbacks(speechWatchdog);
        speechWatchdog = null;
        try { robot.cancelSpeech(); }
        catch (RuntimeException e) { Log.w(TAG, "TTS 취소 실패", e); }
    }

    private void clearTimers() {
        main.removeCallbacks(atFive);
        main.removeCallbacks(atTen);
        main.removeCallbacks(countTimeout);
        main.removeCallbacks(moveTimeout);
        main.removeCallbacks(nextStop);
        main.removeCallbacks(afterFound);
        main.removeCallbacks(rearmPerson);
        main.removeCallbacks(ticker);
    }
    private void safeStop() {
        try { robot.stopMovement(); }
        catch (RuntimeException e) { Log.e(TAG, "이동 중지 요청 실패", e); }
    }
    private void publishLocations() { ui.onLocationsUpdated(safeGetLocations()); }
    private List<String> safeGetLocations() {
        try {
            List<String> locations = robot.getLocations();
            return locations == null ? Collections.emptyList() : new ArrayList<>(locations);
        } catch (RuntimeException e) {
            Log.e(TAG, "저장 장소 조회 실패", e);
            return Collections.emptyList();
        }
    }
    private int remainingSeconds() {
        if (phase == Phase.IDLE) return 600;
        if (phase == Phase.FINISHED || phase == Phase.STOPPED) return stoppedSecondsLeft;
        return Math.max(0, (int) ((deadline - SystemClock.elapsedRealtime() + 999) / 1000));
    }
    private boolean isPlaying() {
        return phase == Phase.COUNTING || phase == Phase.MOVING || phase == Phase.SEARCHING
                || phase == Phase.FOUND_WAIT || phase == Phase.PAUSED;
    }
    private void publish() {
        ui.onGameUpdated(new Snapshot(phase, totalChildren, foundChildren, remainingSeconds(),
                message, movingTo, robot.isReady(), robot.isDetectionConfigured(),
                robot.detectionStatus(), detectionTestSeen, route, visits));
    }
    public static boolean isUnsafeLocationName(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase(Locale.ROOT).trim();
        return lower.contains("home base") || lower.contains("homebase")
                || lower.contains("charging") || lower.contains("charger")
                || lower.contains("충전") || lower.contains("도킹");
    }
}
