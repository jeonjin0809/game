package com.damum.daycaretemi.robot;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/** 두 구현의 공통 부분: 리스너 관리, 메인 스레드 전달, 연결 상태 보관. SDK 의존 없음. */
public abstract class BaseRobotController implements RobotController {

    protected final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile boolean ready;

    private interface Event {
        void send(Listener l);
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void addListener(Listener listener) {
        if (!listeners.add(listener)) return;
        main.post(() -> {
            if (listeners.contains(listener)) listener.onReadyChanged(ready);
            if (listeners.contains(listener)) listener.onDetectionConfigurationChanged(isDetectionConfigured(), detectionStatus());
        });
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    protected static int clampTilt(int degrees) {
        return Math.max(TILT_MIN, Math.min(TILT_MAX, degrees));
    }

    private void dispatch(Event e) {
        main.post(() -> {
            for (Listener l : listeners) e.send(l);
        });
    }

    protected void notifyDetectionConfiguration(boolean ready, String detail) {
        dispatch(l -> l.onDetectionConfigurationChanged(ready, detail));
    }

    protected void notifyReady(boolean r) {
        ready = r;
        dispatch(l -> l.onReadyChanged(r));
    }

    protected void notifyGoTo(String location, MoveStatus status, String detail) {
        dispatch(l -> l.onGoToStatus(location, status, detail));
    }

    protected void notifyTurn(MoveStatus status) {
        dispatch(l -> l.onTurnStatus(status));
    }

    protected void notifySpeech(String text, SpeechStatus status) {
        dispatch(l -> l.onSpeechStatus(text, status));
    }

    protected void notifyLocations(List<String> locations) {
        dispatch(l -> l.onLocationsUpdated(locations));
    }

    protected void notifyPerson(boolean detected) {
        dispatch(l -> l.onPersonDetected(detected));
    }
}
