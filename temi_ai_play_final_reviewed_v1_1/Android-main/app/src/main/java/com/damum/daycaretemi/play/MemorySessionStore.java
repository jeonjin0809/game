package com.damum.daycaretemi.play;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Anonymous local game history (up to 10 complete sessions). No images, names or voice recordings. */
public final class MemorySessionStore {
    private static final String PREFS = "memory_game_history_v1";
    private static final String KEY = "sessions";
    private static final int LIMIT = 10;

    private MemorySessionStore() {}

    public static void save(Context context, MemoryGame game) {
        save(context, game, 0, 0);
    }

    /** Returns a random session ID to safely apply asynchronous teacher notes. */
    public static String save(Context context, MemoryGame game, int llmPlans, int demoPlans) {
        return save(context, game, llmPlans, demoPlans, null, null);
    }

    public static String save(Context context, MemoryGame game, int llmPlans, int demoPlans,
                              String approvedFromSession, FollowUpRecommendation.Plan appliedPlan) {
        return save(context, game, llmPlans, demoPlans, approvedFromSession, appliedPlan,
                Collections.emptyList());
    }

    public static String save(Context context, MemoryGame game, int llmPlans, int demoPlans,
                              String approvedFromSession, FollowUpRecommendation.Plan appliedPlan,
                              List<AgentMemoryPlanner.DecisionAudit> decisions) {
        if (game.phase() != MemoryGame.Phase.FINISHED) return null;
        String sessionId = UUID.randomUUID().toString();
        try {
            JSONObject session = new JSONObject();
            session.put("sessionId", sessionId);
            session.put("llmPlans", llmPlans);
            session.put("demoPlans", demoPlans);
            session.put("note", "실제 게임 기록: 5문제 중 " + game.correctCount()
                    + "문제 정답, 힌트 " + game.totalHints() + "회, 예시 "
                    + game.totalExamples() + "회, 기다림 안내 "
                    + game.totalWaitingPrompts() + "회 사용.");
            FollowUpRecommendation.Plan recommended = FollowUpRecommendation.baseline(
                    FollowUpRecommendation.observe(game.results()));
            session.put("nextPlan", encode(recommended));
            session.put("nextActivity", planActivity(recommended));
            session.put("reportMode", "offline");
            if (approvedFromSession != null && appliedPlan != null) {
                session.put("appliedFromSession", approvedFromSession);
                session.put("appliedMission", appliedPlan.mission);
                MemoryGame.RoundResult first = game.results().get(0);
                MemoryGame.RoundResult last = game.results().get(game.results().size() - 1);
                if (appliedPlan.mission.equals(first.mission) && appliedPlan.mission.equals(last.mission)) {
                    session.put("practiceFirstCorrect", first.correct);
                    session.put("practiceFirstHints", first.hintsUsed);
                    session.put("practiceLastCorrect", last.correct);
                    session.put("practiceLastHints", last.hintsUsed);
                }
            }
            session.put("completedAt", System.currentTimeMillis());
            session.put("correctCount", game.correctCount());
            session.put("totalRounds", game.results().size());
            session.put("hintsUsed", game.totalHints());
            session.put("examplesUsed", game.totalExamples());
            session.put("waitingPrompts", game.totalWaitingPrompts());
            session.put("durationMs", Math.max(0, game.finishedAt() - game.startedAt()));
            JSONArray rounds = new JSONArray();
            for (MemoryGame.RoundResult result : game.results()) {
                JSONObject row = new JSONObject();
                row.put("round", result.roundNumber);
                row.put("shownItems", result.shownItems);
                row.put("category", result.category);
                row.put("mission", result.mission);
                row.put("question", result.prompt);
                row.put("correctAnswer", result.correctId);
                row.put("selectedAnswer", result.selectedId);
                row.put("correct", result.correct);
                row.put("hints", result.hintsUsed);
                row.put("examples", result.examplesUsed);
                row.put("waitingPrompts", result.waitingPrompts);
                row.put("responseMs", result.responseMillis);
                rounds.put(row);
            }
            session.put("rounds", rounds);
            JSONArray audit = new JSONArray();
            if (decisions != null) {
                for (AgentMemoryPlanner.DecisionAudit d : decisions) {
                    JSONObject row = new JSONObject();
                    row.put("round", d.round);
                    row.put("source", d.source);
                    row.put("mission", d.mission);
                    row.put("items", d.items);
                    row.put("reason", d.reason);
                    audit.put(row);
                }
            }
            session.put("decisions", audit);
            JSONArray previous = load(context);
            JSONArray updated = new JSONArray();
            updated.put(session);
            for (int i = 0; i < previous.length() && i < LIMIT - 1; i++) {
                updated.put(previous.getJSONObject(i));
            }
            return prefs(context).edit().putString(KEY, updated.toString()).commit()
                    ? sessionId : null;
        } catch (JSONException ignored) {
            // If a malformed local record is present, the game still runs normally.
            return null;
        }
    }

    /** Only updates the exact saved session; validates that the recommended follow-up
     * is grounded in this very session's outcomes before persisting it. */
    public static boolean updateTeacherReport(Context context, String sessionId,
                                           String note, String mode, JSONObject proposed) {
        if (sessionId == null || note == null || note.isEmpty() || note.length() > 180
                || proposed == null || !("llm".equals(mode) || "demo".equals(mode))) return false;
        try {
            JSONArray sessions = load(context);
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.optJSONObject(i);
                if (session == null || !sessionId.equals(session.optString("sessionId"))) continue;
                FollowUpRecommendation.Plan plan = decode(proposed, mode);
                if (!FollowUpRecommendation.valid(plan, observedFromJson(session.optJSONArray("rounds"))))
                    return false;
                session.put("note", note);
                session.put("nextPlan", encode(plan));
                session.put("nextActivity", planActivity(plan));
                session.put("reportMode", mode);
                return prefs(context).edit().putString(KEY, sessions.toString()).commit();
            }
        } catch (JSONException ignored) { }
        return false;
    }

    public static FollowUpRecommendation.Plan latestPlan(Context context) {
        JSONObject current = load(context).optJSONObject(0);
        if (current == null) return null;
        FollowUpRecommendation.Plan plan = decode(current.optJSONObject("nextPlan"),
                current.optString("reportMode", "offline"));
        return FollowUpRecommendation.valid(plan, observedFromJson(current.optJSONArray("rounds")))
                ? plan : null;
    }

    public static String latestSessionId(Context context) {
        JSONObject latest = load(context).optJSONObject(0);
        return latest == null ? null : latest.optString("sessionId", null);
    }

    public static String latestPlanReview(Context context) {
        JSONObject latest = load(context).optJSONObject(0);
        FollowUpRecommendation.Plan plan = latestPlan(context);
        if (latest == null || plan == null) return "먼저 기억·이야기 놀이를 한 번 완료해 주세요.";
        List<FollowUpRecommendation.Observation> observations = observedFromJson(latest.optJSONArray("rounds"));
        return "최근 놀이: " + latest.optInt("correctCount") + "/" + latest.optInt("totalRounds")
                + "개 정답 · 힌트 " + latest.optInt("hintsUsed")
                + "회 · 예시 " + latest.optInt("examplesUsed", 0) + "회\n\n"
                + "추천 미션: " + FollowUpRecommendation.title(plan.mission) + "\n"
                + "시작 그림 수: " + plan.items + "개\n"
                + "목표: " + FollowUpRecommendation.goal(plan.mission) + "\n\n"
                + "근거: " + FollowUpRecommendation.observedReason(observations, plan.mission) + "\n"
                + "교사용 도움말: " + FollowUpRecommendation.teacherTip(plan.mission) + "\n\n"
                + "출처: " + FollowUpRecommendation.sourceLabel(plan.source) + "\n"
                + "승인하면 이 미션으로 시작하고 마지막 미션에서 다시 연습합니다. \n"
                + "놀이 결과는 발달 진단이 아니며 교사가 판단해야 합니다.";
    }

    private static String planActivity(FollowUpRecommendation.Plan p) {
        return "다음 놀이를 '" + FollowUpRecommendation.title(p.mission) + "' " + p.items
                + "개 그림으로 시작하고, " + FollowUpRecommendation.teacherTip(p.mission);
    }

    private static JSONObject encode(FollowUpRecommendation.Plan p) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("mission", p.mission);
        json.put("category", p.category);
        json.put("difficulty", p.items);
        return json;
    }

    private static FollowUpRecommendation.Plan decode(JSONObject obj, String mode) {
        if (obj == null) return null;
        return new FollowUpRecommendation.Plan(obj.optString("mission", ""),
                obj.optString("category", ""), obj.optInt("difficulty", -1), mode);
    }

    private static List<FollowUpRecommendation.Observation> observedFromJson(JSONArray rounds) {
        if (rounds == null) return Collections.emptyList();
        List<FollowUpRecommendation.Observation> observed = new ArrayList<>();
        for (int i = 0; i < rounds.length(); i++) {
            JSONObject row = rounds.optJSONObject(i);
            if (row == null) return Collections.emptyList();
            observed.add(new FollowUpRecommendation.Observation(row.optString("mission"),
                    row.optBoolean("correct"), row.optInt("hints"),
                    row.optInt("examples", 0), row.optInt("shownItems")));
        }
        return observed;
    }

    public static JSONArray load(Context context) {
        try { return new JSONArray(prefs(context).getString(KEY, "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    /** Short titles allow a teacher to choose an individual record on temi's small screen. */
    public static String[] historyTitles(Context context) {
        JSONArray sessions = load(context);
        String[] titles = new String[sessions.length()];
        SimpleDateFormat format = new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA);
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            titles[i] = s == null ? "기록 " + (i + 1)
                    : format.format(new Date(s.optLong("completedAt"))) + " · "
                    + s.optInt("correctCount") + "/" + s.optInt("totalRounds")
                    + " 정답 · 힌트 " + s.optInt("hintsUsed") + "회";
        }
        return titles;
    }

    public static String summaryForTeacher(Context context, int index) {
        JSONArray sessions = load(context);
        if (index < 0 || index >= sessions.length()) return "선택한 기록이 없습니다.";
        JSONArray one = new JSONArray();
        one.put(sessions.optJSONObject(index));
        return formatTeacherRecords(one);
    }

    public static String summaryForTeacher(Context context) {
        return formatTeacherRecords(load(context));
    }

    private static String formatTeacherRecords(JSONArray sessions) {
        if (sessions.length() == 0) return "아직 완료한 놀이 기록이 없습니다.";
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat format = new SimpleDateFormat("MM월 dd일 HH:mm", Locale.KOREA);
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s == null) continue;
            sb.append(i + 1).append(". ")
              .append(format.format(new Date(s.optLong("completedAt"))))
              .append(" · ").append(s.optInt("correctCount")).append("/")
              .append(s.optInt("totalRounds")).append("개 정답")
              .append(" · 힌트 ").append(s.optInt("hintsUsed")).append("회")
              .append(" · 예시 ").append(s.optInt("examplesUsed", 0)).append("회")
              .append(" · 기다림 안내 ").append(s.optInt("waitingPrompts", 0)).append("회")

              .append("\n   AI 결정 ").append(s.optInt("llmPlans", 0)).append("회")
              .append(" · 규칙 데모 ").append(s.optInt("demoPlans", 0)).append("회\n");
            JSONArray rounds = s.optJSONArray("rounds");
            if (rounds != null) {
                for (int j = 0; j < rounds.length(); j++) {
                    JSONObject r = rounds.optJSONObject(j);
                    if (r != null) sb.append("   ").append(j + 1).append("미션 ")
                            .append(missionTitle(r.optString("mission", "order"))).append(": ")
                            .append(r.optInt("shownItems")).append("개 그림 · ")
                            .append(r.optBoolean("correct") ? "정답" : "오답")
                            .append(" · 힌트 ").append(r.optInt("hints")).append("회")
                            .append(" · 예시 ").append(r.optInt("examples", 0)).append("회")
                            .append(" · 기다림 안내 ").append(r.optInt("waitingPrompts", 0))
                            .append("회\n");
                }
            }
            JSONArray decisions = s.optJSONArray("decisions");
            if (decisions != null && decisions.length() > 0) {
                sb.append("   놀이 선택 기록 (AI/규칙/교사 실제 실행):\n");
                for (int j = 0; j < decisions.length(); j++) {
                    JSONObject d = decisions.optJSONObject(j);
                    if (d == null) continue;
                    String source = d.optString("source", "offline");
                    sb.append("     ").append(d.optInt("round")).append("번 · ")
                      .append("llm".equals(source) ? "실제 AI" :
                              "demo".equals(source) ? "규칙 데모" :
                              "teacher".equals(source) ? "교사 승인" : "오프라인 규칙")
                      .append(" → ").append(missionTitle(d.optString("mission")))
                      .append(" · 그림 ").append(d.optInt("items")).append("개")
                      .append(" ( ").append(d.optString("reason")).append(" )\n");
                }
            }
            if (s.has("appliedMission")) {
                sb.append("   교사 승인 후속 놀이 실행: ")
                  .append(missionTitle(s.optString("appliedMission"))).append("\n");
                if (s.has("practiceFirstCorrect") && s.has("practiceLastCorrect")) {
                    sb.append("   연습 전후 기록 (같은 유형의 서로 다른 문제): ")
                      .append(s.optBoolean("practiceFirstCorrect") ? "첫 문제 정답" : "첫 문제 오답")
                      .append("/힌트 ").append(s.optInt("practiceFirstHints"))
                      .append("회 → ")
                      .append(s.optBoolean("practiceLastCorrect") ? "마지막 문제 정답" : "마지막 문제 오답")
                      .append("/힌트 ").append(s.optInt("practiceLastHints"))
                      .append("회\n");
                }
            }
            sb.append("   교사용 관찰: ").append(s.optString("note", ""))
              .append("\n   후속 놀이 제안: ").append(s.optString("nextActivity", ""))
              .append("\n   제안 출처: ")
              .append("llm".equals(s.optString("reportMode")) ? "AI 생성(교사 검토 필요)"
                      : "demo".equals(s.optString("reportMode")) ? "규칙 데모" : "오프라인 규칙")
              .append("\n\n");
        }
        sb.append("※ 정답·오답·힌트·예시 요청·안내는 놀이 중 기록된 사실입니다. AI 제안은 추정이며 선생님의 확인이 필요합니다. 발달 진단이 아닙니다. 이름·사진·음성은 저장하지 않습니다.");
        return sb.toString();
    }

    private static String missionTitle(String id) {
        switch (id) {
            case "position": return "위치 기억";
            case "missing": return "사라진 그림";
            case "story": return "이야기 순서";
            default: return "순서 기억";
        }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
