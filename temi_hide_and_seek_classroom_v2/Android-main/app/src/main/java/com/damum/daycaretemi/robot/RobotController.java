package com.damum.daycaretemi.robot;

import java.util.List;

/** temi SDK에 직접 의존하지 않는, 실기기/에뮬레이터 공통 로봇 제어 계약. */
public interface RobotController {
    int TILT_MIN = -25;
    int TILT_MAX = 55;

    void start();
    boolean isReady();
    void speak(String text);
    void cancelSpeech();
    void goTo(String location);
    void turnBy(int degrees);
    void tiltHead(int degrees);
    void stopMovement();
    List<String> getLocations();

    /** SDK Settings 권한 요청. 반드시 앱 화면의 교사 버튼에서 호출. */
    void requestDetectionPermission();
    /** 설정 권한을 얻고 감지 모드를 켜도록 요청했는지 여부(실제 사람 감지 성공을 보장하지 않음). */
    boolean isDetectionConfigured();
    String detectionStatus();

    void addListener(Listener listener);
    void removeListener(Listener listener);

    interface Listener {
        default void onReadyChanged(boolean ready) {}
        default void onDetectionConfigurationChanged(boolean configured, String detail) {}
        default void onGoToStatus(String location, MoveStatus status, String detail) {}
        default void onTurnStatus(MoveStatus status) {}
        default void onSpeechStatus(String text, SpeechStatus status) {}
        default void onLocationsUpdated(List<String> locations) {}
        default void onPersonDetected(boolean detected) {}
    }
    enum MoveStatus { START, CALCULATING, GOING, REPOSING, OBSTACLE, COMPLETE, ABORT }
    enum SpeechStatus { STARTED, COMPLETED, ERROR, CANCELED }
}
