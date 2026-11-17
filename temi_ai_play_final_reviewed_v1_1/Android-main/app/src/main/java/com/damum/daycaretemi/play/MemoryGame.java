package com.damum.daycaretemi.play;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One integrated play session with several short observation/memory missions.
 * No child identities, diagnosis, robot movement or network calls in the game engine.
 */
public final class MemoryGame {
    public static final int TOTAL_ROUNDS = 5;
    public enum Phase { READY, MEMORIZE, ANSWER, FEEDBACK, FINISHED }
    public enum Mission {
        ORDER("order", "순서 기억"),
        POSITION("position", "위치 기억"),
        MISSING("missing", "사라진 그림"),
        STORY("story", "이야기 순서");

        public final String id;
        public final String title;
        Mission(String id, String title) { this.id = id; this.title = title; }
        public static Mission fromId(String value) {
            for (Mission m : values()) if (m.id.equals(value)) return m;
            throw new IllegalArgumentException("Unknown mission: " + value);
        }
    }

    public static final class Item {
        public final String id;
        public final String icon;
        public final String label;
        public Item(String id, String icon, String label) {
            if (id == null || id.isEmpty() || icon == null || label == null || label.isEmpty())
                throw new IllegalArgumentException("Invalid item");
            this.id = id;
            this.icon = icon;
            this.label = label;
        }
        public String display() { return icon + "\n" + label; }
    }

    public static final class Question {
        public final Mission mission;
        public final String category;
        public final List<Item> sequence;
        public final List<Item> options;
        public final int askedIndex;
        public final String correctId;
        /** Position mission only: cell index 0..3 for each shown item. */
        public final List<Integer> positionCells;
        private final String prompt;
        private final String correctLabel;
        private final String hintText;

        /** Legacy order-memory constructor: remains available for existing game tests. */
        public Question(String category, List<Item> sequence, List<Item> options, int askedIndex) {
            this(Mission.ORDER, category, sequence, options, askedIndex,
                    sequence.get(askedIndex).id,
                    ordinal(askedIndex) + "에 나온 " + category + "은 무엇일까요?",
                    sequence.get(askedIndex).label, "힌트! 정답이 아닌 그림 하나를 지워 줄게!");
        }

        public Question(Mission mission, String category, List<Item> sequence,
                        List<Item> options, int askedIndex, String correctId,
                        String prompt, String correctLabel, String hintText) {
            this(mission, category, sequence, options, askedIndex, correctId,
                    prompt, correctLabel, hintText, Collections.emptyList());
        }

        public Question(Mission mission, String category, List<Item> sequence,
                        List<Item> options, int askedIndex, String correctId,
                        String prompt, String correctLabel, String hintText,
                        List<Integer> positionCells) {
            if (mission == null || sequence == null || sequence.size() < 2 || sequence.size() > 4
                    || options == null || options.size() < 2 || options.size() > 4
                    || askedIndex < 0 || askedIndex >= sequence.size() || correctId == null
                    || prompt == null || prompt.isEmpty() || correctLabel == null || correctLabel.isEmpty())
                throw new IllegalArgumentException("Invalid mission question");
            this.mission = mission;
            this.category = Objects.requireNonNull(category);
            this.sequence = Collections.unmodifiableList(new ArrayList<>(sequence));
            this.options = Collections.unmodifiableList(new ArrayList<>(options));
            this.askedIndex = askedIndex;
            this.correctId = correctId;
            this.positionCells = Collections.unmodifiableList(new ArrayList<>(positionCells));
            if (mission == Mission.POSITION) {
                Set<Integer> locations = new HashSet<>(positionCells);
                boolean inGrid = true;
                for (Integer c : positionCells) if (c == null || c < 0 || c >= 4) inGrid = false;
                if (positionCells.size() != sequence.size() || locations.size() != positionCells.size()
                        || !inGrid || !correctId.equals("cell" + positionCells.get(askedIndex)))
                    throw new IllegalArgumentException("Invalid 2x2 layout");
            } else if (!positionCells.isEmpty()) throw new IllegalArgumentException("Extra position data");
            this.prompt = prompt;
            this.correctLabel = correctLabel;
            this.hintText = Objects.requireNonNull(hintText);
            Set<String> ids = new HashSet<>();
            for (Item option : options) {
                if (!ids.add(option.id)) throw new IllegalArgumentException("Repeated option");
            }
            if (mission == Mission.STORY) {
                if (sequence.size() < 2 || sequence.size() > 3 || options.size() != sequence.size()
                        || !correctId.equals(joinIds(sequence)))
                    throw new IllegalArgumentException("Invalid story sequence");
                if (!ids.contains(sequence.get(0).id) || !ids.contains(sequence.get(1).id)
                        || (sequence.size() == 3 && !ids.contains(sequence.get(2).id)))
                    throw new IllegalArgumentException("Story cards mismatch");
            } else {
                if (!ids.contains(correctId)) throw new IllegalArgumentException("Correct option absent");
            }
        }
        public String prompt() { return prompt; }
        public String hint() { return hintText; }
        public String answerLabel() { return correctLabel; }
        public Item answer() { return sequence.get(askedIndex); }
        public static String joinIds(List<Item> items) {
            StringBuilder sb = new StringBuilder();
            for (Item item : items) {
                if (sb.length() != 0) sb.append('|');
                sb.append(item.id);
            }
            return sb.toString();
        }
        private static String ordinal(int index) {
            return new String[]{"첫 번째", "두 번째", "세 번째", "네 번째"}[index];
        }
    }

    public interface QuestionPlanner {
        Question nextQuestion(int roundIndex, List<RoundResult> previousResults);
    }

    public static final class RoundResult {
        public final int roundNumber;
        public final int shownItems;
        public final String category;
        public final String mission;
        public final String prompt;
        public final String correctId;
        public final String correctLabel;
        public final String selectedId;
        public final boolean correct;
        public final int hintsUsed;
        public final int examplesUsed;
        public final int waitingPrompts;
        public final long responseMillis;
        RoundResult(int roundNumber, Question question, String selectedId, int hintsUsed,
                    int examplesUsed, int waitingPrompts, long responseMillis) {
            this.roundNumber = roundNumber;
            this.shownItems = question.sequence.size();
            this.category = question.category;
            this.mission = question.mission.id;
            this.prompt = question.prompt();
            this.correctId = question.correctId;
            this.correctLabel = question.answerLabel();
            this.selectedId = selectedId;
            this.correct = question.correctId.equals(selectedId);
            this.hintsUsed = hintsUsed;
            this.examplesUsed = examplesUsed;
            this.waitingPrompts = waitingPrompts;
            this.responseMillis = Math.max(0, responseMillis);
        }
    }

    private final QuestionPlanner planner;
    private final List<RoundResult> results = new ArrayList<>();
    private Phase phase = Phase.READY;
    private Question current;
    private int hintsInRound;
    private int examplesInRound;
    private int waitingPromptsInRound;
    private long answerStartedAt;
    private long startedAt;
    private long finishedAt;

    public MemoryGame(QuestionPlanner planner) { this.planner = Objects.requireNonNull(planner); }
    public Phase phase() { return phase; }
    public Question question() { return current; }
    public int currentRound() { return results.size() + 1; }
    public List<RoundResult> results() { return Collections.unmodifiableList(results); }
    public long startedAt() { return startedAt; }
    public long finishedAt() { return finishedAt; }
    public int hintsInRound() { return hintsInRound; }
    public int examplesInRound() { return examplesInRound; }
    public int waitingPromptsInRound() { return waitingPromptsInRound; }
    public int totalExamples() {
        int n = 0;
        for (RoundResult r : results) n += r.examplesUsed;
        return n;
    }
    public int totalWaitingPrompts() {
        int n = 0;
        for (RoundResult r : results) n += r.waitingPrompts;
        return n;
    }
    public int correctCount() {
        int count = 0;
        for (RoundResult result : results) if (result.correct) count++;
        return count;
    }
    public int totalHints() {
        int count = 0;
        for (RoundResult result : results) count += result.hintsUsed;
        return count;
    }

    /** Time from SystemClock.elapsedRealtime() (or a monotonic test clock). */
    public void start(long now) {
        if (phase != Phase.READY && phase != Phase.FINISHED)
            throw new IllegalStateException("Already playing");
        results.clear();
        startedAt = now;
        finishedAt = 0;
        createNextQuestion();
    }

    private void createNextQuestion() {
        if (results.size() == TOTAL_ROUNDS) {
            phase = Phase.FINISHED;
            return;
        }
        current = Objects.requireNonNull(planner.nextQuestion(results.size(),
                Collections.unmodifiableList(results)));
        hintsInRound = 0;
        examplesInRound = 0;
        waitingPromptsInRound = 0;
        phase = Phase.MEMORIZE;
    }

    public void revealAnswerStage(long now) {
        require(Phase.MEMORIZE);
        phase = Phase.ANSWER;
        answerStartedAt = now;
    }

    public String requestHint() {
        require(Phase.ANSWER);
        if (hintsInRound > 0) return null;
        hintsInRound++;
        return current.hint();
    }

    /** Optional task demonstration that never reveals the active answer. One per round. */
    public String requestExample() {
        require(Phase.ANSWER);
        if (examplesInRound > 0) return null;
        examplesInRound++;
        return PlaySupportCoach.workedExample(current);
    }

    /** One non-intrusive invitation after a long pause. No forced hint or timer penalty. */
    public boolean markWaitingSupport() {
        require(Phase.ANSWER);
        if (waitingPromptsInRound > 0) return false;
        waitingPromptsInRound++;
        return true;
    }

    public RoundResult choose(String selectedId, long now) {
        require(Phase.ANSWER);
        boolean allowed = current.mission == Mission.STORY
                ? validStoryAnswer(selectedId) : validSingleAnswer(selectedId);
        if (!allowed) throw new IllegalArgumentException("Invalid option selection");
        RoundResult result = new RoundResult(results.size() + 1, current, selectedId,
                hintsInRound, examplesInRound, waitingPromptsInRound, now - answerStartedAt);
        results.add(result);
        phase = Phase.FEEDBACK;
        return result;
    }

    private boolean validSingleAnswer(String selectedId) {
        for (Item i : current.options) if (i.id.equals(selectedId)) return true;
        return false;
    }

    private boolean validStoryAnswer(String selectedId) {
        if (selectedId == null) return false;
        String[] parts = selectedId.split("\\|", -1);
        if (parts.length != current.options.size()) return false;
        Set<String> allowed = new HashSet<>(), chosen = new HashSet<>();
        for (Item item : current.options) allowed.add(item.id);
        for (String part : parts) {
            if (!allowed.contains(part) || !chosen.add(part)) return false;
        }
        return true;
    }

    public void advance(long now) {
        require(Phase.FEEDBACK);
        if (results.size() == TOTAL_ROUNDS) {
            finishedAt = now;
            phase = Phase.FINISHED;
            return;
        }
        createNextQuestion();
    }

    private void require(Phase expected) {
        if (phase != expected)
            throw new IllegalStateException("Expected " + expected + " but was " + phase);
    }
}
