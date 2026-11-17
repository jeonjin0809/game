package com.damum.daycaretemi.dev;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

/** emulator 빌드 전용 로그. Logcat에 찍고, 화면 하단 로그 영역(DevPanel)에도 보여준다. */
public final class DevLog {

    public interface Listener {
        void onLine(String line);
    }

    private static final int MAX_LINES = 200;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();   // 메인 스레드에서만 접근
    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private DevLog() {}

    public static void add(String tag, String msg) {
        Log.i(tag, msg);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date()) + " [" + tag + "] " + msg;
        MAIN.post(() -> {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) LINES.pollFirst();
            for (Listener l : LISTENERS) l.onLine(line);
        });
    }

    /** 메인 스레드에서 호출. 지금까지의 로그를 먼저 전달하고 이후 줄을 계속 전달한다. */
    static void observe(Listener l) {
        for (String line : LINES) l.onLine(line);
        LISTENERS.add(l);
    }

    static void unobserve(Listener l) {
        LISTENERS.remove(l);
    }
}
