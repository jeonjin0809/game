package com.damum.daycaretemi.play;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Random;

/** Remote AI only proposes a bounded next mission. Local code enforces game safety. */
public final class AgentMemoryPlanner implements MemoryGame.QuestionPlanner {
    public static final int MIN_ITEMS = 2;
    public static final int MAX_ITEMS = 4;

    public static final class Decision {
        public final String category;
        public final int difficulty;
        public final String mission;
        public final String mode;
        public final String reason;
        public final String supportStyle;
        public Decision(String category, int difficulty, String mission, String mode, String reason) {
            this(category, difficulty, mission, mode, reason, PlaySupportCoach.STEADY);
        }
        public Decision(String category, int difficulty, String mission, String mode,
                        String reason, String supportStyle) {
            this.category = Objects.requireNonNull(category);
            this.difficulty = difficulty;
            this.mission = Objects.requireNonNull(mission);
            this.mode = Objects.requireNonNull(mode);
            this.reason = reason == null ? "" : reason;
            this.supportStyle = supportStyle;
        }
    }

    /** For teacher review: what the agent (or local fallback) actually selected. */
    public static final class DecisionAudit {
        public final int round;
        public final String source;
        public final String mission;
        public final int items;
        public final String reason;
        DecisionAudit(int round, String source, String mission, int items, String reason) {
            this.round = round;
            this.source = source;
            this.mission = mission;
            this.items = items;
            this.reason = reason;
        }
    }

    private final List<DecisionAudit> audit = new ArrayList<>();
    public List<DecisionAudit> decisionAudit() {
        return Collections.unmodifiableList(new ArrayList<>(audit));
    }
    private void record(int roundIndex, String source, MemoryGame.Question q, String reason) {
        audit.add(new DecisionAudit(roundIndex + 1, source, q.mission.id,
                q.sequence.size(), reason == null ? "" : reason));
    }

    private final OfflineMemoryPlanner local;
    private Decision nextDecision;
    private FollowUpRecommendation.Plan followUp;
    private int llmDecisions, demoDecisions;
    private String activeSupportStyle = PlaySupportCoach.STEADY;

    public AgentMemoryPlanner() { this(new Random()); }
    public AgentMemoryPlanner(Random random) { local = new OfflineMemoryPlanner(random); }
    public AgentMemoryPlanner(FollowUpRecommendation.Plan approved) {
        this(new Random());
        if (approved != null) {
            if (approved.items < 2 || approved.items > 3
                    || !("animals".equals(approved.category) || "fruits".equals(approved.category)))
                throw new IllegalArgumentException("Unsupported approved plan");
            MemoryGame.Mission.fromId(approved.mission);
            followUp = approved;
        }
    }
    public String followUpTarget() { return followUp == null ? null : followUp.mission; }
    public int llmDecisions() { return llmDecisions; }
    public int demoDecisions() { return demoDecisions; }
    public String activeSupportStyle() { return activeSupportStyle; }

    public boolean accept(Decision d, List<MemoryGame.RoundResult> history) {
        if (d == null || history == null || history.isEmpty()
                || (!"animals".equals(d.category) && !"fruits".equals(d.category))
                || d.difficulty < MIN_ITEMS || d.difficulty > MAX_ITEMS
                || (!"llm".equals(d.mode) && !"demo".equals(d.mode))
                || d.reason.length() > 110 || d.reason.contains("\n") || d.reason.contains("\r")
                || !PlaySupportCoach.validStyle(d.supportStyle, history)) return false;
        MemoryGame.Mission mission;
        try { mission = MemoryGame.Mission.fromId(d.mission); }
        catch (IllegalArgumentException ex) { return false; }
        MemoryGame.RoundResult last = history.get(history.size() - 1);
        if (Math.abs(d.difficulty - last.shownItems) > 1) return false;
        if ((!last.correct || last.hintsUsed > 0 || last.examplesUsed > 0)
                && d.difficulty > last.shownItems) return false;
        if (mission == MemoryGame.Mission.STORY && d.difficulty > 3) return false;
        if (followUp != null && history.size() == MemoryGame.TOTAL_ROUNDS - 1
                && !followUp.mission.equals(d.mission)) return false;
        // Four different experiences in five rounds, even if an AI repeats itself.
        int unseen = 0;
        for (MemoryGame.Mission m : MemoryGame.Mission.values())
            if (!OfflineMemoryPlanner.seen(history, m)) unseen++;
        if (unseen > 0 && OfflineMemoryPlanner.seen(history, mission)) return false;
        // AI plan is a single-use command, never retained into another session.
        nextDecision = d;
        return true;
    }

    @Override public MemoryGame.Question nextQuestion(int roundIndex, List<MemoryGame.RoundResult> history) {
        Decision d = nextDecision;
        nextDecision = null;
        activeSupportStyle = d == null ? PlaySupportCoach.localStyle(history) : d.supportStyle;
        if (followUp != null && roundIndex == 0 && history.isEmpty()) {
            MemoryGame.Question q = local.nextQuestionWithSettings(0, history, followUp.category,
                    followUp.items, followUp.mission);
            record(roundIndex, "teacher", q, "교사가 승인한 첫 연습 미션");
            return q;
        }
        if (followUp != null && roundIndex == MemoryGame.TOTAL_ROUNDS - 1 && d == null) {
            int count = local.adaptiveCount(history);
            MemoryGame.Question q = local.nextQuestionWithSettings(roundIndex, history,
                    followUp.category, Math.min(3, count), followUp.mission);
            record(roundIndex, "teacher", q, "교사가 승인한 미션을 마지막에 재연습");
            return q;
        }
        if (d == null) {
            MemoryGame.Question q = local.nextQuestion(roundIndex, history);
            record(roundIndex, "offline", q, "게임 기록을 이용한 오프라인 규칙");
            return q;
        }
        MemoryGame.Question q = local.nextQuestionWithSettings(roundIndex, history,
                d.category, d.difficulty, d.mission);
        if ("llm".equals(d.mode)) llmDecisions++;
        else demoDecisions++;
        record(roundIndex, d.mode, q, d.reason);
        return q;
    }
}
