package com.damum.daycaretemi.safety;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArraySet;

/** 앱 내부 응급 이벤트 버스(싱글턴). 어느 스레드에서 post해도 구독자는 메인 스레드에서 받는다. */
public final class SafetyEventBus {

    public interface Listener {
        void onSafetyEvent(SafetyEvent event);
    }

    private static final String TAG = "SafetyEvent";
    private static final SafetyEventBus INSTANCE = new SafetyEventBus();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();

    private SafetyEventBus() {}

    public static SafetyEventBus get() {
        return INSTANCE;
    }

    public void post(SafetyEvent event) {
        Log.i(TAG, event.toString());
        main.post(() -> {
            for (Listener l : listeners) l.onSafetyEvent(event);
        });
    }

    public void subscribe(Listener l) {
        listeners.add(l);
    }

    public void unsubscribe(Listener l) {
        listeners.remove(l);
    }
}
