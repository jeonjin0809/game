package com.damum.daycaretemi.play;

import java.util.List;

/**
 * Pre-reviewed, age-neutral Korean support speech for children 4-7.
 * An agent may choose a support style; the model never sends arbitrary speech to a child.
 * The examples demonstrate the TASK, not the answer to the active question.
 */
public final class PlaySupportCoach {
    public static final String STEADY = "steady";
    public static final String GENTLE = "gentle";
    public static final String CELEBRATE = "celebrate";

    private PlaySupportCoach() { }

    public static String localStyle(List<MemoryGame.RoundResult> history) {
        if (history == null || history.isEmpty()) return STEADY;
        MemoryGame.RoundResult last = history.get(history.size() - 1);
        if (!last.correct || last.hintsUsed > 0 || last.examplesUsed > 0) return GENTLE;
        return CELEBRATE;
    }

    public static boolean validStyle(String style, List<MemoryGame.RoundResult> history) {
        if (!(STEADY.equals(style) || GENTLE.equals(style) || CELEBRATE.equals(style))) return false;
        if (history == null || history.isEmpty()) return STEADY.equals(style);
        MemoryGame.RoundResult last = history.get(history.size() - 1);
        // Never celebrate presumed achievement after an observed error or request for help.
        return !CELEBRATE.equals(style) || (last.correct && last.hintsUsed == 0 && last.examplesUsed == 0);
    }

    public static String introduction(String style, int roundNumber) {
        if (roundNumber == 1) return "우리 함께 천천히 해 보자!";
        if (CELEBRATE.equals(style)) return "좋았어! 이번에는 다른 놀이도 해 보자.";
        if (GENTLE.equals(style)) return "괜찮아. 이번에도 천천히 해 보자. 도움이 필요하면 힌트를 눌러도 돼.";
        return "이번에는 어떤 그림이 나올까? 함께 살펴보자.";
    }

    public static String feedback(MemoryGame.RoundResult result,
                                  List<MemoryGame.RoundResult> previous) {
        if (result.correct) {
            if (result.examplesUsed > 0) return "예시를 살펴보고 답을 골랐구나. 끝까지 해냈어!";
            if (result.hintsUsed > 0) return "힌트를 활용해서 답을 골랐구나. 잘 참여했어!";
            if (previous != null && !previous.isEmpty()) {
                MemoryGame.RoundResult last = previous.get(previous.size() - 1);
                if (!last.correct) return "이번에는 답을 맞혔네! 계속 차근차근 해 보자.";
            }
            return "좋아! 이번 문제를 맞혔어!";
        }
        if (result.examplesUsed > 0 || result.hintsUsed > 0)
            return "이번 답은 달랐어. 도움을 활용하며 끝까지 해 본 게 좋아. 정답은 "
                    + safeLabel(result) + "였어.";
        return "이번 답은 달랐어. 괜찮아, 다음에는 그림을 천천히 살펴보자. 정답은 "
                + safeLabel(result) + "였어.";
    }

    /** Do not include ids or free-form model text in speech. The label is a pre-reviewed card. */
    private static String safeLabel(MemoryGame.RoundResult result) {
        return result.correctLabel;
    }

    public static String waitingMessage() {
        return "천천히 생각해도 괜찮아. 도움이 필요하면 힌트나 예시 보기를 눌러 봐.";
    }

    public static String hintLead(MemoryGame.Mission mission) {
        if (mission == MemoryGame.Mission.STORY) return "처음 장면을 함께 골라 볼까?";
        if (mission == MemoryGame.Mission.POSITION) return "어느 칸이었는지 다시 떠올려 보자.";
        if (mission == MemoryGame.Mission.MISSING) return "없어진 그림을 천천히 떠올려 보자.";
        return "하나씩 살펴보면 돼.";
    }

    /** Unrelated worked examples: intentionally do not reference current answer. */
    public static String workedExample(MemoryGame.Question q) {
        switch (q.mission) {
            case ORDER:
                return "예시! 자동차 다음에 자전거를 보았어. 두 번째는 자전거야. "
                        + "이제 지금 그림의 순서를 떠올려 보자.";
            case POSITION:
                return "예시! 별을 왼쪽 위 칸에 놓았어. 별이 어디 있었지? 왼쪽 위 칸이야. "
                        + "이제 지금 그림의 위치를 떠올려 보자.";
            case MISSING:
                return "예시! 풍선과 자동차를 보았는데 자동차가 가려졌어. "
                        + "사라진 것은 자동차야. 이제 지금 사라진 그림을 생각해 보자.";
            case STORY:
                // Never demonstrate the exact story currently being assessed.
                boolean flowerStory = !q.sequence.isEmpty()
                        && "seed".equals(q.sequence.get(0).id);
                return flowerStory
                        ? "예시! 먼저 손에 물을 묻히고, 그다음 비누로 씻어. "
                          + "어느 일이 먼저였지? 물 묻히기야. 이제 지금 이야기의 순서를 생각해 보자."
                        : "예시! 먼저 씨앗을 심고, 그다음 물을 줘. "
                          + "어느 일이 먼저였지? 씨앗 심기야. 이제 지금 이야기의 순서를 생각해 보자.";
            default:
                throw new IllegalArgumentException("Unknown mission");
        }
    }
}
