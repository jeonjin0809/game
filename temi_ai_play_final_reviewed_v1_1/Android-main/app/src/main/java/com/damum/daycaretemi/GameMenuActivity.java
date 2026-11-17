package com.damum.daycaretemi;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import com.damum.daycaretemi.play.AgentSettings;
import com.damum.daycaretemi.play.MemoryAgentClient;
import com.damum.daycaretemi.play.FollowUpRecommendation;

import androidx.appcompat.app.AppCompatActivity;

import com.damum.daycaretemi.play.MemoryGameActivity;
import com.damum.daycaretemi.play.MemorySessionStore;

/** New launcher, preserving the original HideAndSeek MainActivity as-is. */
public final class GameMenuActivity extends AppCompatActivity {
    private MemoryAgentClient agentClient;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_game_menu);
        agentClient = new MemoryAgentClient();
        ((Button) findViewById(R.id.menu_memory)).setOnClickListener(v ->
                startActivity(new Intent(this, MemoryGameActivity.class)));
        ((Button) findViewById(R.id.menu_hide)).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class)));
        ((Button) findViewById(R.id.menu_agent)).setOnClickListener(v -> editAgentSettings());
        ((Button) findViewById(R.id.menu_follow_up)).setOnClickListener(v -> reviewFollowUp());
        ((Button) findViewById(R.id.menu_history)).setOnClickListener(v -> showHistoryPicker());
    }

    private void showHistoryPicker() {
        String[] titles = MemorySessionStore.historyTitles(this);
        if (titles.length == 0) {
            new AlertDialog.Builder(this).setTitle("교사용 놀이 기록")
                    .setMessage("아직 완료한 놀이가 없어요.")
                    .setPositiveButton("닫기", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("최근 놀이 기록 선택")
                .setItems(titles, (dialog, index) ->
                        new AlertDialog.Builder(this).setTitle("교사용 관찰 기록")
                                .setMessage(MemorySessionStore.summaryForTeacher(this, index))
                                .setPositiveButton("닫기", null).show())
                .setNegativeButton("취소", null).show();
    }

    private void reviewFollowUp() {
        FollowUpRecommendation.Plan recommendation = MemorySessionStore.latestPlan(this);
        if (recommendation == null) {
            new AlertDialog.Builder(this).setTitle("맞춤 놀이 계획")
                    .setMessage("먼저 기억·이야기 놀이를 5문제 완료해야 추천을 만들 수 있어요.")
                    .setPositiveButton("확인", null).show();
            return;
        }
        String fromId = MemorySessionStore.latestSessionId(this);
        new AlertDialog.Builder(this)
                .setTitle("교사용 다음 놀이 계획 확인")
                .setMessage(MemorySessionStore.latestPlanReview(this))
                .setNegativeButton("나중에", null)
                .setNeutralButton("미션 변경", (dialog, which) -> selectTeacherMission(fromId))
                .setPositiveButton("승인하고 놀이 시작", (dialog, which) ->
                        launchFollowUp(recommendation, fromId)).show();
    }

    private void selectTeacherMission(String fromId) {
        final String[] ids = {"order", "position", "missing", "story"};
        final String[] titles = {"그림 순서 기억", "그림 위치 기억", "사라진 그림 찾기", "이야기 순서 맞추기"};
        new AlertDialog.Builder(this)
                .setTitle("첫 번째·마지막에 연습할 미션 선택")
                .setItems(titles, (dialog, which) -> {
                    FollowUpRecommendation.Plan changed = new FollowUpRecommendation.Plan(
                            ids[which], "animals", 2, "teacher");
                    new AlertDialog.Builder(this).setTitle("교사 선택 확인")
                            .setMessage("'" + titles[which] + "'를 그림 2개로 연습할까요? "
                                    + "첫 문제와 마지막 문제에 이 미션을 사용합니다.")
                            .setNegativeButton("취소", null)
                            .setPositiveButton("승인하고 시작", (dialog2, button) ->
                                    launchFollowUp(changed, fromId)).show();
                }).show();
    }

    private void launchFollowUp(FollowUpRecommendation.Plan plan, String fromId) {
        Intent intent = new Intent(this, MemoryGameActivity.class);
        intent.putExtra(MemoryGameActivity.EXTRA_FOLLOW_UP_MISSION, plan.mission);
        intent.putExtra(MemoryGameActivity.EXTRA_FOLLOW_UP_CATEGORY, plan.category);
        intent.putExtra(MemoryGameActivity.EXTRA_FOLLOW_UP_ITEMS, plan.items);
        intent.putExtra(MemoryGameActivity.EXTRA_FOLLOW_UP_SOURCE_SESSION, fromId);
        startActivity(intent);
    }

    private void editAgentSettings() {
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setText(AgentSettings.getUrl(this));
        url.setHint("http://192.168.0.15:8765");
        url.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("노트북 AI 서버 주소")
                .setMessage("같은 Wi-Fi의 노트북 IPv4 주소를 적으세요. 비워두면 오프라인 게임입니다. API 키는 로봇에 입력하지 마세요.")
                .setView(url)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장 · 연결 검사", (dialog, which) -> {
                    try {
                        AgentSettings.setUrl(this, url.getText().toString());
                        String current = AgentSettings.getUrl(this);
                        if (current.isEmpty()) {
                            Toast.makeText(this, "오프라인 모드로 저장했어요.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        agentClient.health(current, (body, error) -> {
                            if (isFinishing()) return;
                            String label = error != null ? "연결 실패 · 오프라인 게임 가능"
                                    : "llm".equals(body.optString("mode"))
                                            ? "AI API 키가 설정된 서버입니다. 실제 모델 호출 성공 여부는 게임 중 확인하세요."
                                            : "규칙 데모 서버 연결됨 (AI 아님)";
                            new AlertDialog.Builder(this).setTitle("서버 연결 결과")
                                    .setMessage(label).setPositiveButton("확인", null).show();
                        });
                    } catch (IllegalArgumentException e) {
                        new AlertDialog.Builder(this).setTitle("주소 형식 오류")
                                .setMessage(e.getMessage()).setPositiveButton("확인", null).show();
                    }
                }).show();
    }

    @Override protected void onDestroy() {
        agentClient.close();
        super.onDestroy();
    }
}
