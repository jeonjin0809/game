package com.damum.daycaretemi;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.damum.daycaretemi.game.HideAndSeekGame;
import com.damum.daycaretemi.robot.RobotController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 실제 temi / emulator 공통 게임 화면. 모든 로봇 제어는 RobotController를 통한다. */
public class MainActivity extends AppCompatActivity {
    private RobotController robot;
    private HideAndSeekGame game;
    private TextView connectionText, detectorText, testText, phaseText;
    private TextView scoreText, timeText, messageText, routeText;
    private Spinner childrenSpinner;
    private Button startButton, routeButton, permissionButton, resumeButton, stopButton;
    private final List<String> availableLocations = new ArrayList<>();
    private final LinkedHashSet<String> selectedLocations = new LinkedHashSet<>();
    private HideAndSeekGame.Snapshot lastSnapshot;

    private final HideAndSeekGame.Listener gameListener = new HideAndSeekGame.Listener() {
        @Override public void onGameUpdated(HideAndSeekGame.Snapshot state) { render(state); }
        @Override public void onLocationsUpdated(List<String> saved) {
            availableLocations.clear();
            for (String name : saved) {
                if (name != null && !name.trim().isEmpty() &&
                        !HideAndSeekGame.isUnsafeLocationName(name) && !availableLocations.contains(name)) {
                    availableLocations.add(name);
                }
            }
            selectedLocations.retainAll(availableLocations);
            refreshRouteText();
            if (lastSnapshot != null) render(lastSnapshot);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        robot = DaycareApp.robot(this);
        connectionText = findViewById(R.id.status_text);
        detectorText = findViewById(R.id.detector_text);
        testText = findViewById(R.id.detector_test_text);
        phaseText = findViewById(R.id.game_phase_text);
        scoreText = findViewById(R.id.game_score_text);
        timeText = findViewById(R.id.game_time_text);
        messageText = findViewById(R.id.game_message_text);
        routeText = findViewById(R.id.route_text);
        childrenSpinner = findViewById(R.id.children_spinner);
        startButton = findViewById(R.id.btn_start);
        routeButton = findViewById(R.id.btn_route);
        permissionButton = findViewById(R.id.btn_permission);
        resumeButton = findViewById(R.id.btn_resume);
        stopButton = findViewById(R.id.btn_stop);

        String[] counts = {"1명", "2명", "3명", "4명", "5명", "6명", "7명", "8명", "9명", "10명"};
        ArrayAdapter<String> countAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, counts);
        countAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        childrenSpinner.setAdapter(countAdapter);
        childrenSpinner.setSelection(2); // 기본 3명

        game = new HideAndSeekGame(robot, gameListener);
        permissionButton.setOnClickListener(v -> game.requestDetectionPermission());
        routeButton.setOnClickListener(v -> selectRoute());
        startButton.setOnClickListener(v -> confirmAndStart());
        resumeButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("탐색 다시 시작")
                .setMessage("장애물과 이동 경로를 확인했고 주변 사람이 충분히 떨어져 있나요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("안전 확인 · 재개", (dialog, which) -> game.resumeByTeacher())
                .show());
        // 로봇 정지는 게임 상태와 상관없이 언제든 요청 가능
        stopButton.setOnClickListener(v -> game.stopByTeacher());
        FlavorModule.attachDevTools(this, findViewById(R.id.dev_container));
        refreshRouteText();
    }

    @Override protected void onStart() {
        super.onStart();
        game.attach();
    }
    @Override protected void onStop() {
        game.detach();  // 앱 화면 이탈 시 순찰 즉시 중지
        super.onStop();
    }

    private void selectRoute() {
        if (availableLocations.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("이동 장소 없음")
                    .setMessage("먼저 temi 지도에서 평탄하고 안전한 2~3곳을 저장해 주세요. "
                            + "home base/충전 위치는 게임에서 자동 제외됩니다.")
                    .setPositiveButton("확인", null).show();
            return;
        }
        final String[] names = availableLocations.toArray(new String[0]);
        final LinkedHashSet<String> draft = new LinkedHashSet<>(selectedLocations);
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) checked[i] = draft.contains(names[i]);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("안전한 이동 장소 2~3곳 선택")
                .setMultiChoiceItems(names, checked, (dialog, index, selected) -> {
                    if (selected) {
                        if (draft.size() >= 3) {
                            ((AlertDialog) dialog).getListView().setItemChecked(index, false);
                            Toast.makeText(this, "최대 3곳까지 선택할 수 있습니다.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        draft.add(names[index]);
                    } else {
                        draft.remove(names[index]);
                    }
                })
                .setNegativeButton("취소", null)
                .setPositiveButton("선택 완료", (dialog, which) -> {
                    if (draft.size() < 2) {
                        Toast.makeText(this, "최소 2곳을 선택해 주세요.", Toast.LENGTH_LONG).show();
                    } else {
                        selectedLocations.clear();
                        selectedLocations.addAll(draft);
                        refreshRouteText();
                        if (lastSnapshot != null) render(lastSnapshot);
                    }
                }).create();
        dlg.show();
    }

    private void confirmAndStart() {
        final List<String> route = new ArrayList<>(selectedLocations);
        final int people = childrenSpinner.getSelectedItemPosition() + 1;
        new AlertDialog.Builder(this)
                .setTitle("어린이집 숨바꼭질 시작 전 확인")
                .setMessage("참가자 " + people + "명, 순찰 장소 " + TextUtils.join(" → ", route)
                        + "\n\n① 평평한 바닥이며 계단·전선·가방이 없는지 확인했나요?"
                        + "\n② 참가자는 이동 경로에 뛰어들지 않고, 발견 후 대기구역으로 이동하나요?"
                        + "\n③ 교사가 로봇 옆에서 즉시 정지 버튼을 누를 수 있나요?"
                        + "\n④ 먼저 사람 감지와 경로 이동을 시험했나요?"
                        + "\n\n시작 후 5분에 중간 멘트, 10분에 자동 종료합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("모두 확인 · 시작", (dialog, which) -> game.start(people, route))
                .show();
    }

    private void refreshRouteText() {
        if (selectedLocations.isEmpty()) routeText.setText("순찰 장소가 선택되지 않았습니다.");
        else routeText.setText("자동 순찰 경로: " + TextUtils.join(" → ", selectedLocations)
                + " → 처음부터 반복");
    }

    private void render(HideAndSeekGame.Snapshot state) {
        lastSnapshot = state;
        connectionText.setText(state.robotReady ? "● temi 연결됨" : "● temi 연결되지 않음");
        detectorText.setText("사람 감지: " + state.detectionStatus);
        testText.setText(state.detectionTestSeen
                ? "● 실제 사람 감지 이벤트 수신 성공" : "○ 테스트 전: 게임 시작 전에 temi 앞에 서서 감지를 확인하세요.");
        phaseText.setText(phaseName(state.phase));
        scoreText.setText("찾은 사람  " + state.foundChildren + " / " + state.totalChildren + "명");
        timeText.setText(String.format(Locale.KOREA, "남은 시간 %02d:%02d",
                state.secondsLeft / 60, state.secondsLeft % 60));
        messageText.setText(state.message);
        boolean playing = state.isPlaying();
        childrenSpinner.setEnabled(!playing);
        routeButton.setEnabled(!playing && !availableLocations.isEmpty());
        permissionButton.setEnabled(!playing && state.robotReady);
        startButton.setEnabled(!playing && state.robotReady && state.detectionConfigured
                && selectedLocations.size() >= 2);
        startButton.setText(state.phase == HideAndSeekGame.Phase.IDLE ? "숨바꼭질 시작" : "새 게임 시작");
        resumeButton.setEnabled(state.phase == HideAndSeekGame.Phase.PAUSED);
        resumeButton.setVisibility(state.phase == HideAndSeekGame.Phase.PAUSED
                ? android.view.View.VISIBLE : android.view.View.GONE);
        stopButton.setEnabled(true);
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static String phaseName(HideAndSeekGame.Phase phase) {
        switch (phase) {
            case COUNTING: return "숨어요! · 숫자 세는 중";
            case MOVING: return "자동 순찰 중 · 이동 경로를 비워 주세요";
            case SEARCHING: return "주변을 찾는 중 · 멈춰서 사람 감지";
            case FOUND_WAIT: return "찾았다! · 발견된 친구는 대기구역으로";
            case PAUSED: return "이동 일시정지 · 교사 확인 필요";
            case FINISHED: return "숨바꼭질 종료!";
            case STOPPED: return "게임 중단 · 로봇 정지";
            default: return "게임 준비";
        }
    }
}
