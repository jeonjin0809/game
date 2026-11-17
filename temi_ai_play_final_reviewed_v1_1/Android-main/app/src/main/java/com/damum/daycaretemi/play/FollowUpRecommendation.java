package com.damum.daycaretemi.play;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A concrete, teacher-reviewable NEXT session plan from observed play results.
 * Only pre-reviewed activities are executable; a language model cannot invent new robot actions.
 * No names, diagnosis, pictures, or inferred personal traits are stored here.
 */
public final class FollowUpRecommendation {
    private FollowUpRecommendation() {}

    public static final class Observation {
        public final String mission;
        public final boolean correct;
        public final int hints;
        public final int examples;
        public final int items;
        public Observation(String mission, boolean correct, int hints, int items) {
            this(mission, correct, hints, 0, items);
        }
        public Observation(String mission, boolean correct, int hints, int examples, int items) {
            this.mission = mission;
            this.correct = correct;
            this.hints = hints;
            this.examples = examples;
            this.items = items;
        }
    }

    public static final class Plan {
        public final String mission;
        public final String category;
        public final int items;
        /** llm, demo, offline, or teacher; never label demo/offline as AI. */
        public final String source;
        public Plan(String mission, String category, int items, String source) {
            this.mission = mission;
            this.category = category;
            this.items = items;
            this.source = source;
        }
    }

    public static List<Observation> observe(List<MemoryGame.RoundResult> rounds) {
        List<Observation> observations = new ArrayList<>();
        for (MemoryGame.RoundResult r : rounds)
            observations.add(new Observation(r.mission, r.correct, r.hintsUsed, r.examplesUsed, r.shownItems));
        return Collections.unmodifiableList(observations);
    }

    public static String title(String mission) {
        switch (mission) {
            case "order": return "그림 순서 기억";
            case "position": return "그림 위치 기억";
            case "missing": return "사라진 그림 찾기";
            case "story": return "이야기 순서 맞추기";
            default: return "알 수 없는 놀이";
        }
    }

    public static String teacherTip(String mission) {
        switch (mission) {
            case "position": return "먼저 그림이 있던 칸을 함께 손으로 가리켜 보세요.";
            case "missing": return "그림을 하나씩 소리 내어 확인한 다음 무엇이 없어졌는지 물어보세요.";
            case "story": return "첫 장면을 함께 고르고 나머지는 아이가 순서대로 선택하게 해 보세요.";
            default: return "그림을 왼쪽부터 한 번씩 가리키며 순서를 말해 보세요.";
        }
    }

    public static String goal(String mission) {
        switch (mission) {
            case "position": return "물건과 공간의 위치 연결 연습";
            case "missing": return "보았던 그림을 비교하고 변화 찾기";
            case "story": return "일어난 일의 앞뒤 순서 생각하기";
            default: return "보았던 그림의 순서를 기억해 보기";
        }
    }

    public static String observedReason(List<Observation> observations, String mission) {
        int struggles = 0, mistakes = 0, hints = 0, examples = 0;
        for (Observation o : observations) {
            if (!o.mission.equals(mission)) continue;
            if (!o.correct || o.hints > 0 || o.examples > 0) struggles++;
            if (!o.correct) mistakes++;
            hints += o.hints;
            examples += o.examples;
        }
        if (struggles == 0) return "이전 놀이 기록을 바탕으로 다음 활동을 제안했어요. 특정 능력을 판단한 것은 아니에요.";
        return title(mission) + "에서 오답 " + mistakes + "회, 힌트 " + hints + "회, 예시 보기 " + examples + "회가 기록돼 한 번 더 연습하도록 제안했어요.";
    }

    public static Plan baseline(List<Observation> observations) {
        if (observations.isEmpty()) return new Plan("order", "animals", 2, "offline");
        Observation target = null;
        for (Observation o : observations) {
            if (!o.correct || o.hints > 0 || o.examples > 0) { target = o; break; }
        }
        if (target != null) return new Plan(target.mission, "animals", 2, "offline");
        return new Plan("story", "animals", 3, "offline");
    }

    public static boolean valid(Plan plan, List<Observation> history) {
        if (plan == null || history == null || history.isEmpty() || history.size() > MemoryGame.TOTAL_ROUNDS)
            return false;
        try { MemoryGame.Mission.fromId(plan.mission); }
        catch (RuntimeException e) { return false; }
        if (!"animals".equals(plan.category) && !"fruits".equals(plan.category)) return false;
        if (plan.items < 2 || plan.items > 3 || plan.source == null
                || !("llm".equals(plan.source) || "demo".equals(plan.source)
                || "offline".equals(plan.source) || "teacher".equals(plan.source))) return false;
        for (Observation o : history)
            if (o == null || o.items < 2 || o.items > 4 || o.hints < 0 || o.hints > 1 || o.examples < 0 || o.examples > 1)
                return false;
        // AI suggestions must have a basis: choose among missions that needed help.
        // Teachers can deliberately override this limitation in a supervised session.
        if ("teacher".equals(plan.source)) return true;
        boolean anyHelp = false, thisMissionNeedsHelp = false;
        for (Observation o : history) {
            boolean help = !o.correct || o.hints > 0 || o.examples > 0;
            anyHelp |= help;
            if (o.mission.equals(plan.mission) && help) thisMissionNeedsHelp = true;
        }
        return !anyHelp || thisMissionNeedsHelp;
    }

    public static String sourceLabel(String source) {
        if ("llm".equals(source)) return "AI 추천 · 교사 승인 필요";
        if ("demo".equals(source)) return "규칙 데모 (AI 아님)";
        if ("teacher".equals(source)) return "교사가 선택한 미션";
        return "오프라인 규칙 추천";
    }
}
