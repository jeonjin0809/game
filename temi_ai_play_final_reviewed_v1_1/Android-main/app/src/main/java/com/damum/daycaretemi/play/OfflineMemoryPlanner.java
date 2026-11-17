package com.damum.daycaretemi.play;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Offline baseline: one cohesive session, four mission styles, fifth revisits a difficult style. */
public final class OfflineMemoryPlanner implements MemoryGame.QuestionPlanner {
    private final Random random;
    private static final List<MemoryGame.Item> ANIMALS = Arrays.asList(
            new MemoryGame.Item("dog", "🐶", "강아지"),
            new MemoryGame.Item("cat", "🐱", "고양이"),
            new MemoryGame.Item("rabbit", "🐰", "토끼"),
            new MemoryGame.Item("bear", "🐻", "곰"));
    private static final List<MemoryGame.Item> FRUITS = Arrays.asList(
            new MemoryGame.Item("apple", "🍎", "사과"),
            new MemoryGame.Item("banana", "🍌", "바나나"),
            new MemoryGame.Item("grape", "🍇", "포도"),
            new MemoryGame.Item("strawberry", "🍓", "딸기"));

    private static final List<List<MemoryGame.Item>> STORIES = Arrays.asList(
            Arrays.asList(new MemoryGame.Item("seed", "🌱", "씨앗 심기"),
                    new MemoryGame.Item("water", "💧", "물 주기"),
                    new MemoryGame.Item("bloom", "🌼", "꽃 피기")),
            Arrays.asList(new MemoryGame.Item("washwater", "🚰", "손에 물 묻히기"),
                    new MemoryGame.Item("soap", "🛁", "비누로 문지르기"),
                    new MemoryGame.Item("rinse", "👐", "깨끗이 헹구기")),
            Arrays.asList(new MemoryGame.Item("ingredients", "🍎", "재료 준비"),
                    new MemoryGame.Item("mix", "🍴", "재료 섞기"),
                    new MemoryGame.Item("salad", "🍽", "샐러드 완성")));

    public OfflineMemoryPlanner() { this(new Random()); }
    public OfflineMemoryPlanner(Random random) { this.random = random; }

    @Override public MemoryGame.Question nextQuestion(int roundIndex, List<MemoryGame.RoundResult> history) {
        int count = adaptiveCount(history);
        MemoryGame.Mission mission = chooseMission(history);
        return nextQuestionWithSettings(roundIndex, history,
                roundIndex % 2 == 0 ? "animals" : "fruits", count, mission.id);
    }

    public int adaptiveCount(List<MemoryGame.RoundResult> history) {
        if (history.isEmpty()) return 2;
        MemoryGame.RoundResult last = history.get(history.size() - 1);
        int n = last.shownItems;
        if (last.correct && last.hintsUsed == 0 && last.examplesUsed == 0) n++;
        else if (!last.correct) n--;
        return Math.max(2, Math.min(4, n));
    }

    /** Guarantees exposure to each of the 4 missions; final mission revisits difficulty. */
    public MemoryGame.Mission chooseMission(List<MemoryGame.RoundResult> history) {
        if (history.isEmpty()) return MemoryGame.Mission.ORDER;
        for (MemoryGame.Mission m : MemoryGame.Mission.values()) {
            if (!seen(history, m)) return m;
        }
        for (MemoryGame.RoundResult r : history)
            if (!r.correct || r.hintsUsed > 0 || r.examplesUsed > 0)
                return MemoryGame.Mission.fromId(r.mission);
        return MemoryGame.Mission.STORY;
    }

    public static boolean seen(List<MemoryGame.RoundResult> history, MemoryGame.Mission mission) {
        for (MemoryGame.RoundResult r : history) if (mission.id.equals(r.mission)) return true;
        return false;
    }

    /** Older two-parameter planning contract retained. */
    public MemoryGame.Question nextQuestionWithSettings(int roundIndex,
            List<MemoryGame.RoundResult> history, String categoryId, int count) {
        return nextQuestionWithSettings(roundIndex, history, categoryId, count, "order");
    }

    public MemoryGame.Question nextQuestionWithSettings(int roundIndex,
            List<MemoryGame.RoundResult> history, String categoryId, int count, String missionId) {
        if (roundIndex < 0 || roundIndex >= MemoryGame.TOTAL_ROUNDS || history.size() != roundIndex)
            throw new IllegalArgumentException("Inconsistent history/round");
        MemoryGame.Mission mission = MemoryGame.Mission.fromId(missionId);
        if (count < 2 || count > 4) throw new IllegalArgumentException("Unsafe number of pictures");
        if (!"animals".equals(categoryId) && !"fruits".equals(categoryId))
            throw new IllegalArgumentException("Unknown category");
        if (mission == MemoryGame.Mission.STORY) return storyQuestion(count);
        List<MemoryGame.Item> pool = "animals".equals(categoryId) ? ANIMALS : FRUITS;
        String category = "animals".equals(categoryId) ? "동물" : "과일";
        List<MemoryGame.Item> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        List<MemoryGame.Item> shown = new ArrayList<>(shuffled.subList(0, count));
        int target = random.nextInt(count);
        MemoryGame.Item answer = shown.get(target);
        if (mission == MemoryGame.Mission.POSITION) {
            // Randomized 2x2 placements rather than a second linear-order quiz.
            String[] positionNames = {"왼쪽 위", "오른쪽 위", "왼쪽 아래", "오른쪽 아래"};
            String[] icons = {"↖", "↗", "↙", "↘"};
            List<Integer> cells = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
            Collections.shuffle(cells, random);
            cells = new ArrayList<>(cells.subList(0, count));
            List<MemoryGame.Item> positions = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                positions.add(new MemoryGame.Item("cell" + i, icons[i], positionNames[i]));
            int targetCell = cells.get(target);
            return new MemoryGame.Question(mission, category, shown, positions, target,
                    "cell" + targetCell, "'" + answer.label + "'는 어느 칸에 있었을까요?",
                    positionNames[targetCell], "힌트! 틀린 위치 한 곳을 가려 줄게!", cells);
        }
        List<MemoryGame.Item> options = new ArrayList<>();
        options.add(answer);
        for (MemoryGame.Item item : shuffled) {
            if (!item.id.equals(answer.id) && options.size() < 3) options.add(item);
        }
        Collections.shuffle(options, random);
        if (mission == MemoryGame.Mission.MISSING)
            return new MemoryGame.Question(mission, category, shown, options, target,
                    answer.id, "어떤 그림이 사라졌을까요?", answer.label,
                    "힌트! 틀린 그림 하나를 숨겨 줄게!");
        return new MemoryGame.Question(category, shown, options, target);
    }

    private MemoryGame.Question storyQuestion(int count) {
        List<MemoryGame.Item> sequence = new ArrayList<>(STORIES.get(random.nextInt(STORIES.size()))
                .subList(0, Math.min(count, 3)));
        List<MemoryGame.Item> scrambled = new ArrayList<>(sequence);
        Collections.shuffle(scrambled, random);
        StringBuilder explanation = new StringBuilder();
        for (MemoryGame.Item i : sequence) {
            if (explanation.length() > 0) explanation.append(" → ");
            explanation.append(i.label);
        }
        return new MemoryGame.Question(MemoryGame.Mission.STORY, "이야기", sequence,
                scrambled, 0, MemoryGame.Question.joinIds(sequence),
                "이야기의 처음부터 끝까지 순서대로 눌러 보세요!", explanation.toString(),
                "힌트! 첫 번째 장면부터 함께 골라 볼게!");
    }
}
