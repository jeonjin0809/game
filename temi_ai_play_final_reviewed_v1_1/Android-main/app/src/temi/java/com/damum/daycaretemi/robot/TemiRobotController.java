package com.damum.daycaretemi.robot;

import android.util.Log;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnDetectionStateChangedListener;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.listeners.OnLocationsUpdatedListener;
import com.robotemi.sdk.listeners.OnMovementStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;
import com.robotemi.sdk.permission.OnRequestPermissionResultListener;
import com.robotemi.sdk.permission.Permission;
import com.robotemi.sdk.navigation.model.SpeedLevel;
import com.robotemi.sdk.navigation.model.SafetyLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** temi SDK 0.10.80. 동작 확인이 필요한 권한·실기기 상태를 UI에 별도로 알림. */
public final class TemiRobotController extends BaseRobotController implements
        OnRobotReadyListener, OnGoToLocationStatusChangedListener,
        OnMovementStatusChangedListener, OnLocationsUpdatedListener,
        OnDetectionStateChangedListener, OnRequestPermissionResultListener,
        Robot.TtsListener {

    private static final String TAG = "TemiRobot";
    private static final float DETECTION_METERS = 1.5f;
    private static final int REQUEST_DETECTION = 9401;
    private final Robot robot = Robot.getInstance();
    private volatile boolean detectionConfigured;
    private volatile String detectionDetail = "temi 연결 후 감지 권한을 설정해 주세요.";

    @Override public void start() {
        robot.addOnRobotReadyListener(this);
        robot.addOnGoToLocationStatusChangedListener(this);
        robot.addOnMovementStatusChangedListener(this);
        robot.addOnLocationsUpdatedListener(this);
        robot.addOnDetectionStateChangedListener(this);
        robot.addOnRequestPermissionResultListener(this);
        robot.addTtsListener(this);
        if (robot.isReady()) onRobotReady(true);
    }

    @Override public boolean isDetectionConfigured() { return detectionConfigured; }
    @Override public String detectionStatus() { return detectionDetail; }

    @Override public void requestDetectionPermission() {
        if (!robot.isReady()) {
            updateDetection(false, "temi 연결을 먼저 확인해 주세요.");
            return;
        }
        try {
            if (robot.checkSelfPermission(Permission.SETTINGS) == Permission.GRANTED) {
                enableDetection();
            } else {
                updateDetection(false, "temi 화면에 나타나는 SETTINGS 권한 요청을 허용해 주세요.");
                robot.requestPermissions(Collections.singletonList(Permission.SETTINGS), REQUEST_DETECTION);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "감지 권한 요청 실패", e);
            updateDetection(false, "권한 요청에 실패했습니다. temi 설정의 앱 권한을 확인해 주세요.");
        }
    }

    private void enableDetection() {
        try {
            if (robot.checkSelfPermission(Permission.SETTINGS) != Permission.GRANTED) {
                updateDetection(false, "SETTINGS 권한이 아직 없습니다.");
                return;
            }
            // SDK에서는 이동하지 않을 때 Detection Mode가 활성화될 수 있음.
            robot.setDetectionModeOn(true, DETECTION_METERS);
            updateDetection(true, "감지 모드 요청 완료(1.5m). 실제 사람 감지 시험이 필요합니다.");
        } catch (RuntimeException e) {
            Log.e(TAG, "감지 모드 설정 실패", e);
            updateDetection(false, "감지 모드 설정 실패. Kiosk 앱 선택·로봇 Tracking User 설정을 확인해 주세요.");
        }
    }

    private void updateDetection(boolean configured, String detail) {
        detectionConfigured = configured;
        detectionDetail = detail;
        notifyDetectionConfiguration(configured, detail);
    }

    @Override public void onRequestPermissionResult(Permission permission, int grantResult, int requestCode) {
        if (permission != Permission.SETTINGS || requestCode != REQUEST_DETECTION) return;
        if (grantResult == Permission.GRANTED) enableDetection();
        else updateDetection(false, "감지 기능에 필요한 SETTINGS 권한이 거부되었습니다.");
    }

    @Override public void onRobotReady(boolean ready) {
        Log.i(TAG, "onRobotReady: " + ready);
        notifyReady(ready);
        if (!ready) updateDetection(false, "temi 연결이 끊어졌습니다.");
        else {
            try {
                if (robot.checkSelfPermission(Permission.SETTINGS) == Permission.GRANTED) enableDetection();
                else updateDetection(false, "감지 권한 설정 버튼을 눌러 SETTINGS 권한을 허용하세요.");
            } catch (RuntimeException e) {
                updateDetection(false, "감지 기능 점검 필요. 권한 설정 버튼을 눌러 주세요.");
            }
        }
    }

    @Override public void speak(String text) { robot.speak(TtsRequest.create(text, false)); }
    @Override public void cancelSpeech() { robot.cancelAllTtsRequests(); }
    @Override public void goTo(String location) {
        if (!detectionConfigured || robot.checkSelfPermission(Permission.SETTINGS) != Permission.GRANTED) {
            updateDetection(false, "이동 전 SETTINGS 권한이 유효하지 않습니다.");
            throw new IllegalStateException("사람 감지/속도 설정 권한이 필요합니다.");
        }
        // 감속 설정이 실패해도 빠른 속도로 움직이지 않도록 이동을 중단한다.
        robot.setGoToSpeed(SpeedLevel.SLOW);
        robot.setNavigationSafety(SafetyLevel.HIGH);
        robot.goTo(location);
    }
    @Override public void turnBy(int degrees) { robot.turnBy(degrees); }
    @Override public void tiltHead(int degrees) { robot.tiltAngle(clampTilt(degrees), 0.5f); }
    @Override public void stopMovement() { robot.stopMovement(); }
    @Override public List<String> getLocations() {
        List<String> list = robot.getLocations();
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    @Override public void onGoToLocationStatusChanged(String location, String status, int id, String description) {
        MoveStatus state = mapGoTo(status);
        if (state != null) notifyGoTo(location, state, description == null ? "" : description);
    }
    @Override public void onMovementStatusChanged(String type, String status) {
        if (!OnMovementStatusChangedListener.TYPE_TURN_BY.equals(type)) return;
        MoveStatus state = mapMovement(status);
        if (state != null) notifyTurn(state);
    }
    @Override public void onLocationsUpdated(List<String> locations) {
        notifyLocations(locations == null ? Collections.emptyList() : new ArrayList<>(locations));
    }
    @Override public void onDetectionStateChanged(int state) {
        if (state == OnDetectionStateChangedListener.DETECTED) notifyPerson(true);
        else if (state == OnDetectionStateChangedListener.LOST || state == OnDetectionStateChangedListener.IDLE) notifyPerson(false);
    }
    @Override public void onTtsStatusChanged(TtsRequest req) {
        SpeechStatus state;
        switch (req.getStatus()) {
            case STARTED: state = SpeechStatus.STARTED; break;
            case COMPLETED: state = SpeechStatus.COMPLETED; break;
            case CANCELED: state = SpeechStatus.CANCELED; break;
            case ERROR:
            case NOT_ALLOWED: state = SpeechStatus.ERROR; break;
            default: return;
        }
        notifySpeech(req.getSpeech(), state);
    }

    private static MoveStatus mapGoTo(String status) {
        if (status == null) return null;
        switch (status) {
            case OnGoToLocationStatusChangedListener.START: return MoveStatus.START;
            case OnGoToLocationStatusChangedListener.CALCULATING: return MoveStatus.CALCULATING;
            case OnGoToLocationStatusChangedListener.GOING: return MoveStatus.GOING;
            case OnGoToLocationStatusChangedListener.REPOSING: return MoveStatus.REPOSING;
            case OnGoToLocationStatusChangedListener.COMPLETE: return MoveStatus.COMPLETE;
            case OnGoToLocationStatusChangedListener.ABORT: return MoveStatus.ABORT;
            default: return null;
        }
    }
    private static MoveStatus mapMovement(String status) {
        if (status == null) return null;
        switch (status) {
            case OnMovementStatusChangedListener.STATUS_START: return MoveStatus.START;
            case OnMovementStatusChangedListener.STATUS_CALCULATING: return MoveStatus.CALCULATING;
            case OnMovementStatusChangedListener.STATUS_GOING: return MoveStatus.GOING;
            case OnMovementStatusChangedListener.STATUS_OBSTACLE_DETECTED: return MoveStatus.OBSTACLE;
            case OnMovementStatusChangedListener.STATUS_COMPLETE: return MoveStatus.COMPLETE;
            case OnMovementStatusChangedListener.STATUS_ABORT:
            case OnMovementStatusChangedListener.STATUS_NODE_INACTIVE: return MoveStatus.ABORT;
            default: return null;
        }
    }
}
