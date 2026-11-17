package com.damum.daycaretemi.dev;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.damum.daycaretemi.DaycareApp;
import com.damum.daycaretemi.R;
import com.damum.daycaretemi.robot.FakeRobotController;
import com.damum.daycaretemi.robot.RobotController;
import com.damum.daycaretemi.safety.SafetyEvent;
import com.damum.daycaretemi.safety.SafetyEvent.Source;
import com.damum.daycaretemi.safety.SafetyEvent.Type;
import com.damum.daycaretemi.safety.SafetyEventBus;

import java.util.List;

/**
 * emulator 빌드 전용 개발 패널: 오른쪽 위 버튼(응급 이벤트·로봇 동작 시뮬레이션) + 하단 로그 영역.
 * Activity의 onStart/onStop에 맞춰 로그·이벤트 구독을 붙였다 뗀다.
 */
public final class DevPanel implements DefaultLifecycleObserver {

    private static final String TAG = "DevPanel";
    private static final int MAX_LOG_CHARS = 20_000;

    private final RobotController robot;
    private final TextView logText;
    private final ScrollView logScroll;
    private boolean personDetected;

    private final DevLog.Listener logListener = this::appendLine;

    private final SafetyEventBus.Listener safetyListener = e -> DevLog.add("Bus", "수신 " + e);

    private final RobotController.Listener robotListener = new RobotController.Listener() {
        @Override
        public void onReadyChanged(boolean ready) {
            DevLog.add("Robot", "onReadyChanged(" + ready + ")");
        }

        @Override
        public void onGoToStatus(String location, RobotController.MoveStatus status, String detail) {
            DevLog.add("Robot", "onGoToStatus(" + location + ", " + status + (detail.isEmpty() ? "" : ", " + detail) + ")");
        }

        @Override
        public void onTurnStatus(RobotController.MoveStatus status) {
            DevLog.add("Robot", "onTurnStatus(" + status + ")");
        }

        @Override
        public void onSpeechStatus(String text, RobotController.SpeechStatus status) {
            DevLog.add("Robot", "onSpeechStatus(" + status + ")");
        }

        @Override
        public void onLocationsUpdated(List<String> locations) {
            DevLog.add("Robot", "onLocationsUpdated(" + locations + ")");
        }

        @Override
        public void onPersonDetected(boolean detected) {
            DevLog.add("Robot", "onPersonDetected(" + detected + ")");
        }
    };

    private DevPanel(AppCompatActivity activity, View root) {
        robot = DaycareApp.robot(activity);
        logText = root.findViewById(R.id.dev_log_text);
        logScroll = root.findViewById(R.id.dev_log_scroll);

        // 응급 이벤트 (샘플 장소·신뢰도)
        click(root, R.id.dev_btn_cry, () -> post(SafetyEvent.now(Type.CRY_DETECTED, Source.DEV_PANEL, null, 0.80f)));
        click(root, R.id.dev_btn_emergency, () -> post(SafetyEvent.now(Type.EMERGENCY_BUTTON, Source.DEV_PANEL)));
        click(root, R.id.dev_btn_fall, () -> post(SafetyEvent.now(Type.FALL_DETECTED, Source.DEV_PANEL, "블록 코너", 0.87f)));
        click(root, R.id.dev_btn_dismiss, () -> post(SafetyEvent.now(Type.DISMISS_VOICE, Source.DEV_PANEL)));

        // 로봇 동작 확인
        Button person = root.findViewById(R.id.dev_btn_person);
        person.setOnClickListener(v -> {
            personDetected = !personDetected;
            person.setText(personDetected ? "사람 사라짐" : "사람 감지");
            if (robot instanceof FakeRobotController) {
                ((FakeRobotController) robot).simulatePersonDetected(personDetected);
            }
        });
        click(root, R.id.dev_btn_goto, () -> robot.goTo("식탁"));
        click(root, R.id.dev_btn_turn, () -> robot.turnBy(90));
        click(root, R.id.dev_btn_stop, robot::stopMovement);
        click(root, R.id.dev_btn_clear, () -> logText.setText(""));
    }

    public static void attach(AppCompatActivity activity, ViewGroup container) {
        View root = LayoutInflater.from(activity).inflate(R.layout.dev_panel, container, true);
        activity.getLifecycle().addObserver(new DevPanel(activity, root));
    }

    private static void click(View root, int id, Runnable r) {
        root.findViewById(id).setOnClickListener(v -> r.run());
    }

    private static void post(SafetyEvent e) {
        DevLog.add(TAG, "버튼 → " + e.type);
        SafetyEventBus.get().post(e);
    }

    private void appendLine(String line) {
        CharSequence cur = logText.getText();
        if (cur.length() > MAX_LOG_CHARS) logText.setText(cur.subSequence(cur.length() - MAX_LOG_CHARS / 2, cur.length()));
        if (logText.length() > 0) logText.append("\n");
        logText.append(line);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        logText.setText("");
        DevLog.observe(logListener);
        SafetyEventBus.get().subscribe(safetyListener);
        robot.addListener(robotListener);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        DevLog.unobserve(logListener);
        SafetyEventBus.get().unsubscribe(safetyListener);
        robot.removeListener(robotListener);
    }
}
